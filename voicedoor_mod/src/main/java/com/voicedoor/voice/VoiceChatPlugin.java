package com.voicedoor.voice;

import com.voicedoor.VoiceDoorMod;
import com.voicedoor.blockentity.VoiceDoorBlockEntity;
import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.events.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.network.chat.Component;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plugin integrasi Simple Voice Chat.
 *
 * Menangkap audio dari pemain saat dalam sesi verifikasi atau registrasi,
 * mengumpulkan frame audio, dan meneruskannya ke VoiceDoorBlockEntity.
 *
 * Simple Voice Chat mengirim audio dalam format PCM 16-bit, 48kHz, mono.
 */
@VoicechatPlugin
public class VoiceChatPlugin implements de.maxhenkel.voicechat.api.VoicechatPlugin {

    public static final String PLUGIN_ID = "voicedoor";

    // Map: playerUUID -> sesi aktif
    private static final Map<UUID, ActiveSession> ACTIVE_SESSIONS = new ConcurrentHashMap<>();

    // PCM settings dari Simple Voice Chat: 48kHz, 16-bit, mono
    private static final int SAMPLE_RATE = 48000;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int CHANNELS = 1;
    private static final int FRAME_SIZE = 960; // 20ms frame @48kHz

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        VoiceDoorMod.LOGGER.info("[VoiceDoor] Simple Voice Chat plugin berhasil diinisialisasi.");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
        registration.registerEvent(PlayerDisconnectedEvent.class, this::onPlayerDisconnected);
    }

    /**
     * Dipanggil setiap kali Simple Voice Chat menerima paket audio dari pemain.
     * Kita kumpulkan frame PCM selama durasi rekaman, lalu konversi ke WAV.
     */
    private void onMicrophonePacket(MicrophonePacketEvent event) {
        VoicechatConnection senderConnection = event.getSenderConnection();
        if (senderConnection == null) return;

        UUID playerUUID = senderConnection.getPlayer().getUuid();
        ActiveSession session = ACTIVE_SESSIONS.get(playerUUID);
        if (session == null) return;

        // Tambahkan frame audio ke buffer
        short[] pcmData = event.getPacket().getOpusDecoded();
        if (pcmData != null) {
            for (short s : pcmData) {
                session.audioBuffer.add(s);
            }
        }

        // Cek apakah durasi rekaman sudah cukup
        long elapsedMs = System.currentTimeMillis() - session.startTimeMs;
        if (elapsedMs >= VoiceDoorBlockEntity.RECORDING_DURATION_MS) {
            finishSession(playerUUID, session);
        }
    }

    private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        UUID playerUUID = event.getPlayer().getUuid();
        ACTIVE_SESSIONS.remove(playerUUID);
    }

    private void finishSession(UUID playerUUID, ActiveSession session) {
        ACTIVE_SESSIONS.remove(playerUUID);

        if (session.audioBuffer.isEmpty()) {
            VoiceDoorMod.LOGGER.warn("[VoiceDoor] Tidak ada audio yang diterima dari pemain {}", playerUUID);
            return;
        }

        // Konversi List<Short> ke byte[] WAV
        short[] pcm = new short[session.audioBuffer.size()];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = session.audioBuffer.get(i);
        }
        byte[] wavBytes = pcmToWav(pcm, SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE);

        // Kirim ke block entity di main thread
        Level level = session.level;
        BlockPos doorPos = session.doorPos;
        ServerPlayer player = session.player;
        boolean isRegistering = session.isRegistering;

        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            serverLevel.getServer().execute(() -> {
                BlockEntity be = level.getBlockEntity(doorPos);
                if (be instanceof VoiceDoorBlockEntity voiceDoorBE) {
                    if (isRegistering) {
                        voiceDoorBE.onVoiceRegistered(player, wavBytes, level, doorPos);
                    } else {
                        voiceDoorBE.onVoiceRecorded(player, wavBytes, level, doorPos);
                    }
                }
            });
        }
    }

    /**
     * Konversi PCM 16-bit ke format WAV dengan header yang benar.
     */
    private static byte[] pcmToWav(short[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int dataSize = pcm.length * 2; // 16-bit = 2 bytes per sample
        int totalSize = 44 + dataSize;
        ByteArrayOutputStream baos = new ByteArrayOutputStream(totalSize);
        DataOutputStream dos = new DataOutputStream(baos);

        try {
            // RIFF header
            dos.write(new byte[]{'R', 'I', 'F', 'F'});
            writeInt32LE(dos, 36 + dataSize);
            dos.write(new byte[]{'W', 'A', 'V', 'E'});

            // fmt chunk
            dos.write(new byte[]{'f', 'm', 't', ' '});
            writeInt32LE(dos, 16);
            writeInt16LE(dos, 1); // PCM
            writeInt16LE(dos, channels);
            writeInt32LE(dos, sampleRate);
            writeInt32LE(dos, sampleRate * channels * bitsPerSample / 8);
            writeInt16LE(dos, channels * bitsPerSample / 8);
            writeInt16LE(dos, bitsPerSample);

            // data chunk
            dos.write(new byte[]{'d', 'a', 't', 'a'});
            writeInt32LE(dos, dataSize);

            // PCM data (little-endian)
            for (short s : pcm) {
                dos.write(s & 0xFF);
                dos.write((s >> 8) & 0xFF);
            }
        } catch (IOException e) {
            VoiceDoorMod.LOGGER.error("Gagal membuat WAV: {}", e.getMessage());
        }

        return baos.toByteArray();
    }

    private static void writeInt32LE(DataOutputStream dos, int value) throws IOException {
        dos.write(value & 0xFF);
        dos.write((value >> 8) & 0xFF);
        dos.write((value >> 16) & 0xFF);
        dos.write((value >> 24) & 0xFF);
    }

    private static void writeInt16LE(DataOutputStream dos, int value) throws IOException {
        dos.write(value & 0xFF);
        dos.write((value >> 8) & 0xFF);
    }

    /**
     * Data class untuk menyimpan state sesi rekaman.
     */
    public static class ActiveSession {
        public final UUID playerUUID;
        public final ServerPlayer player;
        public final Level level;
        public final BlockPos doorPos;
        public final boolean isRegistering;
        public final long startTimeMs;
        public final List<Short> audioBuffer = new ArrayList<>();

        public ActiveSession(UUID playerUUID, ServerPlayer player, Level level,
                             BlockPos doorPos, boolean isRegistering) {
            this.playerUUID = playerUUID;
            this.player = player;
            this.level = level;
            this.doorPos = doorPos;
            this.isRegistering = isRegistering;
            this.startTimeMs = System.currentTimeMillis();
        }
    }

    // -----------------------------------------------------------------------
    // Static helper: mulai sesi rekaman
    // -----------------------------------------------------------------------

    /**
     * Mulai sesi verifikasi suara untuk pemain.
     * Dipanggil dari VoiceDoorBlockEntity.handlePlayerInteraction().
     */
    public static void startVerificationSession(ServerPlayer player, Level level,
                                                 BlockPos doorPos) {
        UUID uuid = player.getUUID();
        if (ACTIVE_SESSIONS.containsKey(uuid)) {
            ACTIVE_SESSIONS.remove(uuid); // Reset sesi lama
        }
        ACTIVE_SESSIONS.put(uuid, new ActiveSession(uuid, player, level, doorPos, false));
        VoiceDoorMod.LOGGER.debug("[VoiceDoor] Sesi verifikasi dimulai untuk {}", player.getName().getString());
    }

    /**
     * Mulai sesi registrasi suara untuk pemain.
     * Dipanggil dari command /voicedoor register.
     */
    public static void startRegistrationSession(ServerPlayer player, Level level,
                                                  BlockPos doorPos) {
        UUID uuid = player.getUUID();
        ACTIVE_SESSIONS.put(uuid, new ActiveSession(uuid, player, level, doorPos, true));
        VoiceDoorMod.LOGGER.debug("[VoiceDoor] Sesi registrasi dimulai untuk {}", player.getName().getString());
    }

    public static boolean hasActiveSession(UUID playerUUID) {
        return ACTIVE_SESSIONS.containsKey(playerUUID);
    }
}
