package com.voicedoor.blockentity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.voicedoor.VoiceDoorMod;
import com.voicedoor.block.ModBlocks;
import com.voicedoor.block.VoiceDoorBlock;
import com.voicedoor.network.ModNetwork;
import com.voicedoor.network.S2COpenDoorPacket;
import com.voicedoor.network.S2CStatusPacket;
import com.voicedoor.voice.VoiceRecordingSession;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Block Entity untuk VoiceDoor.
 *
 * Menyimpan:
 * - ownerUUID: UUID pemilik pintu
 * - ownerName: Nama pemilik (sebagai label di Python API)
 * - authorizedPlayers: Daftar UUID pemain yang boleh masuk
 * - apiUrl: URL Python API server (bisa di-konfigurasi per pintu)
 * - isLocked: Apakah pintu sedang terkunci
 * - openTicks: Berapa tick pintu masih terbuka
 *
 * Alur verifikasi:
 * 1. Pemain klik pintu -> handlePlayerInteraction()
 * 2. Server meminta Simple Voice Chat plugin untuk merekam suara pemain
 * 3. Audio dikirim ke Python API /verify
 * 4. Jika is_owner=true, pintu terbuka selama AUTO_CLOSE_TICKS
 */
public class VoiceDoorBlockEntity extends BlockEntity {

    // Konfigurasi
    public static final String DEFAULT_API_URL = "http://127.0.0.1:5000";
    public static final int AUTO_CLOSE_TICKS = 100; // ~5 detik
    public static final int VERIFY_COOLDOWN_TICKS = 40; // 2 detik antar verifikasi
    public static final int RECORDING_DURATION_MS = 3000; // 3 detik rekaman

    // Data yang disimpan di NBT
    private UUID ownerUUID = null;
    private String ownerName = "";
    private final Set<UUID> authorizedPlayers = new HashSet<>();
    private String apiUrl = DEFAULT_API_URL;
    private boolean isRegistering = false;
    private String registeringForName = "";

    // State runtime (tidak disimpan ke NBT)
    private int openTicks = 0;
    private boolean isOpen = false;
    private final Map<UUID, Integer> verificationCooldowns = new HashMap<>();

