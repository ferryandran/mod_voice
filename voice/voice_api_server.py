"""
voice_api_server.py
-------------------
HTTP API yang dipakai mod Minecraft VoiceDoor untuk membuka pintu dengan suara.

Sistem ini memakai **speaker verification** (1-vs-rest, cosine similarity terhadap
voiceprint yang di-enroll), bukan klasifikasi closed-set. Konsekuensinya:

  * Satu orang saja sudah cukup - tidak perlu anggota kedua.
  * Skornya absolut, bukan probabilitas relatif terhadap daftar anggota.
  * Menambah anggota baru tidak mengganggu anggota lain dan tidak perlu latih ulang.

Endpoint (semua kecuali /health butuh header X-VoiceDoor-Token):

  GET  /health          - health check, tanpa autentikasi
  GET  /status          - status enrollment + model
  POST /challenge       - minta challenge sekali-pakai sebelum verifikasi
  POST /verify          - verifikasi suara terhadap satu member
  POST /register        - simpan sample suara, lalu perbarui voiceprint
  POST /enroll          - hitung ulang voiceprint dari sample yang ada
  POST /train           - latih classifier closed-set (hanya untuk UI Streamlit)

Cara menjalankan:
    python voice_api_server.py                      # 127.0.0.1:5000, token dibuat otomatis
    python voice_api_server.py --host 0.0.0.0       # bisa diakses dari jaringan
    python voice_api_server.py --require-passphrase  # anti-replay kuat (butuh internet)
    python voice_api_server.py --no-auth            # HANYA untuk tes lokal

Token disimpan di `.api_token` (sudah di-gitignore) dan dicetak saat start. Isikan
token itu ke config mod: /voicedoor settoken <token>
"""

from __future__ import annotations

import argparse
import hmac
import io
import logging
import os
import re
import secrets
import sys
import tempfile
import time
import uuid
from collections import deque
from functools import wraps
from pathlib import Path
from threading import Lock

# Make `src` importable
sys.path.insert(0, str(Path(__file__).resolve().parent))

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("voice_api")

try:
    from flask import Flask, jsonify, request
except ImportError:
    logger.error("Flask tidak terinstall. Jalankan: pip install -r requirements.txt")
    sys.exit(1)

import numpy as np

from src.challenge import (
    ChallengeError,
    ChallengeManager,
    ReplayDetectedError,
)
from src.embeddings import EmbeddingUnavailableError, extract
from src.predict import (
    MODEL_FILE,
    ModelNotTrainedError,
    TranscriptionError,
    VoicePredictor,
    transcribe,
)
from src.preprocess import (
    DATA_DIR,
    MODELS_DIR,
    SAMPLE_RATE,
    AudioProcessingError,
    load_audio,
    preprocess_file,
    save_wav,
    scan_dataset,
)
from src.train import MIN_MEMBERS, InsufficientDataError, train_models
from src.verify import (
    DEFAULT_VERIFY_THRESHOLD,
    EnrollmentError,
    EnrollmentStore,
    NotEnrolledError,
)

# ---------------------------------------------------------------------------
# Konfigurasi
# ---------------------------------------------------------------------------
TOKEN_FILE = Path(__file__).resolve().parent / ".api_token"
TOKEN_ENV_VAR = "VOICEDOOR_API_TOKEN"
AUTH_HEADER = "X-VoiceDoor-Token"

# Lantai threshold: client boleh minta LEBIH ketat, tidak boleh lebih longgar.
# Tanpa ini, siapa pun yang bisa POST ke /verify cukup mengirim threshold=0.
MIN_ALLOWED_THRESHOLD = 0.35
MAX_ALLOWED_THRESHOLD = 0.95

# Rate limit per member: menahan brute force (mencoba banyak rekaman berbeda).
RATE_LIMIT_WINDOW_SECONDS = 60.0
RATE_LIMIT_MAX_ATTEMPTS = 12

MAX_AUDIO_BYTES = 8 * 1024 * 1024  # 8 MB, jauh di atas kebutuhan klip 3-10 detik

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = MAX_AUDIO_BYTES + (1024 * 1024)

_enrollment_store: EnrollmentStore | None = None
_enrollment_lock = Lock()
_challenges = ChallengeManager()
_train_lock = Lock()
_api_token: str | None = None
_auth_required = True
_verify_threshold = DEFAULT_VERIFY_THRESHOLD
_transcribe_language = "id-ID"
_require_challenge = True

