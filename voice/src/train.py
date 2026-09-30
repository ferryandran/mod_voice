"""
train.py
--------
Train and compare speaker classifiers (SVM, Random Forest, MLP) on ECAPA embeddings.

Steps:
    1. Preprocess every file in data/raw/<member>/ and split into segments
       (each segment remembers which source file it came from)
    2. Encode every segment with the pretrained ECAPA-TDNN model -> 192-d speaker embedding
    3. Stratified 80/20 train/test split GROUPED BY SOURCE FILE, so segments of
       the same recording never appear in both train and test
    4. Optional augmentation (noise + pitch shift) on the TRAIN split only
    5. Optional class balancing: minority members are oversampled in the TRAIN split
       (helps MLP, which has no class_weight option)
    6. 5-fold stratified group cross-validation (augmented/duplicated copies stay
       in the same fold as their source file)
    7. Pick the best model by CV accuracy, evaluate on the test set,
       save model.pkl / scaler.pkl / label_encoder.pkl / metadata.json / confusion_matrix.png

Standalone usage:
    python src/train.py [--no-augment] [--no-balance] [--no-noise-reduction]
                        [--test-size 0.2] [--overlap 0.0]
"""

from __future__ import annotations

import argparse
import json
import sys
import warnings
from collections import Counter
from dataclasses import asdict, dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Callable

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import joblib
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
from sklearn.base import clone  # noqa: E402
from sklearn.ensemble import RandomForestClassifier  # noqa: E402
from sklearn.metrics import (  # noqa: E402
    ConfusionMatrixDisplay,
    accuracy_score,
    classification_report,
    confusion_matrix,
)
from sklearn.model_selection import StratifiedGroupKFold, cross_val_score, train_test_split  # noqa: E402
from sklearn.neural_network import MLPClassifier  # noqa: E402
from sklearn.pipeline import Pipeline  # noqa: E402
from sklearn.preprocessing import LabelEncoder, StandardScaler  # noqa: E402
from sklearn.svm import SVC  # noqa: E402

from src.embeddings import (  # noqa: E402
    ECAPA_DIM,
    FEATURE_LABEL,
    FEATURE_TYPE,
    EmbeddingUnavailableError,
    extract,
    get_encoder,
)
from src.preprocess import (  # noqa: E402
    DATA_DIR,
    MODELS_DIR,
    SAMPLE_RATE,
    SEGMENT_SECONDS,
    AudioProcessingError,
    augment_segment,
    preprocess_file,
    scan_dataset,
)

MODEL_FILE = "model.pkl"
SCALER_FILE = "scaler.pkl"
ENCODER_FILE = "label_encoder.pkl"
METADATA_FILE = "metadata.json"
CONFUSION_FILE = "confusion_matrix.png"

MIN_MEMBERS = 2
MIN_SEGMENTS_PER_MEMBER = 8
REQUIRED_SECONDS_PER_MEMBER = MIN_SEGMENTS_PER_MEMBER * SEGMENT_SECONDS  # ~20 s of speech
CV_FOLDS = 5
RANDOM_STATE = 42

ProgressCallback = Callable[[float, str], None]


class InsufficientDataError(Exception):
    """Raised when the dataset is too small to train a reliable model."""


@dataclass
class LoadedDataset:
    segments: list[np.ndarray]
    labels: list[str]
    sources: list[int] = field(default_factory=list)   # index of the source file per segment
    files: list[str] = field(default_factory=list)     # "<member>/<filename>" per source index
    skipped: list[tuple[str, str]] = field(default_factory=list)


@dataclass
class ModelScore:
    model: str
    cv_mean: float
    cv_std: float
    test_accuracy: float


