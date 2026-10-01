"""
challenge.py
------------
Replay protection for the VoiceDoor lock.

Speaker verification alone answers "does this sound like the owner?" - it cannot tell
a live person from a loudspeaker playing a recording of that person. In Minecraft,
capturing someone's voice is trivial: proximity chat broadcasts it to everyone nearby.
Without the checks in this module, an attacker records the owner once and replays it
forever.

Two layers, cheapest first:

1. **Challenge nonce** (always on, offline). The door asks for a challenge before it
   will accept audio. Each challenge is single-use and expires, so audio cannot be
   submitted out of band or reused.

2. **Passphrase** (optional, needs internet for speech-to-text). The challenge carries
   a short random phrase that the player must actually say. A recording of the owner
   saying something else fails, so an attacker needs a recording of the owner speaking
   that exact phrase - and it is random per attempt.

Layer 1 also keeps a fingerprint of recently accepted audio, so a byte-identical
resend is rejected even if it arrives with a fresh challenge.

Note on what this does NOT stop: an attacker who can make the owner say the phrase on
demand, or a high-quality voice clone. Those are outside what a Minecraft mod can
reasonably defend against; the goal here is to defeat the realistic attack, which is
replaying captured proximity chat.
"""

from __future__ import annotations

import hashlib
import re
import secrets
import time
import unicodedata
from dataclasses import dataclass, field
from threading import Lock

# Challenge lifetime. Long enough for a player to read the phrase and speak it,
# short enough that a captured challenge id is not useful later.
CHALLENGE_TTL_SECONDS = 45.0

# How long an accepted recording's fingerprint is remembered.
REPLAY_MEMORY_SECONDS = 900.0  # 15 minutes

# Upper bound on tracked state, so a flood of requests cannot grow memory without limit.
MAX_CHALLENGES = 512
MAX_FINGERPRINTS = 4096

# Fraction of challenge words that must appear in the transcript for a pass.
PASSPHRASE_MATCH_RATIO = 0.6

# Short, phonetically distinct Indonesian words. Digits and near-homophones are avoided
# because speech-to-text confuses them ("dua"/"tua", "satu"/"sapu").
PASSPHRASE_WORDS_ID = [
    "merah", "biru", "hijau", "kuning", "hitam", "putih",
    "gunung", "sungai", "hutan", "pantai", "langit", "bintang",
    "kucing", "burung", "gajah", "harimau", "kelinci", "serigala",
    "meja", "pintu", "jendela", "lampu", "kursi", "tangga",
    "besar", "kecil", "cepat", "lambat", "tinggi", "rendah",
]

PASSPHRASE_WORDS_EN = [
    "red", "blue", "green", "yellow", "black", "white",
    "mountain", "river", "forest", "island", "thunder", "shadow",
    "tiger", "rabbit", "falcon", "dolphin", "spider", "dragon",
    "table", "window", "candle", "mirror", "ladder", "garden",
    "silver", "golden", "frozen", "hollow", "crimson", "velvet",
]

PASSPHRASE_WORD_COUNT = 3


class ChallengeError(Exception):
    """Raised when a challenge is unknown, expired, already used, or for another member."""


class ReplayDetectedError(Exception):
    """Raised when the submitted audio was already used for a successful unlock."""


def _normalize_words(text: str) -> list[str]:
    """Lowercase, strip accents and punctuation, split into words.

    Speech-to-text returns things like "Merah, gunung kucing." - we only care which
    words are present, not casing or punctuation.
    """
    decomposed = unicodedata.normalize("NFKD", text.lower())
    stripped = "".join(c for c in decomposed if not unicodedata.combining(c))
    return [w for w in re.split(r"[^a-z0-9]+", stripped) if w]


def fingerprint(audio_bytes: bytes) -> str:
    """Stable fingerprint of a recording (used for exact-replay detection)."""
    return hashlib.sha256(audio_bytes).hexdigest()


@dataclass
class Challenge:
    """One outstanding unlock attempt."""

    challenge_id: str
    member: str
    words: list[str]
    created_at: float
    language: str = "id-ID"
    used: bool = False

    @property
    def phrase(self) -> str:
        return " ".join(self.words)

    def is_expired(self, now: float | None = None) -> bool:
        return (now or time.monotonic()) - self.created_at > CHALLENGE_TTL_SECONDS

    def as_dict(self) -> dict:
        return {
            "challenge_id": self.challenge_id,
            "member": self.member,
            "phrase": self.phrase,
            "words": list(self.words),
            "language": self.language,
            "expires_in": round(CHALLENGE_TTL_SECONDS, 1),
        }


