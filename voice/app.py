"""
app.py
------
Streamlit web interface for the Home Owner Voice Recognition System.

Run with:
    streamlit run app.py
"""

from __future__ import annotations

import html
import logging
import re
import shutil
import sys
import tempfile
import time
import uuid
from pathlib import Path

# Make `src` importable no matter where streamlit is launched from
sys.path.insert(0, str(Path(__file__).resolve().parent))

# Streamlit's hot-reload watcher walks the __path__ of every imported module. SpeechBrain and
# torch register lazy submodules for optional integrations (flair, torch.classes, ...), so
# touching them raises ImportError. The watcher catches it and simply skips the module - only
# a long traceback is left in the console, so drop exactly those records.
logging.getLogger("streamlit.watcher.local_sources_watcher").addFilter(
    lambda record: not any(
        name in record.getMessage() for name in ("speechbrain", "torch.classes", "flair")
    )
)

import matplotlib  # noqa: E402

matplotlib.use("Agg")
import librosa.display  # noqa: E402
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import streamlit as st  # noqa: E402
from matplotlib.ticker import PercentFormatter  # noqa: E402

from src.embeddings import (  # noqa: E402
    FEATURE_LABEL,
    MEL_HOP_LENGTH,
    EmbeddingUnavailableError,
    is_available as ecapa_available,
    log_mel_spectrogram,
)
from src.predict import (  # noqa: E402
    DEFAULT_THRESHOLD,
    MODEL_FILE,
    ModelNotTrainedError,
    PredictionResult,
    TranscriptionError,
    VoicePredictor,
    models_exist,
    transcribe,
)
from src.preprocess import (  # noqa: E402
    DATA_DIR,
    MODELS_DIR,
    SAMPLE_RATE,
    SUPPORTED_EXTENSIONS,
    AudioProcessingError,
    get_audio_duration,
    load_audio,
    save_wav,
    scan_dataset,
)
from src.train import (  # noqa: E402
    MIN_MEMBERS,
    REQUIRED_SECONDS_PER_MEMBER,
    InsufficientDataError,
    TrainingResult,
    load_training_summary,
    train_models,
)

MIME_TYPES = {".wav": "audio/wav", ".mp3": "audio/mpeg", ".flac": "audio/flac", ".ogg": "audio/ogg"}
UPLOAD_TYPES = [ext.lstrip(".") for ext in SUPPORTED_EXTENSIONS]
LANGUAGES = {"English (US)": "en-US", "Indonesian": "id-ID", "English (UK)": "en-GB"}

CUSTOM_CSS = """
<style>
.badge {display:inline-block; padding:0.35rem 0.95rem; border-radius:999px;
        font-weight:600; font-size:0.95rem; color:#fff;}
.badge-green {background:#16a34a;}
.badge-red {background:#dc2626;}
.speaker-name {font-size:3.2rem; font-weight:800; line-height:1.1; margin:0.5rem 0 0.2rem 0;}
.speaker-known {color:#16a34a;}
.speaker-unknown {color:#dc2626;}
</style>
"""


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def flash(level: str, message: str) -> None:
    """Queue a message that survives st.rerun()."""
    st.session_state.setdefault("flash", []).append((level, message))


def show_flash() -> None:
    for level, message in st.session_state.pop("flash", []):
        getattr(st, level)(message)


def sanitize_name(raw: str) -> str:
    """Keep letters, digits, spaces, '-' and '_' so the name is a safe folder name."""
    name = re.sub(r"[^A-Za-z0-9 _-]", "", raw or "")
    return re.sub(r"\s+", " ", name).strip()[:40]


def write_temp_file(data: bytes, suffix: str) -> Path:
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        tmp.write(data)
    return Path(tmp.name)


def file_suffix(filename: str | None, default: str = ".wav") -> str:
    return Path(filename or "").suffix.lower() or default


