package com.voicedoor.voice;

import com.voicedoor.VoiceDoorMod;
import com.voicedoor.blockentity.VoiceDoorBlockEntity;
import com.voicedoor.config.VoiceDoorConfig;
import com.voicedoor.network.ModNetwork;
import com.voicedoor.network.S2CStatusPacket;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Integrasi Simple Voice Chat: mengumpulkan audio mikrofon pemain selama satu sesi
 * rekaman, lalu meneruskannya ke {@link VoiceDoorBlockEntity}.
 *
 * <p>Simple Voice Chat mengirim PCM 16-bit, 48 kHz, mono.
 *
 * <p>Perbaikan dari versi sebelumnya:
 * <ul>
 *   <li><b>Sesi punya batas waktu.</b> Dulu {@code finishSession} hanya dipanggil dari
 *       handler paket mikrofon, jadi pemain yang mengklik pintu lalu diam tidak pernah
 *       menyelesaikan sesinya - dan setiap percobaan berikutnya dijawab "sesi sedang
 *       berjalan" sampai dia keluar dari server. Sekarang {@link #tick()} membatalkan
 *       sesi yang kedaluwarsa.</li>
 *   <li><b>Buffer audio efisien.</b> {@code List<Short>} mem-boxing setiap sample:
 *       4 detik @48 kHz = 192.000 objek {@code Short} (~3 MB untuk data 384 KB).
 *       Sekarang byte PCM ditulis langsung ke {@link ByteArrayOutputStream}.</li>
 *   <li><b>Sesi terikat ke pintu dan challenge tertentu.</b> Dulu sesi hanya di-key
 *       per pemain dan ditimpa diam-diam, sehingga mengklik pintu A lalu cepat pintu B
 *       membuat audio yang sama diverifikasi terhadap pintu B.</li>
 *   <li><b>Batas ukuran.</b> Buffer berhenti tumbuh setelah durasi yang diminta, jadi
 *       mikrofon yang terus aktif tidak bisa menghabiskan memori.</li>
 * </ul>
 */
/*
 * Anotasi yang benar untuk Forge adalah @ForgeVoicechatPlugin. Versi sebelumnya memakai
 * @VoicechatPlugin, yang sebenarnya nama *interface*-nya dan bukan anotasi sama sekali -
 * kodenya bahkan tidak bisa dikompilasi, dan seandainya bisa, plugin-nya tidak akan pernah
 * ditemukan Simple Voice Chat.
 */
@ForgeVoicechatPlugin
public class VoiceChatPlugin implements VoicechatPlugin {

    public static final String PLUGIN_ID = "voicedoor";

    // Format PCM Simple Voice Chat
    private static final int SAMPLE_RATE = 48000;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int CHANNELS = 1;
    private static final int BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8;

    /** Batas keras: 20 detik audio, jauh di atas durasi rekaman maksimum (15 detik). */
    private static final int MAX_BUFFER_BYTES = 20 * SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE;

    /** Audio minimum yang layak dikirim: 0,5 detik. */
    private static final int MIN_BUFFER_BYTES = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE / 2;

    private static final Map<UUID, ActiveSession> ACTIVE_SESSIONS = new ConcurrentHashMap<>();

    /**
     * Instance API, disimpan supaya sesi bisa membuat OpusDecoder sendiri.
     *
     * <p>Simple Voice Chat mengirim audio dalam bentuk Opus terkompresi, bukan PCM mentah.
     * Versi sebelumnya memanggil {@code packet.getOpusDecoded()} - method yang tidak pernah
     * ada di API ini, jadi kodenya tidak bisa dikompilasi.
     */
    private static volatile VoicechatApi api;

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void initialize(VoicechatApi voicechatApi) {
        api = voicechatApi;
        VoiceDoorMod.LOGGER.info("[VoiceDoor] Plugin Simple Voice Chat diinisialisasi.");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
        registration.registerEvent(PlayerDisconnectedEvent.class, this::onPlayerDisconnected);
    }

    // -----------------------------------------------------------------------
    // Event voice chat
    // -----------------------------------------------------------------------
    private void onMicrophonePacket(MicrophonePacketEvent event) {
        VoicechatConnection connection = event.getSenderConnection();
        if (connection == null) return;

        UUID playerUUID = connection.getPlayer().getUuid();
        ActiveSession session = ACTIVE_SESSIONS.get(playerUUID);
        if (session == null || session.finished) return;

        // Paket berisi Opus terkompresi; decoder-nya stateful per stream, jadi setiap
        // sesi punya decoder sendiri dan wajib menutupnya (lihat ActiveSession.release).
        short[] pcm = session.decode(event.getPacket().getOpusEncodedData());
        if (pcm != null && pcm.length > 0) {
            session.append(pcm);
        }

        if (session.isDurationReached()) {
            finishSession(playerUUID, session);
        }
    }

