package com.voicedoor.client;

import com.voicedoor.network.S2CStatusPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Sisi client: menampilkan status verifikasi di atas hotbar.
 *
 * <p>Class ini hanya pernah dimuat di client - dipanggil lewat
 * {@code DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)} dari
 * {@link S2CStatusPacket#handle}, sehingga dedicated server tidak menyentuhnya.
 */
@OnlyIn(Dist.CLIENT)
public final class VoiceDoorClientHandler {

    private VoiceDoorClientHandler() {
    }

    public static void onStatus(S2CStatusPacket packet) {
        Minecraft mc = Minecraft.getInstance();
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
        };

        mc.gui.setOverlayMessage(overlay, false);
    }
}