def store_member_audio(member: str, data: bytes, original_name: str) -> tuple[Path, float]:
    """Validate an uploaded/recorded clip and save it as 16 kHz mono WAV in data/raw/<member>/."""
    suffix = file_suffix(original_name)
    if suffix not in SUPPORTED_EXTENSIONS:
        raise AudioProcessingError(f"Unsupported format '{suffix}'. Use {', '.join(SUPPORTED_EXTENSIONS)}.")
    tmp = write_temp_file(data, suffix)
    try:
        y = load_audio(tmp, SAMPLE_RATE)  # raises on corrupt / silent files
    except AudioProcessingError as exc:
        raise AudioProcessingError(str(exc).replace(tmp.name, original_name)) from exc
    finally:
        tmp.unlink(missing_ok=True)

    duration = y.size / SAMPLE_RATE
    if duration < 1.0:
        raise AudioProcessingError(f"Clip is too short ({duration:.1f} s). Record at least 1 second.")

    stem = re.sub(r"[^A-Za-z0-9_-]", "_", Path(original_name).stem)[:30] or "clip"
    out_path = DATA_DIR / member / f"{stem}_{time.strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:6]}.wav"
    save_wav(y, out_path, SAMPLE_RATE)
    return out_path, duration


def member_stats(files: list[Path]) -> tuple[int, float]:
    return len(files), sum(get_audio_duration(f) for f in files)


@st.cache_resource(show_spinner=False)
def _load_predictor(model_signature: float) -> VoicePredictor:
    """Cached per model-file timestamp, so retraining reloads automatically."""
    return VoicePredictor(MODELS_DIR)


def get_predictor() -> VoicePredictor:
    return _load_predictor((MODELS_DIR / MODEL_FILE).stat().st_mtime)


def style_axes(ax) -> None:
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)


# ---------------------------------------------------------------------------
# Plots
# ---------------------------------------------------------------------------
def plot_probabilities(result: PredictionResult) -> plt.Figure:
    names = list(result.probabilities)[::-1]  # lowest at bottom
    values = [result.probabilities[n] for n in names]
    top_color = "#16a34a" if result.is_known else "#dc2626"
    colors = [top_color if n == result.top_candidate else "#94a3b8" for n in names]

    fig, ax = plt.subplots(figsize=(7, 0.55 * len(names) + 1.4))
    bars = ax.barh(names, values, color=colors)
    ax.axvline(result.threshold, color="#f59e0b", linestyle="--", linewidth=1.5,
               label=f"Threshold ({result.threshold:.0%})")
    for bar, value in zip(bars, values):
        ax.text(min(value + 0.01, 0.9), bar.get_y() + bar.get_height() / 2, f"{value:.1%}",
                va="center", fontsize=9)
    ax.set_xlim(0, 1)
    ax.xaxis.set_major_formatter(PercentFormatter(1.0))
    ax.set_xlabel("Probability")
    ax.set_title("Probability per registered member")
    ax.legend(loc="lower right", fontsize=8)
    style_axes(ax)
    fig.tight_layout()
    return fig


def plot_waveform(y: np.ndarray, sr: int) -> plt.Figure:
    fig, ax = plt.subplots(figsize=(7, 2.8))
    librosa.display.waveshow(y, sr=sr, ax=ax, color="#2563eb")
    ax.set_title("Waveform (after noise reduction & silence removal)")
    ax.set_xlabel("Time (s)")
    ax.set_ylabel("Amplitude")
    style_axes(ax)
    fig.tight_layout()
    return fig


def plot_melspectrogram(y: np.ndarray, sr: int) -> plt.Figure:
    mel = log_mel_spectrogram(y, sr)
    fig, ax = plt.subplots(figsize=(7, 2.8))
    img = librosa.display.specshow(mel, x_axis="time", y_axis="mel", sr=sr,
                                   hop_length=MEL_HOP_LENGTH, ax=ax, cmap="magma")
    fig.colorbar(img, ax=ax, format="%+2.0f dB")
    ax.set_title("Log-mel spectrogram (ECAPA input)")
    ax.set_ylabel("Mel band")
    fig.tight_layout()
    return fig


