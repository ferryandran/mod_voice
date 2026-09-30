"""
preprocess.py
-------------
Audio preprocessing for the Home Owner Voice Recognition System.

Pipeline:
    load (16 kHz, mono) -> noise reduction -> trim silence -> remove long pauses
    -> amplitude normalization -> split into 2-3 s segments (+ optional augmentation)

Standalone usage:
    python src/preprocess.py path/to/audio.wav [--augment] [--no-noise-reduction] [--out-dir segments/]
"""

from __future__ import annotations

import argparse
import sys
import warnings
from pathlib import Path

import librosa
import numpy as np
import soundfile as sf
from scipy.ndimage import uniform_filter

# ---------------------------------------------------------------------------
# Paths & constants
# ---------------------------------------------------------------------------
PROJECT_ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = PROJECT_ROOT / "data" / "raw"
MODELS_DIR = PROJECT_ROOT / "models"

SAMPLE_RATE = 16000          # Target sample rate (Hz)
TOP_DB = 25                  # Silence threshold (dB below peak)
SEGMENT_SECONDS = 2.5        # Length of each training / inference segment
MIN_SEGMENT_SECONDS = 1.0    # Leftover chunks shorter than this are dropped
MIN_SPEECH_SECONDS = 0.5     # Minimum speech required after silence removal
TARGET_PEAK = 0.95           # Peak amplitude after normalization
SUPPORTED_EXTENSIONS = (".wav", ".mp3", ".flac", ".ogg")


class AudioProcessingError(Exception):
    """Raised when an audio file cannot be loaded or does not contain usable speech."""


# ---------------------------------------------------------------------------
# Loading / saving
# ---------------------------------------------------------------------------
def is_supported_file(path: str | Path) -> bool:
    """Return True if the file extension is a supported audio format."""
    return Path(path).suffix.lower() in SUPPORTED_EXTENSIONS


def load_audio(path: str | Path, sr: int = SAMPLE_RATE) -> np.ndarray:
    """Load an audio file as a mono float32 signal resampled to `sr`.

    Raises:
        AudioProcessingError: missing file, unsupported format, corrupt/undecodable
        data, or a completely silent recording.
    """
    path = Path(path)
    if not path.exists():
        raise AudioProcessingError(f"File not found: {path}")
    if not is_supported_file(path):
        raise AudioProcessingError(
            f"Unsupported format '{path.suffix or '(none)'}'. "
            f"Supported formats: {', '.join(SUPPORTED_EXTENSIONS)}"
        )
    if path.stat().st_size == 0:
        raise AudioProcessingError(f"File is empty: {path.name}")

    try:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            y, _ = librosa.load(path, sr=sr, mono=True)
    except Exception as exc:  # librosa/soundfile/audioread raise many different types
        raise AudioProcessingError(
            f"Could not decode '{path.name}' (corrupt file or missing codec): {str(exc) or type(exc).__name__}"
        ) from exc

    if y.size == 0:
        raise AudioProcessingError(f"'{path.name}' contains no audio samples.")
    y = np.nan_to_num(y).astype(np.float32)
    if np.max(np.abs(y)) < 1e-4:
        raise AudioProcessingError(f"'{path.name}' is silent.")
    return y


