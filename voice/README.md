# Voice Recognition Server

Komponen Python dari [VoiceDoor](../README.md). Dua peran dalam satu folder:

| Jalankan ini | Untuk apa | Butuh berapa orang? |
|---|---|---|
| **`voice_api_server.py`** | **API yang dipakai mod Minecraft.** Speaker *verification*: "apakah ini benar Budi?" | 1 |
| `app.py` (Streamlit) | UI eksplorasi dataset. Closed-set *identification*: "di antara anggota keluarga, ini siapa?" | minimal 2 |

**Kalau kamu memasang mod Minecraft, yang kamu butuhkan adalah `voice_api_server.py`.** UI Streamlit sifatnya opsional — berguna untuk melihat dataset, membandingkan model, dan memeriksa spektrogram, tapi pintu tidak memerlukannya sama sekali.

Metode: **librosa** (load, noise reduction, segmentasi) → **SpeechBrain ECAPA-TDNN** (voiceprint 192 dimensi per segmen) → **cosine similarity** terhadap voiceprint yang di-enroll. Untuk UI Streamlit, ada tambahan **scikit-learn** (SVM / Random Forest / MLP) dan **SpeechRecognition** (transkripsi).

---

## 1. Instalasi

Butuh **Python 3.10 – 3.13**.

```bash
cd voice
python -m venv .venv

# Windows
.venv\Scripts\activate
# macOS / Linux
source .venv/bin/activate

pip install -r requirements.txt
```

**Model pretrained:** jalan pertama mengunduh checkpoint ECAPA-TDNN (~80 MB) dari HuggingFace ke `pretrained_models/`, jadi butuh internet satu kali. Setelah itu semuanya jalan offline di CPU.