def plot_model_comparison(scores: pd.DataFrame, best: str) -> plt.Figure:
    x = np.arange(len(scores))
    width = 0.38
    fig, ax = plt.subplots(figsize=(6, 3.4))
    cv_bars = ax.bar(x - width / 2, scores["cv_mean"], width, yerr=scores["cv_std"], capsize=4,
                     label="CV accuracy", color="#3b82f6")
    test_bars = ax.bar(x + width / 2, scores["test_accuracy"], width, label="Test accuracy", color="#10b981")
    for bars in (cv_bars, test_bars):
        for bar in bars:
            ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.02, f"{bar.get_height():.0%}",
                    ha="center", fontsize=8)
    ax.set_xticks(x, [f"{m} ★" if m == best else m for m in scores["model"]])
    ax.set_ylim(0, 1.15)
    ax.yaxis.set_major_formatter(PercentFormatter(1.0))
    ax.set_title("Model comparison")
    ax.legend(loc="lower right", fontsize=8)
    style_axes(ax)
    fig.tight_layout()
    return fig


def plot_confusion_from_summary(cm: list[list[int]], labels: list[str]) -> plt.Figure:
    from sklearn.metrics import ConfusionMatrixDisplay

    fig, ax = plt.subplots(figsize=(5, 4.2))
    ConfusionMatrixDisplay(np.array(cm), display_labels=labels).plot(
        ax=ax, cmap="Blues", colorbar=False, xticks_rotation=45, values_format="d"
    )
    fig.tight_layout()
    return fig


# ---------------------------------------------------------------------------
# Page: Register Member
# ---------------------------------------------------------------------------
def page_register() -> None:
    st.title("🎙️ Register Member")
    st.write("Add voice samples for a family member. Samples are converted to 16 kHz mono WAV "
             "and stored in `data/raw/<name>/`.")
    show_flash()

    members = scan_dataset(DATA_DIR)
    st.session_state.setdefault("register_key", 0)
    widget_key = st.session_state.register_key

    mode = "New member"
    if members:
        mode = st.radio("Member", ["New member", "Existing member"], horizontal=True)

    if mode == "Existing member":
        name = st.selectbox("Select member", list(members))
    else:
        raw_name = st.text_input("Member name", placeholder="e.g. Dad, Mom, Sarah", key="member_name")
        name = sanitize_name(raw_name)
        if raw_name and not name:
            st.error("Name must contain letters or digits.")
        elif raw_name and name != raw_name.strip():
            st.caption(f"Folder name will be: **{name}**")
        # Re-use existing folder if the name only differs in letter case
        for existing in members:
            if name and existing.lower() == name.lower():
                name = existing
                st.caption(f"'{existing}' already exists — new samples will be added to this member.")
                break

    if name and name in members:
        count, total = member_stats(members[name])
        st.caption(f"**{name}** currently has {count} sample(s), {total:.1f} s total "
                   f"(recommended: {REQUIRED_SECONDS_PER_MEMBER:.0f}+ s of speech).")

    st.info("Tip: record 3–5 clips of 5–15 seconds of natural speech (e.g. reading a paragraph) "
            "in the room where the system will be used. Aim for 30+ seconds per member.")

    tab_record, tab_upload = st.tabs(["🎤 Record", "📁 Upload files"])

    with tab_record:
        recording = st.audio_input("Record a voice sample", key=f"rec_{widget_key}")
        if st.button("💾 Save recording", disabled=recording is None, key=f"save_rec_{widget_key}"):
            if not name:
                st.error("Please enter a member name first.")
            else:
                try:
                    _, duration = store_member_audio(name, recording.getvalue(), "recording.wav")
                    flash("success", f"Saved a {duration:.1f} s recording for **{name}**.")
                    st.session_state.register_key += 1
                    st.rerun()
                except AudioProcessingError as exc:
                    st.error(f"Could not save recording: {exc}")

    with tab_upload:
        uploads = st.file_uploader("Upload audio files", type=UPLOAD_TYPES,
                                   accept_multiple_files=True, key=f"up_{widget_key}")
        if st.button("💾 Save uploaded files", disabled=not uploads, key=f"save_up_{widget_key}"):
            if not name:
                st.error("Please enter a member name first.")
            else:
                progress = st.progress(0.0, text="Saving files...")
                saved = 0
                for i, upload in enumerate(uploads, 1):
                    try:
                        store_member_audio(name, upload.getvalue(), upload.name)
                        saved += 1
                    except AudioProcessingError as exc:
                        flash("warning", f"Skipped **{upload.name}**: {exc}")
                    progress.progress(i / len(uploads), text=f"Saving files... {i}/{len(uploads)}")
                if saved:
                    flash("success", f"Saved {saved} file(s) for **{name}**.")
                st.session_state.register_key += 1
                st.rerun()


