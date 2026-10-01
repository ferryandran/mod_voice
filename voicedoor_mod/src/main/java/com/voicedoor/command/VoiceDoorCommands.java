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
import java.util.StringJoiner;
import java.util.UUID;

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
        );
    }

    // -----------------------------------------------------------------------
    // /voicedoor register [nama]
    // -----------------------------------------------------------------------
    private static int cmdRegister(CommandContext<CommandSourceStack> ctx, String nameArg) {
        ServerPlayer player = playerOrNull(ctx);
        if (player == null) return 0;

        VoiceDoorBlockEntity door = lookedAtDoor(player);
        if (door == null) {
            reply(player, "message.voicedoor.look_at_door", ChatFormatting.RED);
            return 0;
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
        BlockPos pos = door.getBlockPos();

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

        VoiceDoorBlockEntity door = lookedAtDoor(player);
        if (door == null) {
            reply(player, "message.voicedoor.look_at_door", ChatFormatting.RED);
            return 0;
        }

        player.sendSystemMessage(Component.translatable("message.voicedoor.info_header")
                .withStyle(ChatFormatting.AQUA));
        line(player, "message.voicedoor.info_position", door.getBlockPos().toShortString());
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
        VoiceDoorBlockEntity door = requireOwnedDoor(ctx, player);
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
        VoiceDoorBlockEntity door = requireOwnedDoor(ctx, player);
        if (door == null) return 0;

        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        boolean removed = door.removeAuthorizedPlayer(target.getUUID());
        player.sendSystemMessage(Component.translatable(
                removed ? "message.voicedoor.denied" : "message.voicedoor.not_allowed",
                target.getName().getString())
                .withStyle(removed ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
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
        VoiceDoorBlockEntity door = lookedAtDoor(player);
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
        VoiceDoorBlockEntity door = lookedAtDoor(player);
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
    // /voicedoor help
    // -----------------------------------------------------------------------
    private static int cmdHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.translatable("message.voicedoor.help_header")
                .withStyle(ChatFormatting.AQUA), false);
        for (String key : new String[]{
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
    private static VoiceDoorBlockEntity requireOwnedDoor(CommandContext<CommandSourceStack> ctx,
                                                         ServerPlayer player) {
        VoiceDoorBlockEntity door = lookedAtDoor(player);
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

    /**
     * Block entity pintu yang sedang dilihat pemain.
     *
     * <p>Mengklik separuh atas juga dihitung: block entity hanya ada di separuh bawah,
     * jadi posisi di bawahnya ikut diperiksa.
     */
    @Nullable
    private static VoiceDoorBlockEntity lookedAtDoor(ServerPlayer player) {
        HitResult hit = player.pick(6.0D, 0.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        Level level = player.level();

        if (level.getBlockEntity(pos) instanceof VoiceDoorBlockEntity door) {
            return door;
        }
        if (level.getBlockEntity(pos.below()) instanceof VoiceDoorBlockEntity door) {
            return door;
        }
        return null;
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