@dataclass
class TrainingResult:
    best_model: str
    test_accuracy: float
    cv_folds: int
    model_scores: list[ModelScore]
    labels: list[str]
    confusion_matrix: np.ndarray
    classification_report_text: str
    classification_report: dict
    samples_per_member: dict[str, int]
    files_per_member: dict[str, int]
    n_segments: int
    n_train: int
    n_test: int
    n_train_rows: int          # train rows after augmentation + balancing
    n_test_files: int          # test recordings (segments voted together)
    file_accuracy: float       # accuracy per whole recording (soft voting) - the real-world number
    file_confusion_matrix: np.ndarray
    file_errors: list[tuple[str, str, str, float]]  # (file, true, predicted, confidence)
    augment: bool
    balance: bool
    grouped_split: bool        # True if the split was grouped by source file
    noise_reduction: bool
    skipped_files: list[tuple[str, str]]
    warnings: list[str] = field(default_factory=list)
    feature_type: str = FEATURE_TYPE
    confusion_plot: str = CONFUSION_FILE
    created_at: str = field(default_factory=lambda: datetime.now().strftime("%Y-%m-%d %H:%M:%S"))

    def to_metadata(self) -> dict:
        """JSON-serializable summary (saved as metadata.json and used by the UI)."""
        data = asdict(self)
        data["confusion_matrix"] = self.confusion_matrix.tolist()
        data["file_confusion_matrix"] = self.file_confusion_matrix.tolist()
        data["feature_dim"] = ECAPA_DIM
        data["sample_rate"] = SAMPLE_RATE
        data["members"] = self.labels
        return data


def _json_default(obj):
    if isinstance(obj, np.generic):
        return obj.item()
    if isinstance(obj, np.ndarray):
        return obj.tolist()
    raise TypeError(f"Not JSON serializable: {type(obj)}")


def _notify(callback: ProgressCallback | None, fraction: float, message: str) -> None:
    if callback is not None:
        callback(float(min(max(fraction, 0.0), 1.0)), message)


def build_models() -> dict[str, object]:
    """The candidate classifiers."""
    return {
        "SVM (RBF)": SVC(
            kernel="rbf", C=10.0, gamma="scale", probability=True,
            class_weight="balanced", random_state=RANDOM_STATE,
        ),
        "Random Forest": RandomForestClassifier(
            n_estimators=300, class_weight="balanced", n_jobs=-1, random_state=RANDOM_STATE,
        ),
        "MLP": MLPClassifier(
            hidden_layer_sizes=(256, 128), activation="relu", alpha=1e-3,
            learning_rate_init=1e-3, max_iter=800, random_state=RANDOM_STATE,
        ),
    }


def load_dataset(
    data_dir: str | Path = DATA_DIR,
    noise_reduction: bool = True,
    overlap: float = 0.0,
    progress_callback: ProgressCallback | None = None,
    span: tuple[float, float] = (0.0, 1.0),
) -> LoadedDataset:
    """Preprocess every member's audio into labelled segments.

    Each segment keeps the index of the recording it came from, so later steps can
    keep all segments of one recording inside the same train/test/CV fold.
    Corrupt or unusable files are skipped and reported instead of stopping training.
    """
    members = {name: files for name, files in scan_dataset(data_dir).items() if files}
    if len(members) < MIN_MEMBERS:
        raise InsufficientDataError(
            f"At least {MIN_MEMBERS} registered members with audio samples are required "
            f"(found {len(members)}). Register more family members first."
        )

    jobs = [(name, f) for name, files in members.items() for f in files]
    segments: list[np.ndarray] = []
    labels: list[str] = []
    sources: list[int] = []
    files: list[str] = []
    skipped: list[tuple[str, str]] = []
    start, end = span

    for i, (name, file) in enumerate(jobs, 1):
        try:
            _, segs = preprocess_file(file, noise_reduction=noise_reduction, overlap=overlap)
            source_id = len(files)
            files.append(f"{name}/{file.name}")
            segments.extend(segs)
            labels.extend([name] * len(segs))
            sources.extend([source_id] * len(segs))
        except AudioProcessingError as exc:
            skipped.append((f"{name}/{file.name}", str(exc)))
        _notify(progress_callback, start + (end - start) * i / len(jobs),
                f"Preprocessing audio {i}/{len(jobs)}: {name}/{file.name}")

    counts = Counter(labels)
    too_small = {name: counts.get(name, 0) for name in members if counts.get(name, 0) < MIN_SEGMENTS_PER_MEMBER}
    if too_small:
        details = ", ".join(f"{n} ({c} segments)" for n, c in too_small.items())
        raise InsufficientDataError(
            f"Not enough usable speech for: {details}. Each member needs at least "
            f"{MIN_SEGMENTS_PER_MEMBER} segments (~{REQUIRED_SECONDS_PER_MEMBER:.0f} s of speech "
            f"after silence removal). Record more samples for these members."
        )
    return LoadedDataset(segments=segments, labels=labels, sources=sources, files=files, skipped=skipped)