# ---------------------------------------------------------------------------
# Page: Train Model
# ---------------------------------------------------------------------------
def render_training_summary(summary: dict) -> None:
    scores = pd.DataFrame(summary["model_scores"])
    best = summary["best_model"]
    best_row = scores.loc[scores["model"] == best].iloc[0]

    c1, c2, c3, c4 = st.columns(4)
    c1.metric("Selected model", best)
    c2.metric("CV accuracy", f"{best_row['cv_mean']:.1%}", help=f"{summary['cv_folds']}-fold cross-validation")
    c3.metric("Test accuracy", f"{summary['test_accuracy']:.1%}",
              help="Per 2.5 s segment. The per-recording number below is what you get in practice.")
    c4.metric("Segments (train / test)", f"{summary['n_train']} / {summary['n_test']}")
    split_kind = ("grouped by recording" if summary.get("grouped_split", False)
                  else "segment-level (fallback)")
    st.caption(
        f"Trained {summary['created_at']} · members: {', '.join(summary['labels'])} · "
        f"features {FEATURE_LABEL} · "
        f"split {split_kind} · augmentation {'on' if summary['augment'] else 'off'} · "
        f"balancing {'on' if summary.get('balance') else 'off'} · "
        f"noise reduction {'on' if summary['noise_reduction'] else 'off'}"
        + (f" · {summary['n_train_rows']} training rows after augmentation"
           if summary.get("n_train_rows") else "")
    )
    if summary.get("file_accuracy") is not None:
        st.success(
            f"**Per-recording accuracy: {summary['file_accuracy']:.1%}** "
            f"({summary.get('n_test_files', '?')} test recordings) — segments of one recording are "
            f"voted together, exactly like the Identify page does."
        )
        if summary.get("file_errors"):
            with st.expander(f"⚠️ {len(summary['file_errors'])} misclassified recording(s)"):
                st.dataframe(
                    pd.DataFrame(summary["file_errors"],
                                 columns=["File", "True", "Predicted", "Confidence"]),
                    hide_index=True, use_container_width=True,
                )
    for note in summary.get("warnings", []):
        st.info(note)

    col_table, col_chart = st.columns(2)
    with col_table:
        st.markdown("#### Accuracy per model")
        table = pd.DataFrame({
            "Model": scores["model"],
            "CV accuracy": scores["cv_mean"].map("{:.1%}".format),
            "CV std": scores["cv_std"].map("±{:.1%}".format),
            "Test accuracy": scores["test_accuracy"].map("{:.1%}".format),
            "Selected": np.where(scores["model"] == best, "✅", ""),
        })
        st.dataframe(table, hide_index=True, use_container_width=True)
    with col_chart:
        fig = plot_model_comparison(scores, best)
        st.pyplot(fig)
        plt.close(fig)

    col_cm, col_report = st.columns(2)
    with col_cm:
        st.markdown(f"#### Confusion matrix — {best}")
        cm_path = MODELS_DIR / summary.get("confusion_plot", "")
        if summary.get("confusion_plot") and cm_path.exists():
            st.image(str(cm_path))
        else:
            fig = plot_confusion_from_summary(summary["confusion_matrix"], summary["labels"])
            st.pyplot(fig)
            plt.close(fig)
    with col_report:
        st.markdown("#### Classification report (test set)")
        report = {k: v for k, v in summary["classification_report"].items() if isinstance(v, dict)}
        report_df = pd.DataFrame(report).T
        report_df["support"] = report_df["support"].astype(int)
        st.dataframe(report_df.round(3), use_container_width=True)

    per_member = summary.get("samples_per_member", {})
    if per_member:
        files_per_member = summary.get("files_per_member", {})
        with st.expander("Data per member"):
            st.dataframe(
                pd.DataFrame({
                    "Member": list(per_member),
                    "Recordings": [files_per_member.get(m, "-") for m in per_member],
                    "Segments": list(per_member.values()),
                }),
                hide_index=True, use_container_width=True,
            )
    if summary.get("skipped_files"):
        with st.expander(f"⚠️ {len(summary['skipped_files'])} file(s) skipped"):
            for file_name, reason in summary["skipped_files"]:
                st.write(f"- **{file_name}**: {reason}")


