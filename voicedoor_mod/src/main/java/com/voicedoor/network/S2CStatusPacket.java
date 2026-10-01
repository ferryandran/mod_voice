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
 */
public class S2CStatusPacket {

    public enum StatusType {
        RECORDING_START,
        RECORDING_STOP,
        VERIFICATION_OK,
        VERIFICATION_FAIL
    }

    private static final int MAX_MESSAGE_CHARS = 256;

    private final StatusType status;
    private final String message;
    private final int seconds;

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

    // -- wire ---------------------------------------------------------------
    public static void encode(S2CStatusPacket pkt, FriendlyByteBuf buf) {
        buf.writeEnum(pkt.status);
        buf.writeUtf(pkt.message, MAX_MESSAGE_CHARS);
        buf.writeVarInt(pkt.seconds);
    }

    public static S2CStatusPacket decode(FriendlyByteBuf buf) {
        return new S2CStatusPacket(
                buf.readEnum(StatusType.class),
                buf.readUtf(MAX_MESSAGE_CHARS),
                buf.readVarInt());
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

    private static String truncate(String raw) {
        return raw.length() <= MAX_MESSAGE_CHARS ? raw : raw.substring(0, MAX_MESSAGE_CHARS);
    }
}