# Classifier closed-set: dipakai UI Streamlit, tidak dipakai pintu.
_predictor: VoicePredictor | None = None
_predictor_mtime: float | None = None
_predictor_lock = Lock()

_attempts: dict[str, deque[float]] = {}
_attempts_lock = Lock()


# ---------------------------------------------------------------------------
# Autentikasi
# ---------------------------------------------------------------------------
def load_or_create_token() -> str:
    """Ambil token dari env var, atau dari .api_token, atau buat yang baru."""
    from_env = os.environ.get(TOKEN_ENV_VAR, "").strip()
    if from_env:
        logger.info("Token API dibaca dari %s.", TOKEN_ENV_VAR)
        return from_env
    if TOKEN_FILE.exists():
        existing = TOKEN_FILE.read_text(encoding="utf-8").strip()
        if existing:
            return existing
    token = secrets.token_urlsafe(32)
    TOKEN_FILE.write_text(token + "\n", encoding="utf-8")
    try:
        os.chmod(TOKEN_FILE, 0o600)  # no-op di Windows, tapi benar di POSIX
    except OSError:
        pass
    logger.info("Token API baru dibuat di %s", TOKEN_FILE)
    return token


def require_token(view):
    """Tolak request tanpa token yang benar.

    Perbandingan memakai hmac.compare_digest supaya tidak membocorkan panjang atau
    prefix token lewat selisih waktu respons.
    """

    @wraps(view)
    def wrapper(*args, **kwargs):
        if not _auth_required:
            return view(*args, **kwargs)
        supplied = request.headers.get(AUTH_HEADER, "")
        if not supplied:
            supplied = (request.form.get("token") or "").strip()
        if not supplied or not _api_token or not hmac.compare_digest(supplied, _api_token):
            logger.warning("Request tanpa token valid ke %s dari %s", request.path, request.remote_addr)
            return jsonify({
                "success": False,
                "error": f"Token tidak valid. Kirim header {AUTH_HEADER}.",
            }), 401
        return view(*args, **kwargs)

    return wrapper


def rate_limited(member: str) -> bool:
    """True kalau `member` sudah melewati kuota percobaan dalam jendela waktu."""
    key = member.strip().lower()
    now = time.monotonic()
    with _attempts_lock:
        bucket = _attempts.setdefault(key, deque())
        while bucket and now - bucket[0] > RATE_LIMIT_WINDOW_SECONDS:
            bucket.popleft()
        if len(bucket) >= RATE_LIMIT_MAX_ATTEMPTS:
            return True
        bucket.append(now)
        # Buang bucket kosong milik member lain supaya dict tidak tumbuh tanpa batas
        if len(_attempts) > 256:
            for k in [k for k, v in _attempts.items() if not v]:
                del _attempts[k]
        return False


# ---------------------------------------------------------------------------
# Helper
# ---------------------------------------------------------------------------
def get_store() -> EnrollmentStore:
    """Enrollment store tunggal, dimuat ulang kalau file di disk berubah."""
    global _enrollment_store
    with _enrollment_lock:
        if _enrollment_store is None:
            _enrollment_store = EnrollmentStore(MODELS_DIR)
        else:
            _enrollment_store.reload()
        return _enrollment_store


def get_predictor() -> VoicePredictor | None:
    """Classifier closed-set (opsional, hanya untuk /status dan UI Streamlit)."""
    global _predictor, _predictor_mtime
    model_path = MODELS_DIR / MODEL_FILE
    with _predictor_lock:
        if not model_path.exists():
            _predictor, _predictor_mtime = None, None
            return None
        try:
            mtime = model_path.stat().st_mtime
        except OSError:
            return _predictor
        if _predictor is None or mtime != _predictor_mtime:
            try:
                _predictor = VoicePredictor(MODELS_DIR)
                _predictor_mtime = mtime
                logger.info("Classifier dimuat: members=%s", _predictor.members)
            except ModelNotTrainedError as exc:
                logger.warning("Gagal memuat classifier: %s", exc)
                _predictor, _predictor_mtime = None, None
        return _predictor


def read_audio_bytes() -> bytes:
    """Ambil byte audio dari multipart 'audio' atau dari body mentah."""
    if "audio" in request.files:
        data = request.files["audio"].read()
    else:
        data = request.get_data(cache=False)
    if not data:
        raise AudioProcessingError("Tidak ada data audio yang diterima.")
    if len(data) > MAX_AUDIO_BYTES:
        raise AudioProcessingError(
            f"Audio terlalu besar ({len(data) / 1e6:.1f} MB, maksimum {MAX_AUDIO_BYTES / 1e6:.0f} MB)."
        )
    return data