def save_wav(y: np.ndarray, path: str | Path, sr: int = SAMPLE_RATE) -> Path:
    """Save a signal as 16-bit PCM WAV."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    sf.write(path, np.clip(y, -1.0, 1.0), sr, subtype="PCM_16")
    return path


def get_audio_duration(path: str | Path) -> float:
    """Return the duration of an audio file in seconds (0.0 if unreadable)."""
    try:
        return float(sf.info(str(path)).duration)
    except Exception:
        try:
            with warnings.catch_warnings():
                warnings.simplefilter("ignore")
                return float(librosa.get_duration(path=str(path)))
        except Exception:
            return 0.0


def scan_dataset(data_dir: str | Path = DATA_DIR) -> dict[str, list[Path]]:
    """Return {member_name: [audio files]} for every member folder in `data_dir`."""
    data_dir = Path(data_dir)
    if not data_dir.exists():
        return {}
    members: dict[str, list[Path]] = {}
    for member_dir in sorted(p for p in data_dir.iterdir() if p.is_dir() and not p.name.startswith(".")):
        files = sorted(
            f for f in member_dir.iterdir()
            if f.is_file() and not f.name.startswith(".") and is_supported_file(f)
        )
        members[member_dir.name] = files
    return members


# ---------------------------------------------------------------------------
# Signal cleaning
# ---------------------------------------------------------------------------
def reduce_noise(
    y: np.ndarray,
    sr: int = SAMPLE_RATE,
    n_fft: int = 1024,
    hop_length: int = 256,
    noise_percentile: float = 10.0,
    threshold_factor: float = 1.5,
    prop_decrease: float = 0.8,
) -> np.ndarray:
    """Lightweight spectral-gating noise reduction (no extra dependencies).

    The noise floor of each frequency bin is estimated from its quietest frames.
    Time-frequency bins below `threshold_factor * noise_floor` are attenuated.
    """
    if y.size < n_fft * 2:
        return y
    stft = librosa.stft(y, n_fft=n_fft, hop_length=hop_length)
    magnitude = np.abs(stft)
    noise_floor = np.percentile(magnitude, noise_percentile, axis=1, keepdims=True)
    speech_mask = magnitude > noise_floor * threshold_factor
    gain = np.where(speech_mask, 1.0, 1.0 - prop_decrease)
    gain = uniform_filter(gain, size=(3, 5), mode="nearest")  # smoothing avoids "musical noise"
    cleaned = librosa.istft(stft * gain, hop_length=hop_length, length=y.size)
    return cleaned.astype(np.float32)


def trim_silence(y: np.ndarray, top_db: int = TOP_DB) -> np.ndarray:
    """Trim leading and trailing silence."""
    trimmed, _ = librosa.effects.trim(y, top_db=top_db)
    return trimmed if trimmed.size else y


def remove_long_pauses(
    y: np.ndarray, sr: int = SAMPLE_RATE, top_db: int = TOP_DB, keep_seconds: float = 0.1
) -> np.ndarray:
    """Drop silent gaps inside the recording, keeping a short margin around speech."""
    intervals = librosa.effects.split(y, top_db=top_db)
    if len(intervals) == 0:
        return y
    pad = int(keep_seconds * sr)
    merged: list[list[int]] = []
    for start, end in intervals:
        start, end = max(0, start - pad), min(len(y), end + pad)
        if merged and start <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], end)
        else:
            merged.append([start, end])
    return np.concatenate([y[s:e] for s, e in merged]).astype(np.float32)


def normalize_amplitude(y: np.ndarray, target_peak: float = TARGET_PEAK) -> np.ndarray:
    """Peak-normalize the signal to `target_peak`."""
    peak = float(np.max(np.abs(y))) if y.size else 0.0
    if peak < 1e-8:
        return y
    return (y / peak * target_peak).astype(np.float32)


def clean_signal(y: np.ndarray, sr: int = SAMPLE_RATE, noise_reduction: bool = True) -> np.ndarray:
    """Noise reduction -> silence trimming -> pause removal -> normalization."""
    if noise_reduction:
        y = reduce_noise(y, sr)
    y = trim_silence(y)
    y = remove_long_pauses(y, sr)
    if y.size < int(MIN_SPEECH_SECONDS * sr):
        raise AudioProcessingError(
            f"Not enough speech after silence removal (need at least {MIN_SPEECH_SECONDS} s)."
        )
    return normalize_amplitude(y)


# ---------------------------------------------------------------------------
# Segmentation & augmentation
# ---------------------------------------------------------------------------
def segment_audio(
    y: np.ndarray,
    sr: int = SAMPLE_RATE,
    segment_seconds: float = SEGMENT_SECONDS,
    min_seconds: float = MIN_SEGMENT_SECONDS,
    overlap: float = 0.0,
) -> list[np.ndarray]:
    """Split a signal into fixed-length segments (the last one may be shorter)."""
    seg_len = int(segment_seconds * sr)
    min_len = int(min_seconds * sr)
    step = max(1, int(seg_len * (1.0 - overlap)))
    if y.size < min_len:
        return []
    if y.size <= seg_len:
        return [y]
    segments: list[np.ndarray] = []
    for start in range(0, y.size, step):
        chunk = y[start:start + seg_len]
        if chunk.size >= min_len:
            segments.append(chunk)
        if start + seg_len >= y.size:
            break
    return segments


def add_noise(y: np.ndarray, rng: np.random.Generator, snr_db: tuple[float, float] = (15.0, 30.0)) -> np.ndarray:
    """Inject Gaussian white noise at a random SNR."""
    snr = rng.uniform(*snr_db)
    signal_power = float(np.mean(y ** 2)) + 1e-12
    noise_power = signal_power / (10 ** (snr / 10))
    return (y + rng.normal(0.0, np.sqrt(noise_power), y.shape)).astype(np.float32)


def shift_pitch(y: np.ndarray, sr: int, n_steps: float) -> np.ndarray:
    """Shift pitch by `n_steps` semitones."""
    return librosa.effects.pitch_shift(y, sr=sr, n_steps=n_steps).astype(np.float32)


def augment_segment(y: np.ndarray, sr: int = SAMPLE_RATE, rng: np.random.Generator | None = None) -> list[np.ndarray]:
    """Return augmented copies of a segment: one with noise, one pitch-shifted."""
    rng = rng or np.random.default_rng()
    noisy = normalize_amplitude(add_noise(y, rng))
    shifted = normalize_amplitude(shift_pitch(y, sr, float(rng.choice([-1.0, -0.5, 0.5, 1.0]))))
    return [noisy, shifted]


# ---------------------------------------------------------------------------
# Full pipeline
# ---------------------------------------------------------------------------
def preprocess_file(
    path: str | Path,
    sr: int = SAMPLE_RATE,
    noise_reduction: bool = True,
    segment_seconds: float = SEGMENT_SECONDS,
    overlap: float = 0.0,
) -> tuple[np.ndarray, list[np.ndarray]]:
    """Load and clean a file, then split it into segments.

    Returns:
        (cleaned_signal, segments). Clips shorter than MIN_SEGMENT_SECONDS but with
        at least MIN_SPEECH_SECONDS of speech are returned as a single segment.
    """
    y = load_audio(path, sr)
    cleaned = clean_signal(y, sr, noise_reduction=noise_reduction)
    segments = segment_audio(cleaned, sr, segment_seconds=segment_seconds, overlap=overlap)
    if not segments:
        segments = [cleaned]
    return cleaned, segments


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Preprocess a single audio file.")
    parser.add_argument("audio", help="Path to an audio file (wav/mp3/flac/ogg)")
    parser.add_argument("--augment", action="store_true", help="Also generate augmented segments")
    parser.add_argument("--no-noise-reduction", action="store_true", help="Disable noise reduction")
    parser.add_argument("--out-dir", help="Optional folder to save the processed segments as WAV")
    args = parser.parse_args(argv)

    try:
        raw = load_audio(args.audio)
        cleaned, segments = preprocess_file(args.audio, noise_reduction=not args.no_noise_reduction)
    except AudioProcessingError as exc:
        print(f"[ERROR] {exc}", file=sys.stderr)
        return 1

    print(f"File            : {args.audio}")
    print(f"Original length : {raw.size / SAMPLE_RATE:.2f} s")
    print(f"Speech length   : {cleaned.size / SAMPLE_RATE:.2f} s (after cleaning)")
    print(f"Segments        : {len(segments)} x ~{SEGMENT_SECONDS} s")

    all_segments = list(segments)
    if args.augment:
        rng = np.random.default_rng(42)
        augmented = [a for seg in segments for a in augment_segment(seg, SAMPLE_RATE, rng)]
        all_segments += augmented
        print(f"Augmented       : +{len(augmented)} segments (noise + pitch shift)")

    if args.out_dir:
        out_dir = Path(args.out_dir)
        for i, seg in enumerate(all_segments):
            save_wav(seg, out_dir / f"segment_{i:03d}.wav")
        print(f"Saved {len(all_segments)} segments to {out_dir.resolve()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
