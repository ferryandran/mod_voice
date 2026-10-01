"""Tests for replay protection - the layer that stops a recorded voice being reused."""

from __future__ import annotations

import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from src import challenge as ch  # noqa: E402
from src.challenge import ChallengeError, ChallengeManager, ReplayDetectedError  # noqa: E402


def test_challenge_is_single_use():
    mgr = ChallengeManager()
    issued = mgr.issue("Budi")

    taken = mgr.take(issued.challenge_id, "Budi")
    assert taken.challenge_id == issued.challenge_id

    with pytest.raises(ChallengeError):
        mgr.take(issued.challenge_id, "Budi")


def test_challenge_is_bound_to_its_member():
    mgr = ChallengeManager()
    issued = mgr.issue("Budi")
    with pytest.raises(ChallengeError, match="member lain"):
        mgr.take(issued.challenge_id, "Ani")


def test_challenge_member_match_ignores_case_and_padding():
    mgr = ChallengeManager()
    issued = mgr.issue("Budi")
    assert mgr.take(issued.challenge_id, "  budi ").member == "Budi"


def test_unknown_challenge_is_rejected():
    mgr = ChallengeManager()
    with pytest.raises(ChallengeError):
        mgr.take("not-a-real-id", "Budi")


def test_expired_challenge_is_rejected(monkeypatch):
    monkeypatch.setattr(ch, "CHALLENGE_TTL_SECONDS", 0.05)
    mgr = ChallengeManager()
    issued = mgr.issue("Budi")
    time.sleep(0.08)
    with pytest.raises(ChallengeError, match="kedaluwarsa"):
        mgr.take(issued.challenge_id, "Budi")


def test_identical_audio_is_rejected_after_being_remembered():
    mgr = ChallengeManager()
    audio = b"RIFF....fake wav payload...."

    digest = mgr.check_not_replayed(audio)   # first time: fine
    mgr.remember(digest)

    with pytest.raises(ReplayDetectedError):
        mgr.check_not_replayed(audio)


def test_failed_attempt_does_not_burn_the_fingerprint():
    """A rejected attempt must stay replayable, or one failed try would lock the
    owner out of ever using that recording again."""
    mgr = ChallengeManager()
    audio = b"some audio"
    mgr.check_not_replayed(audio)           # verification would fail here...
    mgr.check_not_replayed(audio)           # ...so the same bytes are still allowed


def test_different_audio_is_not_confused():
    mgr = ChallengeManager()
    mgr.remember(mgr.check_not_replayed(b"audio one"))
    assert mgr.check_not_replayed(b"audio two")  # no exception


def test_passphrase_is_skipped_when_not_required():
    mgr = ChallengeManager(require_passphrase=False)
    issued = mgr.issue("Budi")
    assert issued.words == []
    ok, ratio = mgr.passphrase_matches(issued, "apa saja")
    assert ok and ratio == 1.0


def test_passphrase_words_are_issued_when_required():
    mgr = ChallengeManager(require_passphrase=True)
    issued = mgr.issue("Budi")
    assert len(issued.words) == ch.PASSPHRASE_WORD_COUNT
    assert len(set(issued.words)) == len(issued.words), "words must not repeat"
    assert all(w in ch.PASSPHRASE_WORDS_ID for w in issued.words)


def test_passphrase_accepts_exact_transcript():
    mgr = ChallengeManager(require_passphrase=True)
    issued = mgr.issue("Budi")
    ok, ratio = mgr.passphrase_matches(issued, issued.phrase)
    assert ok and ratio == 1.0


def test_passphrase_tolerates_punctuation_case_and_filler():
    mgr = ChallengeManager(require_passphrase=True)
    issued = mgr.issue("Budi")
    noisy = f"Eh, {issued.words[0].upper()}... {issued.words[1]}, {issued.words[2]}!"
    ok, _ = mgr.passphrase_matches(issued, noisy)
    assert ok


def test_passphrase_rejects_a_different_phrase():
    """This is the point of the passphrase: a recording of the owner saying something
    else must not open the door."""
    mgr = ChallengeManager(require_passphrase=True)
    issued = mgr.issue("Budi")
    ok, ratio = mgr.passphrase_matches(issued, "selamat pagi semuanya")
    assert not ok and ratio == 0.0


def test_passphrase_partial_match_follows_the_ratio(monkeypatch):
    monkeypatch.setattr(ch, "PASSPHRASE_MATCH_RATIO", 0.6)
    mgr = ChallengeManager(require_passphrase=True)
    issued = mgr.issue("Budi")
    # 2 of 3 words = 0.667, above the 0.6 bar
    ok, ratio = mgr.passphrase_matches(issued, f"{issued.words[0]} {issued.words[1]}")
    assert ok and ratio == pytest.approx(2 / 3, abs=1e-6)
    # 1 of 3 = 0.333, below it
    ok, ratio = mgr.passphrase_matches(issued, issued.words[0])
    assert not ok


def test_english_word_pool_used_for_english_language():
    mgr = ChallengeManager(require_passphrase=True, language="en-US")
    issued = mgr.issue("Budi")
    assert all(w in ch.PASSPHRASE_WORDS_EN for w in issued.words)


def test_state_is_capped(monkeypatch):
    """A flood of requests must not grow memory without limit."""
    monkeypatch.setattr(ch, "MAX_CHALLENGES", 10)
    mgr = ChallengeManager()
    for i in range(50):
        mgr.issue(f"member{i}")
    assert mgr.stats()["outstanding_challenges"] <= 10


def test_fingerprints_are_capped(monkeypatch):
    monkeypatch.setattr(ch, "MAX_FINGERPRINTS", 10)
    mgr = ChallengeManager()
    for i in range(50):
        mgr.remember(ch.fingerprint(f"audio-{i}".encode()))
    assert mgr.stats()["remembered_recordings"] <= 10


def test_fingerprint_is_stable_and_distinct():
    assert ch.fingerprint(b"abc") == ch.fingerprint(b"abc")
    assert ch.fingerprint(b"abc") != ch.fingerprint(b"abd")