def decode_audio(audio_bytes: bytes) -> np.ndarray:
    """Decode audio apa pun menjadi float32 mono di SAMPLE_RATE.

    Jalur utama memakai librosa lewat file sementara (menangani WAV/MP3/FLAC/OGG).
    Kalau gagal - misalnya byte OGG/Opus mentah dari Simple Voice Chat tanpa ekstensi
    yang benar - dicoba ulang lewat soundfile dari memori.

    Helper ini dipakai /register MAUPUN /verify. Sebelumnya hanya /register punya
    fallback, jadi register menerima OGG sementara verify menolaknya.
    """
    tmp_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            tmp.write(audio_bytes)
            tmp_path = Path(tmp.name)
        try:
            return load_audio(tmp_path, SAMPLE_RATE)
        except AudioProcessingError:
            pass  # coba fallback di bawah
    finally:
        if tmp_path is not None:
            tmp_path.unlink(missing_ok=True)

    try:
        import librosa
        import soundfile as sf

        data, sr = sf.read(io.BytesIO(audio_bytes), dtype="float32")
        if data.ndim > 1:
            data = data.mean(axis=1)
        if sr != SAMPLE_RATE:
            data = librosa.resample(data.astype(np.float32), orig_sr=sr, target_sr=SAMPLE_RATE)
        y = np.nan_to_num(data).astype(np.float32)
    except Exception as exc:
        raise AudioProcessingError(f"Format audio tidak didukung: {exc}") from exc

    if y.size == 0:
        raise AudioProcessingError("Audio tidak berisi sample apa pun.")
    if float(np.max(np.abs(y))) < 1e-4:
        raise AudioProcessingError("Audio sunyi - mikrofon mungkin tidak aktif.")
    return y


def safe_member_name(raw: str) -> str:
    """Bersihkan nama member supaya aman dipakai sebagai nama folder."""
    cleaned = re.sub(r"[^A-Za-z0-9_-]", "_", raw.strip())[:30]
    return cleaned or "member"


def store_sample(member: str, y: np.ndarray) -> tuple[Path, float]:
    """Simpan sinyal sebagai WAV 16 kHz di data/raw/<member>/."""
    duration = y.size / SAMPLE_RATE
    out_dir = DATA_DIR / safe_member_name(member)
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"sample_{time.strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:6]}.wav"
    save_wav(y, out_path, SAMPLE_RATE)
    return out_path, duration


def clamp_threshold(raw: str | None) -> float:
    """Ambil threshold dari request, tapi jangan biarkan client melemahkannya.

    Client boleh meminta ambang yang LEBIH KETAT dari konfigurasi server. Permintaan
    yang lebih longgar diabaikan - kalau tidak, `threshold=0` membuat pintu selalu
    terbuka bagi siapa pun yang bisa memanggil endpoint ini.
    """
    floor = max(_verify_threshold, MIN_ALLOWED_THRESHOLD)
    if raw is None or str(raw).strip() == "":
        return floor
    try:
        requested = float(raw)
    except (TypeError, ValueError):
        logger.warning("Threshold tidak valid dari client: %r - pakai %.2f", raw, floor)
        return floor
    if not np.isfinite(requested):
        return floor
    return float(min(max(requested, floor), MAX_ALLOWED_THRESHOLD))


def error(message: str, status: int = 400, **extra):
    payload = {"success": False, "error": message}
    payload.update(extra)
    return jsonify(payload), status


# ---------------------------------------------------------------------------
# Endpoint
# ---------------------------------------------------------------------------
@app.route("/health", methods=["GET"])
def health():
    """Health check - sengaja tanpa autentikasi supaya bisa dipantau."""
    return jsonify({
        "status": "ok",
        "timestamp": time.time(),
        "auth_required": _auth_required,
    })