    private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        ActiveSession session = ACTIVE_SESSIONS.remove(event.getPlayerUuid());
        if (session != null) {
            session.release();
        }
    }

    // -----------------------------------------------------------------------
    // Tick server: menutup sesi yang selesai atau kedaluwarsa
    // -----------------------------------------------------------------------
    /**
     * Dipanggil sekali per tick server dari {@link com.voicedoor.VoiceDoorMod}.
     *
     * <p>Ini yang membuat sesi tidak bisa menggantung. Paket mikrofon saja tidak cukup:
     * pemain yang tidak berbicara tidak menghasilkan paket apa pun.
     */
    public static void tick() {
        if (ACTIVE_SESSIONS.isEmpty()) return;

        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, ActiveSession> entry : ACTIVE_SESSIONS.entrySet()) {
            ActiveSession session = entry.getValue();
            if (session.finished) {
                expired.add(entry.getKey());
                continue;
            }
            session.ticksAlive++;

            if (session.isDurationReached() && session.bufferedBytes() >= MIN_BUFFER_BYTES) {
                finishSession(entry.getKey(), session);
                continue;
            }
            if (session.ticksAlive >= VoiceDoorConfig.sessionTimeoutTicks()) {
                expired.add(entry.getKey());
                abandonSession(session);
            }
        }
        for (UUID uuid : expired) {
            ActiveSession removed = ACTIVE_SESSIONS.remove(uuid);
            if (removed != null) {
                removed.release();
            }
        }
    }

    /** Beri tahu pemain kalau sesinya dibatalkan karena tidak ada suara yang masuk. */
    private static void abandonSession(ActiveSession session) {
        ServerPlayer player = session.player;
        if (player == null || player.hasDisconnected()) return;

        boolean gotSomething = session.bufferedBytes() > 0;
        session.finished = true;
        runOnServer(session.level, () -> {
            ModNetwork.sendTo(player, S2CStatusPacket.recordingStop());
            player.sendSystemMessage(Component.translatable(
                    gotSomething ? "message.voicedoor.too_little_audio" : "message.voicedoor.no_audio"));
        });
        VoiceDoorMod.LOGGER.debug("[VoiceDoor] Sesi {} dibatalkan (buffer {} byte).",
                session.playerUUID, session.bufferedBytes());
    }

    // -----------------------------------------------------------------------
    // Menyelesaikan sesi
    // -----------------------------------------------------------------------
    private static void finishSession(UUID playerUUID, ActiveSession session) {
        if (session.finished) return;
        session.finished = true;
        ACTIVE_SESSIONS.remove(playerUUID);

        int bufferedBytes = session.bufferedBytes();
        if (bufferedBytes < MIN_BUFFER_BYTES) {
            session.release();
            ServerPlayer player = session.player;
            if (player != null && !player.hasDisconnected()) {
                runOnServer(session.level, () -> {
                    ModNetwork.sendTo(player, S2CStatusPacket.recordingStop());
                    player.sendSystemMessage(Component.translatable("message.voicedoor.too_little_audio"));
                });
            }
            return;
        }

        byte[] wav = session.toWav();
        session.release();   // decoder native tidak dibutuhkan lagi setelah ini
        Level level = session.level;
        BlockPos doorPos = session.doorPos;
        ServerPlayer player = session.player;
        boolean registering = session.registering;
        String challengeId = session.challengeId;
        String memberName = session.memberName;

        runOnServer(level, () -> {
            BlockEntity be = level.getBlockEntity(doorPos);
            if (!(be instanceof VoiceDoorBlockEntity door)) {
                // Pintunya dibongkar saat pemain berbicara.
                if (player != null && !player.hasDisconnected()) {
                    player.sendSystemMessage(Component.translatable("message.voicedoor.door_gone"));
                }
                return;
            }
            if (registering) {
                door.onVoiceRegistered(player, wav, level, doorPos, memberName);
            } else {
                door.onVoiceRecorded(player, wav, level, doorPos, challengeId);
            }
        });
    }

    private static void runOnServer(Level level, Runnable action) {
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.getServer().execute(action);
        }
    }

    // -----------------------------------------------------------------------
    // API untuk block entity dan command
    // -----------------------------------------------------------------------
    /**
     * Mulai sesi rekaman untuk {@code player}, terikat ke pintu di {@code doorPos}.
     *
     * <p>Sesi lama pemain yang sama dibatalkan lebih dulu, bukan sekadar ditimpa, supaya
     * pemain mendapat pemberitahuan dan tidak ada callback yang menggantung.
     */
    public static void startSession(ServerPlayer player, Level level, BlockPos doorPos,
                                    boolean registering, String challengeId) {
        UUID uuid = player.getUUID();
        ActiveSession previous = ACTIVE_SESSIONS.remove(uuid);
        if (previous != null) {
            previous.finished = true;
            previous.release();
            VoiceDoorMod.LOGGER.debug("[VoiceDoor] Sesi lama {} diganti sesi baru.", uuid);
        }

        ActiveSession session = new ActiveSession(uuid, player, level, doorPos, registering,
                challengeId, VoiceDoorConfig.recordingMillis());
        ACTIVE_SESSIONS.put(uuid, session);
        VoiceDoorMod.LOGGER.debug("[VoiceDoor] Sesi {} dimulai untuk {} di {}",
                registering ? "registrasi" : "verifikasi", player.getName().getString(),
                doorPos.toShortString());
    }

    /** Varian untuk registrasi, yang memakai nama member alih-alih challenge. */
    public static void startRegistrationSession(ServerPlayer player, Level level, BlockPos doorPos,
                                                String memberName) {
        UUID uuid = player.getUUID();
        ActiveSession previous = ACTIVE_SESSIONS.remove(uuid);
        if (previous != null) {
            previous.finished = true;
            previous.release();
        }
        ActiveSession session = new ActiveSession(uuid, player, level, doorPos, true,
                "", VoiceDoorConfig.recordingMillis());
        session.memberName = memberName;
        ACTIVE_SESSIONS.put(uuid, session);
    }

    public static boolean hasActiveSession(UUID playerUUID) {
        ActiveSession session = ACTIVE_SESSIONS.get(playerUUID);
        return session != null && !session.finished;
    }

    public static void cancelSession(UUID playerUUID) {
        ActiveSession session = ACTIVE_SESSIONS.remove(playerUUID);
        if (session != null) {
            session.finished = true;
            session.release();
        }
    }

    public static int activeSessionCount() {
        return ACTIVE_SESSIONS.size();
    }

    /** Buang semua sesi dan tutup decoder-nya - dipanggil saat server berhenti. */
    public static void clearAll() {
        for (ActiveSession session : ACTIVE_SESSIONS.values()) {
            session.finished = true;
            session.release();
        }
        ACTIVE_SESSIONS.clear();
    }

    // -----------------------------------------------------------------------
    // State sesi
    // -----------------------------------------------------------------------
    private static final class ActiveSession {
        final UUID playerUUID;
        final ServerPlayer player;
        final Level level;
        final BlockPos doorPos;
        final boolean registering;
        final String challengeId;
        final long startTimeMs;
        final int durationMs;

        String memberName = "";
        volatile boolean finished = false;
        int ticksAlive = 0;

        /** Byte PCM little-endian mentah; header WAV ditambahkan saat selesai. */
        private final ByteArrayOutputStream pcm = new ByteArrayOutputStream(64 * 1024);

        /**
         * Decoder Opus milik sesi ini.
         *
         * <p>Dibuat malas pada paket pertama: sesi yang pemainnya tidak pernah berbicara
         * jadi tidak pernah mengalokasikan decoder native sama sekali.
         */
        private OpusDecoder decoder;

        ActiveSession(UUID playerUUID, ServerPlayer player, Level level, BlockPos doorPos,
                      boolean registering, String challengeId, int durationMs) {
            this.playerUUID = playerUUID;
            this.player = player;
            this.level = level;
            this.doorPos = doorPos.immutable();
            this.registering = registering;
            this.challengeId = challengeId == null ? "" : challengeId;
            this.durationMs = durationMs;
            this.startTimeMs = System.currentTimeMillis();
        }

        /**
         * Decode satu paket Opus menjadi PCM 16-bit.
         *
         * @return sample PCM, atau null kalau decoder tidak tersedia / gagal
         */
        synchronized short[] decode(byte[] opusData) {
            if (opusData == null || opusData.length == 0) {
                return null;
            }
            VoicechatApi voicechatApi = api;
            if (voicechatApi == null) {
                return null;
            }
            try {
                if (decoder == null) {
                    decoder = voicechatApi.createDecoder();
                }
                if (decoder.isClosed()) {
                    return null;
                }
                return decoder.decode(opusData);
            } catch (RuntimeException e) {
                // Satu paket rusak tidak boleh menjatuhkan seluruh sesi.
                VoiceDoorMod.LOGGER.debug("[VoiceDoor] Gagal men-decode paket Opus: {}", e.getMessage());
                return null;
            }
        }

        /**
         * Tutup decoder native.
         *
         * <p>Dokumentasi Simple Voice Chat menyatakan tidak menutup decoder menyebabkan
         * memory leak, jadi ini dipanggil di setiap jalur keluar sesi: selesai normal,
         * kedaluwarsa, diganti sesi baru, pemain disconnect, dan server berhenti.
         */
        synchronized void release() {
            if (decoder != null && !decoder.isClosed()) {
                try {
                    decoder.close();
                } catch (RuntimeException e) {
                    VoiceDoorMod.LOGGER.warn("[VoiceDoor] Gagal menutup decoder Opus: {}", e.getMessage());
                }
            }
            decoder = null;
        }

        synchronized void append(short[] samples) {
            if (pcm.size() >= MAX_BUFFER_BYTES) return;
            for (short sample : samples) {
                pcm.write(sample & 0xFF);
                pcm.write((sample >> 8) & 0xFF);
            }
        }

        synchronized int bufferedBytes() {
            return pcm.size();
        }

        boolean isDurationReached() {
            return System.currentTimeMillis() - startTimeMs >= durationMs;
        }

        synchronized byte[] toWav() {
            return WavWriter.wrap(pcm.toByteArray(), SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE);
        }
    }
}
