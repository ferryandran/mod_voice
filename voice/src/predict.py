"""
predict.py
----------
Speaker inference + optional speech-to-text transcript.

Pipeline:
    audio -> preprocessing -> segments -> ECAPA embeddings -> scaler -> model.predict_proba
    -> average probabilities over all segments (soft voting)
    -> top speaker, or "Unknown Voice" if confidence < threshold

Standalone usage:
    python src/predict.py path/to/audio.wav [--threshold 0.6] [--transcribe] [--language en-US]
"""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
import warnings
from dataclasses import dataclass, field
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import joblib
import numpy as np

from src.embeddings import (
    ECAPA_DIM,
    FEATURE_LABEL,
    FEATURE_TYPE,
    EmbeddingUnavailableError,
    extract,
    get_encoder,
)
from src.preprocess import (
    MODELS_DIR,
    SAMPLE_RATE,
    AudioProcessingError,
    load_audio,
    preprocess_file,
    save_wav,
)

MODEL_FILE = "model.pkl"
SCALER_FILE = "scaler.pkl"
ENCODER_FILE = "label_encoder.pkl"
METADATA_FILE = "metadata.json"

UNKNOWN_LABEL = "Unknown Voice"
DEFAULT_THRESHOLD = 0.60


class ModelNotTrainedError(Exception):
    """Raised when model files are missing, corrupt or incompatible."""


class TranscriptionError(Exception):
    """Raised when speech-to-text fails (offline, unclear audio, missing package...)."""


@dataclass
class PredictionResult:
    speaker: str                      # predicted member or UNKNOWN_LABEL
    top_candidate: str                # best-matching member (even if below threshold)
    confidence: float                 # averaged probability of top_candidate
    threshold: float
    is_known: bool
    probabilities: dict[str, float]   # all members, sorted high -> low
    class_names: list[str]            # column order of segment_probabilities
    segment_probabilities: np.ndarray = field(repr=False)  # (n_segments, n_members)
    signal: np.ndarray = field(repr=False)                 # cleaned audio used for inference
    sample_rate: int = SAMPLE_RATE

    @property
    def n_segments(self) -> int:
        return int(self.segment_probabilities.shape[0])


def models_exist(models_dir: str | Path = MODELS_DIR) -> bool:
    """True if all required model artifacts are present."""
    models_dir = Path(models_dir)
    return all((models_dir / f).exists() for f in (MODEL_FILE, SCALER_FILE, ENCODER_FILE))


class VoicePredictor:
    """Loads the trained artifacts once and identifies speakers from audio files."""

    def __init__(self, models_dir: str | Path = MODELS_DIR):
        self.models_dir = Path(models_dir)
        if not models_exist(self.models_dir):
            raise ModelNotTrainedError(
                "No trained model found. Register at least 2 members and train the model first."
            )
        try:
            with warnings.catch_warnings():
                warnings.simplefilter("ignore")
                self.model = joblib.load(self.models_dir / MODEL_FILE)
                self.scaler = joblib.load(self.models_dir / SCALER_FILE)
                self.label_encoder = joblib.load(self.models_dir / ENCODER_FILE)
        except Exception as exc:
            raise ModelNotTrainedError(f"Model files are corrupt or incompatible ({exc}). Please retrain.") from exc

        if not hasattr(self.model, "predict_proba"):
            raise ModelNotTrainedError("Saved model does not support probability estimates. Please retrain.")

        self.metadata = self._load_metadata()
        self.noise_reduction = bool(self.metadata.get("noise_reduction", True))
        self.feature_type = str(self.metadata.get("feature_type", FEATURE_TYPE))
        if self.feature_type != FEATURE_TYPE:
            raise ModelNotTrainedError(
                f"Saved model was trained with '{self.feature_type}' features, but this version "
                f"only supports ECAPA embeddings. Please retrain."
            )
        if getattr(self.scaler, "n_features_in_", ECAPA_DIM) != ECAPA_DIM:
            raise ModelNotTrainedError("Saved model uses a different feature configuration. Please retrain.")
        try:
            get_encoder()
        except EmbeddingUnavailableError as exc:
            raise ModelNotTrainedError(f"Model needs the pretrained ECAPA encoder, but {exc}") from exc
        self.class_names = [str(c) for c in self.label_encoder.inverse_transform(self.model.classes_)]

    def _load_metadata(self) -> dict:
        path = self.models_dir / METADATA_FILE
        try:
            with open(path, encoding="utf-8") as fh:
                return json.load(fh)
        except (OSError, json.JSONDecodeError):
            return {}

    @property
    def members(self) -> list[str]:
        return list(self.class_names)

    def predict(self, audio_path: str | Path, threshold: float = DEFAULT_THRESHOLD) -> PredictionResult:
        """Identify the speaker in `audio_path`.

        Raises:
            AudioProcessingError: unreadable file or not enough speech.
        """
        cleaned, segments = preprocess_file(audio_path, noise_reduction=self.noise_reduction)
        X = self.scaler.transform(extract(segments))
        segment_proba = self.model.predict_proba(X)
        mean_proba = segment_proba.mean(axis=0)  # soft voting over segments

        order = np.argsort(mean_proba)[::-1]
        top = int(order[0])
        confidence = float(mean_proba[top])
        top_name = self.class_names[top]
        is_known = confidence >= threshold

        return PredictionResult(
            speaker=top_name if is_known else UNKNOWN_LABEL,
            top_candidate=top_name,
            confidence=confidence,
            threshold=threshold,
            is_known=is_known,
            probabilities={self.class_names[i]: float(mean_proba[i]) for i in order},
            class_names=list(self.class_names),
            segment_probabilities=segment_proba,
            signal=cleaned,
        )