@app.route("/status", methods=["GET"])
@require_token
def status():
    """Status voiceprint, classifier, dan proteksi replay."""
    store = get_store()
    dataset = {name: files for name, files in scan_dataset(DATA_DIR).items() if files}
    predictor = get_predictor()
    return jsonify({
        "success": True,
        "mode": "verification",
        "enrolled_members": store.members,
        "enrollment_info": store.summary(),
        "verify_threshold": round(max(_verify_threshold, MIN_ALLOWED_THRESHOLD), 4),
        "registered_members": sorted(dataset),
        "sample_counts": {name: len(files) for name, files in sorted(dataset.items())},
        "replay_protection": _challenges.stats(),
        # Classifier closed-set hanya relevan untuk UI Streamlit.
        "classifier_ready": predictor is not None,
        "classifier_members": predictor.members if predictor else [],
        "models_dir": str(MODELS_DIR),
        "data_dir": str(DATA_DIR),
    })


@app.route("/challenge", methods=["POST"])
@require_token
def challenge():
    """Terbitkan challenge sekali-pakai sebelum pintu menerima audio.

    Request: form/JSON { member: str }
    Response: { success, challenge_id, phrase, words, require_passphrase, expires_in }
    """
    body = request.get_json(silent=True) or {}
    member = (request.form.get("member") or body.get("member") or "").strip()
    if not member:
        return error("Parameter 'member' diperlukan.")

    store = get_store()
    if not store.has(member):
        return error(
            f"'{member}' belum punya voiceprint. Daftarkan suara dulu lewat /register.",
            409,
            enrolled_members=store.members,
        )
    # Satu percobaan buka pintu = satu hitungan. Karena /verify tidak bisa dipanggil
    # tanpa challenge, cukup dihitung di sini; /verify hanya menghitung sendiri kalau
    # challenge dimatikan. Kalau keduanya menghitung, kuota efektifnya jadi separuh.
    if rate_limited(member):
        return error(
            f"Terlalu banyak percobaan untuk '{member}'. Tunggu "
            f"{int(RATE_LIMIT_WINDOW_SECONDS)} detik.",
            429,
        )

    issued = _challenges.issue(member)
    payload = issued.as_dict()
    payload["success"] = True
    payload["require_passphrase"] = _challenges.require_passphrase
    return jsonify(payload)