**Format audio:** WAV, MP3, FLAC, OGG ditangani `soundfile`. Kalau MP3 gagal, pasang [FFmpeg](https://ffmpeg.org/download.html) dan tambahkan ke `PATH` — librosa otomatis memakainya sebagai fallback.

**Versi torch:** `requirements.txt` memin `torch==2.14.0` dan `torchaudio==2.11.0`. Angka minor keduanya memang berbeda — penomoran torchaudio tidak lagi mengikuti torch, dan kombinasi ini benar. Untuk wheel CPU yang jauh lebih kecil:

```bash
pip install torch torchaudio --index-url https://download.pytorch.org/whl/cpu
```

---

## 2. Menjalankan API server

```bash
python voice_api_server.py
```

```
====================================================================
VoiceDoor Voice API Server  (mode: speaker verification)
Bind            : 127.0.0.1:5000
Threshold       : 0.45 (client hanya boleh minta lebih ketat)
Challenge       : wajib
Member enrolled : (belum ada)
--------------------------------------------------------------------
API token: 7Kq2mX...
Masukkan ke mod dengan: /voicedoor settoken 7Kq2mX...
====================================================================
```

Token dibuat otomatis saat pertama jalan dan disimpan di `.api_token` (sudah di-gitignore). Jalankan `/voicedoor settoken <token>` di dalam game sekali saja.

### Opsi

| Opsi | Keterangan |
|---|---|
| `--host 0.0.0.0` | Terima koneksi dari jaringan (default hanya localhost) |
| `--port 5000` | Port |
| `--threshold 0.55` | Ambang cosine similarity, lebih tinggi = lebih ketat |
| `--require-passphrase` | Pemain harus mengucapkan frasa acak. Anti-replay terkuat, **butuh internet** |
| `--language id-ID` | Bahasa frasa dan transkripsi |
| `--no-challenge` | Matikan challenge sekali-pakai. Tidak disarankan |
| `--no-auth` | Matikan token. **Hanya untuk tes lokal** |
| `--dev` | Flask dev server + debug, bukan waitress |

Server menolak bind ke `0.0.0.0` kalau `--no-auth` juga dipakai — kombinasi itu berarti siapa pun di jaringan bisa mendaftarkan suaranya sebagai pemilik pintu.

Secara default server dijalankan lewat **waitress**, bukan dev server Flask.

---

## 3. Endpoint

Semua kecuali `/health` butuh header `X-VoiceDoor-Token`.

| Method | Path | Fungsi |
|---|---|---|
| GET | `/health` | Health check, tanpa autentikasi |
| GET | `/status` | Voiceprint terdaftar, ambang, status proteksi replay |
| POST | `/challenge` | Terbitkan challenge sekali-pakai. Body: `member` |
| POST | `/verify` | Verifikasi suara. Form: `audio`, `member`, `challenge_id`, `threshold?` |
| POST | `/register` | Simpan sample lalu perbarui voiceprint. Form: `audio`, `member` |
| POST | `/enroll` | Hitung ulang voiceprint dari sample tersimpan. Body: `member` (atau `"all"`) |
| POST | `/train` | Latih classifier closed-set. **Hanya untuk UI Streamlit**, pintu tidak memakainya |

Contoh alur lengkap dengan curl:

```bash
TOKEN=$(cat .api_token)

# Daftarkan suara (ulangi 3-5 kali dengan rekaman berbeda)
curl -H "X-VoiceDoor-Token: $TOKEN" \
     -F "member=Budi" -F "audio=@sample1.wav" \
     http://127.0.0.1:5000/register

# Minta challenge, lalu verifikasi
CID=$(curl -s -H "X-VoiceDoor-Token: $TOKEN" \
       -d "member=Budi" http://127.0.0.1:5000/challenge | python -c "import sys,json;print(json.load(sys.stdin)['challenge_id'])")

curl -H "X-VoiceDoor-Token: $TOKEN" \
     -F "member=Budi" -F "challenge_id=$CID" -F "audio=@test.wav" \
     http://127.0.0.1:5000/verify
```

---

## 4. Mengkalibrasi ambang

Ini bagian yang paling menentukan apakah pintunya terasa enak dipakai. Nilai default 0.45 adalah titik tengah yang aman, tapi mikrofon dan ruangan setiap orang berbeda.

```bash
# Lihat siapa saja yang sudah punya voiceprint
python src/verify.py list

# Cetak skor mentah sebuah rekaman terhadap satu member
python src/verify.py check rekaman_budi.wav Budi
python src/verify.py check rekaman_orang_lain.wav Budi
```

```
Member     : Budi
Verdict    : ACCEPTED
Similarity : 0.7214 (threshold 0.45)
Segments   : 3 -> 0.731, 0.698, 0.735
```

Rekam beberapa klip dirimu sendiri dan beberapa klip orang lain, lihat sebaran skornya, lalu set ambang di tengah celah antara keduanya. Nilai khas ECAPA:

- orang yang sama: **0.50 – 0.85**
- orang berbeda: **0.00 – 0.30**

Kalau celahnya sempit, penyebabnya hampir selalu sample enrollment yang kurang konsisten. `enroll` mencetak angka **kohesi** — kalau di bawah 0.55, rekam ulang dalam satu sesi dengan mikrofon dan posisi yang sama.

---

## 5. Dataset

```
data/raw/
├── Budi/
│   ├── sample_20261001_101500_a3f2c1.wav
│   └── sample_20261001_101530_b8e491.wav
└── Ani/
    └── ...
```

Satu folder per orang; nama folder adalah label. File ditambahkan otomatis oleh `/register` dari dalam game, atau bisa dikopi manual lalu jalankan `python src/verify.py enroll <nama>`.

**Kebutuhan minimum untuk verification:** 6 segmen (~15 detik bicara setelah silence dibuang). **Disarankan:** 30–60 detik dari 3–5 rekaman terpisah.

Tips merekam:

- Bicara alami (baca paragraf, cerita tentang harimu) lebih baik daripada mengulang satu kata.
- Rekam di tempat dan dengan mikrofon yang nanti dipakai untuk membuka pintu.
- Hindari musik atau TV di latar. Noise ringan sudah ditangani noise reduction.

`data/raw/` dan `models/` sudah di-gitignore — rekaman suara adalah data biometrik dan tidak boleh ikut ter-push.

---

## 6. UI Streamlit (opsional)

```bash
streamlit run app.py
```

Buka http://localhost:8501.

| Halaman | Fungsi |
|---|---|
| Register Member | Rekam atau unggah sample, disimpan sebagai WAV 16 kHz mono |
| Train Model | Latih SVM / Random Forest / MLP, tampilkan akurasi dan confusion matrix |
| Identify Voice | Prediksi siapa yang bicara, grafik probabilitas, transkrip, spektrogram |
| Dataset | Sample dan durasi per member, hapus sample atau member |

UI ini memakai classifier closed-set, jadi butuh **minimal 2 member** dan perlu dilatih ulang setiap ada perubahan anggota. Pintunya tidak.

---

## 7. Cara kerja

**Preprocessing** (`src/preprocess.py`)
1. Load lewat librosa pada 16 kHz mono.
2. Noise reduction spectral-gating (noise floor diperkirakan dari frame terhening).
3. Buang silence di awal/akhir (`top_db=25`) dan jeda panjang di tengah.
4. Normalisasi amplitudo puncak.
5. Potong jadi segmen 2,5 detik (minimal 1 detik).

**Voiceprint** (`src/embeddings.py`)
- `speechbrain/spkrec-ecapa-voxceleb` memetakan setiap segmen ke vektor **192 dimensi** yang sudah di-L2-normalisasi.
- Model melihat filterbank log-mel 80 band; lapisan TDNN + attentive statistics pooling memadatkannya menjadi vektor yang menggambarkan *siapa* yang bicara — jauh lebih tahan terhadap perbedaan mikrofon, ruangan, dan kata yang diucapkan dibanding fitur spektral buatan tangan.

**Verification** (`src/verify.py`) — inilah yang dipakai pintu
- Enrollment: rata-ratakan semua embedding segmen seseorang, normalisasi → satu centroid.
- Verifikasi: cosine similarity setiap segmen terhadap centroid, lalu dirata-rata, dibandingkan dengan ambang.
- Karena vektornya sudah unit length, cosine similarity cuma dot product.
- Disimpan di `models/enrollments.json`, ditulis atomic (temp file + rename) supaya request yang membaca tidak pernah melihat file setengah tertulis.

**Proteksi replay** (`src/challenge.py`)
- Challenge sekali-pakai, kedaluwarsa 45 detik, terikat ke satu member.
- Fingerprint SHA-256 audio yang sudah pernah berhasil, diingat 15 menit.
- Opsional: frasa acak yang harus diucapkan, dicek lewat speech-to-text dengan pencocokan bag-of-words (transkripsi sering menjatuhkan satu kata; menuntut transkrip sempurna membuat pintunya tidak bisa dipakai).

**Classifier closed-set** (`src/train.py`, `src/predict.py`) — hanya untuk UI Streamlit
- Split 80/20 **dikelompokkan per rekaman**, jadi segmen dari satu file tidak pernah muncul di train dan test sekaligus. Tanpa ini akurasinya terlihat jauh lebih tinggi dari kenyataan.
- Augmentasi (noise + pitch shift) hanya pada split train.
- Class balancing dengan oversampling minoritas; baris duplikat mewarisi group file asalnya supaya CV fold tetap bersih.
- 5-fold `StratifiedGroupKFold`, model dengan CV terbaik yang disimpan.

---

## 8. Test

```bash
python -m pytest                    # 67 test
python -m pytest -m "not slow"      # 63 test, lewati yang memuat ECAPA
python -m pytest tests/test_api.py  # hanya endpoint HTTP
```

Test yang bertanda `slow` memuat checkpoint ECAPA sungguhan dan berjalan end-to-end memakai audio sintetis. Audio itu bukan suara manusia, jadi test-nya memastikan *struktur* pipeline-nya benar (skor identik ≈ 1.0, sumber berbeda skornya lebih rendah), bukan seberapa akurat sistemnya membedakan orang nyata.

---

## 9. Troubleshooting

| Masalah | Solusi |
|---|---|
| `Token tidak valid` | Jalankan `/voicedoor settoken <token>`. Token ada di `.api_token` dan dicetak saat server start. |
| `'X' belum punya voiceprint` | Jalankan `/voicedoor register` beberapa kali, atau `python src/verify.py enroll X`. |
| `hanya menghasilkan N segmen` | Rekaman terlalu pendek atau terlalu banyak silence. Bicara lebih lama, 3–5 rekaman. |
| `Challenge tidak dikenal atau kedaluwarsa` | Butuh lebih dari 45 detik antara klik pintu dan bicara. Coba lagi. |
| `Rekaman ini sudah pernah dipakai` | Proteksi replay bekerja. Bicara lagi alih-alih mengirim audio yang sama. |
| `Could not load the ECAPA model` | Jalan pertama butuh internet. Periksa koneksi dan apakah `pretrained_models/` bisa ditulis. |
| Orang lain diterima sebagai pemilik | Naikkan ambang (0.55–0.60) dan tambah sample yang lebih bervariasi. Ukur dulu dengan `verify.py check`. |
| Pemilik sendiri sering ditolak | Kohesi enrollment rendah. Rekam ulang dalam satu sesi, lalu `enroll` lagi. |
| Frasa tidak pernah cocok | Transkripsi butuh internet. Tanpa itu, jangan pakai `--require-passphrase`. |
| Terlalu banyak percobaan | Rate limit 12 per menit per member. Tunggu satu menit. |

---

## 10. Struktur

```
voice/
├── voice_api_server.py     API HTTP yang dipakai mod Minecraft
├── app.py                  UI Streamlit (opsional)
├── src/
│   ├── verify.py           Speaker verification + enrollment  ← dipakai pintu
│   ├── challenge.py        Challenge sekali-pakai + deteksi replay
│   ├── embeddings.py       Voiceprint ECAPA-TDNN
│   ├── preprocess.py       Load, noise reduction, segmentasi, augmentasi
│   ├── train.py            Classifier closed-set (UI Streamlit)
│   └── predict.py          Identifikasi closed-set + transkripsi (UI Streamlit)
├── tests/                  67 test
├── data/raw/<nama>/*.wav   Sample suara (gitignored)
├── models/
│   ├── enrollments.json    Voiceprint (gitignored)
│   └── model.pkl, ...      Artefak classifier (gitignored)
├── pretrained_models/      Checkpoint ECAPA (gitignored)
├── requirements.txt
└── pytest.ini
```
