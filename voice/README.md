# 🏠 Home Owner Voice Recognition System

Speaker recognition for a household: register family members with a few voice samples, train a classifier on pretrained **ECAPA-TDNN** speaker embeddings, and identify who is speaking from a new recording — with a confidence score and an **Unknown Voice** result for strangers.

Method: **Librosa** (audio loading, noise reduction, segmentation), **SpeechBrain ECAPA-TDNN** (192-d speaker embedding per segment), **scikit-learn** (SVM / Random Forest / MLP on top of the embeddings), **SpeechRecognition** (transcript) and **Streamlit** (web UI).

---

## 1. Installation

Requires **Python 3.10 – 3.13**.

```bash
cd voice-recognition
python -m venv .venv

# Windows
.venv\Scripts\activate
# macOS / Linux
source .venv/bin/activate

pip install -r requirements.txt
```

**Audio formats:** WAV, MP3, FLAC and OGG are decoded by `soundfile` (bundled libsndfile). If an MP3 fails to load on your system, install [FFmpeg](https://ffmpeg.org/download.html) and add it to your `PATH` — librosa falls back to it automatically.

**Pretrained model:** the first training or prediction run downloads the ECAPA-TDNN checkpoint (~80 MB) from HuggingFace into `pretrained_models/`, so it needs an internet connection once. After that everything runs offline on the CPU.

**Transcript:** uses the free Google Web Speech API through SpeechRecognition, so it needs an internet connection. When offline, the app shows a warning and everything else keeps working.

---

## 2. Dataset preparation

```
data/raw/
├── Dad/
│   ├── reading_01.wav
│   └── reading_02.wav
├── Mom/
│   └── ...
└── Sarah/
    └── ...
```

- One folder per member; the folder name is the label shown in predictions.
- Add samples either through the **Register Member** page (record or upload) or by copying files into the folders manually.
- **Minimum:** 2 members, each with about **20 s of speech** after silence removal (8 segments of 2.5 s) spread over at least 2 recordings. **Recommended:** 30–60 s per member over **5 or more separate recordings** — the train/test split is grouped by recording, so 5+ files per member are needed for an honest evaluation.
- Short clips are fine: a 2 s clip becomes 1 segment, a 6 s clip 2–3 segments. Many short clips work as well as a few long ones.
- Unequal amounts per member (e.g. 100 clips for one, 50 for the others) are handled by the balancing step.

Recording tips:
- Natural speech (read a paragraph, talk about your day) works better than repeating one word.
- Record in the same place and with the same microphone that will be used for identification.
- Avoid music/TV in the background. Mild noise is handled by noise reduction and augmentation.

---

## 3. Running the app

```bash
streamlit run app.py
```

Open http://localhost:8501.

| Page | What it does |
|------|--------------|
| 🎙️ **Register Member** | Enter a name, record with the microphone or upload WAV/MP3/FLAC/OGG files. Clips are validated and saved as 16 kHz mono WAV in `data/raw/<name>/`. |
| 🧠 **Train Model** | Encodes every segment with ECAPA, trains SVM, Random Forest and MLP, shows accuracy per model, the confusion matrix, the classification report and the selected model. |
| 🔍 **Identify Voice** | Record or upload audio → predicted speaker, similarity %, probability chart, transcript, waveform and log-mel spectrogram. Green badge = recognized, red = unknown. |
| 📊 **Dataset** | Samples and duration per member, delete individual samples or a whole member. |

After adding or deleting members, **retrain the model**.

---

## 4. Command-line usage

Every module runs standalone (from the project root):

```bash
# Inspect preprocessing of one file (optionally save the segments)
python src/preprocess.py data/raw/Dad/reading_01.wav --augment --out-dir segments/

# Show the ECAPA embedding of one file
python src/embeddings.py data/raw/Dad/reading_01.wav

# Train (augmentation, balancing and noise reduction are on by default)
python src/train.py
python src/train.py --no-augment --no-balance --no-noise-reduction --test-size 0.2
python src/train.py --overlap 0.5      # overlapping segments -> more training data
python src/train.py --models-dir models_test   # train into a separate folder

# Predict
python src/predict.py test.wav
python src/predict.py test.wav --threshold 0.7 --transcribe --language id-ID
```

`python -m src.train` style works as well.

---

## 5. How it works

**Preprocessing** (`src/preprocess.py`)
1. Load with librosa at 16 kHz mono.
2. Spectral-gating noise reduction (noise floor estimated from the quietest frames).
3. Trim leading/trailing silence (`librosa.effects.trim`, `top_db=25`) and remove long pauses inside the clip.
4. Peak amplitude normalization.
5. Split into 2.5 s segments (min 1 s) → more training samples.
6. Optional augmentation on the training split only: Gaussian noise injection (SNR 15–30 dB) and pitch shift (±0.5–1 semitone).

**Features — ECAPA embeddings** (`src/embeddings.py`) — per segment:
- A pretrained ECAPA-TDNN model (`speechbrain/spkrec-ecapa-voxceleb`, trained on thousands of VoxCeleb speakers) maps each segment to a **192-dimensional**, L2-normalized speaker embedding.
- Internally the model consumes an 80-band log-mel filterbank (25 ms window, 10 ms hop) and its TDNN + attentive-statistics-pooling layers compress it into a vector that describes *who* is speaking — far less sensitive to the microphone, the room and the spoken words than hand-crafted spectral averages.
- The embedding is then standardized with `StandardScaler` and fed to the scikit-learn classifiers. Segments are encoded in batches of 16 on the CPU.
- The Identify page shows the same log-mel spectrogram the model sees, for a visual sanity check.
- `feature_type` and `feature_dim` are stored in `metadata.json`; loading a model trained with a different representation is refused with a "please retrain" message.

**Training** (`src/train.py`)
- Stratified 80/20 split **grouped by source recording**: every segment of one file lands
  either in train or in test, never both. Without this, segments of the same recording on
  both sides inflate the accuracy. If a member has fewer than 5 recordings the split falls
  back to segment level and a warning is stored in the summary.
- Augmentation runs on the training split only.
- Class balancing (on by default, triggered above a 1.2:1 imbalance): minority members are
  oversampled in the training split. SVM and Random Forest use `class_weight="balanced"`,
  but `MLPClassifier` has no such option, so without this a member with twice the data wins.
  Duplicated rows keep the group of their source file, so CV folds stay clean.
- 5-fold `StratifiedGroupKFold` cross-validation (augmented/duplicated copies stay in the
  same fold as their source recording).
- Candidates: SVM (RBF, `probability=True`), Random Forest, MLPClassifier.
- The model with the best mean CV accuracy is saved to `models/` together with the scaler,
  label encoder, `metadata.json` and `confusion_matrix.png`.

**Prediction** (`src/predict.py`)
- Same preprocessing → segments → features → scaler → `predict_proba`.
- Probabilities are averaged over all segments (soft voting).
- If the top probability is below the threshold (default **0.60**) the result is **Unknown Voice**.

---

## 6. Notes & troubleshooting

| Problem | Fix |
|---------|-----|
| "At least 2 registered members…" | Register a second member. |
| "Not enough usable speech for: …" | Add more/longer recordings for the listed members. |
| "Model has not been trained yet" | Open **Train Model** and click **Start training**. |
| "Saved model uses a different feature configuration" | The model predates the ECAPA-only pipeline — retrain. |
| "Could not load the ECAPA model" | First run needs internet to download the checkpoint; check the connection or that `pretrained_models/` is writable. |
| "ECAPA needs torch, torchaudio and speechbrain" | `pip install -r requirements.txt` (CPU wheels: `pip install torch torchaudio --index-url https://download.pytorch.org/whl/cpu`). |
| File skipped as corrupt | Re-export it as WAV, or install FFmpeg for exotic MP3 encodings. |
| Transcript unavailable | No internet or no recognizable words — prediction still works. |
| Strangers recognized as a member | Raise the threshold (e.g. 0.70–0.80) and add more varied samples per member. |
| 100% accuracy that fails in practice | Check the split label on the Train page: "segment-level (fallback)" means one member has fewer than 5 recordings. Record more separate clips. |
| One member always wins | Keep "Balance members" on, or record more clips for the others. |

About the Unknown threshold: the classifier only knows registered members, so a stranger is always mapped to the *closest* member. The threshold rejects low-confidence matches, but a stranger with a similar voice can still exceed it. Tune it on the Identify page using a few recordings of non-members.

---

## 7. Project structure

```
voice-recognition/
├── app.py              # Streamlit UI
├── src/
│   ├── __init__.py
│   ├── preprocess.py   # load, resample, noise reduction, trim silence, segmentation, augmentation
│   ├── embeddings.py   # pretrained ECAPA-TDNN speaker embeddings (the features)
│   ├── train.py        # training, model comparison, evaluation, saving
│   └── predict.py      # inference + transcription
├── data/raw/<person_name>/*.wav
├── models/             # model.pkl, scaler.pkl, label_encoder.pkl, metadata.json, confusion_matrix.png
├── pretrained_models/  # downloaded ECAPA-TDNN checkpoint (git-ignored)
├── requirements.txt
└── README.md
```
