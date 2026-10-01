package com.voicedoor.blockentity;

import com.voicedoor.VoiceDoorMod;
import com.voicedoor.api.VoiceApiClient;
import com.voicedoor.block.VoiceDoorBlock;
import com.voicedoor.config.VoiceDoorConfig;
import com.voicedoor.network.ModNetwork;
import com.voicedoor.network.S2CStatusPacket;
import com.voicedoor.voice.VoiceChatPlugin;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Block entity VoiceDoor: menyimpan pemilik pintu dan menjalankan alur verifikasi.
 *
 * <p>Alur satu percobaan buka pintu:
 * <ol>
 *   <li>Pemain mengklik pintu. Cooldown dicek.</li>
 *   <li>Server meminta challenge sekali-pakai dari Python API ({@code POST /challenge}).</li>
 *   <li>Sesi rekaman dimulai lewat Simple Voice Chat, terikat ke pintu ini dan challenge itu.</li>
 *   <li>Setelah audio terkumpul, dikirim ke {@code POST /verify} bersama challenge-nya.</li>
 *   <li>Kalau diterima, pintu terbuka selama {@code autoCloseSeconds}.</li>
 * </ol>
 *
 * <p>Challenge di langkah 2 yang mencegah rekaman suara pemilik dipakai ulang: audio tanpa
 * challenge yang valid dan belum terpakai akan ditolak server.
 *
 * <p>Yang disimpan ke NBT: pemilik, daftar pemain berizin, override URL API, dan
 * <b>sisa waktu pintu terbuka</b>. Yang terakhir dulunya hanya ada di memori, sehingga
 * pintu yang terbuka lalu chunk-nya di-unload akan tetap terbuka selamanya - auto-close
 * tidak pernah berjalan lagi setelah chunk dimuat ulang.
 */
public class VoiceDoorBlockEntity extends BlockEntity {

    // Data tersimpan
    private UUID ownerUUID = null;
    private String ownerName = "";
    private final Set<UUID> authorizedPlayers = new HashSet<>();
    /** Override URL API khusus pintu ini. Kosong berarti memakai config server. */
    private String apiUrlOverride = "";
    private int openTicks = 0;

    // State runtime
    private final Map<UUID, Integer> verificationCooldowns = new HashMap<>();

