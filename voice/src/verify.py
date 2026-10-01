"""
verify.py
---------
Speaker VERIFICATION (1-vs-rest) for the VoiceDoor lock.

This is deliberately different from `predict.py`, which does closed-set
IDENTIFICATION ("which of the registered members is this?"). A door needs to answer
a different question: "is this really member X, yes or no?".

Why verification is the right tool for a lock:

* It works with a **single** enrolled member. Identification needs at least two
  classes, so a solo player could never use the door.
* Its score is **absolute** (cosine similarity in embedding space), not a softmax
  probability relative to whoever happens to be registered. With two registered
  members a classifier's baseline probability is already 0.5, so a 0.60 threshold
  barely filters anything.
* Enrolling a new member does not disturb anyone else and needs no retraining -
  we just add one more centroid.

How it works:

    audio -> preprocess -> segments -> ECAPA embedding per segment (L2-normalized)
          -> cosine similarity against the member's enrolled centroid
          -> mean similarity over segments, compared to a threshold

Because `embeddings.extract()` already L2-normalizes every row, cosine similarity
is a plain dot product.

Standalone usage:
    python src/verify.py enroll Budi
    python src/verify.py check some_recording.wav Budi
    python src/verify.py list
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import numpy as np

from src.embeddings import ECAPA_DIM, FEATURE_TYPE, EmbeddingUnavailableError, extract
from src.preprocess import (
    DATA_DIR,
    MODELS_DIR,
    SAMPLE_RATE,
    AudioProcessingError,
    preprocess_file,
    scan_dataset,
)

ENROLLMENT_FILE = "enrollments.json"
ENROLLMENT_VERSION = 1

# Cosine-similarity threshold for accepting a speaker.
#
# Typical ECAPA-TDNN cosine similarities (VoxCeleb-trained, 2.5 s segments):
#   same speaker      ~0.50 - 0.85
#   different speaker ~0.00 - 0.30
# 0.45 sits in the gap with margin on both sides. Raise it towards 0.55-0.60 for a
# stricter door (more false rejections), lower it only after measuring with real
# non-owner recordings - `python src/verify.py check <file> <member>` prints the raw
# score so the gap can be measured on your own microphone and room.
DEFAULT_VERIFY_THRESHOLD = 0.45

# An enrolled member needs at least this many segments for a stable centroid.
MIN_ENROLL_SEGMENTS = 6


class NotEnrolledError(Exception):
    """Raised when a member has no enrollment on file."""


class EnrollmentError(Exception):
    """Raised when a member cannot be enrolled (no usable audio, too little speech)."""


@dataclass
class VerificationResult:
    """Outcome of checking one recording against one enrolled member."""

    member: str
    accepted: bool
    similarity: float                 # mean cosine similarity over segments
    threshold: float
    n_segments: int
    per_segment: list[float] = field(default_factory=list)
    best_other: str | None = None     # closest other enrolled member, for diagnostics
    best_other_similarity: float = 0.0

    def as_dict(self) -> dict:
        return {
            "member": self.member,
            "accepted": self.accepted,
            "similarity": round(self.similarity, 4),
            "threshold": round(self.threshold, 4),
            "n_segments": self.n_segments,
            "per_segment": [round(v, 4) for v in self.per_segment],
            "best_other": self.best_other,
            "best_other_similarity": round(self.best_other_similarity, 4),
        }


@dataclass
class MemberEnrollment:
    """One member's voiceprint: the mean direction of all their segment embeddings."""

    name: str
    centroid: np.ndarray
    n_segments: int
    n_files: int
    updated_at: str
    self_similarity_mean: float = 0.0
    self_similarity_min: float = 0.0

    def as_dict(self) -> dict:
        return {
            "centroid": [float(v) for v in self.centroid],
            "n_segments": self.n_segments,
            "n_files": self.n_files,
            "updated_at": self.updated_at,
            "self_similarity_mean": round(self.self_similarity_mean, 4),
            "self_similarity_min": round(self.self_similarity_min, 4),
        }

    @classmethod
    def from_dict(cls, name: str, data: dict) -> "MemberEnrollment":
        centroid = np.asarray(data["centroid"], dtype=np.float32)
        if centroid.shape != (ECAPA_DIM,):
            raise EnrollmentError(
                f"Enrollment for '{name}' has {centroid.shape} values, expected ({ECAPA_DIM},). "
                f"Re-enroll this member."
            )
        return cls(
            name=name,
            centroid=centroid,
            n_segments=int(data.get("n_segments", 0)),
            n_files=int(data.get("n_files", 0)),
            updated_at=str(data.get("updated_at", "")),
            self_similarity_mean=float(data.get("self_similarity_mean", 0.0)),
            self_similarity_min=float(data.get("self_similarity_min", 0.0)),
        )