    // Thread pool untuk request HTTP asinkron
    private static final ExecutorService HTTP_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "VoiceDoor-HTTP");
        t.setDaemon(true);
        return t;
    });

    public VoiceDoorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.VOICE_DOOR.get(), pos, state);
    }

    // -----------------------------------------------------------------------
    // Tick
    // -----------------------------------------------------------------------
    public static void tick(Level level, BlockPos pos, BlockState state, VoiceDoorBlockEntity be) {
        if (level.isClientSide) return;

        // Auto-close pintu
        if (be.isOpen && be.openTicks > 0) {
            be.openTicks--;
            if (be.openTicks <= 0) {
                be.closeDoor(level, pos, state);
            }
        }

        // Kurangi cooldown verifikasi
        be.verificationCooldowns.replaceAll((uuid, ticks) -> ticks - 1);
        be.verificationCooldowns.entrySet().removeIf(e -> e.getValue() <= 0);
    }

    // -----------------------------------------------------------------------
    // Interaksi pemain
    // -----------------------------------------------------------------------
    public void handlePlayerInteraction(ServerPlayer player, BlockPos pos, Level level) {
        UUID playerUUID = player.getUUID();

        // Cek cooldown
        if (verificationCooldowns.containsKey(playerUUID)) {
            player.sendSystemMessage(Component.literal(
                    "§e[VoiceDoor] Tunggu sebentar sebelum mencoba lagi..."));
            return;
        }

        // Jika belum ada pemilik, pemain pertama yang klik jadi pemilik sementara
        if (ownerUUID == null) {
            player.sendSystemMessage(Component.literal(
                    "§6[VoiceDoor] Pintu belum punya pemilik! Gunakan: §e/voicedoor register §6untuk mendaftarkan suaramu."));
            return;
        }

        // Set cooldown
        verificationCooldowns.put(playerUUID, VERIFY_COOLDOWN_TICKS);

        player.sendSystemMessage(Component.literal(
                "§b[VoiceDoor] Memverifikasi suara... Bicaralah ke mikrofon!"));

        // Mulai sesi verifikasi suara
        if (VoiceDoorMod.VOICE_CHAT_AVAILABLE) {
            // Mulai rekaman via Simple Voice Chat
            VoiceRecordingSession.startVerification(player, this, pos, level);
        } else {
            player.sendSystemMessage(Component.literal(
                    "§c[VoiceDoor] Simple Voice Chat tidak terinstall! Tidak bisa memverifikasi suara."));
        }
    }

    /**
     * Dipanggil setelah rekaman suara selesai (dari VoiceRecordingSession).
     * Mengirim audio ke Python API dan membuka pintu jika terverifikasi.
     */
    public void onVoiceRecorded(ServerPlayer player, byte[] audioData, Level level, BlockPos pos) {
        String expectedSpeaker = ownerName;
        String apiUrl = this.apiUrl;
        BlockPos doorPos = pos;

        HTTP_EXECUTOR.submit(() -> {
            try {
                String boundary = UUID.randomUUID().toString().replace("-", "");
                String apiEndpoint = apiUrl + "/verify";

                HttpURLConnection conn = (HttpURLConnection) new URL(apiEndpoint).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                // Build multipart body
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                PrintStream ps = new PrintStream(baos);

                // Field: expected_speaker
                ps.print("--" + boundary + "\r\n");
                ps.print("Content-Disposition: form-data; name=\"expected_speaker\"\r\n\r\n");
                ps.print(expectedSpeaker + "\r\n");

                // Field: threshold
                ps.print("--" + boundary + "\r\n");
                ps.print("Content-Disposition: form-data; name=\"threshold\"\r\n\r\n");
                ps.print("0.60\r\n");

                // File: audio
                ps.print("--" + boundary + "\r\n");
                ps.print("Content-Disposition: form-data; name=\"audio\"; filename=\"voice.wav\"\r\n");
                ps.print("Content-Type: audio/wav\r\n\r\n");
                ps.flush();
                baos.write(audioData);
                ps.print("\r\n--" + boundary + "--\r\n");
                ps.flush();

                byte[] body = baos.toByteArray();
                conn.setRequestProperty("Content-Length", String.valueOf(body.length));
                conn.getOutputStream().write(body);

                int responseCode = conn.getResponseCode();
                InputStream responseStream = (responseCode >= 200 && responseCode < 300)
                        ? conn.getInputStream() : conn.getErrorStream();

                String responseText = new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
                JsonObject json = JsonParser.parseString(responseText).getAsJsonObject();

                boolean success = json.get("success").getAsBoolean();
                boolean isOwner = success && json.has("is_owner") && json.get("is_owner").getAsBoolean();
                String speakerName = success && json.has("speaker") ? json.get("speaker").getAsString() : "Unknown";
                double confidence = success && json.has("confidence") ? json.get("confidence").getAsDouble() : 0.0;

                // Kembali ke main thread server
                if (level instanceof ServerLevel serverLevel) {
                    serverLevel.getServer().execute(() -> {
                        if (isOwner) {
                            player.sendSystemMessage(Component.literal(
                                    String.format("§a[VoiceDoor] ✓ Suara dikenali: §e%s §a(%.0f%% keyakinan). Pintu terbuka!",
                                            speakerName, confidence * 100)));
                            openDoor(level, doorPos, level.getBlockState(doorPos));
                        } else {
                            String reason = success
                                    ? String.format("§c%.0f%% keyakinan (terlalu rendah)", confidence * 100)
                                    : json.has("error") ? "§c" + json.get("error").getAsString() : "§cVerifikasi gagal";
                            player.sendSystemMessage(Component.literal(
                                    "§c[VoiceDoor] ✗ Akses ditolak. " + reason));
                        }
                    });
                }

            } catch (Exception e) {
                VoiceDoorMod.LOGGER.error("[VoiceDoor] Gagal menghubungi API server: {}", e.getMessage());
                if (level instanceof ServerLevel serverLevel) {
                    serverLevel.getServer().execute(() -> {
                        player.sendSystemMessage(Component.literal(
                                "§c[VoiceDoor] Tidak bisa terhubung ke server pengenal suara. " +
                                "Pastikan Python API berjalan di: " + apiUrl));
                    });
                }
            }
        });
    }

    /**
     * Kirim audio ke API server untuk mendaftarkan suara (saat /voicedoor register).
     */
    public void onVoiceRegistered(ServerPlayer player, byte[] audioData, Level level, BlockPos pos) {
        String memberName = registeringForName.isEmpty() ? player.getName().getString() : registeringForName;
        String apiUrl = this.apiUrl;

        HTTP_EXECUTOR.submit(() -> {
            try {
                String boundary = UUID.randomUUID().toString().replace("-", "");
                HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl + "/register").openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                PrintStream ps = new PrintStream(baos);

                ps.print("--" + boundary + "\r\n");
                ps.print("Content-Disposition: form-data; name=\"member\"\r\n\r\n");
                ps.print(memberName + "\r\n");

                ps.print("--" + boundary + "\r\n");
                ps.print("Content-Disposition: form-data; name=\"audio\"; filename=\"voice.wav\"\r\n");
                ps.print("Content-Type: audio/wav\r\n\r\n");
                ps.flush();
                baos.write(audioData);
                ps.print("\r\n--" + boundary + "--\r\n");
                ps.flush();

                byte[] body = baos.toByteArray();
                conn.setRequestProperty("Content-Length", String.valueOf(body.length));
                conn.getOutputStream().write(body);

                int responseCode = conn.getResponseCode();
                String responseText = new String(
                        (responseCode >= 200 ? conn.getInputStream() : conn.getErrorStream()).readAllBytes(),
                        StandardCharsets.UTF_8);
                JsonObject json = JsonParser.parseString(responseText).getAsJsonObject();
                boolean success = json.get("success").getAsBoolean();

                if (level instanceof ServerLevel serverLevel) {
                    serverLevel.getServer().execute(() -> {
                        if (success) {
                            double duration = json.get("duration").getAsDouble();
                            player.sendSystemMessage(Component.literal(
                                    String.format("§a[VoiceDoor] ✓ Sample suara (%.1f detik) tersimpan untuk: §e%s",
                                            duration, memberName)));

                            // Set owner jika belum ada
                            if (ownerUUID == null) {
                                ownerUUID = player.getUUID();
                                ownerName = memberName;
                                setChanged();
                                player.sendSystemMessage(Component.literal(
                                        "§6[VoiceDoor] Kamu sekarang jadi pemilik pintu ini!"));
                            }
                        } else {
                            String error = json.has("error") ? json.get("error").getAsString() : "Unknown error";
                            player.sendSystemMessage(Component.literal(
                                    "§c[VoiceDoor] Gagal menyimpan sample: " + error));
                        }
                        isRegistering = false;
                    });
                }

            } catch (Exception e) {
                VoiceDoorMod.LOGGER.error("[VoiceDoor] Gagal mendaftarkan suara: {}", e.getMessage());
                if (level instanceof ServerLevel serverLevel) {
                    serverLevel.getServer().execute(() -> {
                        player.sendSystemMessage(Component.literal(
                                "§c[VoiceDoor] Tidak bisa terhubung ke API server: " + e.getMessage()));
                        isRegistering = false;
                    });
                }
            }
        });
    }

    // -----------------------------------------------------------------------
    // Buka / Tutup pintu
    // -----------------------------------------------------------------------
    private void openDoor(Level level, BlockPos pos, BlockState state) {
        if (level.getBlockState(pos).getBlock() instanceof VoiceDoorBlock doorBlock) {
            doorBlock.setOpen(null, level, state, pos, true);
            isOpen = true;
            openTicks = AUTO_CLOSE_TICKS;
        }
    }

    private void closeDoor(Level level, BlockPos pos, BlockState state) {
        BlockState currentState = level.getBlockState(pos);
        if (currentState.getBlock() instanceof VoiceDoorBlock doorBlock) {
            doorBlock.setOpen(null, level, currentState, pos, false);
            isOpen = false;
        }
    }

    // -----------------------------------------------------------------------
    // Getters / Setters
    // -----------------------------------------------------------------------
    public UUID getOwnerUUID() { return ownerUUID; }
    public String getOwnerName() { return ownerName; }
    public String getApiUrl() { return apiUrl; }
    public boolean isRegistering() { return isRegistering; }
    public String getRegisteringForName() { return registeringForName; }

    public void setOwner(UUID uuid, String name) {
        this.ownerUUID = uuid;
        this.ownerName = name;
        setChanged();
    }

    public void startRegistering(String name) {
        this.isRegistering = true;
        this.registeringForName = name;
    }

    public void setApiUrl(String url) {
        this.apiUrl = url;
        setChanged();
    }

    public void addAuthorizedPlayer(UUID uuid) {
        authorizedPlayers.add(uuid);
        setChanged();
    }

    public boolean isAuthorized(UUID uuid) {
        return uuid.equals(ownerUUID) || authorizedPlayers.contains(uuid);
    }

    // -----------------------------------------------------------------------
    // NBT Serialization
    // -----------------------------------------------------------------------
    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (ownerUUID != null) {
            tag.putUUID("OwnerUUID", ownerUUID);
            tag.putString("OwnerName", ownerName);
        }
        tag.putString("ApiUrl", apiUrl);

        // Simpan authorized players
        CompoundTag authTag = new CompoundTag();
        int i = 0;
        for (UUID uuid : authorizedPlayers) {
            authTag.putUUID("auth_" + i, uuid);
            i++;
        }
        authTag.putInt("count", i);
        tag.put("AuthorizedPlayers", authTag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.hasUUID("OwnerUUID")) {
            ownerUUID = tag.getUUID("OwnerUUID");
            ownerName = tag.getString("OwnerName");
        }
        if (tag.contains("ApiUrl")) {
            apiUrl = tag.getString("ApiUrl");
        }

        // Load authorized players
        if (tag.contains("AuthorizedPlayers")) {
            CompoundTag authTag = tag.getCompound("AuthorizedPlayers");
            int count = authTag.getInt("count");
            for (int i = 0; i < count; i++) {
                if (authTag.hasUUID("auth_" + i)) {
                    authorizedPlayers.add(authTag.getUUID("auth_" + i));
                }
            }
        }
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        if (pkt.getTag() != null) {
            load(pkt.getTag());
        }
    }
}