def page_train() -> None:
    st.title("🧠 Train Model")
    st.write("Compares **SVM (RBF)**, **Random Forest** and **MLP** with cross-validation "
             "and keeps the best one.")
    show_flash()

    members = {name: files for name, files in scan_dataset(DATA_DIR).items() if files}
    if members:
        rows = []
        for name, files in members.items():
            count, total = member_stats(files)
            rows.append({"Member": name, "Samples": count, "Duration (s)": round(total, 1)})
        st.dataframe(pd.DataFrame(rows), hide_index=True, use_container_width=True)

    enough_members = len(members) >= MIN_MEMBERS
    if not enough_members:
        st.warning(f"At least {MIN_MEMBERS} members with samples are required to train "
                   f"(currently {len(members)}). Go to **Register Member** first.")

    col1, col2, col3 = st.columns(3)
    augment = col1.checkbox("Data augmentation (noise injection + pitch shift)", value=True,
                            help="Creates extra training samples. Slower, but usually more robust.")
    noise_reduction = col2.checkbox("Noise reduction", value=True,
                                    help="Spectral gating before feature extraction.")
    balance = col3.checkbox("Balance members", value=True,
                            help="Oversamples members with fewer samples in the training split, "
                                 "so a member with twice the data does not dominate.")

    st.caption(f"Features: {FEATURE_LABEL}. The first training run downloads the "
               f"~80 MB pretrained model into `pretrained_models/`.")
    encoder_ready = ecapa_available()
    if not encoder_ready:
        st.error("ECAPA needs torch, torchaudio and speechbrain: `pip install -r requirements.txt`.")

    if st.button("🚀 Start training", type="primary", disabled=not (enough_members and encoder_ready)):
        progress = st.progress(0.0, text="Starting...")

        def on_progress(fraction: float, message: str) -> None:
            progress.progress(fraction, text=message)

        try:
            result: TrainingResult = train_models(
                DATA_DIR, MODELS_DIR, augment=augment, noise_reduction=noise_reduction,
                balance=balance, progress_callback=on_progress,
            )
            _load_predictor.clear()
            st.success(f"Training finished — selected **{result.best_model}** "
                       f"with {result.test_accuracy:.1%} test accuracy.")
        except (InsufficientDataError, EmbeddingUnavailableError) as exc:
            progress.empty()
            st.error(str(exc))
        except Exception as exc:  # unexpected errors: show details instead of crashing the app
            progress.empty()
            st.error("Training failed unexpectedly.")
            st.exception(exc)

    summary = load_training_summary(MODELS_DIR)
    if summary and models_exist(MODELS_DIR):
        st.divider()
        st.subheader("Latest training results")
        try:
            render_training_summary(summary)
        except (KeyError, IndexError, ValueError, TypeError):
            st.warning("Training summary is incomplete. Retrain the model to refresh it.")


