package com.voicedoor.network;

import com.voicedoor.client.VoiceDoorClientHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Paket dari server ke client untuk membuka VoiceDoorScreen GUI di layar pemain.
 */
public class S2COpenVoiceScreenPacket {

    private final BlockPos pos;
    private final boolean isGarage;
    private final String allowedVoices;
    private final String phrase;

    public S2COpenVoiceScreenPacket(BlockPos pos, boolean isGarage, String allowedVoices, String phrase) {
        this.pos = pos;
        this.isGarage = isGarage;
        this.allowedVoices = allowedVoices == null ? "" : allowedVoices;
        this.phrase = phrase == null ? "" : phrase;
    }

    public static void encode(S2COpenVoiceScreenPacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
        buf.writeBoolean(pkt.isGarage);
        buf.writeUtf(pkt.allowedVoices, 256);
        buf.writeUtf(pkt.phrase, 256);
    }

    public static S2COpenVoiceScreenPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        boolean isGarage = buf.readBoolean();
        String allowedVoices = buf.readUtf(256);
        String phrase = buf.readUtf(256);
        return new S2COpenVoiceScreenPacket(pos, isGarage, allowedVoices, phrase);
    }

    public static void handle(S2COpenVoiceScreenPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> VoiceDoorClientHandler.openScreen(pkt.pos, pkt.isGarage, pkt.allowedVoices, pkt.phrase)));
        ctx.setPacketHandled(true);
    }

    public BlockPos getPos() {
        return pos;
    }

    public boolean isGarage() {
        return isGarage;
    }

    public String getAllowedVoices() {
        return allowedVoices;
    }

    public String getPhrase() {
        return phrase;
    }
}
