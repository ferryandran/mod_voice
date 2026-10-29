package com.voicedoor.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.voicedoor.VoiceDoorMod;
import com.voicedoor.api.VoiceApiClient;
import com.voicedoor.blockentity.VoiceDoorBlockEntity;
import com.voicedoor.config.VoiceDoorConfig;
import com.voicedoor.garage.DoorTarget;
import com.voicedoor.network.ModNetwork;
import com.voicedoor.network.S2CStatusPacket;
import com.voicedoor.voice.VoiceChatPlugin;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Command {@code /voicedoor}.
 *
 * <p>Semua parsing JSON di sini memakai Gson lewat {@link VoiceApiClient}. Versi
 * sebelumnya punya parser string buatan sendiri ({@code extractJsonString}) yang tidak
 * menangani escape, objek bersarang, maupun spasi setelah nama kunci - padahal Gson sudah
 * ada di classpath dan dipakai di tempat lain.
 */
public final class VoiceDoorCommands {

    private static final int OP_LEVEL = 2;

    private VoiceDoorCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("voicedoor")
                .then(Commands.literal("register")
                        .executes(ctx -> cmdRegister(ctx, ""))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> cmdRegister(ctx, StringArgumentType.getString(ctx, "name")))))

                .then(Commands.literal("info").executes(VoiceDoorCommands::cmdInfo))
                .then(Commands.literal("status").executes(VoiceDoorCommands::cmdStatus))
                .then(Commands.literal("help").executes(VoiceDoorCommands::cmdHelp))

                .then(Commands.literal("claim")
                        .executes(ctx -> cmdClaim(ctx, ""))
                        .then(Commands.argument("member", StringArgumentType.greedyString())
                                .executes(ctx -> cmdClaim(ctx, StringArgumentType.getString(ctx, "member")))))
                .then(Commands.literal("unclaim")
                        .executes(VoiceDoorCommands::cmdClearOwner))

                .then(Commands.literal("addvoice")
                        .then(Commands.argument("member", StringArgumentType.greedyString())
                                .executes(ctx -> cmdAddVoice(ctx, StringArgumentType.getString(ctx, "member")))))
                .then(Commands.literal("removevoice")
                        .then(Commands.argument("member", StringArgumentType.word())
                                .executes(ctx -> cmdRemoveVoice(ctx, StringArgumentType.getString(ctx, "member")))))

                .then(Commands.literal("allow")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(VoiceDoorCommands::cmdAllow)))
                .then(Commands.literal("deny")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(VoiceDoorCommands::cmdDeny)))

                // --- OP saja -------------------------------------------------
                .then(Commands.literal("setapi")
                        .requires(src -> src.hasPermission(OP_LEVEL))
                        .then(Commands.argument("url", StringArgumentType.greedyString())
                                .executes(ctx -> cmdSetApi(ctx, StringArgumentType.getString(ctx, "url")))))

                .then(Commands.literal("settoken")
                        .requires(src -> src.hasPermission(OP_LEVEL))
                        .then(Commands.argument("token", StringArgumentType.greedyString())
                                .executes(ctx -> cmdSetToken(ctx, StringArgumentType.getString(ctx, "token")))))

                .then(Commands.literal("setthreshold")
                        .requires(src -> src.hasPermission(OP_LEVEL))
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.35D, 0.95D))
                                .executes(ctx -> cmdSetThreshold(ctx,
                                        DoubleArgumentType.getDouble(ctx, "value")))))

                .then(Commands.literal("setowner")
                        .requires(src -> src.hasPermission(OP_LEVEL))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(VoiceDoorCommands::cmdSetOwner)))

                .then(Commands.literal("clearowner")
                        .requires(src -> src.hasPermission(OP_LEVEL))
                        .executes(VoiceDoorCommands::cmdClearOwner))

                .then(Commands.literal("enroll")
                        .requires(src -> src.hasPermission(OP_LEVEL))
                        .then(Commands.argument("member", StringArgumentType.word())
                                .executes(ctx -> cmdEnroll(ctx, StringArgumentType.getString(ctx, "member")))))

                .then(Commands.literal("reload")
                        .requires(src -> src.hasPermission(OP_LEVEL))
                        .executes(VoiceDoorCommands::cmdReload))

                .then(Commands.literal("setpassword")
                        .requires(src -> src.hasPermission(OP_LEVEL))
                        .executes(ctx -> cmdSetPassword(ctx, ""))  // no arg = clear password
                        .then(Commands.argument("password", StringArgumentType.greedyString())
                                .executes(ctx -> cmdSetPassword(ctx, StringArgumentType.getString(ctx, "password")))))

                .then(Commands.literal("password")
                        .requires(src -> src.hasPermission(OP_LEVEL))
                        .executes(VoiceDoorCommands::cmdPasswordStatus)
                        .then(Commands.literal("status")
                                .executes(VoiceDoorCommands::cmdPasswordStatus))
                        .then(Commands.literal("off")
                                .executes(ctx -> applyPasswordChange(ctx.getSource(), "")))
                        .then(Commands.literal("disable")
                                .executes(ctx -> applyPasswordChange(ctx.getSource(), "")))
                        .then(Commands.literal("on")
                                .executes(ctx -> cmdPasswordOn(ctx, ""))
                                .then(Commands.argument("password", StringArgumentType.greedyString())
                                        .executes(ctx -> cmdPasswordOn(ctx, StringArgumentType.getString(ctx, "password")))))
                        .then(Commands.literal("enable")
                                .executes(ctx -> cmdPasswordOn(ctx, ""))
                                .then(Commands.argument("password", StringArgumentType.greedyString())
                                        .executes(ctx -> cmdPasswordOn(ctx, StringArgumentType.getString(ctx, "password")))))
                        .then(Commands.literal("toggle")
                                .executes(VoiceDoorCommands::cmdPasswordToggle))
                        .then(Commands.literal("set")
                                .then(Commands.argument("password", StringArgumentType.greedyString())
                                        .executes(ctx -> applyPasswordChange(ctx.getSource(), StringArgumentType.getString(ctx, "password"))))))

                .then(Commands.literal("registerfile")
                        .requires(src -> src.hasPermission(OP_LEVEL))
                        .then(Commands.argument("member", StringArgumentType.word())
                                .executes(ctx -> cmdRegisterFile(
                                        ctx, StringArgumentType.getString(ctx, "member"), ""))
                                .then(Commands.argument("folder", StringArgumentType.greedyString())
                                        .executes(ctx -> cmdRegisterFile(
                                                ctx,
                                                StringArgumentType.getString(ctx, "member"),
                                                StringArgumentType.getString(ctx, "folder"))))))
        );
    }

    // -----------------------------------------------------------------------
    // /voicedoor claim [member]
    // -----------------------------------------------------------------------
    private static int cmdClaim(CommandContext<CommandSourceStack> ctx, String memberArg) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;

        DoorTarget door = DoorTarget.findLookedAt(player);
        if (door == null) {
            reply(player, "message.voicedoor.look_at_door", ChatFormatting.RED);
            return 0;
        }

        if (door.hasOwner()) {
            boolean isOwner = player.getUUID().equals(door.getOwnerUUID());
            if (!isOwner && !ctx.getSource().hasPermission(OP_LEVEL)) {
                reply(player, "message.voicedoor.already_claimed", ChatFormatting.RED);
                return 0;
            }
        }

        String member = memberArg.isEmpty() ? player.getName().getString() : memberArg.trim();
        door.setOwner(player.getUUID(), member);
        for (String m : member.split(",")) {
            String trimmed = m.trim();
            if (!trimmed.isEmpty()) {
                door.addVoice(trimmed);
            }
        }
        player.sendSystemMessage(Component.translatable(
                "message.voicedoor.claimed_success", member)
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    // -----------------------------------------------------------------------
    // /voicedoor register [nama]
    // -----------------------------------------------------------------------
    private static int cmdRegister(CommandContext<CommandSourceStack> ctx, String nameArg) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;

        DoorTarget door = DoorTarget.findLookedAt(player);
        if (door == null) {
            reply(player, "message.voicedoor.look_at_door", ChatFormatting.RED);
            return 0;
        }

        if (door.isGarageDoor()) {
            player.sendSystemMessage(Component.literal(
                    "[VoiceDoor] Untuk pintu garasi Macaw's, gunakan '/voicedoor claim [member]' " +
                    "atau '/voicedoor setowner <player>'. Untuk dataset, gunakan '/voicedoor registerfile <member>'.")
                    .withStyle(ChatFormatting.YELLOW));
            return 1;
        }

        if (VoiceChatPlugin.hasActiveSession(player.getUUID())) {
            reply(player, "message.voicedoor.session_busy", ChatFormatting.YELLOW);
            return 0;
        }
        if (!VoiceDoorMod.VOICE_CHAT_AVAILABLE) {
            reply(player, "message.voicedoor.no_voicechat", ChatFormatting.RED);
            return 0;
        }

        // Hanya pemilik (atau OP) boleh mendaftarkan nama selain namanya sendiri.
        String memberName = nameArg.isEmpty() ? player.getName().getString() : nameArg;
        if (!nameArg.isEmpty() && !nameArg.equalsIgnoreCase(player.getName().getString())) {
            boolean isOwner = door.hasOwner() && player.getUUID().equals(door.getOwnerUUID());
            if (!isOwner && !ctx.getSource().hasPermission(OP_LEVEL)) {
                reply(player, "message.voicedoor.cannot_register_other", ChatFormatting.RED);
                return 0;
            }
        }

        Level level = player.level();
        BlockPos pos = door.getPos();

        // Client dengan mod mendapat GUI mulai/berhenti (sample bisa sampai 20 detik).
        // Client tanpa mod tetap memakai rekaman berdurasi tetap lewat chat.
        if (ModNetwork.hasModInstalled(player)) {
            VoiceDoorBlockEntity.openRegistrationScreen(player, pos, memberName);
            return 1;
        }

        player.sendSystemMessage(Component.translatable("message.voicedoor.register_start", memberName)
                .withStyle(ChatFormatting.GOLD));
        player.sendSystemMessage(Component.translatable("message.voicedoor.register_tip")
                .withStyle(ChatFormatting.GRAY));

        VoiceChatPlugin.startRegistrationSession(player, level, pos, memberName);
        ModNetwork.sendTo(player, S2CStatusPacket.recordingStart("", VoiceDoorConfig.recordingSeconds()));
        player.sendSystemMessage(Component.translatable(
                "message.voicedoor.speak_now", VoiceDoorConfig.recordingSeconds()));
        return 1;
    }

    // -----------------------------------------------------------------------
    // /voicedoor info
    // -----------------------------------------------------------------------
    private static int cmdInfo(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;

        DoorTarget door = DoorTarget.findLookedAt(player);
        if (door == null) {
            reply(player, "message.voicedoor.look_at_door", ChatFormatting.RED);
            return 0;
        }

        player.sendSystemMessage(Component.translatable("message.voicedoor.info_header")
                .withStyle(ChatFormatting.AQUA));
        line(player, "message.voicedoor.info_position",
                door.getPos().toShortString() + (door.isGarageDoor() ? " (Macaw's Garage)" : ""));
        line(player, "message.voicedoor.info_owner",
                door.hasOwner() ? door.getOwnerName() : "-");
        line(player, "message.voicedoor.info_api",
                door.getApiUrl() + (door.hasApiUrlOverride() ? " (override)" : ""));
        line(player, "message.voicedoor.info_threshold",
                String.format(java.util.Locale.ROOT, "%.2f", VoiceDoorConfig.threshold()));
        line(player, "message.voicedoor.info_state",
                door.isOpen()
                        ? Component.translatable("message.voicedoor.state_open",
                                door.getRemainingOpenTicks() / 20).getString()
                        : Component.translatable("message.voicedoor.state_closed").getString());

        java.util.Collection<String> voiceList = door.getVoiceMembers();
        String voicesStr = voiceList.isEmpty()
                ? (door.hasOwner() ? door.getOwnerName() : "-")
                : String.join(", ", voiceList);
        line(player, "message.voicedoor.info_voices", voicesStr);

        StringJoiner allowed = new StringJoiner(", ");
        for (UUID uuid : door.authorizedList()) {
            ServerPlayer known = player.server.getPlayerList().getPlayer(uuid);
            allowed.add(known != null ? known.getName().getString() : uuid.toString().substring(0, 8));
        }
        line(player, "message.voicedoor.info_allowed",
                allowed.length() == 0 ? "-" : allowed.toString());
        return 1;
    }

    // -----------------------------------------------------------------------
    // /voicedoor status
    // -----------------------------------------------------------------------
    private static int cmdStatus(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;

        reply(player, "message.voicedoor.status_checking", ChatFormatting.AQUA);

        boolean queued = VoiceApiClient.tryGetAsync("/status", response -> onServer(player, () -> {
            if (!response.isSuccess()) {
                player.sendSystemMessage(Component.translatable(
                        "message.voicedoor.status_unreachable",
                        VoiceDoorConfig.apiUrl(), response.errorMessage())
                        .withStyle(ChatFormatting.RED));
                return;
            }

            String mode = response.optString("mode", "?");
            String enrolled = joinArray(response.body() == null ? null
                    : response.body().get("enrolled_members"));
            String registered = joinArray(response.body() == null ? null
                    : response.body().get("registered_members"));
            double serverThreshold = response.optDouble("verify_threshold", -1.0);

            player.sendSystemMessage(Component.translatable("message.voicedoor.status_header")
                    .withStyle(ChatFormatting.AQUA));
            line(player, "message.voicedoor.status_server", VoiceDoorConfig.apiUrl());
            line(player, "message.voicedoor.status_mode", mode);
            line(player, "message.voicedoor.status_enrolled", enrolled.isEmpty() ? "-" : enrolled);
            line(player, "message.voicedoor.status_samples", registered.isEmpty() ? "-" : registered);
            if (serverThreshold >= 0) {
                line(player, "message.voicedoor.status_threshold",
                        String.format(java.util.Locale.ROOT, "%.2f", serverThreshold));
            }
            line(player, "message.voicedoor.status_token",
                    VoiceDoorConfig.hasToken()
                            ? Component.translatable("message.voicedoor.token_set").getString()
                            : Component.translatable("message.voicedoor.token_missing").getString());
        }));

        if (!queued) reply(player, "message.voicedoor.server_busy", ChatFormatting.RED);
        return 1;
    }

    // -----------------------------------------------------------------------
    // Izin pemain
    // -----------------------------------------------------------------------
    private static int cmdAllow(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        DoorTarget door = requireOwnedDoor(ctx, player);
        if (door == null) return 0;

        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        door.addAuthorizedPlayer(target.getUUID());
        player.sendSystemMessage(Component.translatable(
                "message.voicedoor.allowed", target.getName().getString())
                .withStyle(ChatFormatting.GREEN));
        target.sendSystemMessage(Component.translatable(
                "message.voicedoor.you_were_allowed", door.getOwnerName())
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int cmdDeny(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        DoorTarget door = requireOwnedDoor(ctx, player);
        if (door == null) return 0;

        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        boolean removed = door.removeAuthorizedPlayer(target.getUUID());
        player.sendSystemMessage(Component.translatable(
                removed ? "message.voicedoor.denied" : "message.voicedoor.not_allowed",
                target.getName().getString())
                .withStyle(removed ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        return removed ? 1 : 0;
    }

    private static int cmdAddVoice(CommandContext<CommandSourceStack> ctx, String memberArg) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        DoorTarget door = requireOwnedDoor(ctx, player);
        if (door == null) return 0;

        String[] parts = memberArg.split(",");
        int count = 0;
        for (String part : parts) {
            String m = part.trim();
            if (!m.isEmpty()) {
                if (door.addVoice(m)) {
                    count++;
                }
            }
        }

        player.sendSystemMessage(Component.literal(
                "[VoiceDoor] Berhasil menambahkan " + count + " profil suara. Suara yang diizinkan sekarang: " +
                String.join(", ", door.getVoiceMembers()))
                .withStyle(ChatFormatting.GREEN));
        return count;
    }

    private static int cmdRemoveVoice(CommandContext<CommandSourceStack> ctx, String memberArg) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        DoorTarget door = requireOwnedDoor(ctx, player);
        if (door == null) return 0;

        boolean removed = door.removeVoice(memberArg.trim());
        if (removed) {
            player.sendSystemMessage(Component.literal(
                    "[VoiceDoor] Profil suara '" + memberArg.trim() + "' dihapus. Sisa suara yang diizinkan: " +
                    String.join(", ", door.getVoiceMembers()))
                    .withStyle(ChatFormatting.GREEN));
        } else {
            player.sendSystemMessage(Component.literal(
                    "[VoiceDoor] Profil suara '" + memberArg.trim() + "' tidak ditemukan pada pintu ini.")
                    .withStyle(ChatFormatting.YELLOW));
        }
        return removed ? 1 : 0;
    }

    // -----------------------------------------------------------------------
    // Command OP
    // -----------------------------------------------------------------------
    private static int cmdSetApi(CommandContext<CommandSourceStack> ctx, String url) {
        CommandSourceStack source = ctx.getSource();
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            source.sendFailure(Component.translatable("message.voicedoor.bad_url"));
            return 0;
        }
        VoiceDoorConfig.setApiUrl(trimmed);
        source.sendSuccess(() -> Component.translatable(
                "message.voicedoor.api_set", VoiceDoorConfig.apiUrl())
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /**
     * Token tidak pernah digaungkan kembali ke chat. Mengirimnya utuh akan membuat token
     * muncul di log server dan di layar pemain lain yang menonton.
     */
    private static int cmdSetToken(CommandContext<CommandSourceStack> ctx, String token) {
        CommandSourceStack source = ctx.getSource();
        String trimmed = token.trim();
        if (trimmed.isEmpty()) {
            VoiceDoorConfig.setApiToken("");
            source.sendSuccess(() -> Component.translatable("message.voicedoor.token_cleared")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 1;
        }
        VoiceDoorConfig.setApiToken(trimmed);
        source.sendSuccess(() -> Component.translatable(
                "message.voicedoor.token_saved", trimmed.length())
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int cmdSetThreshold(CommandContext<CommandSourceStack> ctx, double value) {
        VoiceDoorConfig.setThreshold(value);
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "message.voicedoor.threshold_set",
                String.format(java.util.Locale.ROOT, "%.2f", VoiceDoorConfig.threshold()))
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int cmdSetOwner(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        DoorTarget door = DoorTarget.findLookedAt(player);
        if (door == null) {
            reply(player, "message.voicedoor.look_at_door", ChatFormatting.RED);
            return 0;
        }
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        door.setOwner(target.getUUID(), target.getName().getString());
        player.sendSystemMessage(Component.translatable(
                "message.voicedoor.owner_set", target.getName().getString())
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int cmdClearOwner(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;
        DoorTarget door = DoorTarget.findLookedAt(player);
        if (door == null) {
            reply(player, "message.voicedoor.look_at_door", ChatFormatting.RED);
            return 0;
        }
        door.clearOwner();
        reply(player, "message.voicedoor.owner_cleared", ChatFormatting.GREEN);
        return 1;
    }

    private static int cmdEnroll(CommandContext<CommandSourceStack> ctx, String member) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.translatable("message.voicedoor.enroll_start", member)
                .withStyle(ChatFormatting.AQUA), false);

        boolean queued = VoiceApiClient.tryPostJsonAsync("/enroll",
                "{\"member\":\"" + member.replace("\"", "\\\"") + "\"}",
                response -> {
                    Component message = response.isSuccess()
                            ? Component.translatable("message.voicedoor.enroll_done",
                                    response.optString("member", member),
                                    (int) response.optDouble("n_segments", 0),
                                    String.format(java.util.Locale.ROOT, "%.2f",
                                            response.optDouble("cohesion", 0.0)))
                                .withStyle(ChatFormatting.GREEN)
                            : Component.translatable("message.voicedoor.enroll_failed",
                                    response.errorMessage()).withStyle(ChatFormatting.RED);
                    // sendSuccess harus dipanggil di main thread server
                    source.getServer().execute(() -> source.sendSuccess(() -> message, false));
                });

        if (!queued) {
            source.sendFailure(Component.translatable("message.voicedoor.server_busy"));
            return 0;
        }
        return 1;
    }

    private static int cmdReload(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "message.voicedoor.reloaded",
                VoiceDoorConfig.apiUrl(),
                String.format(java.util.Locale.ROOT, "%.2f", VoiceDoorConfig.threshold()),
                VoiceChatPlugin.activeSessionCount())
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    // -----------------------------------------------------------------------
    // /voicedoor password <on|off|toggle|status|set> dan /voicedoor setpassword
    // -----------------------------------------------------------------------
    private static int cmdPasswordStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String current = VoiceDoorConfig.doorPassword();
        if (current.isEmpty()) {
            source.sendSuccess(() -> Component.literal("[VoiceDoor] Status Password: ")
                    .append(Component.literal("NONAKTIF (OFF)").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                    .append(Component.literal(" - Pintu hanya memakai verifikasi biometrik suara.").withStyle(ChatFormatting.GRAY)),
                    false);
            source.sendSuccess(() -> Component.literal("  Untuk mengaktifkan: /voicedoor password on [password]")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
        } else {
            source.sendSuccess(() -> Component.literal("[VoiceDoor] Status Password: ")
                    .append(Component.literal("AKTIF (ON)").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                    .append(Component.literal(" | Kata Sandi: \"").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(current).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                    .append(Component.literal("\"").withStyle(ChatFormatting.GRAY)),
                    false);
            source.sendSuccess(() -> Component.literal("  Untuk mematikan: /voicedoor password off")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
        }
        return 1;
    }

    private static int cmdPasswordOn(CommandContext<CommandSourceStack> ctx, String password) {
        String trimmed = password.trim();
        if (trimmed.isEmpty()) {
            trimmed = "kopdes jaya jaya jaya";
        }
        return applyPasswordChange(ctx.getSource(), trimmed);
    }

    private static int cmdPasswordToggle(CommandContext<CommandSourceStack> ctx) {
        if (VoiceDoorConfig.hasPassword()) {
            return applyPasswordChange(ctx.getSource(), "");
        } else {
            return applyPasswordChange(ctx.getSource(), "kopdes jaya jaya jaya");
        }
    }

    private static int cmdSetPassword(CommandContext<CommandSourceStack> ctx, String password) {
        String trimmed = password.trim();
        if (trimmed.equalsIgnoreCase("off") || trimmed.equalsIgnoreCase("none") ||
            trimmed.equalsIgnoreCase("disable") || trimmed.equalsIgnoreCase("false")) {
            trimmed = "";
        } else if (trimmed.equalsIgnoreCase("on") || trimmed.equalsIgnoreCase("enable") ||
                   trimmed.equalsIgnoreCase("true")) {
            trimmed = "kopdes jaya jaya jaya";
        }
        return applyPasswordChange(ctx.getSource(), trimmed);
    }

    private static int applyPasswordChange(CommandSourceStack source, String password) {
        String trimmed = password.trim();

        // Simpan ke config lokal
        VoiceDoorConfig.setDoorPassword(trimmed);

        if (trimmed.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "[VoiceDoor] Password dinonaktifkan (OFF). Pintu sekarang hanya memverifikasi suara.")
                    .withStyle(ChatFormatting.YELLOW), true);
        } else {
            source.sendSuccess(() -> Component.literal(
                    "[VoiceDoor] Password diaktifkan (ON): \"" + trimmed + "\"")
                    .withStyle(ChatFormatting.GREEN), true);
        }

        // Kirim juga ke server Python API
        boolean queued = VoiceApiClient.tryPostJsonAsync("/password",
                "{\"password\":\"" + trimmed.replace("\"", "\\\"") + "\"}",
                response -> {
                    Component message = response.isSuccess()
                            ? Component.literal("[VoiceDoor] Password tersinkronisasi ke API server.")
                                .withStyle(ChatFormatting.GREEN)
                            : Component.literal("[VoiceDoor] Gagal sinkronisasi password ke API server: " +
                                    response.errorMessage()).withStyle(ChatFormatting.YELLOW);
                    if (source.getServer() != null) {
                        source.getServer().execute(() -> source.sendSuccess(() -> message, false));
                    }
                });

        if (!queued) {
            source.sendSuccess(() -> Component.literal(
                    "[VoiceDoor] Password tersimpan di config. Sinkronisasi ke API server ditunda (server sibuk).")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    // -----------------------------------------------------------------------
    // /voicedoor registerfile <member> [folder]
    // -----------------------------------------------------------------------
    /**
     * Daftarkan suara dari dataset yang sudah dikumpulkan tanpa perlu rekam ulang.
     *
     * <p>Membaca semua file *.wav dari folder dataset lokal (relatif terhadap direktori
     * kerja server) dan mengirimnya satu per satu ke endpoint {@code POST /register}.
     * Folder default: {@code voice/data/raw/<member>/}
     *
     * <p>Penggunaan:
     * <ul>
     *   <li>{@code /voicedoor registerfile ferry} — baca dari {@code voice/data/raw/ferry/}</li>
     *   <li>{@code /voicedoor registerfile ferry C:/path/ke/folder} — folder custom</li>
     * </ul>
     */
    private static int cmdRegisterFile(CommandContext<CommandSourceStack> ctx,
                                       String member, String folderArg) {
        CommandSourceStack source = ctx.getSource();

        // Tentukan folder: argumen eksplisit atau default voice/data/raw/<member>
        Path folder;
        if (folderArg == null || folderArg.isBlank()) {
            folder = Paths.get("voice", "data", "raw", member);
        } else {
            folder = Paths.get(folderArg.trim());
        }

        // Kumpulkan semua file audio (OGG, WAV, MP3, FLAC)
        List<Path> audioFiles = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            source.sendFailure(Component.literal(
                    "[VoiceDoor] Folder tidak ditemukan: " + folder.toAbsolutePath()));
            return 0;
        }
        try {
            Files.walk(folder)
                    .filter(p -> {
                        String s = p.toString().toLowerCase();
                        return s.endsWith(".ogg") || s.endsWith(".wav") || s.endsWith(".mp3") || s.endsWith(".flac");
                    })
                    .sorted()
                    .forEach(audioFiles::add);
        } catch (IOException e) {
            source.sendFailure(Component.literal(
                    "[VoiceDoor] Gagal membaca folder: " + e.getMessage()));
            return 0;
        }

        if (audioFiles.isEmpty()) {
            source.sendFailure(Component.literal(
                    "[VoiceDoor] Tidak ada file audio (.ogg/.wav/.mp3/.flac) di: " + folder.toAbsolutePath()));
            return 0;
        }

        Path serverRaw = Paths.get("voice", "data", "raw", member);
        if (Files.exists(serverRaw) && folder.toAbsolutePath().normalize().equals(serverRaw.toAbsolutePath().normalize())) {
            source.sendSuccess(() -> Component.literal(
                    "[VoiceDoor] Dataset ditemukan langsung di folder server (" + audioFiles.size() +
                    " file audio). Menjalankan kalkulasi voiceprint...")
                    .withStyle(ChatFormatting.AQUA), false);
            return cmdEnroll(ctx, member);
        }

        source.sendSuccess(() -> Component.literal(
                "[VoiceDoor] Mengunggah " + audioFiles.size() + " file audio untuk member \"" +
                member + "\" dari: " + folder.toAbsolutePath())
                .withStyle(ChatFormatting.AQUA), false);

        sendSequentialRegister(source, member, audioFiles, 0, 0, 0);
        return 1;
    }

    private static void sendSequentialRegister(CommandSourceStack source, String member,
                                               List<Path> audioFiles, int index,
                                               int successCount, int failedCount) {
        if (index >= audioFiles.size()) {
            if (source.getServer() != null) {
                source.getServer().execute(() ->
                        reportRegisterFileDone(source, member, audioFiles.size(), successCount, failedCount));
            } else {
                reportRegisterFileDone(source, member, audioFiles.size(), successCount, failedCount);
            }
            return;
        }

        Path audioPath = audioFiles.get(index);
        byte[] audioBytes;
        try {
            audioBytes = Files.readAllBytes(audioPath);
        } catch (IOException e) {
            VoiceDoorMod.LOGGER.warn("[VoiceDoor] Gagal membaca {}: {}", audioPath, e.getMessage());
            sendSequentialRegister(source, member, audioFiles, index + 1, successCount, failedCount + 1);
            return;
        }

        String fileName = audioPath.getFileName().toString();
        String lower = fileName.toLowerCase();
        String contentType = "audio/wav";
        if (lower.endsWith(".ogg")) {
            contentType = "audio/ogg";
        } else if (lower.endsWith(".mp3")) {
            contentType = "audio/mpeg";
        } else if (lower.endsWith(".flac")) {
            contentType = "audio/flac";
        }

        VoiceApiClient.MultipartBody body = new VoiceApiClient.MultipartBody()
                .field("member", member)
                .field("auto_enroll", "false")
                .file("audio", fileName, contentType, audioBytes);

        boolean queued = VoiceApiClient.tryPostFormAsync("/register", body, response -> {
            int newSuccess = successCount + (response.isSuccess() ? 1 : 0);
            int newFailed = failedCount + (response.isSuccess() ? 0 : 1);
            if (!response.isSuccess()) {
                VoiceDoorMod.LOGGER.warn("[VoiceDoor] Gagal register {}: {}", fileName, response.errorMessage());
            }
            sendSequentialRegister(source, member, audioFiles, index + 1, newSuccess, newFailed);
        });

        if (!queued) {
            sendSequentialRegister(source, member, audioFiles, index + 1, successCount, failedCount + 1);
        }
    }

    private static void reportRegisterFileDone(CommandSourceStack source, String member,
                                               int total, int success, int failed) {
        if (failed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "[VoiceDoor] Selesai! " + success + "/" + total + " file berhasil didaftarkan" +
                    " untuk \"" + member + "\". Jalankan /voicedoor enroll " + member +
                    " untuk menghitung voiceprint.")
                    .withStyle(ChatFormatting.GREEN), false);
        } else {
            source.sendSuccess(() -> Component.literal(
                    "[VoiceDoor] Selesai: " + success + " berhasil, " + failed + " gagal" +
                    " dari total " + total + " file untuk \"" + member + "\".")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
    }



    // -----------------------------------------------------------------------
    // /voicedoor help
    // -----------------------------------------------------------------------
    private static int cmdHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.translatable("message.voicedoor.help_header")
                .withStyle(ChatFormatting.AQUA), false);
        for (String key : new String[]{
                "message.voicedoor.help_claim",
                "message.voicedoor.help_unclaim",
                "message.voicedoor.help_register",
                "message.voicedoor.help_info",
                "message.voicedoor.help_status",
                "message.voicedoor.help_allow",
                "message.voicedoor.help_deny",
        }) {
            source.sendSuccess(() -> Component.translatable(key), false);
        }
        if (source.hasPermission(OP_LEVEL)) {
            source.sendSuccess(() -> Component.translatable("message.voicedoor.help_op_header")
                    .withStyle(ChatFormatting.GOLD), false);
            for (String key : new String[]{
                    "message.voicedoor.help_setapi",
                    "message.voicedoor.help_settoken",
                    "message.voicedoor.help_setthreshold",
                    "message.voicedoor.help_setowner",
                    "message.voicedoor.help_clearowner",
                    "message.voicedoor.help_enroll",
                    "message.voicedoor.help_reload",
                    "message.voicedoor.help_registerfile",
                    "message.voicedoor.help_password",
                    "message.voicedoor.help_setpassword",
            }) {
                source.sendSuccess(() -> Component.translatable(key), false);
            }
        }
        return 1;
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------
    @Nullable
    private static ServerPlayer playerOrNull(CommandContext<CommandSourceStack> ctx) {
        try {
            return ctx.getSource().getPlayerOrException();
        } catch (CommandSyntaxException e) {
            ctx.getSource().sendFailure(Component.translatable("message.voicedoor.players_only"));
            return null;
        }
    }

    /** Pintu yang dilihat pemain, dan pastikan dia pemiliknya (atau OP). */
    @Nullable
    private static DoorTarget requireOwnedDoor(CommandContext<CommandSourceStack> ctx,
                                               ServerPlayer player) {
        DoorTarget door = DoorTarget.findLookedAt(player);
        if (door == null) {
            reply(player, "message.voicedoor.look_at_door", ChatFormatting.RED);
            return null;
        }
        if (!door.hasOwner()) {
            reply(player, "message.voicedoor.no_owner", ChatFormatting.YELLOW);
            return null;
        }
        boolean isOwner = player.getUUID().equals(door.getOwnerUUID());
        if (!isOwner && !ctx.getSource().hasPermission(OP_LEVEL)) {
            reply(player, "message.voicedoor.not_owner", ChatFormatting.RED);
            return null;
        }
        return door;
    }

    private static void reply(ServerPlayer player, String key, ChatFormatting color) {
        player.sendSystemMessage(Component.translatable(key).withStyle(color));
    }

    private static void line(ServerPlayer player, String key, Object value) {
        player.sendSystemMessage(Component.translatable(key, value).withStyle(ChatFormatting.GRAY));
    }

    private static void onServer(ServerPlayer player, Runnable action) {
        if (player.level() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().execute(action);
        }
    }

    /** Gabungkan array JSON menjadi string yang enak dibaca. */
    private static String joinArray(@Nullable JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return "";
        }
        JsonArray array = element.getAsJsonArray();
        StringJoiner joiner = new StringJoiner(", ");
        for (JsonElement item : array) {
            if (item.isJsonPrimitive()) {
                joiner.add(item.getAsString());
            }
        }
        return joiner.toString();
    }
}