def _normalize(v: np.ndarray) -> np.ndarray:
    """Return `v` scaled to unit length (safe for all-zero input)."""
    norm = float(np.linalg.norm(v))
    if norm < 1e-12:
        return v.astype(np.float32)
    return (v / norm).astype(np.float32)


def _write_json_atomic(path: Path, payload: dict) -> None:
    """Write JSON via a temp file + rename, so readers never see a half-written file.

    A plain `open(path, "w")` truncates immediately: a concurrent reader (the API
    server serving /verify while an enrollment is being saved) would get invalid JSON.
    `os.replace` is atomic on both POSIX and Windows.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(dir=str(path.parent), prefix=path.name, suffix=".tmp")
    tmp = Path(tmp_name)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, indent=2)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, path)
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise


class EnrollmentStore:
    """Reads and writes `models/enrollments.json`.

    The file is small (192 floats per member) and reloaded whenever its mtime changes,
    so a long-running API server picks up enrollments made by the CLI or the Streamlit
    app without a restart.
    """

    def __init__(self, models_dir: str | Path = MODELS_DIR):
        self.models_dir = Path(models_dir)
        self.path = self.models_dir / ENROLLMENT_FILE
        self._members: dict[str, MemberEnrollment] = {}
        self._mtime: float | None = None
        self.reload()

    # -- persistence -------------------------------------------------------
    def reload(self) -> None:
        """Load the file if it exists and has changed since the last read."""
        if not self.path.exists():
            self._members, self._mtime = {}, None
            return
        mtime = self.path.stat().st_mtime
        if self._mtime is not None and mtime == self._mtime:
            return
        try:
            with open(self.path, encoding="utf-8") as fh:
                payload = json.load(fh)
        except (OSError, json.JSONDecodeError):
            # Corrupt or mid-write: keep whatever we already had rather than wiping state.
            return
        if str(payload.get("feature_type", FEATURE_TYPE)) != FEATURE_TYPE:
            raise EnrollmentError(
                "enrollments.json was produced with a different feature type. Re-enroll all members."
            )
        members: dict[str, MemberEnrollment] = {}
        for name, data in (payload.get("members") or {}).items():
            members[name] = MemberEnrollment.from_dict(name, data)
        self._members, self._mtime = members, mtime

    def save(self) -> None:
        payload = {
            "version": ENROLLMENT_VERSION,
            "feature_type": FEATURE_TYPE,
            "feature_dim": ECAPA_DIM,
            "sample_rate": SAMPLE_RATE,
            "updated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "members": {name: m.as_dict() for name, m in sorted(self._members.items())},
        }
        _write_json_atomic(self.path, payload)
        self._mtime = self.path.stat().st_mtime if self.path.exists() else None

    # -- accessors ---------------------------------------------------------
    @property
    def members(self) -> list[str]:
        return sorted(self._members)

    def get(self, member: str) -> MemberEnrollment:
        """Case-insensitive lookup of one member's enrollment."""
        if member in self._members:
            return self._members[member]
        lowered = member.strip().lower()
        for name, enrollment in self._members.items():
            if name.lower() == lowered:
                return enrollment
        raise NotEnrolledError(
            f"'{member}' has no enrolled voiceprint. Record samples and run enrollment first."
        )

    def has(self, member: str) -> bool:
        try:
            self.get(member)
            return True
        except NotEnrolledError:
            return False

    def remove(self, member: str) -> bool:
        """Delete one member's enrollment. Returns True if something was removed."""
        for name in list(self._members):
            if name.lower() == member.strip().lower():
                del self._members[name]
                self.save()
                return True
        return False

    def summary(self) -> dict:
        return {
            name: {
                "n_segments": m.n_segments,
                "n_files": m.n_files,
                "updated_at": m.updated_at,
                "self_similarity_mean": round(m.self_similarity_mean, 4),
                "self_similarity_min": round(m.self_similarity_min, 4),
            }
            for name, m in sorted(self._members.items())
        }

    # -- enrollment --------------------------------------------------------
    def enroll(
        self,
        member: str,
        data_dir: str | Path = DATA_DIR,
        noise_reduction: bool = True,
    ) -> MemberEnrollment:
        """(Re)compute `member`'s centroid from every sample in data/raw/<member>/.

        Raises:
            EnrollmentError: no folder, no readable audio, or too little speech.
            EmbeddingUnavailableError: torch/speechbrain missing or model undownloadable.
        """
        members = scan_dataset(data_dir)
        match = next((n for n in members if n.lower() == member.strip().lower()), None)
        if match is None:
            raise EnrollmentError(f"No sample folder found for '{member}' in {Path(data_dir)}.")
        files = members[match]
        if not files:
            raise EnrollmentError(f"'{match}' has no audio samples yet.")

        segments: list[np.ndarray] = []
        used_files = 0
        skipped: list[str] = []
        for file in files:
            try:
                _, segs = preprocess_file(file, noise_reduction=noise_reduction)
            except AudioProcessingError as exc:
                skipped.append(f"{file.name}: {exc}")
                continue
            segments.extend(segs)
            used_files += 1

        if len(segments) < MIN_ENROLL_SEGMENTS:
            detail = f" Skipped: {'; '.join(skipped)}" if skipped else ""
            raise EnrollmentError(
                f"'{match}' only yielded {len(segments)} usable segments, need at least "
                f"{MIN_ENROLL_SEGMENTS} (about {MIN_ENROLL_SEGMENTS * 2.5:.0f} s of speech). "
                f"Record more samples.{detail}"
            )

        X = extract(segments)                      # already L2-normalized rows
        centroid = _normalize(X.mean(axis=0))
        sims = X @ centroid                        # how tightly the samples cluster

        enrollment = MemberEnrollment(
            name=match,
            centroid=centroid,
            n_segments=int(X.shape[0]),
            n_files=used_files,
            updated_at=datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            self_similarity_mean=float(sims.mean()),
            self_similarity_min=float(sims.min()),
        )
        self._members[match] = enrollment
        self.save()
        return enrollment

    def enroll_all(
        self, data_dir: str | Path = DATA_DIR, noise_reduction: bool = True
    ) -> tuple[list[MemberEnrollment], list[tuple[str, str]]]:
        """Enroll every member folder. Returns (enrolled, [(member, error), ...])."""
        done: list[MemberEnrollment] = []
        failed: list[tuple[str, str]] = []
        for name in scan_dataset(data_dir):
            try:
                done.append(self.enroll(name, data_dir, noise_reduction))
            except (EnrollmentError, AudioProcessingError) as exc:
                failed.append((name, str(exc)))
        return done, failed

    # -- verification ------------------------------------------------------
    def verify_segments(
        self,
        X: np.ndarray,
        member: str,
        threshold: float = DEFAULT_VERIFY_THRESHOLD,
    ) -> VerificationResult:
        """Compare already-extracted embeddings against `member`'s centroid."""
        enrollment = self.get(member)
        if X.size == 0:
            raise AudioProcessingError("No usable speech segments in the recording.")

        sims = X @ enrollment.centroid
        mean_sim = float(sims.mean())

        # Diagnostics: the closest *other* enrolled member. Not part of the decision -
        # it just makes "accepted with 0.47 but Ani scores 0.71" visible in the logs.
        best_other, best_other_sim = None, 0.0
        for name, other in self._members.items():
            if name == enrollment.name:
                continue
            score = float((X @ other.centroid).mean())
            if best_other is None or score > best_other_sim:
                best_other, best_other_sim = name, score

        return VerificationResult(
            member=enrollment.name,
            accepted=mean_sim >= threshold,
            similarity=mean_sim,
            threshold=threshold,
            n_segments=int(X.shape[0]),
            per_segment=[float(v) for v in sims],
            best_other=best_other,
            best_other_similarity=best_other_sim,
        )

    def verify_file(
        self,
        audio_path: str | Path,
        member: str,
        threshold: float = DEFAULT_VERIFY_THRESHOLD,
        noise_reduction: bool = True,
    ) -> VerificationResult:
        """Preprocess `audio_path` and verify it against `member`.

        Raises:
            NotEnrolledError: the member has no voiceprint.
            AudioProcessingError: unreadable audio or not enough speech.
            EmbeddingUnavailableError: ECAPA encoder unavailable.
        """
        self.get(member)  # fail fast before the expensive preprocessing
        _, segments = preprocess_file(audio_path, noise_reduction=noise_reduction)
        X = extract(segments)
        return self.verify_segments(X, member, threshold)

    def identify_segments(self, X: np.ndarray) -> list[tuple[str, float]]:
        """Score a recording against every enrolled member, best first (diagnostics)."""
        scores = [(name, float((X @ m.centroid).mean())) for name, m in self._members.items()]
        return sorted(scores, key=lambda pair: pair[1], reverse=True)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def _cmd_enroll(args) -> int:
    store = EnrollmentStore(args.models_dir)
    try:
        if args.member.lower() == "all":
            done, failed = store.enroll_all(args.data_dir, not args.no_noise_reduction)
            for e in done:
                print(f"Enrolled {e.name:<20} {e.n_segments:>3} segments from {e.n_files} file(s), "
                      f"cohesion {e.self_similarity_mean:.3f} (min {e.self_similarity_min:.3f})")
            for name, err in failed:
                print(f"[SKIP] {name}: {err}", file=sys.stderr)
            return 0 if done else 1
        e = store.enroll(args.member, args.data_dir, not args.no_noise_reduction)
    except (EnrollmentError, EmbeddingUnavailableError, AudioProcessingError) as exc:
        print(f"[ERROR] {exc}", file=sys.stderr)
        return 1
    print(f"Enrolled '{e.name}': {e.n_segments} segments from {e.n_files} file(s)")
    print(f"Sample cohesion: mean {e.self_similarity_mean:.3f}, worst {e.self_similarity_min:.3f}")
    if e.self_similarity_mean < 0.55:
        print("[WARN] Low cohesion - the samples sound inconsistent. Re-record in one sitting "
              "with the same microphone for a sharper voiceprint.", file=sys.stderr)
    return 0