@app.route("/verify", methods=["POST"])
@require_token
def verify():
    """Verifikasi suara terhadap satu member.

    Request: multipart/form-data
      - audio           : file audio (WAV/MP3/FLAC/OGG)
      - member          : nama member yang diklaim  (alias lama: expected_speaker)
      - challenge_id    : id dari /challenge (wajib kecuali server memakai --no-challenge)
      - threshold       : opsional, hanya boleh LEBIH ketat dari setelan server

    Response:
      { success, accepted, is_owner, member, similarity, threshold, n_segments,
        passphrase_ok?, transcript?, best_other?, best_other_similarity? }

    `is_owner` dipertahankan sebagai alias `accepted` supaya kompatibel dengan mod lama.
    """
    member = (
        request.form.get("member")
        or request.form.get("expected_speaker")
        or ""
    ).strip()
    if not member:
        return error("Parameter 'member' diperlukan.")

    store = get_store()
    try:
        store.get(member)
    except NotEnrolledError as exc:
        return error(str(exc), 409, enrolled_members=store.members)

    # Sudah dihitung di /challenge. Hitung di sini hanya kalau challenge dimatikan,
    # supaya /verify tetap terlindungi tanpa mengurangi kuota dua kali per percobaan.
    if not _require_challenge and rate_limited(member):
        return error(
            f"Terlalu banyak percobaan untuk '{member}'. Tunggu "
            f"{int(RATE_LIMIT_WINDOW_SECONDS)} detik.",
            429,
        )

    threshold = clamp_threshold(request.form.get("threshold"))

    # -- audio ------------------------------------------------------------
    try:
        audio_bytes = read_audio_bytes()
    except AudioProcessingError as exc:
        return error(str(exc))

    # -- challenge + replay ------------------------------------------------
    challenge_id = (request.form.get("challenge_id") or "").strip()
    taken = None
    if _require_challenge:
        if not challenge_id:
            return error("Parameter 'challenge_id' diperlukan. Minta dulu lewat POST /challenge.", 428)
        try:
            taken = _challenges.take(challenge_id, member)
        except ChallengeError as exc:
            return error(str(exc), 409)

    try:
        digest = _challenges.check_not_replayed(audio_bytes)
    except ReplayDetectedError as exc:
        logger.warning("Replay ditolak untuk member '%s'", member)
        return error(str(exc), 409, replay=True)

    # -- verifikasi --------------------------------------------------------
    try:
        y = decode_audio(audio_bytes)
    except AudioProcessingError as exc:
        return error(str(exc))

    tmp_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            tmp_path = Path(tmp.name)
        save_wav(y, tmp_path, SAMPLE_RATE)
        _, segments = preprocess_file(tmp_path, noise_reduction=True)
        X = extract(segments)
        result = store.verify_segments(X, member, threshold)
    except AudioProcessingError as exc:
        return error(str(exc))
    except NotEnrolledError as exc:
        return error(str(exc), 409)
    except EmbeddingUnavailableError as exc:
        logger.error("Encoder ECAPA tidak tersedia: %s", exc)
        return error(f"Encoder suara tidak tersedia: {exc}", 503)
    except Exception as exc:
        logger.exception("Verifikasi gagal")
        return error(f"Verifikasi gagal: {exc}", 500)
    finally:
        if tmp_path is not None:
            tmp_path.unlink(missing_ok=True)

    payload = result.as_dict()
    payload["success"] = True

    # -- passphrase (opsional, butuh internet) -----------------------------
    accepted = result.accepted
    if accepted and taken is not None and taken.words:
        transcript = _transcribe_bytes(y, taken.language)
        if transcript is None:
            logger.warning("Passphrase diminta tapi transkripsi tidak tersedia - tolak demi aman.")
            payload["passphrase_ok"] = False
            payload["transcript"] = None
            payload["error"] = ("Frasa tidak bisa diverifikasi (transkripsi tidak tersedia). "
                                "Pastikan server punya koneksi internet.")
            accepted = False
        else:
            ok, ratio = _challenges.passphrase_matches(taken, transcript)
            payload["passphrase_ok"] = ok
            payload["passphrase_ratio"] = round(ratio, 3)
            payload["transcript"] = transcript
            payload["expected_phrase"] = taken.phrase
            if not ok:
                payload["error"] = f"Frasa tidak cocok. Diminta: '{taken.phrase}'."
                accepted = False

    payload["accepted"] = accepted
    payload["is_owner"] = accepted          # alias kompatibilitas
    payload["speaker"] = result.member if accepted else "Unknown Voice"
    payload["confidence"] = payload["similarity"]

    if accepted:
        _challenges.remember(digest)

    logger.info(
        "verify member=%s accepted=%s similarity=%.4f threshold=%.2f segments=%d",
        member, accepted, result.similarity, threshold, result.n_segments,
    )
    return jsonify(payload)


def _transcribe_bytes(y: np.ndarray, language: str) -> str | None:
    """Transkripsi sinyal lewat file sementara. None kalau gagal/offline."""
    tmp_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            tmp_path = Path(tmp.name)
        save_wav(y, tmp_path, SAMPLE_RATE)
        return transcribe(tmp_path, language=language)
    except (TranscriptionError, AudioProcessingError) as exc:
        logger.warning("Transkripsi gagal: %s", exc)
        return None
    except Exception as exc:
        logger.warning("Transkripsi gagal tak terduga: %s", exc)
        return None
    finally:
        if tmp_path is not None:
            tmp_path.unlink(missing_ok=True)


@app.route("/register", methods=["POST"])
@require_token
def register():
    """Simpan satu sample suara, lalu perbarui voiceprint member itu.

    Request: multipart/form-data { audio, member }
    Response: { success, member, duration, n_samples, enrolled, enrollment? }

    `enrolled` false berarti sample tersimpan tapi belum cukup untuk membuat
    voiceprint - pesan di `hint` menjelaskan berapa lagi yang dibutuhkan.
    """
    member = (request.form.get("member") or "").strip()
    if not member:
        return error("Parameter 'member' diperlukan.")

    try:
        audio_bytes = read_audio_bytes()
        y = decode_audio(audio_bytes)
    except AudioProcessingError as exc:
        return error(str(exc))

    duration = y.size / SAMPLE_RATE
    if duration < 1.0:
        return error(f"Klip terlalu pendek ({duration:.1f} detik). Minimal 1 detik.")

    try:
        out_path, duration = store_sample(member, y)
    except Exception as exc:
        logger.exception("Gagal menyimpan sample")
        return error(f"Gagal menyimpan sample: {exc}", 500)

    safe = safe_member_name(member)
    n_samples = len(scan_dataset(DATA_DIR).get(safe, []))

    payload = {
        "success": True,
        "member": safe,
        "duration": round(duration, 2),
        "path": str(out_path),
        "n_samples": n_samples,
    }

    # Perbarui voiceprint segera, supaya pintu langsung bisa dipakai.
    store = get_store()
    try:
        enrollment = store.enroll(safe, DATA_DIR)
        payload["enrolled"] = True
        payload["enrollment"] = {
            "n_segments": enrollment.n_segments,
            "n_files": enrollment.n_files,
            "cohesion": round(enrollment.self_similarity_mean, 4),
        }
        if enrollment.self_similarity_mean < 0.55:
            payload["hint"] = ("Voiceprint tersimpan tapi sample-nya kurang konsisten. "
                               "Rekam beberapa kali lagi dengan mikrofon dan ruangan yang sama.")
    except EnrollmentError as exc:
        payload["enrolled"] = False
        payload["hint"] = str(exc)
    except EmbeddingUnavailableError as exc:
        payload["enrolled"] = False
        payload["hint"] = f"Encoder suara tidak tersedia: {exc}"
    except Exception as exc:
        logger.exception("Enrollment gagal setelah register")
        payload["enrolled"] = False
        payload["hint"] = f"Enrollment gagal: {exc}"

    return jsonify(payload)


