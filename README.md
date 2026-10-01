# VoiceDoor

Pintu Minecraft yang hanya terbuka setelah suara pemiliknya diverifikasi.

Repositori ini berisi **dua komponen yang harus berjalan bersamaan**:

| Folder | Isi | Butuh dijalankan? |
|---|---|---|
| [voicedoor_mod/](voicedoor_mod/) | Mod Minecraft Forge 1.20.1 | Dipasang di Minecraft |
| [voice/](voice/) | Python API server (pengenal suara ECAPA-TDNN) | **Ya, harus jalan** sebelum pintu bisa dipakai |

Mod-nya tidak melakukan pengenalan suara sendiri. Dia merekam audio lewat Simple Voice Chat lalu mengirimkannya ke Python server untuk diverifikasi. **Tanpa server Python yang berjalan, pintu tidak akan pernah terbuka.**

---

## Cara kerja singkat

```
Pemain klik pintu
      │
      ├─► Mod minta challenge sekali-pakai dari Python API
      │
      ├─► Simple Voice Chat merekam suara pemain (4 detik)
      │
      ├─► Audio + challenge dikirim ke POST /verify
      │
      ├─► Python: ECAPA-TDNN ubah audio jadi voiceprint 192 dimensi,
      │           bandingkan dengan voiceprint pemilik (cosine similarity)
      │
      └─► Cocok di atas ambang → pintu terbuka 5 detik, lalu menutup sendiri
```

Sistemnya memakai **speaker verification** (1-vs-rest), bukan klasifikasi antar anggota. Praktisnya: satu orang saja sudah cukup, tidak perlu melatih model, dan menambah orang baru tidak mengganggu yang sudah ada.

---

## Instalasi

### 1. Python API server

Butuh **Python 3.10–3.13**.

```bash
cd voice
python -m venv .venv

# Windows
.venv\Scripts\activate
# macOS / Linux
source .venv/bin/activate

pip install -r requirements.txt
python voice_api_server.py
```

Saat pertama dijalankan, server:

- mengunduh checkpoint ECAPA-TDNN (~80 MB) dari HuggingFace — **butuh internet sekali**, setelah itu jalan offline;
- membuat token API dan mencetaknya ke konsol.

Salin token itu — dibutuhkan di langkah 3.

```
====================================================================
API token: kJ8x...  (contoh)
Masukkan ke mod dengan: /voicedoor settoken kJ8x...
====================================================================
```

Detail lengkap, opsi CLI, dan troubleshooting ada di [voice/README.md](voice/README.md).

### 2. Mod Minecraft

Butuh **Forge 1.20.1 (47.3.0+)** dan **[Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) 2.5.0+**.

```bash
cd voicedoor_mod
./gradlew build          # Windows: gradlew.bat build
```

Hasilnya di `build/libs/voicedoor-1.0.0.jar`. Taruh di folder `mods/` instance Minecraft-mu, bersama jar rilis Simple Voice Chat.

Gradle wrapper sudah disertakan dan dipin ke Gradle 8.1.1 — tidak perlu memasang Gradle sendiri, dan jangan pakai Gradle 9 (ForgeGradle 6 tidak kompatibel dengannya).

> **Untuk `./gradlew runClient` (lingkungan dev), jangan taruh jar Simple Voice Chat di `run/mods/`.**
> Jar rilisnya memakai nama SRG (ter-obfuscate), sementara dev environment memakai mapping
> `official`, jadi voicechat akan crash saat start dengan
> `NoSuchMethodError: net.minecraft.client.Minecraft.m_91087_()`.
> `build.gradle` sudah menariknya sebagai `runtimeOnly fg.deobf(...)` dari maven Modrinth,
> yang meremapnya lebih dulu. Di Minecraft sungguhan justru sebaliknya: pasang jar rilisnya
> seperti biasa, karena runtime produksi memang memakai nama SRG.

### 3. Sambungkan keduanya

Di dalam game, sebagai operator:

```
/voicedoor settoken <token dari langkah 1>
/voicedoor status
```

`status` harus menjawab `Mode: verification`. Kalau tidak bisa menghubungi server, periksa apakah `voice_api_server.py` masih berjalan.

---

## Pemakaian

