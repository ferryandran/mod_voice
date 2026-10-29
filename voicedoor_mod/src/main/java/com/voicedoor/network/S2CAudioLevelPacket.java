package com.voicedoor.network;

import com.voicedoor.client.VoiceDoorClientHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Paket dari server ke client yang membawa level volume suara saat ini (0.0f - 1.0f).
 * Digunakan untuk visualizer VU meter dan equalizer bar di GUI saat merekam.
 */
public class S2CAudioLevelPacket {

    private final float level;

    public S2CAudioLevelPacket(float level) {
        this.level = Math.max(0.0f, Math.min(1.0f, level));
    }

    public static void encode(S2CAudioLevelPacket pkt, FriendlyByteBuf buf) {
        buf.writeFloat(pkt.level);
    }

    public static S2CAudioLevelPacket decode(FriendlyByteBuf buf) {
        return new S2CAudioLevelPacket(buf.readFloat());
    }

    public static void handle(S2CAudioLevelPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        VoiceDoorClientHandler.onAudioLevel(pkt.level)));
        ctx.get().setPacketHandled(true);
    }

    public float getLevel() {
        return level;
    }
}
