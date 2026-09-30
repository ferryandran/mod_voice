"""
embeddings.py
-------------
ECAPA-TDNN speaker embeddings - the feature representation of this project.

A pretrained ECAPA-TDNN model (SpeechBrain, trained on VoxCeleb - thousands of speakers)
turns each audio segment into a 192-dimensional vector that describes WHO is speaking and
is far less sensitive to microphone, room and spoken words than hand-crafted features.
The scikit-learn classifiers (SVM / Random Forest / MLP) are trained on these vectors.

Requires: torch, torchaudio, speechbrain (see requirements.txt). The model (~80 MB) is
downloaded from HuggingFace on first use and cached in pretrained_models/.

Standalone usage:
    python src/embeddings.py path/to/audio.wav
"""

from __future__ import annotations

import argparse
import sys
import warnings
from pathlib import Path
from typing import Callable

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import numpy as np

from src.preprocess import PROJECT_ROOT, SAMPLE_RATE, AudioProcessingError, preprocess_file

ECAPA_SOURCE = "speechbrain/spkrec-ecapa-voxceleb"
ECAPA_DIR = PROJECT_ROOT / "pretrained_models" / "spkrec-ecapa-voxceleb"
ECAPA_DIM = 192
BATCH_SIZE = 16

FEATURE_TYPE = "ecapa"
FEATURE_LABEL = "ECAPA-TDNN speaker embedding (192-d, pretrained on VoxCeleb)"

# Log-mel front end of ECAPA (25 ms window, 10 ms hop, 80 bands) - used for the UI heatmap.
N_MELS = 80
MEL_N_FFT = 400
MEL_HOP_LENGTH = 160

_encoder = None


class EmbeddingUnavailableError(Exception):
    """Raised when torch/speechbrain are missing or the pretrained model cannot be loaded."""


def is_available() -> bool:
    """True if torch and speechbrain are installed.

    Uses find_spec instead of a real import: it is much faster and keeps the heavy
    packages out of sys.modules until get_encoder() actually needs them.
    """
    import importlib.util

    try:
        return all(importlib.util.find_spec(name) is not None for name in ("torch", "speechbrain"))
    except (ImportError, ValueError):
        return False


def get_encoder():
    """Load the pretrained ECAPA model once (downloads it on first use)."""
    global _encoder
    if _encoder is not None:
        return _encoder
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            from speechbrain.inference.speaker import EncoderClassifier

            _encoder = EncoderClassifier.from_hparams(
                source=ECAPA_SOURCE, savedir=str(ECAPA_DIR), run_opts={"device": "cpu"}
            )
    except ImportError as exc:
        raise EmbeddingUnavailableError(
            "ECAPA features need torch, torchaudio and speechbrain. Install them with "
            "`pip install -r requirements.txt`."
        ) from exc
    except Exception as exc:
        raise EmbeddingUnavailableError(
            f"Could not load the ECAPA model (first use needs internet to download it): {exc}"
        ) from exc
    _encoder.eval()
    return _encoder


def extract(
    segments: list[np.ndarray],
    sr: int = SAMPLE_RATE,
    progress_callback: Callable[[int, int], None] | None = None,
) -> np.ndarray:
    """ECAPA embeddings for many segments -> array of shape (n_segments, ECAPA_DIM).

    Vectors are L2-normalized (speaker embeddings are compared by direction, not length).
    """
    if not segments:
        return np.empty((0, ECAPA_DIM), dtype=np.float32)
    if sr != SAMPLE_RATE:
        raise ValueError(f"ECAPA expects {SAMPLE_RATE} Hz audio, got {sr} Hz.")

    import torch

    encoder = get_encoder()
    rows: list[np.ndarray] = []
    total = len(segments)
    for start in range(0, total, BATCH_SIZE):
        batch = segments[start:start + BATCH_SIZE]
        max_len = max(seg.size for seg in batch)
        wavs = torch.zeros(len(batch), max_len)
        for i, seg in enumerate(batch):
            wavs[i, :seg.size] = torch.from_numpy(np.asarray(seg, dtype=np.float32))
        wav_lens = torch.tensor([seg.size / max_len for seg in batch])  # relative lengths (padding)
        with torch.no_grad():
            emb = encoder.encode_batch(wavs, wav_lens).squeeze(1).cpu().numpy()
        rows.append(emb)
        if progress_callback is not None:
            progress_callback(min(start + BATCH_SIZE, total), total)

    X = np.vstack(rows)
    X /= np.linalg.norm(X, axis=1, keepdims=True) + 1e-12
    return np.nan_to_num(X).astype(np.float32)


# Backwards-compatible alias (older code/notebooks called this name).
extract_embeddings_batch = extract


def log_mel_spectrogram(y: np.ndarray, sr: int = SAMPLE_RATE) -> np.ndarray:
    """Log-mel filterbank (N_MELS x frames) - what ECAPA sees before its TDNN layers.

    Visualization only; the classifiers are trained on the 192-d embedding, not on this.
    """
    import librosa

    mel = librosa.feature.melspectrogram(
        y=y, sr=sr, n_fft=MEL_N_FFT, hop_length=MEL_HOP_LENGTH, n_mels=N_MELS
    )
    return librosa.power_to_db(mel, ref=np.max)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Extract ECAPA speaker embeddings from an audio file.")
    parser.add_argument("audio", help="Path to an audio file")
    parser.add_argument("--no-noise-reduction", action="store_true", help="Disable noise reduction")
    args = parser.parse_args(argv)

    try:
        _, segments = preprocess_file(args.audio, noise_reduction=not args.no_noise_reduction)
        X = extract(segments)
    except (AudioProcessingError, EmbeddingUnavailableError) as exc:
        print(f"[ERROR] {exc}", file=sys.stderr)
        return 1

    print(f"Segments  : {X.shape[0]}")
    print(f"Embedding : {X.shape[1]} values (expected {ECAPA_DIM})")
    print("First values:", np.round(X[0, :8], 4))
    return 0


if __name__ == "__main__":
    sys.exit(main())