```
1. Pasang blok VoiceDoor (craft: 3 besi + 2 emas + 1 redstone).

2. Arahkan pandangan ke pintu, lalu:
   /voicedoor register

   Bicaralah alami selama 4 detik. Ulangi 3-5 kali supaya voiceprint stabil.
   Pendaftar pertama otomatis menjadi pemilik pintu.

3. Klik pintu. Bicaralah saat diminta. Pintu terbuka kalau suaranya cocok.
```

### Command

| Command | Keterangan |
|---|---|
| `/voicedoor register [nama]` | Rekam sample suara untuk pintu yang dilihat |
| `/voicedoor info` | Detail pintu itu (pemilik, ambang, status) |
| `/voicedoor status` | Cek koneksi dan kondisi server suara |
| `/voicedoor allow <pemain>` | Izinkan pemain lain memakai pintumu |
| `/voicedoor deny <pemain>` | Cabut izin itu |
| `/voicedoor help` | Daftar lengkap |

Khusus operator: `settoken`, `setapi`, `setthreshold`, `setowner`, `clearowner`, `enroll`, `reload`.

### Konfigurasi

Setelan server ada di `<world>/serverconfig/voicedoor-server.toml` — URL API, token, ambang verifikasi, durasi rekaman, lama pintu terbuka, dan cooldown. File itu dibuat otomatis saat world pertama dimuat.

---

## Keamanan: apa yang dilindungi dan apa yang tidak

Yang **sudah** ditangani:

- **Autentikasi API.** Semua endpoint kecuali `/health` menolak request tanpa token yang benar. Tanpa ini, siapa pun yang bisa menjangkau port 5000 tinggal mendaftarkan suaranya sendiri dengan nama pemilik lalu masuk.
- **Challenge sekali-pakai.** Pintu meminta challenge sebelum menerima audio. Challenge kedaluwarsa dalam 45 detik dan tidak bisa dipakai dua kali, jadi audio tidak bisa dikirim di luar alur normal.
- **Deteksi replay.** Rekaman yang sudah pernah dipakai untuk membuka pintu ditolak kalau dikirim ulang.
- **Ambang tidak bisa dilemahkan client.** Server hanya menerima permintaan ambang yang **lebih ketat** dari setelannya sendiri.
- **Rate limiting.** 12 percobaan per menit per member, untuk menahan brute force.
- **Passphrase acak (opsional).** Jalankan server dengan `--require-passphrase`: pemain harus mengucapkan frasa acak yang berbeda setiap percobaan, diverifikasi lewat speech-to-text. Ini yang benar-benar mematikan serangan replay — tapi butuh internet.

Yang **tidak** dilindungi, dan sebaiknya kamu sadari:

- **Voice cloning.** Model TTS modern bisa meniru suara dari sample pendek. Tidak ada mekanisme di sini yang menghadapinya.
- **Orang yang bisa menyuruh pemilik bicara.** Kalau penyerang bisa membuat pemilik mengucapkan frasa yang diminta saat itu, passphrase pun tidak menolong.
- **Suara yang mirip.** Speaker verification bekerja dengan probabilitas. Saudara kandung atau orang bersuara serupa bisa lolos, terutama pada ambang rendah. Naikkan `threshold` ke 0.55–0.60 kalau ini jadi masalah.
- **Lalu lintas HTTP biasa.** Kalau server API tidak di mesin yang sama, audio dan token dikirim tanpa enkripsi. Pakai HTTPS lewat reverse proxy, atau biarkan API di localhost saja.

Singkatnya: ini cukup untuk permainan dan untuk menahan serangan realistis di server Minecraft (merekam proximity chat lalu memutarnya ulang). Jangan dipakai untuk apa pun yang benar-benar perlu diamankan.

---

## Kalau ada masalah

