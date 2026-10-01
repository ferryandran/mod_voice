"""Shared fixtures: synthetic speech-like audio and isolated data/model folders.

The tests never touch the real `data/raw` or `models` folders - every fixture points the
modules at a tmp_path instead, so running the suite cannot destroy someone's voiceprints.
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from src.preprocess import SAMPLE_RATE, save_wav  # noqa: E402


def synth_voice(
    seed: int,
    seconds: float = 4.0,
    f0: float = 120.0,
    formants: tuple[float, ...] = (700.0, 1220.0, 2600.0),
    sr: int = SAMPLE_RATE,
) -> np.ndarray:
    """Build a crude voiced-speech signal: a buzzy glottal source shaped by formants.

    This is NOT real speech, so it says nothing about how well the system separates real
    people. It is a deterministic, dependency-free stand-in that exercises the whole
    pipeline (preprocess -> ECAPA -> cosine similarity) and lets us assert the
    *structural* properties: identical input scores near 1.0, a different synthetic
    "voice" scores lower, and segment counts line up.
    """
    rng = np.random.default_rng(seed)
    t = np.arange(int(seconds * sr), dtype=np.float32) / sr

    # Slightly wavering pitch so the signal is not a pure tone.
    vibrato = 1.0 + 0.02 * np.sin(2 * np.pi * 4.5 * t)
    phase = 2 * np.pi * f0 * np.cumsum(vibrato) / sr

    # Glottal source: harmonics with 1/n falloff.
    source = sum(np.sin(n * phase) / n for n in range(1, 25)).astype(np.float32)

    # Formant resonances give each synthetic "speaker" its own spectral envelope.
    shaped = np.zeros_like(source)
    for fc in formants:
        shaped += np.sin(2 * np.pi * fc * t) * source * 0.3
    signal = source * 0.6 + shaped

    # Syllable-rate amplitude envelope, so silence trimming has something to chew on.
    envelope = 0.55 + 0.45 * np.sin(2 * np.pi * 3.1 * t + rng.uniform(0, np.pi))
    signal = signal * envelope
    signal += rng.normal(0.0, 0.004, signal.shape).astype(np.float32)

    peak = float(np.max(np.abs(signal)))
    return (signal / peak * 0.9).astype(np.float32) if peak > 0 else signal


@pytest.fixture
def voice_a() -> np.ndarray:
    return synth_voice(seed=1, f0=118.0, formants=(700.0, 1220.0, 2600.0))


@pytest.fixture
def voice_b() -> np.ndarray:
    """A clearly different synthetic voice: higher pitch, shifted formants."""
    return synth_voice(seed=2, f0=205.0, formants=(430.0, 2100.0, 3100.0))


@pytest.fixture
def data_dir(tmp_path: Path) -> Path:
    d = tmp_path / "data" / "raw"
    d.mkdir(parents=True)
    return d


@pytest.fixture
def models_dir(tmp_path: Path) -> Path:
    d = tmp_path / "models"
    d.mkdir(parents=True)
    return d


@pytest.fixture
def write_samples():
    """Helper: write N copies of a signal into data_dir/<member>/ as separate files."""

    def _write(data_dir: Path, member: str, signal: np.ndarray, n_files: int = 3) -> list[Path]:
        member_dir = data_dir / member
        member_dir.mkdir(parents=True, exist_ok=True)
        paths = []
        rng = np.random.default_rng(99)
        for i in range(n_files):
            # Vary each "recording" slightly so they are not byte-identical.
            jittered = signal + rng.normal(0.0, 0.002, signal.shape).astype(np.float32)
            paths.append(save_wav(jittered, member_dir / f"rec_{i:02d}.wav", SAMPLE_RATE))
        return paths

    return _write
