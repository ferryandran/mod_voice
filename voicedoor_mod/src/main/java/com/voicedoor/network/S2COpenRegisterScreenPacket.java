package com.voicedoor.network;

import com.voicedoor.client.VoiceDoorClientHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Paket dari server ke client untuk membuka VoiceRegisterScreen.
 *
 * <p>Nama member di sini hanya untuk ditampilkan. Server tidak pernah menerima nama member
 * dari client: tombol rekam di GUI memakai nama yang sudah disetujui server saat
 * {@code /voicedoor register} dijalankan (lihat {@code VoiceDoorBlockEntity.grantRegistration}).
 */
public class S2COpenRegisterScreenPacket {

    private final BlockPos pos;
    private final String memberName;
    private final int maxSeconds;

    public S2COpenRegisterScreenPacket(BlockPos pos, String memberName, int maxSeconds) {
        this.pos = pos;
        this.memberName = memberName == null ? "" : memberName;
        this.maxSeconds = maxSeconds;
    }

    public static void encode(S2COpenRegisterScreenPacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
        buf.writeUtf(pkt.memberName, 64);
        buf.writeVarInt(pkt.maxSeconds);
    }

    public static S2COpenRegisterScreenPacket decode(FriendlyByteBuf buf) {
        return new S2COpenRegisterScreenPacket(buf.readBlockPos(), buf.readUtf(64), buf.readVarInt());
    }

    public static void handle(S2COpenRegisterScreenPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> VoiceDoorClientHandler.openRegisterScreen(pkt.pos, pkt.memberName, pkt.maxSeconds)));
        ctx.setPacketHandled(true);
    }
}