def grouped_train_test_split(
    y: np.ndarray, groups: np.ndarray, test_size: float = 0.2
) -> tuple[np.ndarray, np.ndarray, bool]:
    """Stratified train/test split that never splits one recording across both sides.

    Returns (train_idx, test_idx, grouped). `grouped` is False when the grouped split
    is impossible (too few recordings per member) and a plain segment-level split was
    used as a fallback.
    """
    n_classes = int(y.max()) + 1
    n_splits = max(2, min(10, int(round(1.0 / max(test_size, 0.05)))))
    files_per_class = [len(np.unique(groups[y == c])) for c in range(n_classes)]

    if min(files_per_class) >= n_splits:
        splitter = StratifiedGroupKFold(n_splits=n_splits, shuffle=True, random_state=RANDOM_STATE)
        for train_idx, test_idx in splitter.split(np.zeros(len(y)), y, groups=groups):
            # both sides must contain every member, otherwise try the next fold
            if len(np.unique(y[train_idx])) == n_classes and len(np.unique(y[test_idx])) == n_classes:
                return train_idx, test_idx, True

    # Fallback: segment-level stratified split (segments of one file may be shared)
    n_test = max(int(np.ceil(len(y) * test_size)), n_classes)
    train_idx, test_idx = train_test_split(
        np.arange(len(y)), test_size=n_test, stratify=y, random_state=RANDOM_STATE
    )
    return train_idx, test_idx, False


def balance_classes(
    y: np.ndarray, groups: np.ndarray, rng: np.random.Generator
) -> np.ndarray:
    """Return row indices that oversample minority classes up to the largest class.

    Duplicated rows keep the group of their source recording, so CV folds stay clean.
    """
    counts = Counter(y.tolist())
    target = max(counts.values())
    extra: list[int] = []
    for label, count in counts.items():
        if count >= target:
            continue
        pool = np.flatnonzero(y == label)
        extra.extend(rng.choice(pool, size=target - count, replace=True).tolist())
    return np.array(extra, dtype=int)


def evaluate_per_file(
    model,
    X_test_scaled: np.ndarray,
    y_test: np.ndarray,
    test_files: np.ndarray,
    file_names: list[str],
    class_order: np.ndarray,
    class_names: list[str],
) -> tuple[float, np.ndarray, list[tuple[str, str, str, float]]]:
    """Evaluate the way the app actually predicts: average the probabilities of all
    segments of one recording (soft voting), then take the top class.

    The segment-level report counts every 2.5 s chunk separately, so it is pessimistic
    compared to real usage, where a whole recording is judged at once.
    """
    proba = model.predict_proba(X_test_scaled)
    order = {int(c): i for i, c in enumerate(class_order)}   # model column -> class id
    inverse = {i: int(c) for c, i in order.items()}
    true_labels, pred_labels, errors = [], [], []

    for file_id in np.unique(test_files):
        mask = test_files == file_id
        mean_proba = proba[mask].mean(axis=0)
        best_col = int(np.argmax(mean_proba))
        predicted, truth = inverse[best_col], int(y_test[mask][0])
        true_labels.append(truth)
        pred_labels.append(predicted)
        if predicted != truth:
            errors.append((file_names[int(file_id)], class_names[truth],
                           class_names[predicted], float(mean_proba[best_col])))

    label_ids = list(range(len(class_names)))
    accuracy = accuracy_score(true_labels, pred_labels)
    cm = confusion_matrix(true_labels, pred_labels, labels=label_ids)
    return float(accuracy), cm, errors


