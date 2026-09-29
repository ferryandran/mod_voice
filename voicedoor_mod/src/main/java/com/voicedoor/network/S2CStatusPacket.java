package com.voicedoor.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Packet dari server ke client untuk status verifikasi.
 */
public class S2CStatusPacket {
    public enum StatusType { RECORDING_START, RECORDING_STOP, VERIFICATION_OK, VERIFICATION_FAIL }

    private final StatusType status;
    private final String message;

    public S2CStatusPacket(StatusType status, String message) {
        this.status = status;
        this.message = message;
    }

    public static void encode(S2CStatusPacket pkt, FriendlyByteBuf buf) {
        buf.writeEnum(pkt.status);
        buf.writeUtf(pkt.message, 256);
    }

    public static S2CStatusPacket decode(FriendlyByteBuf buf) {
        return new S2CStatusPacket(buf.readEnum(StatusType.class), buf.readUtf(256));
    }

    public static void handle(S2CStatusPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            // Client-side: tampilkan UI feedback (recording indicator, dll)
        });
        ctx.setPacketHandled(true);
    }

    public StatusType getStatus() { return status; }
    public String getMessage() { return message; }
}
