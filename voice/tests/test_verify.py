"""Tests for speaker verification: enrollment storage, cosine scoring, thresholds.

The tests that need the ECAPA encoder are marked `slow` - the first run downloads the
~80 MB checkpoint. Skip them with `pytest -m "not slow"`.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from src.embeddings import ECAPA_DIM, is_available as ecapa_available  # noqa: E402
from src.preprocess import SAMPLE_RATE, save_wav  # noqa: E402
from src.verify import (  # noqa: E402
    DEFAULT_VERIFY_THRESHOLD,
    MIN_ENROLL_SEGMENTS,
    EnrollmentError,
    EnrollmentStore,
    MemberEnrollment,
    NotEnrolledError,
    _normalize,
)

needs_ecapa = pytest.mark.skipif(not ecapa_available(), reason="torch/speechbrain not installed")
slow = pytest.mark.slow


# ---------------------------------------------------------------------------
# Pure logic - no model needed
# ---------------------------------------------------------------------------
def test_normalize_gives_unit_length():
    v = np.array([3.0, 4.0], dtype=np.float32)
    out = _normalize(v)
    assert np.linalg.norm(out) == pytest.approx(1.0, abs=1e-6)


def test_normalize_survives_all_zeros():
    """A zero vector has no direction; normalizing must not divide by zero."""
    out = _normalize(np.zeros(8, dtype=np.float32))
    assert np.all(np.isfinite(out))


def _fake_enrollment(name: str, seed: int) -> MemberEnrollment:
    rng = np.random.default_rng(seed)
    return MemberEnrollment(
        name=name,
        centroid=_normalize(rng.normal(size=ECAPA_DIM).astype(np.float32)),
        n_segments=12,
        n_files=3,
        updated_at="2026-10-01 00:00:00",
    )


def test_store_roundtrips_through_disk(models_dir: Path):
    store = EnrollmentStore(models_dir)
    store._members["Budi"] = _fake_enrollment("Budi", 1)
    store.save()

    reloaded = EnrollmentStore(models_dir)
    assert reloaded.members == ["Budi"]
    assert reloaded.get("Budi").centroid.shape == (ECAPA_DIM,)
    np.testing.assert_allclose(
        reloaded.get("Budi").centroid, store.get("Budi").centroid, atol=1e-6
    )


def test_lookup_is_case_insensitive(models_dir: Path):
    store = EnrollmentStore(models_dir)
    store._members["Budi"] = _fake_enrollment("Budi", 1)
    assert store.get("budi").name == "Budi"
    assert store.get("  BUDI  ").name == "Budi"
    assert store.has("bUdI")


def test_missing_member_raises(models_dir: Path):
    store = EnrollmentStore(models_dir)
    with pytest.raises(NotEnrolledError):
        store.get("Nobody")
    assert not store.has("Nobody")


def test_remove_deletes_and_persists(models_dir: Path):
    store = EnrollmentStore(models_dir)
    store._members["Budi"] = _fake_enrollment("Budi", 1)
    store.save()

    assert store.remove("budi") is True
    assert EnrollmentStore(models_dir).members == []
    assert store.remove("budi") is False


def test_corrupt_file_does_not_wipe_loaded_state(models_dir: Path):
    store = EnrollmentStore(models_dir)
    store._members["Budi"] = _fake_enrollment("Budi", 1)
    store.save()

    (models_dir / "enrollments.json").write_text("{not valid json", encoding="utf-8")
    store.reload()
    assert store.members == ["Budi"], "a mid-write read must not drop enrollments"


def test_wrong_dimension_is_rejected(models_dir: Path):
    path = models_dir / "enrollments.json"
    path.write_text(json.dumps({
        "version": 1, "feature_type": "ecapa", "feature_dim": ECAPA_DIM,
        "members": {"Budi": {"centroid": [0.1, 0.2, 0.3], "n_segments": 9, "n_files": 2}},
    }), encoding="utf-8")
    with pytest.raises(EnrollmentError, match="Re-enroll"):
        EnrollmentStore(models_dir)


def test_save_is_atomic_leaving_no_temp_files(models_dir: Path):
    store = EnrollmentStore(models_dir)
    store._members["Budi"] = _fake_enrollment("Budi", 1)
    store.save()
    store.save()
    leftovers = [p.name for p in models_dir.iterdir() if p.suffix == ".tmp"]
    assert leftovers == [], f"temp files left behind: {leftovers}"


def test_verify_segments_scores_by_cosine_similarity(models_dir: Path):
    """The decision is a plain dot product against the centroid, so we can assert it
    exactly with hand-made vectors."""
    store = EnrollmentStore(models_dir)
    centroid = np.zeros(ECAPA_DIM, dtype=np.float32)
    centroid[0] = 1.0
    store._members["Budi"] = MemberEnrollment("Budi", centroid, 12, 3, "now")

    # Identical direction -> similarity 1.0
    X_same = np.tile(centroid, (3, 1))
    result = store.verify_segments(X_same, "Budi", threshold=0.45)
    assert result.similarity == pytest.approx(1.0, abs=1e-6)
    assert result.accepted

    # Orthogonal -> similarity 0.0
    X_orth = np.zeros((3, ECAPA_DIM), dtype=np.float32)
    X_orth[:, 1] = 1.0
    result = store.verify_segments(X_orth, "Budi", threshold=0.45)
    assert result.similarity == pytest.approx(0.0, abs=1e-6)
    assert not result.accepted


def test_verify_averages_over_segments(models_dir: Path):
    store = EnrollmentStore(models_dir)
    centroid = np.zeros(ECAPA_DIM, dtype=np.float32)
    centroid[0] = 1.0
    store._members["Budi"] = MemberEnrollment("Budi", centroid, 12, 3, "now")

    X = np.zeros((2, ECAPA_DIM), dtype=np.float32)
    X[0, 0] = 1.0     # similarity 1.0
    X[1, 1] = 1.0     # similarity 0.0
    result = store.verify_segments(X, "Budi", threshold=0.45)
    assert result.similarity == pytest.approx(0.5, abs=1e-6)
    assert result.n_segments == 2
    assert result.per_segment == pytest.approx([1.0, 0.0], abs=1e-6)


def test_threshold_boundary_is_inclusive(models_dir: Path):
    store = EnrollmentStore(models_dir)
    centroid = np.zeros(ECAPA_DIM, dtype=np.float32)
    centroid[0] = 1.0
    store._members["Budi"] = MemberEnrollment("Budi", centroid, 12, 3, "now")
    X = np.zeros((1, ECAPA_DIM), dtype=np.float32)
    X[0, 0] = 0.5

    assert store.verify_segments(X, "Budi", threshold=0.5).accepted
    assert not store.verify_segments(X, "Budi", threshold=0.50001).accepted


def test_verify_reports_closest_other_member(models_dir: Path):
    """Diagnostics: if a stranger scores higher against someone else, the logs show it."""
    store = EnrollmentStore(models_dir)
    budi = np.zeros(ECAPA_DIM, dtype=np.float32); budi[0] = 1.0
    ani = np.zeros(ECAPA_DIM, dtype=np.float32); ani[1] = 1.0
    store._members["Budi"] = MemberEnrollment("Budi", budi, 12, 3, "now")
    store._members["Ani"] = MemberEnrollment("Ani", ani, 12, 3, "now")

    X = np.zeros((1, ECAPA_DIM), dtype=np.float32)
    X[0, 1] = 1.0                                     # looks exactly like Ani
    result = store.verify_segments(X, "Budi", threshold=0.45)
    assert not result.accepted
    assert result.best_other == "Ani"
    assert result.best_other_similarity == pytest.approx(1.0, abs=1e-6)


def test_single_member_verification_works(models_dir: Path):
    """The whole point of switching to verification: one enrolled member is enough."""
    store = EnrollmentStore(models_dir)
    centroid = np.zeros(ECAPA_DIM, dtype=np.float32); centroid[0] = 1.0
    store._members["Solo"] = MemberEnrollment("Solo", centroid, 12, 3, "now")

    result = store.verify_segments(np.tile(centroid, (4, 1)), "Solo", threshold=0.45)
    assert result.accepted
    assert result.best_other is None


# ---------------------------------------------------------------------------
# End-to-end through the real ECAPA encoder
# ---------------------------------------------------------------------------
@needs_ecapa
@slow
def test_enroll_then_verify_same_voice(data_dir, models_dir, voice_a, write_samples):
    write_samples(data_dir, "Budi", voice_a, n_files=3)
    store = EnrollmentStore(models_dir)
    enrollment = store.enroll("Budi", data_dir)

    assert enrollment.n_files == 3
    assert enrollment.n_segments >= MIN_ENROLL_SEGMENTS
    assert enrollment.centroid.shape == (ECAPA_DIM,)
    assert np.linalg.norm(enrollment.centroid) == pytest.approx(1.0, abs=1e-4)

    probe = data_dir / "probe.wav"
    save_wav(voice_a, probe, SAMPLE_RATE)
    result = store.verify_file(probe, "Budi", threshold=DEFAULT_VERIFY_THRESHOLD)
    assert result.accepted, f"same voice scored only {result.similarity:.3f}"
    assert result.similarity > 0.5


@needs_ecapa
@slow
def test_different_voice_scores_lower_than_the_enrolled_one(
    data_dir, models_dir, voice_a, voice_b, write_samples
):
    """A different synthetic source must score below the matching one.

    This asserts ordering, not an absolute pass/fail: the fixtures are synthetic tones,
    not real speakers, so the absolute numbers carry no meaning about real accuracy.
    """
    write_samples(data_dir, "Budi", voice_a, n_files=3)
    store = EnrollmentStore(models_dir)
    store.enroll("Budi", data_dir)

    same = data_dir / "same.wav"
    other = data_dir / "other.wav"
    save_wav(voice_a, same, SAMPLE_RATE)
    save_wav(voice_b, other, SAMPLE_RATE)

    sim_same = store.verify_file(same, "Budi").similarity
    sim_other = store.verify_file(other, "Budi").similarity
    assert sim_other < sim_same, f"other={sim_other:.3f} not below same={sim_same:.3f}"


@needs_ecapa
@slow
def test_enroll_refuses_too_little_audio(data_dir, models_dir):
    """Half a second of speech cannot produce a stable voiceprint."""
    short = np.zeros(int(0.4 * SAMPLE_RATE), dtype=np.float32)
    short[::80] = 0.5
    (data_dir / "Tiny").mkdir(parents=True)
    save_wav(short, data_dir / "Tiny" / "a.wav", SAMPLE_RATE)

    store = EnrollmentStore(models_dir)
    with pytest.raises((EnrollmentError, Exception)):
        store.enroll("Tiny", data_dir)


@needs_ecapa
@slow
def test_enroll_unknown_member_raises(data_dir, models_dir):
    store = EnrollmentStore(models_dir)
    with pytest.raises(EnrollmentError, match="No sample folder"):
        store.enroll("Ghost", data_dir)