    public VoiceDoorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.VOICE_DOOR.get(), pos, state);
    }

    // -----------------------------------------------------------------------
    // Tick
    // -----------------------------------------------------------------------
    public static void tick(Level level, BlockPos pos, BlockState state, VoiceDoorBlockEntity be) {
        if (level.isClientSide) return;

        if (be.openTicks > 0) {
            be.openTicks--;
            if (be.openTicks == 0) {
                be.closeDoor(level, pos);
                be.setChanged();
            }
        }

        // Map cooldown biasanya kosong; lewati supaya tidak mengalokasikan iterator
        // 20x per detik per pintu tanpa ada yang dikerjakan.
        if (!be.verificationCooldowns.isEmpty()) {
            be.verificationCooldowns.entrySet().removeIf(entry -> {
                int remaining = entry.getValue() - 1;
                entry.setValue(remaining);
                return remaining <= 0;
            });
        }
    }

    // -----------------------------------------------------------------------
    // Interaksi pemain
    // -----------------------------------------------------------------------
    public void handlePlayerInteraction(ServerPlayer player, BlockPos pos, Level level) {
        UUID playerUUID = player.getUUID();

        if (ownerUUID == null || ownerName.isEmpty()) {
            player.sendSystemMessage(Component.translatable("message.voicedoor.no_owner"));
            return;
        }

        Integer cooldown = verificationCooldowns.get(playerUUID);
        if (cooldown != null && cooldown > 0) {
            player.sendSystemMessage(Component.translatable(
                    "message.voicedoor.cooldown", Math.max(1, cooldown / 20)));
            return;
        }

        if (!VoiceDoorMod.VOICE_CHAT_AVAILABLE) {
            player.sendSystemMessage(Component.translatable("message.voicedoor.no_voicechat"));
            return;
        }

        if (VoiceChatPlugin.hasActiveSession(playerUUID)) {
            player.sendSystemMessage(Component.translatable("message.voicedoor.session_busy"));
            return;
        }

        verificationCooldowns.put(playerUUID, VoiceDoorConfig.cooldownTicks());

        if (!VoiceDoorConfig.requireChallenge()) {
            // Server API dijalankan dengan --no-challenge: rekam langsung.
            beginRecording(player, pos, level, "", "");
            return;
        }

        requestChallengeThenRecord(player, pos, level);
    }

    /**
     * Minta challenge sekali-pakai, lalu mulai merekam.
     *
     * <p>Rekaman sengaja baru dimulai setelah server menjawab: kalau direkam lebih dulu
     * dan challenge gagal, pemain sudah berbicara untuk apa-apa.
     */
    private void requestChallengeThenRecord(ServerPlayer player, BlockPos pos, Level level) {
        String member = ownerName;
        boolean queued = VoiceApiClient.tryPostJsonAsync(
                "/challenge",
                "{\"member\":\"" + escapeJson(member) + "\"}",
                response -> runOnServer(level, () -> {
                    if (!player.isAlive() || player.hasDisconnected()) return;

                    if (!response.isSuccess()) {
                        player.sendSystemMessage(Component.translatable(
                                "message.voicedoor.challenge_failed", response.errorMessage()));
                        return;
                    }
                    String challengeId = response.optString("challenge_id", "");
                    if (challengeId.isEmpty()) {
                        player.sendSystemMessage(Component.translatable(
                                "message.voicedoor.challenge_failed", "challenge_id kosong"));
                        return;
                    }
                    String phrase = response.optBoolean("require_passphrase", false)
                            ? response.optString("phrase", "")
                            : "";
                    beginRecording(player, pos, level, challengeId, phrase);
                }));

        if (!queued) {
            player.sendSystemMessage(Component.translatable("message.voicedoor.server_busy"));
            verificationCooldowns.remove(player.getUUID());
        }
    }

    private void beginRecording(ServerPlayer player, BlockPos pos, Level level,
                                String challengeId, String phrase) {
        VoiceChatPlugin.startSession(player, level, pos, false, challengeId);

        if (phrase.isEmpty()) {
            player.sendSystemMessage(Component.translatable(
                    "message.voicedoor.speak_now", VoiceDoorConfig.recordingSeconds()));
        } else {
            player.sendSystemMessage(Component.translatable(
                    "message.voicedoor.speak_phrase", phrase, VoiceDoorConfig.recordingSeconds()));
        }
        ModNetwork.sendTo(player, S2CStatusPacket.recordingStart(phrase, VoiceDoorConfig.recordingSeconds()));
    }

    // -----------------------------------------------------------------------
    // Callback setelah rekaman selesai
    // -----------------------------------------------------------------------
    /** Kirim audio ke {@code /verify} dan buka pintu kalau server menerimanya. */
    public void onVoiceRecorded(ServerPlayer player, byte[] wavBytes, Level level, BlockPos doorPos,
                                String challengeId) {
        ModNetwork.sendTo(player, S2CStatusPacket.recordingStop());
        player.sendSystemMessage(Component.translatable("message.voicedoor.verifying"));

        VoiceApiClient.MultipartBody body = new VoiceApiClient.MultipartBody()
                .field("member", ownerName)
                .field("expected_speaker", ownerName)  // nama lama, untuk server versi sebelumnya
                .field("threshold", String.format(java.util.Locale.ROOT, "%.4f", VoiceDoorConfig.threshold()));
        if (!challengeId.isEmpty()) {
            body.field("challenge_id", challengeId);
        }
        body.file("audio", "voice.wav", "audio/wav", wavBytes);

        boolean queued = VoiceApiClient.tryPostFormAsync("/verify", body, response -> runOnServer(level, () -> {
            if (!player.isAlive() || player.hasDisconnected()) return;

            boolean accepted = response.isSuccess() && response.optBoolean("accepted",
                    response.optBoolean("is_owner", false));
            double similarity = response.optDouble("similarity", response.optDouble("confidence", 0.0));

            if (VoiceDoorConfig.logVerificationDetails()) {
                VoiceDoorMod.LOGGER.info(
                        "[VoiceDoor] {} di {} -> accepted={} similarity={} status={}",
                        player.getName().getString(), doorPos.toShortString(),
                        accepted, String.format(java.util.Locale.ROOT, "%.4f", similarity),
                        response.statusCode());
            }

            if (accepted) {
                player.sendSystemMessage(Component.translatable(
                        "message.voicedoor.accepted",
                        response.optString("member", ownerName),
                        String.format(java.util.Locale.ROOT, "%.0f", similarity * 100)));
                ModNetwork.sendTo(player, S2CStatusPacket.verificationOk(similarity));
                openDoor(level, doorPos);
            } else {
                String reason = response.isSuccess()
                        ? Component.translatable("message.voicedoor.reason_low_score",
                                String.format(java.util.Locale.ROOT, "%.0f", similarity * 100),
                                String.format(java.util.Locale.ROOT, "%.0f", VoiceDoorConfig.threshold() * 100))
                            .getString()
                        : response.errorMessage();
                player.sendSystemMessage(Component.translatable("message.voicedoor.rejected", reason));
                ModNetwork.sendTo(player, S2CStatusPacket.verificationFail(reason));
            }
        }));

        if (!queued) {
            player.sendSystemMessage(Component.translatable("message.voicedoor.server_busy"));
        }
    }

    /** Kirim audio ke {@code /register} untuk mendaftarkan atau memperkuat voiceprint. */
    public void onVoiceRegistered(ServerPlayer player, byte[] wavBytes, Level level, BlockPos doorPos,
                                  String memberName) {
        ModNetwork.sendTo(player, S2CStatusPacket.recordingStop());

        String member = memberName.isEmpty() ? player.getName().getString() : memberName;
        VoiceApiClient.MultipartBody body = new VoiceApiClient.MultipartBody()
                .field("member", member)
                .file("audio", "voice.wav", "audio/wav", wavBytes);

        boolean queued = VoiceApiClient.tryPostFormAsync("/register", body, response -> runOnServer(level, () -> {
            if (!player.isAlive() || player.hasDisconnected()) return;

            if (!response.isSuccess()) {
                player.sendSystemMessage(Component.translatable(
                        "message.voicedoor.register_failed", response.errorMessage()));
                return;
            }

            double duration = response.optDouble("duration", 0.0);
            int samples = (int) response.optDouble("n_samples", 0);
            player.sendSystemMessage(Component.translatable(
                    "message.voicedoor.sample_saved",
                    String.format(java.util.Locale.ROOT, "%.1f", duration), member, samples));

            boolean enrolled = response.optBoolean("enrolled", false);
            String hint = response.optString("hint", "");
            if (enrolled) {
                player.sendSystemMessage(Component.translatable("message.voicedoor.enrolled", member));
            } else if (!hint.isEmpty()) {
                player.sendSystemMessage(Component.translatable("message.voicedoor.enroll_pending", hint));
            }
            if (enrolled && !hint.isEmpty()) {
                player.sendSystemMessage(Component.translatable("message.voicedoor.enroll_hint", hint));
            }

            // Pemain pertama yang berhasil mendaftar menjadi pemilik pintu ini.
            if (ownerUUID == null && enrolled) {
                setOwner(player.getUUID(), member);
                player.sendSystemMessage(Component.translatable("message.voicedoor.now_owner"));
            }
        }));

        if (!queued) {
            player.sendSystemMessage(Component.translatable("message.voicedoor.server_busy"));
        }
    }

    /** Jalankan aksi di main thread server. Dipanggil dari thread HTTP. */
    private static void runOnServer(Level level, Runnable action) {
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.getServer().execute(action);
        }
    }

    // -----------------------------------------------------------------------
    // Buka / tutup
    // -----------------------------------------------------------------------
    private void openDoor(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof VoiceDoorBlock door) {
            door.setOpen(null, level, state, pos, true);
            openTicks = VoiceDoorConfig.autoCloseTicks();
            setChanged();
        }
    }

    private void closeDoor(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof VoiceDoorBlock door && state.getValue(VoiceDoorBlock.OPEN)) {
            door.setOpen(null, level, state, pos, false);
        }
    }

    // -----------------------------------------------------------------------
    // Getter / setter
    // -----------------------------------------------------------------------
    @Nullable
    public UUID getOwnerUUID() {
        return ownerUUID;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public boolean hasOwner() {
        return ownerUUID != null && !ownerName.isEmpty();
    }

    /** URL efektif: override pintu ini kalau ada, kalau tidak config server. */
    public String getApiUrl() {
        return apiUrlOverride.isEmpty() ? VoiceDoorConfig.apiUrl() : apiUrlOverride;
    }

    public boolean hasApiUrlOverride() {
        return !apiUrlOverride.isEmpty();
    }

    public void setApiUrlOverride(String url) {
        this.apiUrlOverride = url == null ? "" : url.trim();
        setChanged();
    }

    public void setOwner(@Nullable UUID uuid, String name) {
        this.ownerUUID = uuid;
        this.ownerName = name == null ? "" : name;
        setChanged();
    }

    public void clearOwner() {
        this.ownerUUID = null;
        this.ownerName = "";
        this.authorizedPlayers.clear();
        setChanged();
    }

    public void addAuthorizedPlayer(UUID uuid) {
        authorizedPlayers.add(uuid);
        setChanged();
    }

    public boolean removeAuthorizedPlayer(UUID uuid) {
        boolean removed = authorizedPlayers.remove(uuid);
        if (removed) setChanged();
        return removed;
    }

    public Set<UUID> getAuthorizedPlayers() {
        return Set.copyOf(authorizedPlayers);
    }

    /**
     * Apakah {@code uuid} termasuk pemilik atau pemain berizin.
     *
     * <p>Ini TIDAK melewati verifikasi suara - daftar izin menentukan siapa yang boleh
     * mencoba membuka pintu dengan suaranya sendiri, bukan siapa yang boleh masuk tanpa
     * bicara. Pintu tetap selalu meminta verifikasi.
     */
    public boolean isAuthorized(UUID uuid) {
        return uuid.equals(ownerUUID) || authorizedPlayers.contains(uuid);
    }

    public boolean isOpen() {
        return openTicks > 0;
    }

    public int getRemainingOpenTicks() {
        return openTicks;
    }

    // -----------------------------------------------------------------------
    // NBT
    // -----------------------------------------------------------------------
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (ownerUUID != null) {
            tag.putUUID("OwnerUUID", ownerUUID);
        }
        tag.putString("OwnerName", ownerName);
        tag.putString("ApiUrlOverride", apiUrlOverride);
        // Disimpan supaya auto-close tetap berjalan setelah chunk dimuat ulang.
        tag.putInt("OpenTicks", openTicks);

        ListTag list = new ListTag();
        for (UUID uuid : authorizedPlayers) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", uuid);
            list.add(entry);
        }
        tag.put("Authorized", list);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        ownerUUID = tag.hasUUID("OwnerUUID") ? tag.getUUID("OwnerUUID") : null;
        ownerName = tag.getString("OwnerName");
        apiUrlOverride = tag.getString("ApiUrlOverride");
        openTicks = tag.getInt("OpenTicks");

        authorizedPlayers.clear();
        if (tag.contains("Authorized", Tag.TAG_LIST)) {
            ListTag list = tag.getList("Authorized", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                if (entry.hasUUID("Id")) {
                    authorizedPlayers.add(entry.getUUID("Id"));
                }
            }
        } else if (tag.contains("AuthorizedPlayers", Tag.TAG_COMPOUND)) {
            // Format lama: CompoundTag dengan auth_0..auth_N dan sebuah "count".
            CompoundTag legacy = tag.getCompound("AuthorizedPlayers");
            int count = legacy.getInt("count");
            for (int i = 0; i < count; i++) {
                if (legacy.hasUUID("auth_" + i)) {
                    authorizedPlayers.add(legacy.getUUID("auth_" + i));
                }
            }
        }

        // Migrasi dari versi yang menyimpan URL penuh dengan kunci lama.
        if (apiUrlOverride.isEmpty() && tag.contains("ApiUrl")) {
            String legacyUrl = tag.getString("ApiUrl");
            if (!legacyUrl.isEmpty() && !legacyUrl.equals(VoiceDoorConfig.apiUrl())) {
                apiUrlOverride = legacyUrl;
            }
        }
    }

    /**
     * Hanya kirim ke client apa yang dibutuhkan untuk render.
     *
     * <p>Versi lama mengirim seluruh NBT lewat {@code saveWithoutMetadata()}, sehingga
     * setiap client bisa membaca UUID pemilik dan URL API tiap pintu.
     */
    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("OwnerName", ownerName);
        return tag;
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            ownerName = tag.getString("OwnerName");
        }
    }

    // -----------------------------------------------------------------------
    // Util
    // -----------------------------------------------------------------------
    /** Escape minimal untuk menyisipkan nama ke dalam body JSON kecil. */
    private static String escapeJson(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Daftar pemain berizin sebagai list agar mudah ditampilkan command. */
    public List<UUID> authorizedList() {
        return new ArrayList<>(authorizedPlayers);
    }
}
