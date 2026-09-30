"""
voice_api_server.py
-------------------
Flask HTTP API server yang membungkus sistem pengenal suara ECAPA-TDNN.
Digunakan oleh mod Minecraft VoiceDoor untuk verifikasi suara pemilik rumah.

Endpoint:
  POST /verify          - Verifikasi suara, kembalikan speaker + confidence
  POST /register        - Daftarkan sample suara untuk user baru/existing
  POST /train           - Latih ulang model
  GET  /status          - Status model dan daftar member terdaftar
  GET  /health          - Health check

Cara menjalankan:
    python voice_api_server.py
    python voice_api_server.py --host 0.0.0.0 --port 5000
"""

from __future__ import annotations

import argparse
import io
import logging
import os
import sys
import tempfile
import time
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
    logger.error("Flask tidak terinstall. Jalankan: pip install flask")
    sys.exit(1)

from src.preprocess import (
    DATA_DIR,
    MODELS_DIR,
    SAMPLE_RATE,
    AudioProcessingError,
    load_audio,
    save_wav,
    scan_dataset,
)
from src.predict import (
    DEFAULT_THRESHOLD,
    MODEL_FILE,
    ModelNotTrainedError,
    VoicePredictor,
    models_exist,
)
from src.train import (
    MIN_MEMBERS,
    InsufficientDataError,
    train_models,
)
from src.embeddings import EmbeddingUnavailableError

# ---------------------------------------------------------------------------
# Flask App
# ---------------------------------------------------------------------------
app = Flask(__name__)

_predictor: VoicePredictor | None = None
_predictor_mtime: float = 0.0
_predictor_lock = Lock()
_train_lock = Lock()


def get_predictor() -> VoicePredictor | None:
    """Lazy-load dan reload predictor jika model berubah."""
    global _predictor, _predictor_mtime
    model_path = MODELS_DIR / MODEL_FILE
    if not model_path.exists():
        return None
    mtime = model_path.stat().st_mtime
    with _predictor_lock:
        if _predictor is None or mtime != _predictor_mtime:
            try:
                _predictor = VoicePredictor(MODELS_DIR)
                _predictor_mtime = mtime
                logger.info("Model dimuat: members=%s", _predictor.members)
            except ModelNotTrainedError as exc:
                logger.warning("Gagal memuat model: %s", exc)
                _predictor = None
    return _predictor


def save_audio_from_request(member_name: str) -> tuple[Path, float]:
    """
    Simpan audio dari request ke data/raw/<member_name>/.
    Request harus mengirim file audio sebagai multipart/form-data 'audio',
    atau raw bytes dengan Content-Type audio/wav.
    Kembalikan (path, durasi_detik).
    """
    if "audio" in request.files:
        audio_bytes = request.files["audio"].read()
    else:
        audio_bytes = request.data

    if not audio_bytes:
        raise AudioProcessingError("Tidak ada data audio yang diterima.")

    # Simpan ke file temp untuk diproses
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        tmp.write(audio_bytes)
        tmp_path = Path(tmp.name)

    try:
        y = load_audio(tmp_path, SAMPLE_RATE)
    except AudioProcessingError:
        # Coba format OGG (dari Simple Voice Chat)
        try:
            import soundfile as sf
            data, sr = sf.read(io.BytesIO(audio_bytes))
            import numpy as np
            import librosa
            if data.ndim > 1:
                data = data.mean(axis=1)
            y = librosa.resample(data.astype(float), orig_sr=sr, target_sr=SAMPLE_RATE)
            y = y.astype("float32")
        except Exception as exc:
            tmp_path.unlink(missing_ok=True)
            raise AudioProcessingError(f"Format audio tidak didukung: {exc}")
    finally:
        tmp_path.unlink(missing_ok=True)

    duration = y.size / SAMPLE_RATE
    if duration < 1.0:
        raise AudioProcessingError(f"Clip terlalu pendek ({duration:.1f} s). Minimal 1 detik.")

    import re
    import uuid
    safe_name = re.sub(r"[^A-Za-z0-9_-]", "_", member_name)[:30] or "member"
    out_dir = DATA_DIR / safe_name
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"sample_{time.strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:6]}.wav"
    save_wav(y, out_path, SAMPLE_RATE)
    return out_path, duration


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@app.route("/health", methods=["GET"])
def health():
    """Health check sederhana."""
    return jsonify({"status": "ok", "timestamp": time.time()})


@app.route("/status", methods=["GET"])
def status():
    """Status model dan daftar member terdaftar."""
    members_data = scan_dataset(DATA_DIR)
    member_info = {}
    for name, files in members_data.items():
        total_dur = sum(f.stat().st_size for f in files) / (SAMPLE_RATE * 2)  # approx
        member_info[name] = {"files": len(files)}

    predictor = get_predictor()
    return jsonify({
        "model_ready": predictor is not None,
        "model_members": predictor.members if predictor else [],
        "registered_members": list(members_data.keys()),
        "member_info": member_info,
        "models_dir": str(MODELS_DIR),
        "data_dir": str(DATA_DIR),
    })


