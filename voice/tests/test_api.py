"""Tests for the HTTP surface: authentication, threshold clamping, challenge flow.

These use Flask's test client, so no server is started and no ECAPA model is needed -
verification itself is stubbed. The point is to pin the *security* behaviour: an
unauthenticated caller gets nothing, and a caller cannot talk the server into a weaker
threshold than it was configured with.
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import voice_api_server as api  # noqa: E402
from src.verify import MemberEnrollment  # noqa: E402
from src.embeddings import ECAPA_DIM  # noqa: E402

TOKEN = "test-token-abc123"


@pytest.fixture
def client(monkeypatch, tmp_path):
    """An app configured with one enrolled member and a known token."""
    monkeypatch.setattr(api, "_api_token", TOKEN)
    monkeypatch.setattr(api, "_auth_required", True)
    monkeypatch.setattr(api, "_verify_threshold", 0.45)
    monkeypatch.setattr(api, "_require_challenge", True)

    centroid = np.zeros(ECAPA_DIM, dtype=np.float32)
    centroid[0] = 1.0

    class StubStore:
        def __init__(self):
            self._members = {"Budi": MemberEnrollment("Budi", centroid, 12, 3, "now")}
            self.last_threshold = None

        @property
        def members(self):
            return ["Budi"]

        def has(self, m):
            return m.strip().lower() == "budi"

        def get(self, m):
            if not self.has(m):
                from src.verify import NotEnrolledError
                raise NotEnrolledError(f"'{m}' belum enrolled")
            return self._members["Budi"]

        def summary(self):
            return {"Budi": {"n_segments": 12, "n_files": 3}}

        def verify_segments(self, X, member, threshold):
            from src.verify import VerificationResult
            self.last_threshold = threshold
            return VerificationResult(
                member="Budi", accepted=0.8 >= threshold, similarity=0.8,
                threshold=threshold, n_segments=2, per_segment=[0.82, 0.78],
            )

    stub = StubStore()
    monkeypatch.setattr(api, "get_store", lambda: stub)
    monkeypatch.setattr(api, "decode_audio", lambda b: np.zeros(16000, dtype=np.float32))
    monkeypatch.setattr(api, "preprocess_file", lambda p, **kw: (None, [np.zeros(16000)]))
    monkeypatch.setattr(api, "extract", lambda segs: np.tile(centroid, (2, 1)))
    monkeypatch.setattr(api, "save_wav", lambda y, p, sr=16000: Path(p))
    # Fresh challenge manager and rate-limit buckets per test: both are module-level
    # singletons in production, so without this one test's attempts throttle the next.
    monkeypatch.setattr(api, "_challenges", type(api._challenges)())
    monkeypatch.setattr(api, "_attempts", {})

    api.app.config["TESTING"] = True
    with api.app.test_client() as c:
        c.stub = stub
        yield c


def auth(**extra):
    headers = {api.AUTH_HEADER: TOKEN}
    headers.update(extra)
    return headers


def get_challenge(client, member="Budi"):
    resp = client.post("/challenge", data={"member": member}, headers=auth())
    assert resp.status_code == 200, resp.get_json()
    return resp.get_json()["challenge_id"]


# ---------------------------------------------------------------------------
# Authentication
# ---------------------------------------------------------------------------
def test_health_needs_no_token(client):
    assert client.get("/health").status_code == 200


@pytest.mark.parametrize("path,method", [
    ("/status", "get"), ("/challenge", "post"), ("/verify", "post"),
    ("/register", "post"), ("/enroll", "post"), ("/train", "post"),
])
def test_every_real_endpoint_requires_a_token(client, path, method):
    resp = getattr(client, method)(path)
    assert resp.status_code == 401, f"{path} answered {resp.status_code} without a token"
    assert resp.get_json()["success"] is False


def test_wrong_token_is_rejected(client):
    resp = client.get("/status", headers={api.AUTH_HEADER: "wrong"})
    assert resp.status_code == 401


def test_correct_token_is_accepted(client):
    assert client.get("/status", headers=auth()).status_code == 200


def test_status_reports_verification_mode(client):
    body = client.get("/status", headers=auth()).get_json()
    assert body["mode"] == "verification"
    assert body["enrolled_members"] == ["Budi"]
    assert "replay_protection" in body


# ---------------------------------------------------------------------------
# Threshold clamping - the bypass this used to allow
# ---------------------------------------------------------------------------
def test_client_cannot_weaken_the_threshold(client):
    """The original bug: threshold=0 made every voice 'the owner'."""
    cid = get_challenge(client)
    resp = client.post("/verify", headers=auth(), data={
        "member": "Budi", "challenge_id": cid, "threshold": "0",
        "audio": (Path(__file__), "voice.wav"),
    })
    assert resp.status_code == 200
    assert client.stub.last_threshold >= api.MIN_ALLOWED_THRESHOLD


def test_client_may_ask_for_a_stricter_threshold(client):
    cid = get_challenge(client)
    client.post("/verify", headers=auth(), data={
        "member": "Budi", "challenge_id": cid, "threshold": "0.9",
        "audio": (Path(__file__), "voice.wav"),
    })
    assert client.stub.last_threshold == pytest.approx(0.9)


def test_garbage_threshold_falls_back_instead_of_crashing(client):
    """This used to raise ValueError outside the try block -> HTML 500, not JSON."""
    cid = get_challenge(client)
    resp = client.post("/verify", headers=auth(), data={
        "member": "Budi", "challenge_id": cid, "threshold": "not-a-number",
        "audio": (Path(__file__), "voice.wav"),
    })
    assert resp.status_code == 200
    assert resp.is_json
    assert client.stub.last_threshold >= api.MIN_ALLOWED_THRESHOLD


@pytest.mark.parametrize("raw,expected_at_least", [
    ("-5", api.MIN_ALLOWED_THRESHOLD), ("nan", api.MIN_ALLOWED_THRESHOLD),
    ("inf", api.MIN_ALLOWED_THRESHOLD), ("", api.MIN_ALLOWED_THRESHOLD),
])
def test_clamp_threshold_handles_hostile_input(raw, expected_at_least, monkeypatch):
    monkeypatch.setattr(api, "_verify_threshold", 0.45)
    assert api.clamp_threshold(raw) >= expected_at_least
    assert api.clamp_threshold(raw) <= api.MAX_ALLOWED_THRESHOLD


def test_clamp_threshold_never_exceeds_the_ceiling(monkeypatch):
    monkeypatch.setattr(api, "_verify_threshold", 0.45)
    assert api.clamp_threshold("99") == api.MAX_ALLOWED_THRESHOLD


# ---------------------------------------------------------------------------
# Challenge flow
# ---------------------------------------------------------------------------
def test_verify_without_a_challenge_is_refused(client):
    resp = client.post("/verify", headers=auth(), data={
        "member": "Budi", "audio": (Path(__file__), "voice.wav"),
    })
    assert resp.status_code == 428
    assert "challenge" in resp.get_json()["error"].lower()


def test_challenge_cannot_be_reused(client):
    cid = get_challenge(client)
    first = client.post("/verify", headers=auth(), data={
        "member": "Budi", "challenge_id": cid, "audio": (Path(__file__), "v.wav")})
    assert first.status_code == 200

    second = client.post("/verify", headers=auth(), data={
        "member": "Budi", "challenge_id": cid, "audio": (Path(__file__), "v.wav")})
    assert second.status_code == 409


def test_challenge_for_unenrolled_member_is_refused(client):
    resp = client.post("/challenge", data={"member": "Ghost"}, headers=auth())
    assert resp.status_code == 409
    assert resp.get_json()["enrolled_members"] == ["Budi"]


def test_challenge_requires_a_member(client):
    assert client.post("/challenge", data={}, headers=auth()).status_code == 400


def test_replay_of_identical_audio_is_refused(client, tmp_path):
    """Same bytes, fresh challenge: the fingerprint cache must still catch it."""
    audio = tmp_path / "same.wav"
    audio.write_bytes(b"RIFF" + b"\x00" * 100)

    cid = get_challenge(client)
    first = client.post("/verify", headers=auth(), data={
        "member": "Budi", "challenge_id": cid, "audio": (audio.open("rb"), "v.wav")})
    assert first.status_code == 200 and first.get_json()["accepted"] is True

    cid2 = get_challenge(client)
    second = client.post("/verify", headers=auth(), data={
        "member": "Budi", "challenge_id": cid2, "audio": (audio.open("rb"), "v.wav")})
    assert second.status_code == 409
    assert second.get_json().get("replay") is True


# ---------------------------------------------------------------------------
# Verify responses
# ---------------------------------------------------------------------------
def test_verify_response_keeps_backwards_compatible_fields(client):
    cid = get_challenge(client)
    body = client.post("/verify", headers=auth(), data={
        "member": "Budi", "challenge_id": cid, "audio": (Path(__file__), "v.wav")}).get_json()
    # New names
    assert body["accepted"] is True
    assert body["similarity"] == pytest.approx(0.8)
    # Aliases the older mod build reads
    assert body["is_owner"] is True
    assert body["confidence"] == pytest.approx(0.8)
    assert body["speaker"] == "Budi"


def test_verify_accepts_the_legacy_expected_speaker_field(client, tmp_path):
    """`expected_speaker` is what the older mod build sent; it must still resolve.

    Each attempt uses distinct bytes on purpose - identical audio would (correctly) be
    caught by the replay detector and mask what this test is checking.
    """
    first = tmp_path / "a.wav"
    second = tmp_path / "b.wav"
    first.write_bytes(b"RIFF" + b"\x01" * 64)
    second.write_bytes(b"RIFF" + b"\x02" * 64)

    resp = client.post("/verify", headers=auth(), data={
        "member": "Budi", "challenge_id": get_challenge(client),
        "audio": (first.open("rb"), "v.wav")})
    assert resp.status_code == 200

    resp2 = client.post("/verify", headers=auth(), data={
        "expected_speaker": "Budi", "challenge_id": get_challenge(client),
        "audio": (second.open("rb"), "v.wav")})
    assert resp2.status_code == 200
    assert resp2.get_json()["member"] == "Budi"


def test_verify_requires_a_member(client):
    assert client.post("/verify", headers=auth(), data={}).status_code == 400


def test_verify_for_unenrolled_member_is_refused(client):
    resp = client.post("/verify", headers=auth(), data={
        "member": "Ghost", "audio": (Path(__file__), "v.wav")})
    assert resp.status_code == 409


def test_rejected_voice_reports_unknown_speaker(client, monkeypatch):
    monkeypatch.setattr(api, "_verify_threshold", 0.95)
    cid = get_challenge(client)
    body = client.post("/verify", headers=auth(), data={
        "member": "Budi", "challenge_id": cid, "audio": (Path(__file__), "v.wav")}).get_json()
    assert body["accepted"] is False
    assert body["is_owner"] is False
    assert body["speaker"] == "Unknown Voice"


# ---------------------------------------------------------------------------
# Rate limiting
# ---------------------------------------------------------------------------
def test_repeated_attempts_get_rate_limited(client, monkeypatch):
    monkeypatch.setattr(api, "_attempts", {})
    codes = [client.post("/challenge", data={"member": "Budi"}, headers=auth()).status_code
             for _ in range(api.RATE_LIMIT_MAX_ATTEMPTS + 3)]
    assert 429 in codes, "brute-force attempts were never throttled"


# ---------------------------------------------------------------------------
# Misc
# ---------------------------------------------------------------------------
def test_unknown_endpoint_returns_json_not_html(client):
    resp = client.get("/nope", headers=auth())
    assert resp.status_code == 404
    assert resp.is_json


def test_no_auth_mode_lets_requests_through(client, monkeypatch):
    monkeypatch.setattr(api, "_auth_required", False)
    assert client.get("/status").status_code == 200
