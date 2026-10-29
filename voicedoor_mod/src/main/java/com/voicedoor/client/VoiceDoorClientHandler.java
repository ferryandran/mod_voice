package com.voicedoor.client;

import com.voicedoor.client.gui.VoiceDoorScreen;
import com.voicedoor.client.gui.VoiceRegisterScreen;
import com.voicedoor.network.S2CStatusPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Sisi client: membuka GUI layar verifikasi dan menampilkan status verifikasi.
 *
 * <p>Class ini hanya pernah dimuat di client - dipanggil lewat
 * {@code DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)} dari
 * {@link S2CStatusPacket#handle}, sehingga dedicated server tidak menyentuhnya.
 */
@OnlyIn(Dist.CLIENT)
public final class VoiceDoorClientHandler {

    private VoiceDoorClientHandler() {
    }

    public static void openScreen(BlockPos pos, boolean isGarage, String allowedVoices, String phrase) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new VoiceDoorScreen(pos, isGarage, allowedVoices, phrase));
    }

    public static void openRegisterScreen(BlockPos pos, String memberName, int maxSeconds) {
        Minecraft.getInstance().setScreen(new VoiceRegisterScreen(pos, memberName, maxSeconds));
    }

    public static void onAudioLevel(float level) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof VoiceDoorScreen voiceScreen) {
            voiceScreen.onAudioLevel(level);
        } else if (mc.screen instanceof VoiceRegisterScreen registerScreen) {
            registerScreen.onAudioLevel(level);
        }
    }

    public static void onStatus(S2CStatusPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof VoiceDoorScreen voiceScreen) {
            voiceScreen.onServerStatus(packet);
        } else if (mc.screen instanceof VoiceRegisterScreen registerScreen) {
            registerScreen.onServerStatus(packet);
            // GUI registrasi sudah menampilkan statusnya sendiri.
            return;
        }

        if (mc.gui == null) {
            return;
        }

        Component overlay = switch (packet.getStatus()) {
            case RECORDING_START -> packet.getMessage().isEmpty()
                    ? Component.translatable("overlay.voicedoor.recording", packet.getSeconds())
                        .withStyle(ChatFormatting.AQUA)
                    : Component.translatable("overlay.voicedoor.recording_phrase",
                            packet.getMessage(), packet.getSeconds())
                        .withStyle(ChatFormatting.AQUA);

            case RECORDING_STOP -> Component.translatable("overlay.voicedoor.processing")
                    .withStyle(ChatFormatting.GRAY);

            case VERIFICATION_OK -> Component.translatable("overlay.voicedoor.accepted", packet.getMessage())
                    .withStyle(ChatFormatting.GREEN);

            case VERIFICATION_FAIL -> Component.translatable("overlay.voicedoor.rejected")
                    .withStyle(ChatFormatting.RED);

            case REGISTER_OK -> Component.translatable("overlay.voicedoor.register_ok", packet.getSeconds())
                    .withStyle(ChatFormatting.GREEN);

            case REGISTER_FAIL -> Component.translatable("overlay.voicedoor.register_fail")
                    .withStyle(ChatFormatting.RED);
        };

        mc.gui.setOverlayMessage(overlay, false);
    }
}
