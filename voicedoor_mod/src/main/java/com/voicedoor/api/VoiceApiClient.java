package com.voicedoor.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.voicedoor.VoiceDoorMod;
import com.voicedoor.config.VoiceDoorConfig;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Satu-satunya tempat mod ini berbicara HTTP dengan Python voice API.
 *
 * <p>Sebelumnya logika HTTP tersebar di block entity dan class command, masing-masing
 * dengan bug sendiri. Yang diperbaiki di sini:
 *
 * <ul>
 *   <li><b>Deteksi error HTTP.</b> Kode lama memakai {@code code >= 200 ? getInputStream()
 *       : getErrorStream()}. 404 dan 500 juga {@code >= 200}, jadi selalu memanggil
 *       {@code getInputStream()} yang melempar IOException untuk respons error - pesan
 *       error dari server tidak pernah sampai ke pemain.</li>
 *   <li><b>Encoding.</b> {@code new PrintStream(baos)} memakai charset default platform,
 *       merusak nama dengan karakter non-ASCII. Semua teks di sini ditulis UTF-8.</li>
 *   <li><b>Thread pool.</b> {@code newCachedThreadPool} tidak punya batas. Di sini
 *       jumlahnya dibatasi config, dan request yang melebihi kapasitas ditolak dengan
 *       pesan yang jelas, bukan menumpuk thread.</li>
 *   <li><b>Parsing.</b> Memakai Gson, bukan pencarian string manual yang tidak menangani
 *       escape maupun objek bersarang.</li>
 *   <li><b>Autentikasi.</b> Token dikirim sebagai header di setiap request.</li>
 * </ul>
 */
public final class VoiceApiClient {

    private static final String AUTH_HEADER = "X-VoiceDoor-Token";
    private static final int MAX_RESPONSE_BYTES = 1 << 20; // 1 MB, jauh di atas balasan wajar