@app.route("/verify", methods=["POST"])
def verify():
    """
    Verifikasi suara dan kembalikan hasil identifikasi.

    Request: multipart/form-data
      - audio: file audio (WAV/OGG dari Simple Voice Chat)
      - threshold: float opsional (default 0.60)
      - expected_speaker: string opsional (nama pemilik yang diharapkan)

    Response JSON:
      {
        "success": true,
        "speaker": "PlayerName",
        "is_known": true,
        "confidence": 0.85,
        "is_owner": true,
        "threshold": 0.60,
        "probabilities": {"PlayerName": 0.85, ...}
      }
    """
    predictor = get_predictor()
    if predictor is None:
        return jsonify({
            "success": False,
            "error": "Model belum dilatih. Daftarkan minimal 2 anggota dan latih model.",
        }), 503

    threshold = float(request.form.get("threshold", DEFAULT_THRESHOLD))
    expected_speaker = request.form.get("expected_speaker", "").strip()

    if "audio" in request.files:
        audio_bytes = request.files["audio"].read()
    else:
        audio_bytes = request.data

    if not audio_bytes:
        return jsonify({"success": False, "error": "Tidak ada data audio."}), 400

    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        tmp.write(audio_bytes)
        tmp_path = Path(tmp.name)

    try:
        result = predictor.predict(tmp_path, threshold=threshold)
    except AudioProcessingError as exc:
        return jsonify({"success": False, "error": str(exc)}), 400
    except Exception as exc:
        logger.exception("Prediction error")
        return jsonify({"success": False, "error": f"Prediksi gagal: {exc}"}), 500
    finally:
        tmp_path.unlink(missing_ok=True)

    is_owner = (
        result.is_known
        and (not expected_speaker or result.speaker.lower() == expected_speaker.lower())
    )

    return jsonify({
        "success": True,
        "speaker": result.speaker,
        "top_candidate": result.top_candidate,
        "is_known": result.is_known,
        "confidence": round(result.confidence, 4),
        "is_owner": is_owner,
        "threshold": result.threshold,
        "n_segments": result.n_segments,
        "probabilities": {k: round(v, 4) for k, v in result.probabilities.items()},
    })


@app.route("/register", methods=["POST"])
def register():
    """
    Daftarkan sample suara untuk seorang anggota.

    Request: multipart/form-data
      - audio: file audio
      - member: nama anggota (string)

    Response JSON:
      {"success": true, "member": "PlayerName", "duration": 5.2, "path": "..."}
    """
    member = (request.form.get("member") or "").strip()
    if not member:
        return jsonify({"success": False, "error": "Parameter 'member' diperlukan."}), 400

    try:
        out_path, duration = save_audio_from_request(member)
    except AudioProcessingError as exc:
        return jsonify({"success": False, "error": str(exc)}), 400
    except Exception as exc:
        logger.exception("Register error")
        return jsonify({"success": False, "error": str(exc)}), 500

    return jsonify({
        "success": True,
        "member": member,
        "duration": round(duration, 2),
        "path": str(out_path),
    })


@app.route("/train", methods=["POST"])
def train():
    """
    Latih ulang model dengan semua data yang ada.

    Request JSON opsional:
      {"augment": true, "noise_reduction": true, "balance": true}

    Response JSON:
      {"success": true, "best_model": "SVM", "test_accuracy": 0.92, "members": [...]}
    """
    if not _train_lock.acquire(blocking=False):
        return jsonify({"success": False, "error": "Training sudah berjalan."}), 409

    try:
        body = request.get_json(silent=True) or {}
        augment = bool(body.get("augment", True))
        noise_reduction = bool(body.get("noise_reduction", True))
        balance = bool(body.get("balance", True))

        members = {n: f for n, f in scan_dataset(DATA_DIR).items() if f}
        if len(members) < MIN_MEMBERS:
            return jsonify({
                "success": False,
                "error": f"Minimal {MIN_MEMBERS} anggota diperlukan (saat ini: {len(members)}).",
            }), 400

        logger.info("Memulai training... members=%s", list(members))
        result = train_models(
            DATA_DIR, MODELS_DIR,
            augment=augment,
            noise_reduction=noise_reduction,
            balance=balance,
        )

        # Reset predictor agar dimuat ulang
        global _predictor, _predictor_mtime
        with _predictor_lock:
            _predictor = None
            _predictor_mtime = 0.0

        return jsonify({
            "success": True,
            "best_model": result.best_model,
            "test_accuracy": round(result.test_accuracy, 4),
            "members": list(members.keys()),
        })
    except (InsufficientDataError, EmbeddingUnavailableError) as exc:
        return jsonify({"success": False, "error": str(exc)}), 400
    except Exception as exc:
        logger.exception("Training error")
        return jsonify({"success": False, "error": f"Training gagal: {exc}"}), 500
    finally:
        _train_lock.release()


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Voice Recognition API Server untuk VoiceDoor Mod")
    parser.add_argument("--host", default="127.0.0.1", help="Host (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=5000, help="Port (default: 5000)")
    parser.add_argument("--debug", action="store_true", help="Mode debug Flask")
    args = parser.parse_args()

    DATA_DIR.mkdir(parents=True, exist_ok=True)
    MODELS_DIR.mkdir(parents=True, exist_ok=True)

    logger.info("=" * 60)
    logger.info("VoiceDoor Python API Server")
    logger.info("Host: %s  Port: %d", args.host, args.port)
    logger.info("Data dir: %s", DATA_DIR)
    logger.info("Models dir: %s", MODELS_DIR)
    logger.info("=" * 60)

    # Pre-load model jika ada
    p = get_predictor()
    if p:
        logger.info("Model siap. Members: %s", p.members)
    else:
        logger.info("Model belum dilatih. Gunakan /register dan /train terlebih dahulu.")

    app.run(host=args.host, port=args.port, debug=args.debug, threaded=True)