@app.route("/enroll", methods=["POST"])
@require_token
def enroll():
    """Hitung ulang voiceprint dari sample yang sudah ada.

    Request: form/JSON { member: str }   ('all' untuk semua member)
    """
    body = request.get_json(silent=True) or {}
    member = (request.form.get("member") or body.get("member") or "").strip()
    if not member:
        return error("Parameter 'member' diperlukan ('all' untuk semua).")

    store = get_store()
    try:
        if member.lower() == "all":
            done, failed = store.enroll_all(DATA_DIR)
            return jsonify({
                "success": bool(done),
                "enrolled": [e.name for e in done],
                "failed": [{"member": n, "error": e} for n, e in failed],
                "members": store.members,
            })
        enrollment = store.enroll(member, DATA_DIR)
    except EnrollmentError as exc:
        return error(str(exc))
    except EmbeddingUnavailableError as exc:
        return error(f"Encoder suara tidak tersedia: {exc}", 503)
    except Exception as exc:
        logger.exception("Enrollment gagal")
        return error(f"Enrollment gagal: {exc}", 500)

    return jsonify({
        "success": True,
        "member": enrollment.name,
        "n_segments": enrollment.n_segments,
        "n_files": enrollment.n_files,
        "cohesion": round(enrollment.self_similarity_mean, 4),
        "worst_sample": round(enrollment.self_similarity_min, 4),
        "members": store.members,
    })


@app.route("/train", methods=["POST"])
@require_token
def train():
    """Latih classifier closed-set. TIDAK diperlukan untuk pintu.

    Endpoint ini hanya melayani UI Streamlit, yang memang menjawab pertanyaan
    "di antara anggota keluarga, ini siapa?". Pintu memakai /verify.
    """
    if not _train_lock.acquire(blocking=False):
        return error("Training sudah berjalan.", 409)
    try:
        body = request.get_json(silent=True) or {}
        members = {n: f for n, f in scan_dataset(DATA_DIR).items() if f}
        if len(members) < MIN_MEMBERS:
            return error(
                f"Classifier butuh minimal {MIN_MEMBERS} anggota (saat ini {len(members)}). "
                f"Pintu tidak memerlukan ini - cukup /register lalu /verify.",
            )

        logger.info("Memulai training classifier... members=%s", list(members))
        result = train_models(
            DATA_DIR, MODELS_DIR,
            augment=bool(body.get("augment", True)),
            noise_reduction=bool(body.get("noise_reduction", True)),
            balance=bool(body.get("balance", True)),
        )
        global _predictor, _predictor_mtime
        with _predictor_lock:
            _predictor, _predictor_mtime = None, None

        return jsonify({
            "success": True,
            "best_model": result.best_model,
            "test_accuracy": round(result.test_accuracy, 4),
            "file_accuracy": round(result.file_accuracy, 4),
            "members": list(members),
            "note": "Classifier ini untuk UI Streamlit; pintu memakai verification.",
        })
    except (InsufficientDataError, EmbeddingUnavailableError) as exc:
        return error(str(exc))
    except Exception as exc:
        logger.exception("Training gagal")
        return error(f"Training gagal: {exc}", 500)
    finally:
        _train_lock.release()


