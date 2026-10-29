package com.voicedoor.network;

import com.voicedoor.blockentity.VoiceDoorBlockEntity;
import com.voicedoor.garage.GarageDoorHandler;
import com.voicedoor.voice.VoiceChatPlugin;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Paket dari client ke server saat pemain berinteraksi dengan VoiceDoorScreen
 * (tombol Mulai Rekam, Berhenti Rekam, atau Tutup).
 */
public class C2SVoiceActionPacket {

    public enum Action {
        START,
        STOP,
        CANCEL,
        /** Mulai merekam sample registrasi dari VoiceRegisterScreen. */
        REGISTER_START
    }

    private final BlockPos pos;
    private final boolean isGarage;
    private final Action action;

    public C2SVoiceActionPacket(BlockPos pos, boolean isGarage, Action action) {
        this.pos = pos;
        this.isGarage = isGarage;
        this.action = action;
    }

    public static void encode(C2SVoiceActionPacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
        buf.writeBoolean(pkt.isGarage);
        buf.writeEnum(pkt.action);
    }

    public static C2SVoiceActionPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        boolean isGarage = buf.readBoolean();
        Action action = buf.readEnum(Action.class);
        return new C2SVoiceActionPacket(pos, isGarage, action);
    }

    public static void handle(C2SVoiceActionPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || !player.isAlive()) return;

            switch (pkt.action) {
                case START -> {
                    if (pkt.isGarage) {
                        GarageDoorHandler.startSessionFromGui(player, pkt.pos);
                    } else {
                        VoiceDoorBlockEntity.startSessionFromGui(player, pkt.pos);
                    }
                }
                case STOP -> {
                    VoiceChatPlugin.stopSessionManually(player.getUUID());
                }
                case CANCEL -> {
                    VoiceChatPlugin.cancelSession(player.getUUID());
                }
                case REGISTER_START -> {
                    VoiceDoorBlockEntity.startRegistrationFromGui(player, pkt.pos);
                }
            }
        });
        ctx.setPacketHandled(true);
    }
}
