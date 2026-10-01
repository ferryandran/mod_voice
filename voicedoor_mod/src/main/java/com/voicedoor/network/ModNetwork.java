package com.voicedoor.network;

import com.voicedoor.VoiceDoorMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Channel jaringan mod ini.
 *
 * <p>Protokolnya opsional di kedua arah: mod ini tetap berfungsi kalau client tidak
 * memasangnya (pemain hanya kehilangan indikator di layar, pesan chat tetap ada). Karena
 * itu {@code clientAcceptedVersions} dan {@code serverAcceptedVersions} menerima
 * {@code NetworkRegistry.ACCEPTVANILLA} juga - kalau tidak, client tanpa mod akan ditolak
 * masuk ke server.
 */
public final class ModNetwork {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(VoiceDoorMod.MOD_ID, "main"))
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            .clientAcceptedVersions(v -> true)
            .serverAcceptedVersions(v -> true)
            .simpleChannel();

    private static int packetId = 0;

    private ModNetwork() {
    }

    public static void register() {
        CHANNEL.messageBuilder(S2CStatusPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CStatusPacket::encode)
                .decoder(S2CStatusPacket::decode)
                .consumerMainThread(S2CStatusPacket::handle)
                .add();
    }

    /** Kirim satu paket status ke satu pemain. Aman dipanggil untuk pemain tanpa mod. */
    public static void sendTo(ServerPlayer player, S2CStatusPacket packet) {
        if (player == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
