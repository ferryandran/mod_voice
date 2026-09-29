package com.voicedoor.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.voicedoor.VoiceDoorMod;
import com.voicedoor.blockentity.VoiceDoorBlockEntity;
import com.voicedoor.voice.VoiceRecordingSession;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Commands untuk VoiceDoor mod:
 *
 * /voicedoor register [nama]
 *   - Mulai sesi rekaman untuk mendaftarkan suara ke pintu yang sedang dilihat
 *   - Nama opsional (default: nama pemain)
 *
 * /voicedoor train
 *   - Trigger training model di Python API server
 *
 * /voicedoor status
 *   - Cek status API server dan daftar member terdaftar
 *
 * /voicedoor setowner <player>
 *   - Set owner pintu yang sedang dilihat (OP only)
 *
 * /voicedoor setapi <url>
 *   - Set URL API server untuk pintu yang sedang dilihat (OP only)
 *
 * /voicedoor info
 *   - Tampilkan info pintu yang sedang dilihat
 */
public class VoiceDoorCommands {

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "VoiceDoor-CMD");
        t.setDaemon(true);
        return t;
    });

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("voicedoor")
                // /voicedoor register [nama]
                .then(Commands.literal("register")
                        .executes(ctx -> cmdRegister(ctx, ""))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> cmdRegister(ctx, StringArgumentType.getString(ctx, "name")))))

                // /voicedoor train
                .then(Commands.literal("train")
                        .executes(VoiceDoorCommands::cmdTrain))

                // /voicedoor status
                .then(Commands.literal("status")
                        .executes(VoiceDoorCommands::cmdStatus))

                // /voicedoor info
                .then(Commands.literal("info")
                        .executes(VoiceDoorCommands::cmdInfo))

                // /voicedoor setapi <url> (OP only)
                .then(Commands.literal("setapi")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("url", StringArgumentType.string())
                                .executes(ctx -> cmdSetApi(ctx, StringArgumentType.getString(ctx, "url")))))

                // /voicedoor setowner <playerName> (OP only)
                .then(Commands.literal("setowner")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("playerName", StringArgumentType.word())
                                .executes(ctx -> cmdSetOwner(ctx, StringArgumentType.getString(ctx, "playerName")))))

                // /voicedoor help
                .then(Commands.literal("help")
                        .executes(VoiceDoorCommands::cmdHelp))
        );
    }

    // -----------------------------------------------------------------------
    // /voicedoor register [nama]
    // -----------------------------------------------------------------------
    private static int cmdRegister(CommandContext<CommandSourceStack> ctx, String nameArg) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Command ini hanya bisa digunakan oleh pemain."));
            return 0;
        }

        String memberName = nameArg.isEmpty() ? player.getName().getString() : nameArg;

        // Cari VoiceDoor yang sedang dilihat pemain
        VoiceDoorBlockEntity voiceDoorBE = getLookedAtDoor(player);
        if (voiceDoorBE == null) {
            player.sendSystemMessage(Component.literal(
                    "§c[VoiceDoor] Arahkan pandanganmu ke VoiceDoor terlebih dahulu!"));
            return 0;
        }

        Level level = player.level();
        BlockPos pos = voiceDoorBE.getBlockPos();

        player.sendSystemMessage(Component.literal(
                "§6[VoiceDoor] Memulai pendaftaran suara untuk: §e" + memberName));
        player.sendSystemMessage(Component.literal(
                "§7[VoiceDoor] Tip: Rekam 3-5 kali, masing-masing bicara 3-5 detik secara alami."));

        VoiceRecordingSession.startRegistration(player, voiceDoorBE, pos, level, memberName);
        return 1;
    }

    // -----------------------------------------------------------------------
    // /voicedoor train
    // -----------------------------------------------------------------------
    private static int cmdTrain(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Command ini hanya bisa digunakan oleh pemain."));
            return 0;
        }

        // Dapatkan API URL dari pintu yang dilihat, atau gunakan default
        VoiceDoorBlockEntity be = getLookedAtDoor(player);
        String apiUrl = be != null ? be.getApiUrl() : VoiceDoorBlockEntity.DEFAULT_API_URL;

        player.sendSystemMessage(Component.literal(
                "§b[VoiceDoor] Memulai training model di server Python... Ini bisa memakan beberapa menit."));

        EXECUTOR.submit(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl + "/train").openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(300000); // 5 menit timeout untuk training
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");

                String body = "{\"augment\":true,\"noise_reduction\":true,\"balance\":true}";
                conn.setRequestProperty("Content-Length", String.valueOf(body.length()));
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.close();

                int code = conn.getResponseCode();
                String response;
                try (BufferedReader br = new BufferedReader(new InputStreamReader(
                        code >= 200 ? conn.getInputStream() : conn.getErrorStream()))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    response = sb.toString();
                }

                // Parse JSON sederhana
                boolean success = response.contains("\"success\":true");
                String finalMsg;
                if (success) {
                    String model = extractJsonString(response, "best_model");
                    String accuracy = extractJsonString(response, "test_accuracy");
                    finalMsg = String.format("§a[VoiceDoor] ✓ Training selesai! Model: §e%s §a| Akurasi: §e%s",
                            model, accuracy);
                } else {
                    String error = extractJsonString(response, "error");
                    finalMsg = "§c[VoiceDoor] Training gagal: " + error;
                }

                String finalFinalMsg = finalMsg;
                if (player.server != null) {
                    player.server.execute(() ->
                            player.sendSystemMessage(Component.literal(finalFinalMsg)));
                }

            } catch (Exception e) {
                VoiceDoorMod.LOGGER.error("[VoiceDoor] Training request gagal: {}", e.getMessage());
                if (player.server != null) {
                    player.server.execute(() ->
                            player.sendSystemMessage(Component.literal(
                                    "§c[VoiceDoor] Tidak bisa terhubung ke API server: " + e.getMessage())));
                }
            }
        });

        return 1;
    }

    // -----------------------------------------------------------------------
    // /voicedoor status
    // -----------------------------------------------------------------------
    private static int cmdStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Command ini hanya bisa digunakan oleh pemain."));
            return 0;
        }

        VoiceDoorBlockEntity be = getLookedAtDoor(player);
        String apiUrl = be != null ? be.getApiUrl() : VoiceDoorBlockEntity.DEFAULT_API_URL;

        EXECUTOR.submit(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl + "/status").openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(5000);

                int code = conn.getResponseCode();
                String response;
                try (BufferedReader br = new BufferedReader(new InputStreamReader(
                        code >= 200 ? conn.getInputStream() : conn.getErrorStream()))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    response = sb.toString();
                }

                String modelReady = response.contains("\"model_ready\":true") ? "§a✓ Siap" : "§c✗ Belum dilatih";
                String members = extractJsonString(response, "registered_members");

                String finalMsg = String.format(
                        "§b[VoiceDoor] Status API: §fServer=%s | Model=%s | Members=%s",
                        apiUrl, modelReady, members.isEmpty() ? "§c(belum ada)" : "§e" + members);

                if (player.server != null) {
                    player.server.execute(() ->
                            player.sendSystemMessage(Component.literal(finalMsg)));
                }

            } catch (Exception e) {
                if (player.server != null) {
                    player.server.execute(() ->
                            player.sendSystemMessage(Component.literal(
                                    "§c[VoiceDoor] API server tidak bisa dihubungi di: " + apiUrl)));
                }
            }
        });

        return 1;
    }

    // -----------------------------------------------------------------------
    // /voicedoor info
    // -----------------------------------------------------------------------
    private static int cmdInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Command ini hanya bisa digunakan oleh pemain."));
            return 0;
        }

        VoiceDoorBlockEntity be = getLookedAtDoor(player);
        if (be == null) {
            player.sendSystemMessage(Component.literal(
                    "§c[VoiceDoor] Arahkan pandanganmu ke VoiceDoor!"));
            return 0;
        }

        player.sendSystemMessage(Component.literal("§b--- VoiceDoor Info ---"));
        player.sendSystemMessage(Component.literal("§7Posisi: §f" + be.getBlockPos().toShortString()));
        player.sendSystemMessage(Component.literal("§7Pemilik: §e" +
                (be.getOwnerName().isEmpty() ? "§c(belum ada)" : be.getOwnerName())));
        player.sendSystemMessage(Component.literal("§7API Server: §f" + be.getApiUrl()));
        return 1;
    }

    // -----------------------------------------------------------------------
    // /voicedoor setapi <url>
    // -----------------------------------------------------------------------
    private static int cmdSetApi(CommandContext<CommandSourceStack> ctx, String url) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Command ini hanya bisa digunakan oleh pemain."));
            return 0;
        }

        VoiceDoorBlockEntity be = getLookedAtDoor(player);
        if (be == null) {
            player.sendSystemMessage(Component.literal(
                    "§c[VoiceDoor] Arahkan pandanganmu ke VoiceDoor!"));
            return 0;
        }

        // Bersihkan trailing slash
        String cleanUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        be.setApiUrl(cleanUrl);
        player.sendSystemMessage(Component.literal(
                "§a[VoiceDoor] API URL diubah ke: §e" + cleanUrl));
        return 1;
    }

    // -----------------------------------------------------------------------
    // /voicedoor setowner <playerName>
    // -----------------------------------------------------------------------
    private static int cmdSetOwner(CommandContext<CommandSourceStack> ctx, String playerName) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Command ini hanya bisa digunakan oleh pemain."));
            return 0;
        }

        VoiceDoorBlockEntity be = getLookedAtDoor(player);
        if (be == null) {
            player.sendSystemMessage(Component.literal(
                    "§c[VoiceDoor] Arahkan pandanganmu ke VoiceDoor!"));
            return 0;
        }

        ServerPlayer targetPlayer = player.server.getPlayerList().getPlayerByName(playerName);
        if (targetPlayer == null) {
            player.sendSystemMessage(Component.literal(
                    "§c[VoiceDoor] Pemain '" + playerName + "' tidak ditemukan atau tidak online."));
            return 0;
        }

        be.setOwner(targetPlayer.getUUID(), playerName);
        player.sendSystemMessage(Component.literal(
                "§a[VoiceDoor] Owner pintu diubah ke: §e" + playerName));
        return 1;
    }

    // -----------------------------------------------------------------------
    // /voicedoor help
    // -----------------------------------------------------------------------
    private static int cmdHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal(
                "§b=== VoiceDoor Commands ===\n" +
                "§e/voicedoor register §7[nama] §f- Daftarkan suara ke pintu yang dilihat\n" +
                "§e/voicedoor train §f- Latih model pengenal suara\n" +
                "§e/voicedoor status §f- Cek status API server\n" +
                "§e/voicedoor info §f- Info pintu yang dilihat\n" +
                "§e/voicedoor setapi §7<url> §f- (OP) Set URL API server\n" +
                "§e/voicedoor setowner §7<player> §f- (OP) Set pemilik pintu\n" +
                "§e/voicedoor help §f- Tampilkan bantuan ini"
        ), false);
        return 1;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Dapatkan VoiceDoorBlockEntity dari pintu yang sedang dilihat pemain.
     * Melihat ke bawah satu blok juga (untuk bagian atas pintu).
     */
    private static VoiceDoorBlockEntity getLookedAtDoor(ServerPlayer player) {
        HitResult hitResult = player.pick(5.0, 0, false);
        if (hitResult.getType() != HitResult.Type.BLOCK) return null;

        BlockPos pos = ((BlockHitResult) hitResult).getBlockPos();
        Level level = player.level();

        // Cek blok yang diklik
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof VoiceDoorBlockEntity vbe) return vbe;

        // Cek blok di bawah (mungkin klik bagian atas pintu)
        be = level.getBlockEntity(pos.below());
        if (be instanceof VoiceDoorBlockEntity vbe) return vbe;

        return null;
    }

    /**
     * Ekstrak nilai string dari JSON sederhana (tanpa library JSON).
     */
    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return "";
        int valueStart = idx + search.length();
        while (valueStart < json.length() && json.charAt(valueStart) == ' ') valueStart++;

        if (valueStart >= json.length()) return "";
        char c = json.charAt(valueStart);

        if (c == '"') {
            // String value
            int end = json.indexOf('"', valueStart + 1);
            return end < 0 ? "" : json.substring(valueStart + 1, end);
        } else if (c == '[') {
            // Array value
            int end = json.indexOf(']', valueStart);
            return end < 0 ? "" : json.substring(valueStart, end + 1);
        } else {
            // Number or boolean
            int end = valueStart;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
            return json.substring(valueStart, end).trim();
        }
    }
}