    /**
     * Pool terbatas dengan queue pendek. Kalau penuh, request ditolak seketika supaya
     * pemain dapat pesan "server sibuk" alih-alih menunggu tanpa batas.
     */
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            1, Math.max(1, VoiceDoorConfig.maxConcurrentRequests()),
            30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(16),
            r -> {
                Thread t = new Thread(r, "VoiceDoor-HTTP");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

    private VoiceApiClient() {
    }

    /** Hasil satu panggilan API: sukses dengan body, atau gagal dengan alasan. */
    public record ApiResponse(boolean ok, int statusCode, @Nullable JsonObject body, @Nullable String error) {

        public static ApiResponse failure(String reason) {
            return new ApiResponse(false, -1, null, reason);
        }

        /** Nilai {@code success} dari body, false kalau tidak ada body. */
        public boolean isSuccess() {
            return ok && body != null && optBoolean("success", false);
        }

        public boolean optBoolean(String key, boolean fallback) {
            if (body == null) return fallback;
            JsonElement el = body.get(key);
            if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return fallback;
            try {
                return el.getAsBoolean();
            } catch (RuntimeException e) {
                return fallback;
            }
        }

        public double optDouble(String key, double fallback) {
            if (body == null) return fallback;
            JsonElement el = body.get(key);
            if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return fallback;
            try {
                return el.getAsDouble();
            } catch (RuntimeException e) {
                return fallback;
            }
        }

        public String optString(String key, String fallback) {
            if (body == null) return fallback;
            JsonElement el = body.get(key);
            if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return fallback;
            try {
                return el.getAsString();
            } catch (RuntimeException e) {
                return fallback;
            }
        }

        /**
         * Pesan yang layak ditampilkan ke pemain: {@code error} dari server kalau ada,
         * kalau tidak alasan transport, kalau tidak pesan generik.
         */
        public String errorMessage() {
            String fromBody = optString("error", "");
            if (!fromBody.isEmpty()) return fromBody;
            if (error != null && !error.isEmpty()) return error;
            if (statusCode > 0) return "Server menjawab HTTP " + statusCode + ".";
            return "Permintaan ke server suara gagal.";
        }
    }

    /** Dijalankan di thread HTTP; hasilnya harus dikembalikan ke main thread oleh pemanggil. */
    public interface ResponseHandler {
        void accept(ApiResponse response);
    }

    // -----------------------------------------------------------------------
    // Submit asinkron
    // -----------------------------------------------------------------------
    // Semua varian mengembalikan false kalau antrean penuh, supaya pemanggil bisa memberi
    // tahu pemain alih-alih menunggu balasan yang tidak akan pernah datang.

    public static boolean tryPostFormAsync(String path, MultipartBody body, ResponseHandler handler) {
        return submit(() -> handler.accept(postForm(path, body)));
    }

    public static boolean tryPostJsonAsync(String path, String json, ResponseHandler handler) {
        return submit(() -> handler.accept(postJson(path, json)));
    }

    public static boolean tryGetAsync(String path, ResponseHandler handler) {
        return submit(() -> handler.accept(get(path)));
    }

    private static boolean submit(Runnable task) {
        try {
            EXECUTOR.submit(task);
            return true;
        } catch (RejectedExecutionException e) {
            VoiceDoorMod.LOGGER.warn("[VoiceDoor] Antrean request HTTP penuh, permintaan ditolak.");
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Panggilan sinkron (selalu dari thread HTTP)
    // -----------------------------------------------------------------------
    public static ApiResponse get(String path) {
        HttpURLConnection conn = null;
        try {
            conn = open(path, "GET");
            return readResponse(conn);
        } catch (IOException e) {
            return ApiResponse.failure(describe(e));
        } finally {
            disconnect(conn);
        }
    }

    public static ApiResponse postJson(String path, String json) {
        HttpURLConnection conn = null;
        try {
            conn = open(path, "POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            return readResponse(conn);
        } catch (IOException e) {
            return ApiResponse.failure(describe(e));
        } finally {
            disconnect(conn);
        }
    }

    public static ApiResponse postForm(String path, MultipartBody body) {
        HttpURLConnection conn = null;
        try {
            conn = open(path, "POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + body.boundary());
            byte[] payload = body.build();
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            return readResponse(conn);
        } catch (IOException e) {
            return ApiResponse.failure(describe(e));
        } finally {
            disconnect(conn);
        }
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------
    private static HttpURLConnection open(String path, String method) throws IOException {
        String base = VoiceDoorConfig.apiUrl();
        if (base.isEmpty()) {
            throw new IOException("URL API belum diatur. Pakai /voicedoor setapi <url>.");
        }
        URL url;
        try {
            url = URI.create(base + path).toURL();
        } catch (IllegalArgumentException e) {
            throw new IOException("URL API tidak valid: " + base);
        }
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new IOException("Hanya http/https yang didukung, bukan " + protocol + ".");
        }
        if (!(url.openConnection() instanceof HttpURLConnection conn)) {
            throw new IOException("Koneksi bukan HTTP: " + base);
        }
        conn.setRequestMethod(method);
        conn.setConnectTimeout(VoiceDoorConfig.connectTimeoutMillis());
        conn.setReadTimeout(VoiceDoorConfig.readTimeoutMillis());
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "VoiceDoorMod/1.0");
        if (VoiceDoorConfig.hasToken()) {
            conn.setRequestProperty(AUTH_HEADER, VoiceDoorConfig.apiToken());
        }
        return conn;
    }

    /**
     * Baca respons dengan benar untuk kode sukses MAUPUN error.
     *
     * <p>Inilah perbaikan bug lama: stream error dipilih berdasarkan {@code 2xx}, bukan
     * {@code >= 200}, sehingga body JSON dari respons 4xx/5xx ikut terbaca dan alasan
     * aslinya bisa ditampilkan ke pemain.
     */
    private static ApiResponse readResponse(HttpURLConnection conn) throws IOException {
        int status = conn.getResponseCode();
        boolean success = status >= 200 && status < 300;

        InputStream stream = success ? conn.getInputStream() : conn.getErrorStream();
        String text = stream == null ? "" : readLimited(stream);

        if (text.isEmpty()) {
            return new ApiResponse(success, status, null,
                    success ? "Server menjawab tanpa isi." : "Server menjawab HTTP " + status + ".");
        }

        JsonObject json;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                return new ApiResponse(false, status, null, "Balasan server bukan objek JSON.");
            }
            json = parsed.getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            // Biasanya halaman HTML error dari proxy atau Flask debug page.
            String preview = text.length() > 120 ? text.substring(0, 120) + "..." : text;
            return new ApiResponse(false, status, null,
                    "Balasan server bukan JSON (HTTP " + status + "): " + preview);
        }
        return new ApiResponse(success, status, json, success ? null : "HTTP " + status);
    }

    private static String readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_RESPONSE_BYTES) {
                throw new IOException("Balasan server terlalu besar (>" + (MAX_RESPONSE_BYTES / 1024) + " KB).");
            }
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void disconnect(@Nullable HttpURLConnection conn) {
        if (conn != null) {
            conn.disconnect();
        }
    }

    /** Ubah exception jaringan menjadi kalimat yang berguna untuk pemain. */
    private static String describe(IOException e) {
        String message = e.getMessage();
        if (e instanceof java.net.SocketTimeoutException) {
            return "Server suara tidak menjawab dalam waktu yang ditentukan.";
        }
        if (e instanceof java.net.ConnectException) {
            return "Tidak bisa terhubung ke " + VoiceDoorConfig.apiUrl()
                    + ". Pastikan 'python voice_api_server.py' sudah berjalan.";
        }
        if (e instanceof java.net.UnknownHostException) {
            return "Host tidak ditemukan: " + message;
        }
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    // -----------------------------------------------------------------------
    // Pembangun multipart
    // -----------------------------------------------------------------------
    /**
     * Pembangun body {@code multipart/form-data}, seluruhnya UTF-8.
     *
     * <p>Header bagian ditulis eksplisit sebagai byte UTF-8 daripada lewat
     * {@code PrintStream}, yang memakai charset default JVM dan merusak nama non-ASCII.
     */
    public static final class MultipartBody {
        private final String boundary = UUID.randomUUID().toString().replace("-", "");
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        public String boundary() {
            return boundary;
        }

        public MultipartBody field(String name, String value) {
            write("--" + boundary + "\r\n");
            write("Content-Disposition: form-data; name=\"" + escape(name) + "\"\r\n");
            write("Content-Type: text/plain; charset=utf-8\r\n\r\n");
            write(value == null ? "" : value);
            write("\r\n");
            return this;
        }

        public MultipartBody file(String name, String filename, String contentType, byte[] data) {
            write("--" + boundary + "\r\n");
            write("Content-Disposition: form-data; name=\"" + escape(name)
                    + "\"; filename=\"" + escape(filename) + "\"\r\n");
            write("Content-Type: " + contentType + "\r\n\r\n");
            buffer.write(data, 0, data.length);
            write("\r\n");
            return this;
        }

        public byte[] build() {
            write("--" + boundary + "--\r\n");
            return buffer.toByteArray();
        }

        private void write(String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            buffer.write(bytes, 0, bytes.length);
        }

        /** Lindungi header dari kutip dan baris baru yang bisa merusak struktur multipart. */
        private static String escape(String raw) {
            return raw.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\r", "")
                    .replace("\n", "");
        }
    }
}