def plot_confusion_matrix(cm: np.ndarray, labels: list[str], title: str, path: Path) -> Path:
    """Save a confusion-matrix heatmap to `path`."""
    size = max(4.5, 0.9 * len(labels) + 2.5)
    fig, ax = plt.subplots(figsize=(size, size * 0.85))
    ConfusionMatrixDisplay(cm, display_labels=labels).plot(
        ax=ax, cmap="Blues", colorbar=False, xticks_rotation=45, values_format="d"
    )
    ax.set_title(title)
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)
    return path


def train_models(
    data_dir: str | Path = DATA_DIR,
    models_dir: str | Path = MODELS_DIR,
    augment: bool = True,
    noise_reduction: bool = True,
    balance: bool = True,
    test_size: float = 0.2,
    overlap: float = 0.0,
    progress_callback: ProgressCallback | None = None,
) -> TrainingResult:
    """Run the full training pipeline and save the best model to `models_dir`."""
    data_dir, models_dir = Path(data_dir), Path(models_dir)
    cb = progress_callback
    notes: list[str] = []

    # Fail fast (missing packages / no internet) before the slow preprocessing step
    _notify(cb, 0.0, "Loading pretrained ECAPA model...")
    get_encoder()

    # 1. Preprocess ----------------------------------------------------------
    _notify(cb, 0.0, "Scanning dataset...")
    dataset = load_dataset(data_dir, noise_reduction, overlap, cb, span=(0.0, 0.35))

    # 2. Features ------------------------------------------------------------
    X_all = extract(
        dataset.segments,
        progress_callback=lambda i, t: _notify(cb, 0.35 + 0.20 * i / t, f"Encoding ECAPA embeddings {i}/{t}"),
    )
    encoder = LabelEncoder()
    y_all = encoder.fit_transform(dataset.labels)
    file_ids = np.array(dataset.sources)
    class_names = [str(c) for c in encoder.classes_]
    n_classes = len(class_names)
    n_total = len(y_all)

    # 3. Train/test split grouped by source recording ------------------------
    train_idx, test_idx, grouped = grouped_train_test_split(y_all, file_ids, test_size)
    if not grouped:
        notes.append(
            "Too few recordings per member for a file-level split — segments of the same "
            "recording may appear in both train and test, so the test accuracy is optimistic. "
            "Add more separate recordings per member."
        )
    X_train, y_train = X_all[train_idx], y_all[train_idx]
    groups = file_ids[train_idx].copy()
    X_test, y_test = X_all[test_idx], y_all[test_idx]

    # 4. Augmentation (train split only, avoids test leakage) ---------------
    if augment:
        rng = np.random.default_rng(RANDOM_STATE)
        aug_segments, aug_y, aug_g = [], [], []
        for k, i in enumerate(train_idx, 1):
            for variant in augment_segment(dataset.segments[i], SAMPLE_RATE, rng):
                aug_segments.append(variant)
                aug_y.append(y_all[i])
                aug_g.append(file_ids[i])
            _notify(cb, 0.55 + 0.05 * k / len(train_idx), f"Augmenting segments {k}/{len(train_idx)}")
        aug_X = extract(
            aug_segments,
            progress_callback=lambda i, t: _notify(cb, 0.60 + 0.10 * i / t,
                                                   f"Encoding ECAPA embeddings (augmented) {i}/{t}"),
        )
        X_train = np.vstack([X_train, aug_X])
        y_train = np.concatenate([y_train, np.array(aug_y)])
        groups = np.concatenate([groups, np.array(aug_g)])

    # 5. Class balancing (train split only) ---------------------------------
    class_counts = Counter(y_all[train_idx].tolist())
    imbalance_ratio = max(class_counts.values()) / max(min(class_counts.values()), 1)
    if balance and imbalance_ratio > 1.2:
        rng = np.random.default_rng(RANDOM_STATE + 1)
        extra = balance_classes(y_train, groups, rng)
        if extra.size:
            X_train = np.vstack([X_train, X_train[extra]])
            y_train = np.concatenate([y_train, y_train[extra]])
            groups = np.concatenate([groups, groups[extra]])
        notes.append(
            f"Class imbalance {imbalance_ratio:.1f}:1 — minority members were oversampled "
            f"in the training split only."
        )

    # 6. Cross-validation + test evaluation per model ------------------------
    files_per_class = [len(np.unique(groups[y_train == c])) for c in range(n_classes)]
    cv_folds = min(CV_FOLDS, min(files_per_class))
    if cv_folds < 2:
        raise InsufficientDataError(
            "Not enough separate recordings per member for cross-validation. "
            "Add at least 2 recordings per member."
        )
    if cv_folds < CV_FOLDS:
        notes.append(f"Only {cv_folds}-fold cross-validation was possible "
                     f"(a member has {min(files_per_class)} usable recordings).")
    cv = StratifiedGroupKFold(n_splits=cv_folds, shuffle=True, random_state=RANDOM_STATE)

    scaler = StandardScaler().fit(X_train)
    X_train_s, X_test_s = scaler.transform(X_train), scaler.transform(X_test)

    models = build_models()
    scores: list[ModelScore] = []
    fitted: dict[str, object] = {}
    for m, (name, model) in enumerate(models.items()):
        _notify(cb, 0.70 + 0.25 * m / len(models), f"Training {name} ({cv_folds}-fold CV)...")
        pipeline = Pipeline([("scaler", StandardScaler()), ("clf", clone(model))])
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            cv_scores = cross_val_score(
                pipeline, X_train, y_train, groups=groups, cv=cv,
                scoring="accuracy", error_score="raise",
            )
            final_model = clone(model).fit(X_train_s, y_train)
        test_acc = accuracy_score(y_test, final_model.predict(X_test_s))
        scores.append(ModelScore(name, float(cv_scores.mean()), float(cv_scores.std()), float(test_acc)))
        fitted[name] = final_model

    # 7. Select best, evaluate, save -----------------------------------------
    _notify(cb, 0.96, "Evaluating best model and saving artifacts...")
    best = max(scores, key=lambda s: (s.cv_mean, s.test_accuracy))
    best_model = fitted[best.model]
    y_pred = best_model.predict(X_test_s)
    label_ids = list(range(n_classes))

    report_text = classification_report(
        y_test, y_pred, labels=label_ids, target_names=class_names, zero_division=0
    )
    report_dict = classification_report(
        y_test, y_pred, labels=label_ids, target_names=class_names, zero_division=0, output_dict=True
    )
    cm = confusion_matrix(y_test, y_pred, labels=label_ids)
    file_accuracy, file_cm, file_errors = evaluate_per_file(
        best_model, X_test_s, y_test, file_ids[test_idx], dataset.files,
        best_model.classes_, class_names,
    )

    models_dir.mkdir(parents=True, exist_ok=True)
    plot_confusion_matrix(cm, class_names, f"Confusion Matrix - {best.model}", models_dir / CONFUSION_FILE)
    joblib.dump(best_model, models_dir / MODEL_FILE)
    joblib.dump(scaler, models_dir / SCALER_FILE)
    joblib.dump(encoder, models_dir / ENCODER_FILE)

    files_per_member = Counter(name.split("/", 1)[0] for name in dataset.files)
    result = TrainingResult(
        best_model=best.model,
        test_accuracy=best.test_accuracy,
        cv_folds=cv_folds,
        model_scores=scores,
        labels=class_names,
        confusion_matrix=cm,
        classification_report_text=report_text,
        classification_report=report_dict,
        samples_per_member=dict(Counter(dataset.labels)),
        files_per_member=dict(files_per_member),
        n_segments=n_total,
        n_train=len(train_idx),
        n_test=len(test_idx),
        n_train_rows=int(len(y_train)),
        n_test_files=int(len(np.unique(file_ids[test_idx]))),
        file_accuracy=file_accuracy,
        file_confusion_matrix=file_cm,
        file_errors=file_errors,
        augment=augment,
        balance=balance,
        grouped_split=grouped,
        noise_reduction=noise_reduction,
        skipped_files=dataset.skipped,
        warnings=notes,
    )
    with open(models_dir / METADATA_FILE, "w", encoding="utf-8") as fh:
        json.dump(result.to_metadata(), fh, indent=2, default=_json_default)

    _notify(cb, 1.0, "Training complete.")
    return result


