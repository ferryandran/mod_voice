package com.voicedoor.network;

import com.voicedoor.client.VoiceDoorClientHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Status verifikasi dari server ke client, untuk umpan balik di layar.
 *
 * <p>Di versi sebelumnya paket ini terdaftar tapi handler-nya kosong dan tidak pernah ada
 * satu pun pengiriman - seluruh package {@code network} adalah kode mati. Sekarang paket
 * ini benar-benar dipakai: menampilkan indikator "bicaralah sekarang" beserta frasa yang
 * harus diucapkan, lalu hasil verifikasinya.
 *
 * <p>Penting: chat sudah menampilkan pesan yang sama, tapi saat pemain harus berbicara
 * dalam hitungan detik, teks di tengah layar jauh lebih mudah dilihat daripada baris chat
 * yang mungkin sudah tergulung.
 *
 * <p>Ditambahkan di v2: field detail verifikasi (detectedSpeaker, transcript, voiceOk,
 * passwordOk) untuk GUI detection feedback.
 */
public class S2CStatusPacket {

    public enum StatusType {
        RECORDING_START,
        RECORDING_STOP,
        VERIFICATION_OK,
        VERIFICATION_FAIL,
        // Ditambahkan di akhir: enum dikirim sebagai ordinal.
        REGISTER_OK,
        REGISTER_FAIL
    }

    private static final int MAX_MESSAGE_CHARS = 256;

    private final StatusType status;
    private final String message;
    private final int seconds;

    // Detail verifikasi (hanya diisi untuk VERIFICATION_OK dan VERIFICATION_FAIL)
    private String detectedSpeaker = "";
    private String transcript = "";
    private boolean voiceOk = false;
    private boolean passwordOk = false;

    public S2CStatusPacket(StatusType status, String message, int seconds) {
        this.status = status;
        this.message = message == null ? "" : truncate(message);
        this.seconds = seconds;
    }

    // -- factory ------------------------------------------------------------
    /** @param phrase frasa yang harus diucapkan, atau kosong kalau passphrase tidak dipakai */
    public static S2CStatusPacket recordingStart(String phrase, int seconds) {
        return new S2CStatusPacket(StatusType.RECORDING_START, phrase, seconds);
    }

    public static S2CStatusPacket recordingStop() {
        return new S2CStatusPacket(StatusType.RECORDING_STOP, "", 0);
    }

    public static S2CStatusPacket verificationOk(double similarity) {
        return new S2CStatusPacket(StatusType.VERIFICATION_OK,
                String.format(java.util.Locale.ROOT, "%.0f", similarity * 100), 0);
    }

    public static S2CStatusPacket verificationFail(String reason) {
        return new S2CStatusPacket(StatusType.VERIFICATION_FAIL, reason, 0);
    }

    /**
     * Hasil verifikasi lengkap dengan detail deteksi suara dan password.
     *
     * @param accepted    apakah pintu terbuka
     * @param similarity  skor kemiripan suara
     * @param speaker     nama speaker yang terdeteksi (atau "Unknown Voice")
     * @param transcript  apa yang diucapkan
     * @param voiceOk     apakah suara cocok dengan voiceprint
     * @param passwordOk  apakah password yang diucapkan benar
     * @param reason      alasan penolakan (jika ditolak), kosong jika diterima
     */
    public static S2CStatusPacket verificationResult(boolean accepted, double similarity,
                                                      String speaker, String transcript,
                                                      boolean voiceOk, boolean passwordOk,
                                                      String reason) {
        S2CStatusPacket pkt = new S2CStatusPacket(
                accepted ? StatusType.VERIFICATION_OK : StatusType.VERIFICATION_FAIL,
                accepted ? String.format(java.util.Locale.ROOT, "%.0f", similarity * 100) : reason,
                0);
        pkt.detectedSpeaker = speaker == null ? "" : truncate(speaker);
        pkt.transcript = transcript == null ? "" : truncate(transcript);
        pkt.voiceOk = voiceOk;
        pkt.passwordOk = passwordOk;
        return pkt;
    }

    /**
     * @param summary  ringkasan untuk GUI registrasi (durasi, kohesi, saran)
     * @param nSamples jumlah sample member itu yang sekarang tersimpan
     */
    public static S2CStatusPacket registerOk(String summary, int nSamples) {
        return new S2CStatusPacket(StatusType.REGISTER_OK, summary, nSamples);
    }

    public static S2CStatusPacket registerFail(String reason) {
        return new S2CStatusPacket(StatusType.REGISTER_FAIL, reason, 0);
    }

    // -- wire ---------------------------------------------------------------
    public static void encode(S2CStatusPacket pkt, FriendlyByteBuf buf) {
        buf.writeEnum(pkt.status);
        buf.writeUtf(pkt.message, MAX_MESSAGE_CHARS);
        buf.writeVarInt(pkt.seconds);
        buf.writeUtf(pkt.detectedSpeaker, MAX_MESSAGE_CHARS);
        buf.writeUtf(pkt.transcript, MAX_MESSAGE_CHARS);
        buf.writeBoolean(pkt.voiceOk);
        buf.writeBoolean(pkt.passwordOk);
    }

    public static S2CStatusPacket decode(FriendlyByteBuf buf) {
        S2CStatusPacket pkt = new S2CStatusPacket(
                buf.readEnum(StatusType.class),
                buf.readUtf(MAX_MESSAGE_CHARS),
                buf.readVarInt());
        pkt.detectedSpeaker = buf.readUtf(MAX_MESSAGE_CHARS);
        pkt.transcript = buf.readUtf(MAX_MESSAGE_CHARS);
        pkt.voiceOk = buf.readBoolean();
        pkt.passwordOk = buf.readBoolean();
        return pkt;
    }

    public static void handle(S2CStatusPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() ->
                // Dibungkus DistExecutor supaya class client tidak pernah dimuat di server.
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> VoiceDoorClientHandler.onStatus(pkt)));
        ctx.setPacketHandled(true);
    }

    // -- accessor -----------------------------------------------------------
    public StatusType getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public int getSeconds() {
        return seconds;
    }

    public String getDetectedSpeaker() {
        return detectedSpeaker;
    }

    public String getTranscript() {
        return transcript;
    }

    public boolean isVoiceOk() {
        return voiceOk;
    }

    public boolean isPasswordOk() {
        return passwordOk;
    }

    private static String truncate(String raw) {
        return raw.length() <= MAX_MESSAGE_CHARS ? raw : raw.substring(0, MAX_MESSAGE_CHARS);
    }
}

