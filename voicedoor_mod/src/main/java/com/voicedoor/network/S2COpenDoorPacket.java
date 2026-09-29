package com.voicedoor.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Packet dari server ke client untuk notifikasi pintu dibuka/ditutup.
 * (Opsional - digunakan untuk sinkronisasi animasi di client side)
 */
public class S2COpenDoorPacket {
    private final BlockPos doorPos;
    private final boolean open;
    private final String speakerName;

    public S2COpenDoorPacket(BlockPos doorPos, boolean open, String speakerName) {
        this.doorPos = doorPos;
        this.open = open;
        this.speakerName = speakerName;
    }

    public static void encode(S2COpenDoorPacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.doorPos);
        buf.writeBoolean(pkt.open);
        buf.writeUtf(pkt.speakerName, 64);
    }

    public static S2COpenDoorPacket decode(FriendlyByteBuf buf) {
        return new S2COpenDoorPacket(buf.readBlockPos(), buf.readBoolean(), buf.readUtf(64));
    }

    public static void handle(S2COpenDoorPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            // Client-side handling (animasi, partikel, dll)
            // Tidak diperlukan jika hanya menggunakan server-side state
        });
        ctx.setPacketHandled(true);
    }

    public BlockPos getDoorPos() { return doorPos; }
    public boolean isOpen() { return open; }
    public String getSpeakerName() { return speakerName; }
}