def load_training_summary(models_dir: str | Path = MODELS_DIR) -> dict | None:
    """Load metadata.json from the last training run (None if missing/corrupt)."""
    path = Path(models_dir) / METADATA_FILE
    if not path.exists():
        return None
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, json.JSONDecodeError):
        return None


def print_summary(result: TrainingResult) -> None:
    print("\n" + "=" * 60)
    print("MODEL COMPARISON")
    print("=" * 60)
    print(f"{'Model':<16}{'CV accuracy':>18}{'Test accuracy':>16}")
    for s in result.model_scores:
        marker = "  <- selected" if s.model == result.best_model else ""
        print(f"{s.model:<16}{s.cv_mean:>10.2%} ± {s.cv_std:<5.2%}{s.test_accuracy:>16.2%}{marker}")
    split_kind = "grouped by recording" if result.grouped_split else "segment-level (fallback)"
    print(f"\nFeatures: {FEATURE_LABEL}")
    print(f"Segments: {result.n_segments} (train {result.n_train} / test {result.n_test}), "
          f"split {split_kind}")
    print(f"Training rows after augmentation/balancing: {result.n_train_rows}, "
          f"{result.cv_folds}-fold CV, augmentation={'on' if result.augment else 'off'}, "
          f"balancing={'on' if result.balance else 'off'}")
    print("Recordings per member:", ", ".join(f"{k}={v}" for k, v in result.files_per_member.items()))
    print("Segments per member :", ", ".join(f"{k}={v}" for k, v in result.samples_per_member.items()))
    print(f"\nClassification report ({result.best_model}, test set):\n")
    print(result.classification_report_text)
    print("Confusion matrix per segment (rows = true, cols = predicted):")
    print("labels:", result.labels)
    print(result.confusion_matrix)
    print(f"\nPER-RECORDING accuracy (soft voting, how the app actually predicts): "
          f"{result.file_accuracy:.2%} on {result.n_test_files} test recordings")
    print(result.file_confusion_matrix)
    if result.file_errors:
        print("Misclassified recordings:")
        for name, truth, predicted, conf in result.file_errors:
            print(f"  - {name}: {truth} -> {predicted} ({conf:.1%} confidence)")
    for note in result.warnings:
        print(f"\n[NOTE] {note}")
    if result.skipped_files:
        print("\nSkipped files:")
        for name, reason in result.skipped_files:
            print(f"  - {name}: {reason}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Train the speaker recognition model.")
    parser.add_argument("--data-dir", default=str(DATA_DIR), help="Folder with <member>/*.wav")
    parser.add_argument("--models-dir", default=str(MODELS_DIR), help="Output folder for model files")
    parser.add_argument("--no-augment", action="store_true", help="Disable noise/pitch augmentation")
    parser.add_argument("--no-balance", action="store_true", help="Disable oversampling of minority members")
    parser.add_argument("--no-noise-reduction", action="store_true", help="Disable noise reduction")
    parser.add_argument("--test-size", type=float, default=0.2, help="Test split ratio (default 0.2)")
    parser.add_argument("--overlap", type=float, default=0.0,
                        help="Segment overlap 0-0.9 (e.g. 0.5 doubles the segments of long files)")
    args = parser.parse_args(argv)

    last_step = {"value": -1}

    def cli_progress(fraction: float, message: str) -> None:
        step = int(fraction * 20)  # print at most every 5%
        if step != last_step["value"]:
            last_step["value"] = step
            print(f"[{fraction * 100:5.1f}%] {message}")

    try:
        result = train_models(
            data_dir=args.data_dir,
            models_dir=args.models_dir,
            augment=not args.no_augment,
            noise_reduction=not args.no_noise_reduction,
            balance=not args.no_balance,
            test_size=args.test_size,
            overlap=args.overlap,
            progress_callback=cli_progress,
        )
    except (InsufficientDataError, EmbeddingUnavailableError) as exc:
        print(f"[ERROR] {exc}", file=sys.stderr)
        return 1

    print_summary(result)
    print(f"\nSaved model files to: {Path(args.models_dir).resolve()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())