def _cmd_check(args) -> int:
    store = EnrollmentStore(args.models_dir)
    try:
        result = store.verify_file(args.audio, args.member, args.threshold)
    except (NotEnrolledError, AudioProcessingError, EmbeddingUnavailableError) as exc:
        print(f"[ERROR] {exc}", file=sys.stderr)
        return 1
    verdict = "ACCEPTED" if result.accepted else "REJECTED"
    print(f"Member     : {result.member}")
    print(f"Verdict    : {verdict}")
    print(f"Similarity : {result.similarity:.4f} (threshold {result.threshold:.2f})")
    print(f"Segments   : {result.n_segments} -> "
          f"{', '.join(f'{v:.3f}' for v in result.per_segment)}")
    if result.best_other:
        print(f"Closest other enrolled member: {result.best_other} at {result.best_other_similarity:.4f}")
    return 0 if result.accepted else 2


def _cmd_list(args) -> int:
    store = EnrollmentStore(args.models_dir)
    summary = store.summary()
    if not summary:
        print("No members enrolled yet.")
        return 0
    print(f"{'Member':<20}{'Segments':>9}{'Files':>7}{'Cohesion':>10}  Updated")
    for name, info in summary.items():
        print(f"{name:<20}{info['n_segments']:>9}{info['n_files']:>7}"
              f"{info['self_similarity_mean']:>10.3f}  {info['updated_at']}")
    return 0