| Gejala | Penyebab dan solusi |
|---|---|
| `Mod voicedoor requires voicechat 2.5.0 or above` padahal versinya lebih baru | Lihat catatan versi di bawah |
| `NoSuchMethodError: ...Minecraft.m_91087_()` saat `runClient` | Ada jar rilis voicechat di `run/mods/`. Keluarkan — Gradle sudah menyediakan versi yang sudah diremap |
| Mod tidak muncul di Minecraft sungguhan | Jar rilis Simple Voice Chat belum ada di `mods/` |
| `Token tidak valid` | Jalankan `/voicedoor settoken <token>` dengan token dari terminal server Python |
| `Tidak bisa terhubung ke http://127.0.0.1:5000` | Server Python mati atau terminalnya sudah ditutup |
| Nama item tampil sebagai `block.voicedoor.voice_door` | Build lama; jalankan `./gradlew build` lagi |
| `'X' belum punya voiceprint` | Perlu `/voicedoor register` beberapa kali lagi |
| Pintu terbuka untuk orang lain | Ambang terlalu rendah. Ukur dulu dengan `verify.py check`, lalu `/voicedoor setthreshold 0.6` |

### Catatan versi Simple Voice Chat

Versi Forge-nya melaporkan diri sebagai **`1.20.1-2.6.23`** — diawali versi Minecraft, bukan `2.6.23`. Akibatnya `versionRange="[2.5.0,)"` **selalu gagal**: Forge membandingkan komponen pertama, `1` lawan `2`, lalu menolak memuat mod dengan pesan yang menyesatkan:

```
Mod voicedoor requires voicechat 2.5.0 or above
    Currently, voicechat is 1.20.1-2.6.23
```

Range yang benar memakai format yang sama: `[1.20.1-2.5.0,)`. Kalau kamu menaikkan versi Minecraft nanti, range ini ikut harus diubah.

---

## Pengembangan

```bash
# Test Python (67 test)
cd voice
python -m pytest                    # semua
python -m pytest -m "not slow"      # lewati yang butuh model ECAPA

# Jalankan Minecraft dengan mod terpasang
cd voicedoor_mod
./gradlew runClient
```

Server API punya beberapa opsi yang berguna saat mengembangkan:

```bash
python voice_api_server.py --no-auth        # tanpa token, hanya untuk tes lokal
python voice_api_server.py --dev            # Flask dev server + debug
python voice_api_server.py --threshold 0.6  # lebih ketat
```

CLI untuk memeriksa voiceprint tanpa masuk game:

```bash
cd voice
python src/verify.py list                       # siapa saja yang sudah enrolled
python src/verify.py enroll Budi                # bangun ulang voiceprint
python src/verify.py check rekaman.wav Budi     # cetak skor mentahnya
```

`check` itu cara paling berguna untuk mengkalibrasi ambang: rekam beberapa klip orang lain, lihat skornya, lalu set `threshold` di tengah celah antara skor pemilik dan skor orang lain.

---

## Struktur

```
mod_voice/
├── README.md               ← file ini
├── voicedoor_mod/          Mod Forge
│   ├── gradlew(.bat)       Wrapper, dipin ke Gradle 8.1.1
│   └── src/main/java/com/voicedoor/
│       ├── VoiceDoorMod.java       Entry point, tick sesi
│       ├── api/                    Klien HTTP ke Python API
│       ├── block/                  Blok pintu dua bagian
│       ├── blockentity/            State pemilik + alur verifikasi
│       ├── client/                 Indikator di layar
│       ├── command/                /voicedoor
│       ├── config/                 voicedoor-server.toml
│       ├── item/                   Item blok + tab creative
│       ├── network/                Paket status ke client
│       └── voice/                  Integrasi Simple Voice Chat, penulis WAV
└── voice/                  Python API server
    ├── voice_api_server.py Endpoint HTTP
    ├── app.py              UI Streamlit (opsional, untuk eksplorasi dataset)
    ├── src/
    │   ├── verify.py       Speaker verification - ini yang dipakai pintu
    │   ├── challenge.py    Challenge sekali-pakai + deteksi replay
    │   ├── embeddings.py   Voiceprint ECAPA-TDNN
    │   ├── preprocess.py   Load, noise reduction, segmentasi
    │   ├── train.py        Classifier closed-set (hanya untuk UI Streamlit)
    │   └── predict.py      Identifikasi closed-set (hanya untuk UI Streamlit)
    └── tests/              67 test
```

Catatan soal `train.py` dan `predict.py`: keduanya melayani UI Streamlit, yang menjawab pertanyaan berbeda — *"di antara anggota keluarga, ini siapa?"*. Pintu tidak memakainya dan tidak memerlukan model terlatih.

---

## Lisensi

MIT