# ---------------------------------------------------------------------------
# Page: Identify Voice
# ---------------------------------------------------------------------------
def render_prediction(result: PredictionResult, audio_bytes: bytes, suffix: str,
                      transcript: str | None, transcript_error: str | None) -> None:
    if result.is_known:
        badge = '<span class="badge badge-green">✔ Recognized family member</span>'
        name_class = "speaker-known"
    else:
        badge = '<span class="badge badge-red">✖ Unknown voice — possible stranger</span>'
        name_class = "speaker-unknown"
    st.markdown(badge, unsafe_allow_html=True)
    st.markdown(f'<div class="speaker-name {name_class}">{html.escape(result.speaker)}</div>',
                unsafe_allow_html=True)
    if not result.is_known:
        st.caption(f"Closest match: **{result.top_candidate}** ({result.confidence:.1%}), "
                   f"below the {result.threshold:.0%} threshold.")

    c1, c2, c3 = st.columns(3)
    c1.metric("Similarity", f"{result.confidence:.1%}")
    c2.metric("Segments analyzed", result.n_segments)
    c3.metric("Threshold", f"{result.threshold:.0%}")
    st.progress(min(max(result.confidence, 0.0), 1.0), text=f"Similarity: {result.confidence:.1%}")
    st.audio(audio_bytes, format=MIME_TYPES.get(suffix, "audio/wav"))

    st.markdown("#### Probabilities")
    fig = plot_probabilities(result)
    st.pyplot(fig)
    plt.close(fig)

    st.markdown("#### Transcript")
    if transcript is not None:
        st.write(f"🗣️ “{transcript}”")
    elif transcript_error:
        st.warning(f"Transcript unavailable: {transcript_error}")
    else:
        st.caption("Transcription disabled.")

    st.markdown("#### Visualizations")
    col_wave, col_mel = st.columns(2)
    with col_wave:
        fig = plot_waveform(result.signal, result.sample_rate)
        st.pyplot(fig)
        plt.close(fig)
    with col_mel:
        fig = plot_melspectrogram(result.signal, result.sample_rate)
        st.pyplot(fig)
        plt.close(fig)

    with st.expander("Segment-level probabilities (voting details)"):
        seg_df = pd.DataFrame(result.segment_probabilities, columns=result.class_names)
        seg_df.insert(0, "Segment", np.arange(1, len(seg_df) + 1))
        seg_df["Vote"] = seg_df.drop(columns="Segment").idxmax(axis=1)
        st.dataframe(seg_df.round(3), hide_index=True, use_container_width=True)


def page_identify() -> None:
    st.title("🔍 Identify Voice")
    show_flash()

    if not models_exist(MODELS_DIR):
        st.warning("The model has not been trained yet. Register at least 2 members and open **Train Model**.")
        return
    try:
        predictor = get_predictor()
    except ModelNotTrainedError as exc:
        st.error(str(exc))
        return

    st.caption(f"Model: **{predictor.metadata.get('best_model', 'unknown')}** · "
               f"Features: {FEATURE_LABEL} · "
               f"Members: {', '.join(predictor.members)}")

    col1, col2, col3 = st.columns([2, 1, 1])
    threshold = col1.slider("Unknown-voice threshold", 0.30, 0.95, DEFAULT_THRESHOLD, 0.05,
                            help="Predictions below this confidence are reported as 'Unknown Voice'.")
    do_transcribe = col2.checkbox("Transcribe speech", value=True, help="Requires an internet connection.")
    language = LANGUAGES[col3.selectbox("Language", list(LANGUAGES), disabled=not do_transcribe)]

    source = st.radio("Input", ["🎤 Record", "📁 Upload"], horizontal=True)
    if source == "🎤 Record":
        audio_file = st.audio_input("Record the voice to identify", key="identify_rec")
        suffix = ".wav"
    else:
        audio_file = st.file_uploader("Upload an audio file", type=UPLOAD_TYPES, key="identify_up")
        suffix = file_suffix(audio_file.name if audio_file else None)

    if not st.button("🔍 Identify speaker", type="primary", disabled=audio_file is None):
        return
    if suffix not in SUPPORTED_EXTENSIONS:
        st.error(f"Unsupported format '{suffix}'.")
        return

    audio_bytes = audio_file.getvalue()
    tmp_path = write_temp_file(audio_bytes, suffix)
    transcript, transcript_error = None, None
    try:
        with st.spinner("Analyzing voice..."):
            result = predictor.predict(tmp_path, threshold=threshold)
        if do_transcribe:
            with st.spinner("Transcribing speech..."):
                try:
                    transcript = transcribe(tmp_path, language=language)
                except TranscriptionError as exc:
                    transcript_error = str(exc)
    except AudioProcessingError as exc:
        st.error(f"Could not process this audio: {exc}")
        return
    except Exception as exc:
        st.error("Prediction failed unexpectedly.")
        st.exception(exc)
        return
    finally:
        tmp_path.unlink(missing_ok=True)

    st.divider()
    render_prediction(result, audio_bytes, suffix, transcript, transcript_error)


