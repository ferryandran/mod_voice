package com.voicedoor.voice;

import com.voicedoor.VoiceDoorMod;
import com.voicedoor.blockentity.VoiceDoorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.network.chat.Component;

/**
 * Penghubung antara VoiceDoorBlockEntity dan VoiceChatPlugin.
 * Menginisialisasi sesi rekaman suara.
 */
public class VoiceRecordingSession {

    /**
     * Mulai sesi verifikasi suara.
     * Pemain harus berbicara ke mikrofon selama RECORDING_DURATION_MS ms.
     */
    public static void startVerification(ServerPlayer player, VoiceDoorBlockEntity be,
                                          BlockPos pos, Level level) {
        if (!VoiceDoorMod.VOICE_CHAT_AVAILABLE) {
            player.sendSystemMessage(Component.literal(
                    "§c[VoiceDoor] Simple Voice Chat tidak tersedia!"));
            return;
        }

        if (VoiceChatPlugin.hasActiveSession(player.getUUID())) {
            player.sendSystemMessage(Component.literal(
                    "§e[VoiceDoor] Sesi rekaman sedang berjalan, harap tunggu..."));
            return;
        }

        // Mulai sesi rekaman
        VoiceChatPlugin.startVerificationSession(player, level, pos);

        int durationSec = VoiceDoorBlockEntity.RECORDING_DURATION_MS / 1000;
        player.sendSystemMessage(Component.literal(
                String.format("§b[VoiceDoor] 🎙️ Bicaralah ke mikrofon selama %d detik untuk membuka pintu...",
                        durationSec)));
    }

    /**
     * Mulai sesi registrasi suara untuk mendaftarkan pemilik baru.
     */
    public static void startRegistration(ServerPlayer player, VoiceDoorBlockEntity be,
                                          BlockPos pos, Level level, String memberName) {
        if (!VoiceDoorMod.VOICE_CHAT_AVAILABLE) {
            player.sendSystemMessage(Component.literal(
                    "§c[VoiceDoor] Simple Voice Chat tidak tersedia!"));
            return;
        }

        if (VoiceChatPlugin.hasActiveSession(player.getUUID())) {
            player.sendSystemMessage(Component.literal(
                    "§e[VoiceDoor] Sesi rekaman sedang berjalan, harap tunggu..."));
            return;
        }

        be.startRegistering(memberName);
        VoiceChatPlugin.startRegistrationSession(player, level, pos);

        int durationSec = VoiceDoorBlockEntity.RECORDING_DURATION_MS / 1000;
        player.sendSystemMessage(Component.literal(
                String.format("§a[VoiceDoor] 🎙️ Bicaralah secara alami selama %d detik untuk mendaftarkan suaramu sebagai: §e%s",
                        durationSec, memberName)));
    }
}