def transcribe(audio_path: str | Path, language: str = "en-US", timeout: float = 15.0) -> str:
    """Speech-to-text via Google Web Speech API (requires internet).

    Raises:
        TranscriptionError: package missing, offline, API error or no recognizable speech.
    """
    try:
        import speech_recognition as sr
    except ImportError as exc:
        raise TranscriptionError("SpeechRecognition package is not installed.") from exc

    # SpeechRecognition only reads WAV/AIFF/FLAC -> convert to a temporary 16 kHz WAV first
    try:
        y = load_audio(audio_path, SAMPLE_RATE)
    except AudioProcessingError as exc:
        raise TranscriptionError(str(exc)) from exc

    tmp = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
    tmp.close()
    tmp_path = Path(tmp.name)
    try:
        save_wav(y, tmp_path, SAMPLE_RATE)
        recognizer = sr.Recognizer()
        recognizer.operation_timeout = timeout
        with sr.AudioFile(str(tmp_path)) as source:
            audio = recognizer.record(source)
        return recognizer.recognize_google(audio, language=language)
    except sr.UnknownValueError as exc:
        raise TranscriptionError("Speech could not be understood (unclear audio or no words).") from exc
    except sr.RequestError as exc:
        raise TranscriptionError(f"Transcription service unavailable (offline?): {exc}") from exc
    except Exception as exc:
        raise TranscriptionError(f"Transcription failed: {exc}") from exc
    finally:
        tmp_path.unlink(missing_ok=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Identify the speaker in an audio file.")
    parser.add_argument("audio", help="Path to an audio file")
    parser.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD, help="Unknown-voice threshold (0-1)")
    parser.add_argument("--models-dir", default=str(MODELS_DIR), help="Folder containing model files")
    parser.add_argument("--transcribe", action="store_true", help="Also transcribe speech (needs internet)")
    parser.add_argument("--language", default="en-US", help="Transcription language, e.g. en-US or id-ID")
    args = parser.parse_args(argv)

    try:
        predictor = VoicePredictor(args.models_dir)
        result = predictor.predict(args.audio, threshold=args.threshold)
    except (ModelNotTrainedError, AudioProcessingError) as exc:
        print(f"[ERROR] {exc}", file=sys.stderr)
        return 1

    status = "RECOGNIZED" if result.is_known else "UNKNOWN"
    print(f"Speaker    : {result.speaker}  [{status}]")
    print(f"Confidence : {result.confidence:.2%} (threshold {result.threshold:.0%}, closest: {result.top_candidate})")
    print(f"Segments   : {result.n_segments} ({FEATURE_LABEL})")
    print("Probabilities:")
    for name, p in result.probabilities.items():
        print(f"  {name:<20} {p:7.2%}  {'#' * int(p * 40)}")

    if args.transcribe:
        try:
            print(f"Transcript : {transcribe(args.audio, args.language)}")
        except TranscriptionError as exc:
            print(f"Transcript : (unavailable) {exc}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