@dataclass
class ChallengeManager:
    """Thread-safe store of outstanding challenges and recent audio fingerprints.

    A single instance is shared by all Flask worker threads, so every mutation happens
    under `_lock`.
    """

    require_passphrase: bool = False
    language: str = "id-ID"
    _challenges: dict[str, Challenge] = field(default_factory=dict)
    _fingerprints: dict[str, float] = field(default_factory=dict)
    _lock: Lock = field(default_factory=Lock)

    # -- housekeeping ------------------------------------------------------
    def _prune(self, now: float) -> None:
        """Drop expired challenges and stale fingerprints. Caller must hold the lock."""
        for key in [k for k, c in self._challenges.items() if c.used or c.is_expired(now)]:
            del self._challenges[key]
        for key in [k for k, t in self._fingerprints.items() if now - t > REPLAY_MEMORY_SECONDS]:
            del self._fingerprints[key]

        # Hard caps: if something is hammering the endpoint, discard the oldest entries
        # rather than letting the dicts grow forever.
        if len(self._challenges) > MAX_CHALLENGES:
            for key, _ in sorted(self._challenges.items(), key=lambda kv: kv[1].created_at)[
                : len(self._challenges) - MAX_CHALLENGES
            ]:
                del self._challenges[key]
        if len(self._fingerprints) > MAX_FINGERPRINTS:
            for key, _ in sorted(self._fingerprints.items(), key=lambda kv: kv[1])[
                : len(self._fingerprints) - MAX_FINGERPRINTS
            ]:
                del self._fingerprints[key]

    # -- issuing -----------------------------------------------------------
    def issue(self, member: str, word_count: int = PASSPHRASE_WORD_COUNT) -> Challenge:
        """Create a single-use challenge for `member`."""
        words: list[str] = []
        if self.require_passphrase:
            pool = PASSPHRASE_WORDS_EN if self.language.lower().startswith("en") else PASSPHRASE_WORDS_ID
            # sample without replacement so the phrase never repeats a word
            words = list(secrets.SystemRandom().sample(pool, k=min(word_count, len(pool))))
        now = time.monotonic()
        challenge = Challenge(
            challenge_id=secrets.token_urlsafe(16),
            member=member,
            words=words,
            created_at=now,
            language=self.language,
        )
        with self._lock:
            # Insert first, then prune: pruning before the insert would let the dict
            # reach MAX + 1. The new entry has the newest timestamp, so the cap never
            # evicts the challenge we are about to return.
            self._challenges[challenge.challenge_id] = challenge
            self._prune(now)
        return challenge

    # -- redeeming ---------------------------------------------------------
    def take(self, challenge_id: str, member: str) -> Challenge:
        """Consume a challenge, marking it used so it cannot be replayed.

        Raises:
            ChallengeError: unknown, expired, already used, or issued for another member.
        """
        now = time.monotonic()
        with self._lock:
            self._prune(now)
            challenge = self._challenges.get(challenge_id)
            if challenge is None:
                raise ChallengeError("Challenge tidak dikenal atau sudah kedaluwarsa.")
            if challenge.used:
                raise ChallengeError("Challenge sudah dipakai.")
            if challenge.is_expired(now):
                del self._challenges[challenge_id]
                raise ChallengeError("Challenge kedaluwarsa, coba lagi.")
            if challenge.member.lower() != member.strip().lower():
                raise ChallengeError("Challenge diterbitkan untuk member lain.")
            challenge.used = True
            del self._challenges[challenge_id]
        return challenge

    # -- replay ------------------------------------------------------------
    def check_not_replayed(self, audio_bytes: bytes) -> str:
        """Raise if this exact recording was already accepted. Returns its fingerprint.

        Call this *before* verifying; call `remember` only after a successful unlock so
        a failed attempt does not burn the fingerprint.
        """
        digest = fingerprint(audio_bytes)
        now = time.monotonic()
        with self._lock:
            self._prune(now)
            if digest in self._fingerprints:
                raise ReplayDetectedError(
                    "Rekaman ini sudah pernah dipakai untuk membuka pintu (indikasi replay)."
                )
        return digest

    def remember(self, digest: str) -> None:
        """Record a fingerprint as spent."""
        now = time.monotonic()
        with self._lock:
            self._fingerprints[digest] = now
            self._prune(now)   # after the insert, so the cap is a real upper bound

    # -- passphrase --------------------------------------------------------
    def passphrase_matches(self, challenge: Challenge, transcript: str) -> tuple[bool, float]:
        """Check a transcript against a challenge phrase.

        Returns (passed, ratio). Matching is bag-of-words rather than exact string
        equality: speech-to-text inserts filler, reorders nothing but often drops a
        word, and demanding a perfect transcript makes the door unusable in practice.
        """
        if not challenge.words:
            return True, 1.0
        spoken = set(_normalize_words(transcript))
        expected = [w.lower() for w in challenge.words]
        hits = sum(1 for w in expected if w in spoken)
        ratio = hits / len(expected)
        return ratio >= PASSPHRASE_MATCH_RATIO, ratio

    # -- diagnostics -------------------------------------------------------
    def stats(self) -> dict:
        with self._lock:
            return {
                "require_passphrase": self.require_passphrase,
                "language": self.language,
                "outstanding_challenges": len(self._challenges),
                "remembered_recordings": len(self._fingerprints),
                "challenge_ttl_seconds": CHALLENGE_TTL_SECONDS,
                "replay_memory_seconds": REPLAY_MEMORY_SECONDS,
            }
