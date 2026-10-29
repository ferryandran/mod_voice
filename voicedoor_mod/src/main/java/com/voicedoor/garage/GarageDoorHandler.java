package com.voicedoor.garage;

import com.voicedoor.VoiceDoorMod;
import com.voicedoor.api.VoiceApiClient;
import com.voicedoor.config.VoiceDoorConfig;
import com.voicedoor.network.ModNetwork;
import com.voicedoor.network.S2CStatusPacket;
import com.voicedoor.voice.VoiceChatPlugin;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Event handler untuk mengintercept klik pada Macaw's Garage Door dan menjalankan verifikasi suara.
 */
@Mod.EventBusSubscriber(modid = VoiceDoorMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class GarageDoorHandler {

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        BlockPos clickedPos = event.getPos();
        Level level = event.getLevel();
        BlockState state = level.getBlockState(clickedPos);

        if (!GarageDoorHelper.isGarageDoor(state.getBlock())) {
            return;
        }

        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        BlockPos clickedTop = GarageDoorHelper.findTopPos(level, clickedPos);
        GarageDoorData data = GarageDoorData.get(serverLevel);
        // Rangkaian pintu yang bergandengan = satu pintu; kunci salah satu kolom berlaku untuk semua
        GarageDoorData.Entry entry = GarageDoorHelper.findLock(serverLevel, clickedTop);

        if (entry == null) {
            // Tidak ada kolom yang terkunci, biarkan Macaw's Doors menanganinya secara normal
            return;
        }
        BlockPos topPos = entry.pos;

        // Intercept interaksi agar pintu tidak langsung terbuka secara manual
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        // Jika pintu sedang terbuka, klik kanan akan menutup pintu langsung
        if (GarageDoorHelper.isOpen(level, clickedTop) || GarageDoorHelper.isOpen(level, topPos)) {
            Set<BlockPos> linked = GarageDoorHelper.setDoorOpen(serverLevel, topPos, false);
            for (BlockPos pos : linked) {
                GarageDoorData.Entry linkedEntry = data.getEntry(pos);
                if (linkedEntry != null) linkedEntry.openTicks = 0;
            }
            entry.openTicks = 0;
            data.setDirty();
            player.sendSystemMessage(Component.translatable("message.voicedoor.door_closed"));
            return;
        }

        // Buka GUI Screen interaktif di layar pemain
        ModNetwork.sendTo(player, new com.voicedoor.network.S2COpenVoiceScreenPacket(
                topPos, true, entry.getVoiceQueryString(), ""));
    }

    public static void startSessionFromGui(ServerPlayer player, BlockPos topPos) {
        if (!(player.level() instanceof ServerLevel serverLevel)) return;
        GarageDoorData data = GarageDoorData.get(serverLevel);
        GarageDoorData.Entry entry = data.getEntry(topPos);
        if (entry == null || !entry.hasOwner()) return;

        handleVerification(player, serverLevel, topPos, entry, true);
    }

    private static void handleVerification(ServerPlayer player, ServerLevel level, BlockPos topPos,
                                           GarageDoorData.Entry entry, boolean fromGui) {
        UUID playerUUID = player.getUUID();

        int cooldown = entry.getCooldown(playerUUID);
        if (cooldown > 0) {
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

        entry.setCooldown(playerUUID, VoiceDoorConfig.cooldownTicks());

        if (!VoiceDoorConfig.requireChallenge()) {
            beginRecording(player, level, topPos, entry, "", "", fromGui);
            return;
        }

        // Minta challenge sekali-pakai dari Python voice server
        String member = entry.getVoiceQueryString();
        boolean queued = VoiceApiClient.tryPostJsonAsync(
                "/challenge",
                "{\"member\":\"" + escapeJson(member) + "\"}",
                response -> level.getServer().execute(() -> {
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
                    beginRecording(player, level, topPos, entry, challengeId, phrase, fromGui);
                }));

        if (!queued) {
            player.sendSystemMessage(Component.translatable("message.voicedoor.server_busy"));
            entry.cooldowns.remove(playerUUID);
        }
    }

    private static void beginRecording(ServerPlayer player, ServerLevel level, BlockPos topPos,
                                       GarageDoorData.Entry entry, String challengeId, String phrase, boolean fromGui) {
        int maxDurationMs = fromGui ? 20000 : VoiceDoorConfig.recordingMillis();
        VoiceChatPlugin.startCustomSession(player, level, topPos, false, challengeId, maxDurationMs,
                (p, wav, lvl, pos, chall, reg) -> onVoiceRecorded(p, wav, (ServerLevel) lvl, pos, chall, entry));

        int seconds = maxDurationMs / 1000;
        if (phrase.isEmpty()) {
            player.sendSystemMessage(Component.translatable(
                    "message.voicedoor.speak_now", seconds));
        } else {
            player.sendSystemMessage(Component.translatable(
                    "message.voicedoor.speak_phrase", phrase, seconds));
        }
        ModNetwork.sendTo(player, S2CStatusPacket.recordingStart(phrase, seconds));
    }

    private static void onVoiceRecorded(ServerPlayer player, byte[] wavBytes, ServerLevel level,
                                        BlockPos topPos, String challengeId, GarageDoorData.Entry entry) {
        ModNetwork.sendTo(player, S2CStatusPacket.recordingStop());
        player.sendSystemMessage(Component.translatable("message.voicedoor.verifying"));

        String memberQuery = entry.getVoiceQueryString();
        VoiceApiClient.MultipartBody body = new VoiceApiClient.MultipartBody()
                .field("member", memberQuery)
                .field("expected_speaker", memberQuery)
                .field("threshold", String.format(Locale.ROOT, "%.4f", VoiceDoorConfig.threshold()));
        if (!challengeId.isEmpty()) {
            body.field("challenge_id", challengeId);
        }
        body.file("audio", "voice.wav", "audio/wav", wavBytes);

        boolean queued = VoiceApiClient.tryPostFormAsync("/verify", body, response -> level.getServer().execute(() -> {
            if (!player.isAlive() || player.hasDisconnected()) return;

            boolean accepted = response.isSuccess() && response.optBoolean("accepted",
                    response.optBoolean("is_owner", false));
            double similarity = response.optDouble("similarity", response.optDouble("confidence", 0.0));
            boolean voiceOk = response.has("voice_ok")
                    ? response.optBoolean("voice_ok", false)
                    : (similarity >= VoiceDoorConfig.threshold());
            boolean passwordOk = response.optBoolean("password_ok", true);
            String detectedSpeaker = response.optString("detected_speaker",
                    response.optString("speaker", entry.ownerName));
            String transcript = response.optString("transcript", "");

            if (VoiceDoorConfig.logVerificationDetails()) {
                VoiceDoorMod.LOGGER.info(
                        "[VoiceDoor-Garage] {} di {} -> accepted={} voice_ok={} password_ok={} similarity={} speaker={} transcript=\"{}\" status={}",
                        player.getName().getString(), topPos.toShortString(),
                        accepted, voiceOk, passwordOk,
                        String.format(Locale.ROOT, "%.4f", similarity),
                        detectedSpeaker, transcript, response.statusCode());
            }

            if (accepted) {
                String matchedSpeaker = response.optString("member", entry.ownerName);
                player.sendSystemMessage(Component.translatable(
                        "message.voicedoor.accepted",
                        matchedSpeaker,
                        String.format(Locale.ROOT, "%.0f", similarity * 100)));
                if (transcript != null && !transcript.trim().isEmpty()) {
                    player.sendSystemMessage(Component.literal("  [Terdengar: \"" + transcript + "\"]")
                            .withStyle(ChatFormatting.AQUA));
                }
                ModNetwork.sendTo(player, S2CStatusPacket.verificationResult(
                        true, similarity, detectedSpeaker, transcript, voiceOk, passwordOk, ""));

                // Buka pintu garasi Macaw's
                // Buka pintu beserta pintu bergandengan yang tidak terkunci / kuncinya sama
                GarageDoorData data = GarageDoorData.get(level);
                for (BlockPos pos : GarageDoorHelper.setDoorOpen(level, topPos, true)) {
                    GarageDoorData.Entry linkedEntry = data.getEntry(pos);
                    if (linkedEntry != null && linkedEntry.hasOwner()) {
                        linkedEntry.openTicks = VoiceDoorConfig.autoCloseTicks();
                    }
                }
                entry.openTicks = VoiceDoorConfig.autoCloseTicks();
                data.setDirty();
            } else {
                String reason = response.isSuccess()
                        ? com.voicedoor.blockentity.VoiceDoorBlockEntity.buildRejectionReason(similarity, voiceOk, passwordOk, transcript)
                        : response.errorMessage();
                player.sendSystemMessage(Component.translatable("message.voicedoor.rejected", reason));
                if (transcript != null && !transcript.trim().isEmpty()) {
                    player.sendSystemMessage(Component.literal("  [Terdengar: \"" + transcript + "\"]")
                            .withStyle(ChatFormatting.YELLOW));
                }
                ModNetwork.sendTo(player, S2CStatusPacket.verificationResult(
                        false, similarity, detectedSpeaker, transcript, voiceOk, passwordOk, reason));
            }
        }));

        if (!queued) {
            player.sendSystemMessage(Component.translatable("message.voicedoor.server_busy"));
        }
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;

        GarageDoorData data = GarageDoorData.get(serverLevel);
        boolean dirty = false;

        for (GarageDoorData.Entry entry : data.getAllDoors().values()) {
            if (entry.openTicks > 0) {
                entry.openTicks--;
                if (entry.openTicks == 0) {
                    GarageDoorHelper.setDoorOpen(serverLevel, entry.pos, false);
                    dirty = true;
                }
            } else if (entry.hasOwner() && serverLevel.isLoaded(entry.pos)
                    && GarageDoorHelper.isGarageDoor(serverLevel.getBlockState(entry.pos).getBlock())
                    && GarageDoorHelper.isOpen(serverLevel, entry.pos)) {
                // Pintu terkunci terbuka tanpa verifikasi suara (misal redstone, yang lewat
                // toggleDoor Macaw's ikut membuka kolom ini) -> tutup lagi seluruh rangkaian
                GarageDoorHelper.setDoorOpen(serverLevel, entry.pos, false);
            }

            if (!entry.cooldowns.isEmpty()) {
                entry.cooldowns.entrySet().removeIf(e -> {
                    int rem = e.getValue() - 1;
                    e.setValue(rem);
                    return rem <= 0;
                });
            }
        }

        if (dirty) {
            data.setDirty();
        }
    }

    private static String escapeJson(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
