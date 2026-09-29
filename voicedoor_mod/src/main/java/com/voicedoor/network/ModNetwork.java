package com.voicedoor.network;

import com.voicedoor.VoiceDoorMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class ModNetwork {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(VoiceDoorMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    public static void register() {
        CHANNEL.registerMessage(packetId++, S2COpenDoorPacket.class,
                S2COpenDoorPacket::encode,
                S2COpenDoorPacket::decode,
                S2COpenDoorPacket::handle);

        CHANNEL.registerMessage(packetId++, S2CStatusPacket.class,
                S2CStatusPacket::encode,
                S2CStatusPacket::decode,
                S2CStatusPacket::handle);
    }
}