# ---------------------------------------------------------------------------
# Page: Dataset
# ---------------------------------------------------------------------------
def page_dataset() -> None:
    st.title("📊 Dataset")
    show_flash()

    members = scan_dataset(DATA_DIR)
    if not members:
        st.info("No members registered yet. Go to **Register Member** to add voice samples.")
        return

    rows = []
    for name, files in members.items():
        count, total = member_stats(files)
        rows.append({
            "Member": name,
            "Samples": count,
            "Total duration (s)": round(total, 1),
            "Status": "✅ Ready" if total >= REQUIRED_SECONDS_PER_MEMBER else "⚠️ Needs more audio",
        })
    df = pd.DataFrame(rows)

    c1, c2, c3 = st.columns(3)
    c1.metric("Members", len(df))
    c2.metric("Samples", int(df["Samples"].sum()))
    c3.metric("Total audio", f"{df['Total duration (s)'].sum():.0f} s")

    st.dataframe(df, hide_index=True, use_container_width=True)
    st.bar_chart(df.set_index("Member")["Total duration (s)"])
    st.caption(f"'Ready' means at least {REQUIRED_SECONDS_PER_MEMBER:.0f} s of audio. "
               "Silence is removed before training, so more is better.")

    st.subheader("🗂️ Samples")
    for name, files in members.items():
        with st.expander(f"{name} — {len(files)} sample(s)"):
            if not files:
                st.write("No samples.")
                continue
            st.dataframe(
                pd.DataFrame({"File": [f.name for f in files],
                              "Duration (s)": [round(get_audio_duration(f), 1) for f in files]}),
                hide_index=True, use_container_width=True,
            )
            to_delete = st.multiselect("Delete selected samples", [f.name for f in files], key=f"del_files_{name}")
            if st.button("Delete samples", disabled=not to_delete, key=f"del_btn_{name}"):
                for file_name in to_delete:
                    (DATA_DIR / name / file_name).unlink(missing_ok=True)
                flash("success", f"Deleted {len(to_delete)} sample(s) from **{name}**. Retrain the model to apply.")
                st.rerun()

    st.subheader("🗑️ Delete member")
    target = st.selectbox("Member to delete", list(members))
    confirm = st.checkbox(f"I understand that all samples of **{target}** will be permanently deleted.",
                          key=f"confirm_delete_{target}")
    if st.button("Delete member", type="primary", disabled=not confirm):
        try:
            shutil.rmtree(DATA_DIR / target)
            message = f"Deleted member **{target}**."
            if models_exist(MODELS_DIR):
                message += " Retrain the model so this member is removed from predictions."
            flash("success", message)
        except OSError as exc:
            flash("error", f"Could not delete {target}: {exc}")
        st.rerun()


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
PAGES = {
    "🎙️ Register Member": page_register,
    "🧠 Train Model": page_train,
    "🔍 Identify Voice": page_identify,
    "📊 Dataset": page_dataset,
}


def main() -> None:
    st.set_page_config(page_title="Home Owner Voice Recognition", page_icon="🏠", layout="wide")
    st.markdown(CUSTOM_CSS, unsafe_allow_html=True)
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    MODELS_DIR.mkdir(parents=True, exist_ok=True)

    st.sidebar.title("🏠 Home Voice ID")
    st.sidebar.caption("Speaker recognition for registered family members")
    page = st.sidebar.radio("Navigation", list(PAGES))

    st.sidebar.divider()
    registered = sum(1 for files in scan_dataset(DATA_DIR).values() if files)
    st.sidebar.metric("Registered members", registered)
    if models_exist(MODELS_DIR):
        summary = load_training_summary(MODELS_DIR) or {}
        st.sidebar.success(f"Model ready: {summary.get('best_model', 'trained')}")
    else:
        st.sidebar.warning("Model not trained")

    PAGES[page]()


if __name__ == "__main__":
    main()