def _cmd_remove(args) -> int:
    store = EnrollmentStore(args.models_dir)
    if store.remove(args.member):
        print(f"Removed enrollment for '{args.member}'.")
        return 0
    print(f"[ERROR] '{args.member}' is not enrolled.", file=sys.stderr)
    return 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Speaker verification for the VoiceDoor lock.")
    parser.add_argument("--models-dir", default=str(MODELS_DIR), help="Folder holding enrollments.json")
    parser.add_argument("--data-dir", default=str(DATA_DIR), help="Folder with <member>/*.wav")
    sub = parser.add_subparsers(dest="command", required=True)

    p_enroll = sub.add_parser("enroll", help="Compute a member's voiceprint ('all' for everyone)")
    p_enroll.add_argument("member")
    p_enroll.add_argument("--no-noise-reduction", action="store_true")
    p_enroll.set_defaults(func=_cmd_enroll)

    p_check = sub.add_parser("check", help="Verify a recording against a member")
    p_check.add_argument("audio")
    p_check.add_argument("member")
    p_check.add_argument("--threshold", type=float, default=DEFAULT_VERIFY_THRESHOLD)
    p_check.set_defaults(func=_cmd_check)

    p_list = sub.add_parser("list", help="Show enrolled members")
    p_list.set_defaults(func=_cmd_list)

    p_remove = sub.add_parser("remove", help="Delete a member's voiceprint")
    p_remove.add_argument("member")
    p_remove.set_defaults(func=_cmd_remove)

    args = parser.parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":
    sys.exit(main())
