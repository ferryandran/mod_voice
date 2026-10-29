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

        CHANNEL.messageBuilder(S2COpenVoiceScreenPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2COpenVoiceScreenPacket::encode)
                .decoder(S2COpenVoiceScreenPacket::decode)
                .consumerMainThread(S2COpenVoiceScreenPacket::handle)
                .add();

        CHANNEL.messageBuilder(C2SVoiceActionPacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SVoiceActionPacket::encode)
                .decoder(C2SVoiceActionPacket::decode)
                .consumerMainThread(C2SVoiceActionPacket::handle)
                .add();

        CHANNEL.messageBuilder(S2COpenRegisterScreenPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2COpenRegisterScreenPacket::encode)
                .decoder(S2COpenRegisterScreenPacket::decode)
                .consumerMainThread(S2COpenRegisterScreenPacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CAudioLevelPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CAudioLevelPacket::encode)
                .decoder(S2CAudioLevelPacket::decode)
                .consumerMainThread(S2CAudioLevelPacket::handle)
                .add();
    }

    /** Apakah client pemain ini memasang mod (dan bisa membuka GUI). */
    public static boolean hasModInstalled(ServerPlayer player) {
        return player != null && CHANNEL.isRemotePresent(player.connection.connection);
    }

    /** Kirim paket ke satu pemain (Server -> Client). */
    public static void sendTo(ServerPlayer player, Object packet) {
        if (player == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /** Kirim paket ke server (Client -> Server). */
    public static void sendToServer(Object packet) {
        CHANNEL.sendToServer(packet);
    }
}
