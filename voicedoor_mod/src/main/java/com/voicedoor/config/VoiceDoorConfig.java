package com.voicedoor.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/**
 * Konfigurasi server untuk VoiceDoor, tersimpan di
 * {@code <world>/serverconfig/voicedoor-server.toml}.
 *
 * <p>Sebelumnya URL API disimpan per-pintu di NBT dan tidak ada tempat untuk menyimpan
 * token. Kredensial tidak boleh berada di NBT: NBT block entity dikirim ke client lewat
 * {@code getUpdateTag()}, jadi setiap pemain akan bisa membacanya. Sekarang URL dan token
 * tinggal di config sisi server, dan pintu hanya menyimpan pemiliknya.
 *
 * <p>Nilai dibaca lewat getter, bukan disalin ke field statis, karena Forge memuat ulang
 * config saat file diedit - menyalinnya sekali saat startup akan membuat perubahan
 * tidak pernah terpakai.
 */
public final class VoiceDoorConfig {

    public static final ForgeConfigSpec SPEC;
    private static final Server SERVER;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        SERVER = new Server(builder);
        SPEC = builder.build();
    }

    private VoiceDoorConfig() {
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC, "voicedoor-server.toml");
    }

    // -----------------------------------------------------------------------
    // Getter
    // -----------------------------------------------------------------------
    public static String apiUrl() {
        return stripTrailingSlash(SERVER.apiUrl.get());
    }

    public static String apiToken() {
        return SERVER.apiToken.get().trim();
    }

    public static boolean hasToken() {
        return !apiToken().isEmpty();
    }

    public static double threshold() {
        return SERVER.threshold.get();
    }

    public static int recordingMillis() {
        return SERVER.recordingSeconds.get() * 1000;
    }

    public static int recordingSeconds() {
        return SERVER.recordingSeconds.get();
    }

    /** Batas waktu sesi rekaman: durasi rekaman + kelonggaran, dalam tick. */
    public static int sessionTimeoutTicks() {
        return (SERVER.recordingSeconds.get() + SERVER.sessionGraceSeconds.get()) * 20;
    }

    public static int autoCloseTicks() {
        return SERVER.autoCloseSeconds.get() * 20;
    }

    public static int cooldownTicks() {
        return SERVER.cooldownSeconds.get() * 20;
    }

    public static boolean requireChallenge() {
        return SERVER.requireChallenge.get();
    }

    public static int connectTimeoutMillis() {
        return SERVER.connectTimeoutSeconds.get() * 1000;
    }

    public static int readTimeoutMillis() {
        return SERVER.readTimeoutSeconds.get() * 1000;
    }

    public static int maxConcurrentRequests() {
        return SERVER.maxConcurrentRequests.get();
    }

    public static boolean logVerificationDetails() {
        return SERVER.logVerificationDetails.get();
    }

    // -----------------------------------------------------------------------
    // Setter runtime (dipakai command OP)
    // -----------------------------------------------------------------------
    public static void setApiUrl(String url) {
        SERVER.apiUrl.set(stripTrailingSlash(url));
        SERVER.apiUrl.save();
    }

    public static void setApiToken(String token) {
        SERVER.apiToken.set(token == null ? "" : token.trim());
        SERVER.apiToken.save();
    }

    public static void setThreshold(double value) {
        SERVER.threshold.set(Math.min(Math.max(value, 0.35D), 0.95D));
        SERVER.threshold.save();
    }

    private static String stripTrailingSlash(String url) {
        String trimmed = url == null ? "" : url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    // -----------------------------------------------------------------------
    // Definisi
    // -----------------------------------------------------------------------
    private static final class Server {
        private final ForgeConfigSpec.ConfigValue<String> apiUrl;
        private final ForgeConfigSpec.ConfigValue<String> apiToken;
        private final ForgeConfigSpec.DoubleValue threshold;
        private final ForgeConfigSpec.IntValue recordingSeconds;
        private final ForgeConfigSpec.IntValue sessionGraceSeconds;
        private final ForgeConfigSpec.IntValue autoCloseSeconds;
        private final ForgeConfigSpec.IntValue cooldownSeconds;
        private final ForgeConfigSpec.BooleanValue requireChallenge;
        private final ForgeConfigSpec.IntValue connectTimeoutSeconds;
        private final ForgeConfigSpec.IntValue readTimeoutSeconds;
        private final ForgeConfigSpec.IntValue maxConcurrentRequests;
        private final ForgeConfigSpec.BooleanValue logVerificationDetails;

        private Server(ForgeConfigSpec.Builder builder) {
            builder.comment("Koneksi ke Python voice API server").push("api");

            apiUrl = builder
                    .comment("URL dasar API server. Jalankan: python voice_api_server.py")
                    .define("url", "http://127.0.0.1:5000");

            apiToken = builder
                    .comment(
                            "Token autentikasi API. Server mencetaknya saat start dan menyimpannya",
                            "di voice/.api_token. Set dengan: /voicedoor settoken <token>",
                            "Kosong berarti tidak mengirim token - hanya untuk server yang",
                            "dijalankan dengan --no-auth."
                    )
                    .define("token", "");

            connectTimeoutSeconds = builder
                    .comment("Batas waktu membuka koneksi ke API (detik)")
                    .defineInRange("connectTimeoutSeconds", 5, 1, 60);

            readTimeoutSeconds = builder
                    .comment("Batas waktu menunggu balasan verifikasi (detik)")
                    .defineInRange("readTimeoutSeconds", 20, 1, 300);

            maxConcurrentRequests = builder
                    .comment(
                            "Jumlah maksimum request HTTP yang berjalan bersamaan.",
                            "Membatasi thread pool supaya spam klik tidak membuat thread tanpa batas."
                    )
                    .defineInRange("maxConcurrentRequests", 4, 1, 32);

            builder.pop();
            builder.comment("Perilaku verifikasi suara").push("verification");

            threshold = builder
                    .comment(
                            "Ambang cosine similarity untuk menerima suara (0.35 - 0.95).",
                            "Server hanya menerima nilai yang LEBIH KETAT dari setelannya sendiri,",
                            "jadi menaikkan angka ini berpengaruh, menurunkannya belum tentu.",
                            "Naikkan ke 0.55-0.60 untuk pintu yang lebih ketat."
                    )
                    .defineInRange("threshold", 0.45D, 0.35D, 0.95D);

            requireChallenge = builder
                    .comment(
                            "Minta challenge sekali-pakai sebelum mengirim audio.",
                            "Ini yang mencegah rekaman suara pemilik dipakai ulang. Jangan dimatikan",
                            "kecuali server API juga dijalankan dengan --no-challenge."
                    )
                    .define("requireChallenge", true);

            recordingSeconds = builder
                    .comment("Lama merekam suara pemain untuk satu percobaan (detik)")
                    .defineInRange("recordingSeconds", 4, 1, 15);

            sessionGraceSeconds = builder
                    .comment(
                            "Kelonggaran setelah durasi rekaman sebelum sesi dibatalkan.",
                            "Tanpa ini, pemain yang mengklik pintu lalu diam akan terkunci dalam",
                            "sesi yang tidak pernah selesai."
                    )
                    .defineInRange("sessionGraceSeconds", 8, 1, 60);

            builder.pop();
            builder.comment("Perilaku pintu").push("door");

            autoCloseSeconds = builder
                    .comment("Berapa lama pintu tetap terbuka setelah verifikasi berhasil (detik)")
                    .defineInRange("autoCloseSeconds", 5, 1, 120);

            cooldownSeconds = builder
                    .comment("Jeda minimum antar percobaan verifikasi per pemain (detik)")
                    .defineInRange("cooldownSeconds", 3, 0, 60);

            builder.pop();
            builder.comment("Diagnostik").push("logging");

            logVerificationDetails = builder
                    .comment("Catat skor similarity setiap percobaan ke log server")
                    .define("logVerificationDetails", true);

            builder.pop();
        }
    }
}