@app.errorhandler(413)
def too_large(_exc):
    return error("Audio terlalu besar.", 413)


@app.errorhandler(404)
def not_found(_exc):
    return error(f"Endpoint tidak dikenal: {request.path}", 404)


@app.errorhandler(500)
def server_error(_exc):
    return error("Kesalahan internal server.", 500)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Voice API Server untuk mod VoiceDoor")
    p.add_argument("--host", default="127.0.0.1",
                   help="Host bind (default 127.0.0.1; pakai 0.0.0.0 untuk akses jaringan)")
    p.add_argument("--port", type=int, default=5000, help="Port (default 5000)")
    p.add_argument("--threshold", type=float, default=DEFAULT_VERIFY_THRESHOLD,
                   help=f"Ambang cosine similarity (default {DEFAULT_VERIFY_THRESHOLD})")
    p.add_argument("--require-passphrase", action="store_true",
                   help="Wajibkan pemain mengucapkan frasa acak (anti-replay kuat, butuh internet)")
    p.add_argument("--language", default="id-ID",
                   help="Bahasa transkripsi frasa, misal id-ID atau en-US")
    p.add_argument("--no-challenge", action="store_true",
                   help="Matikan challenge sekali-pakai (TIDAK disarankan)")
    p.add_argument("--no-auth", action="store_true",
                   help="Matikan autentikasi token - HANYA untuk tes lokal")
    p.add_argument("--dev", action="store_true",
                   help="Pakai Flask dev server + debug, bukan waitress")
    return p


def main(argv: list[str] | None = None) -> int:
    global _api_token, _auth_required, _verify_threshold, _require_challenge, _transcribe_language

    args = build_arg_parser().parse_args(argv)

    _auth_required = not args.no_auth
    _verify_threshold = float(min(max(args.threshold, MIN_ALLOWED_THRESHOLD), MAX_ALLOWED_THRESHOLD))
    _require_challenge = not args.no_challenge
    _transcribe_language = args.language
    _challenges.require_passphrase = args.require_passphrase
    _challenges.language = args.language

    DATA_DIR.mkdir(parents=True, exist_ok=True)
    MODELS_DIR.mkdir(parents=True, exist_ok=True)

    if _auth_required:
        _api_token = load_or_create_token()

    store = get_store()

    logger.info("=" * 68)
    logger.info("VoiceDoor Voice API Server  (mode: speaker verification)")
    logger.info("Bind            : %s:%d", args.host, args.port)
    logger.info("Threshold       : %.2f (client hanya boleh minta lebih ketat)", _verify_threshold)
    logger.info("Challenge       : %s", "wajib" if _require_challenge else "DIMATIKAN")
    logger.info("Passphrase      : %s", "wajib" if args.require_passphrase else "tidak")
    logger.info("Member enrolled : %s", ", ".join(store.members) or "(belum ada)")
    logger.info("Data dir        : %s", DATA_DIR)
    logger.info("Models dir      : %s", MODELS_DIR)
    if _auth_required:
        logger.info("-" * 68)
        logger.info("API token: %s", _api_token)
        logger.info("Masukkan ke mod dengan: /voicedoor settoken %s", _api_token)
    else:
        logger.warning("-" * 68)
        logger.warning("AUTENTIKASI DIMATIKAN - siapa pun yang bisa menjangkau port ini")
        logger.warning("dapat mendaftarkan suara sebagai pemilik pintu. Jangan pakai di server nyata.")
    if args.host == "0.0.0.0" and not _auth_required:
        logger.error("Menolak bind 0.0.0.0 tanpa autentikasi. Hapus --no-auth.")
        return 1
    logger.info("=" * 68)

    if not store.members:
        logger.info("Belum ada voiceprint. Pemain cukup jalankan /voicedoor register di dalam game.")

    if args.dev:
        logger.warning("Flask dev server dipakai - jangan untuk penggunaan nyata.")
        app.run(host=args.host, port=args.port, debug=True, threaded=True)
        return 0

    try:
        from waitress import serve
    except ImportError:
        logger.warning("waitress tidak terinstall (pip install waitress). "
                       "Sementara memakai Flask dev server.")
        app.run(host=args.host, port=args.port, threaded=True)
        return 0

    logger.info("Menjalankan waitress di http://%s:%d", args.host, args.port)
    serve(app, host=args.host, port=args.port, threads=8)
    return 0


if __name__ == "__main__":
    sys.exit(main())
