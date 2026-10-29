package com.voicedoor.client.gui;

import com.voicedoor.network.C2SVoiceActionPacket;
import com.voicedoor.network.ModNetwork;
import com.voicedoor.network.S2CStatusPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * GUI untuk merekam sample registrasi suara dengan tombol mulai/berhenti.
 *
 * <p>Dibanding {@code /voicedoor register} lama yang selalu merekam {@code recordingSeconds}
 * (default 4 detik), di sini pemain menentukan sendiri kapan berhenti, sampai batas
 * {@code maxSeconds}. Klip yang lebih panjang menghasilkan voiceprint yang lebih stabil.
 * Setelah satu sample tersimpan, pemain bisa langsung merekam sample berikutnya.
 */
@OnlyIn(Dist.CLIENT)
public class VoiceRegisterScreen extends Screen {

    /** Di bawah ini server menolak klipnya ("Minimal 1 detik"), jadi tombol berhenti dikunci. */
    private static final int MIN_STOP_SECONDS = 2;
    /** Durasi yang disarankan per sample; timer berubah hijau setelah ini. */
    private static final int GOOD_SECONDS = 6;
    /** Jumlah sample yang disarankan untuk satu voiceprint. */
    private static final int RECOMMENDED_SAMPLES = 10;
    /** Kalau server tidak menjawab selama ini, GUI berhenti menunggu. */
    private static final int PROCESSING_TIMEOUT_TICKS = 20 * 30;

    private enum State {
        IDLE,
        RECORDING,
        PROCESSING,
        SAVED,
        FAILURE
    }

    private static final int BOX_WIDTH = 260;
    private static final int BOX_HEIGHT = 216;

    private final BlockPos doorPos;
    private final String memberName;
    private final int maxSeconds;

    private State state = State.IDLE;
    private String statusMessage = "";
    private int savedSamples = -1;
    private long recordingStartTime = 0;
    private int recordingSecondsElapsed = 0;
    private int processingTicks = 0;

    private Button recordButton;

    // Real-time audio volume visualizer
    private float targetAudioLevel = 0.0f;
    private float smoothedAudioLevel = 0.0f;
    private long lastAudioPacketTime = 0;

    public void onAudioLevel(float level) {
        this.targetAudioLevel = level;
        this.lastAudioPacketTime = System.currentTimeMillis();
    }

    public VoiceRegisterScreen(BlockPos doorPos, String memberName, int maxSeconds) {
        super(Component.literal("VoiceDoor Register"));
        this.doorPos = doorPos;
        this.memberName = memberName == null ? "" : memberName;
        this.maxSeconds = Math.max(MIN_STOP_SECONDS + 1, maxSeconds);
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int boxY = this.height / 2 - BOX_HEIGHT / 2;

        this.recordButton = Button.builder(Component.empty(), btn -> onRecordButtonClicked())
                .bounds(centerX - 80, boxY + BOX_HEIGHT - 58, 160, 24)
                .build();
        this.addRenderableWidget(this.recordButton);

        this.addRenderableWidget(Button.builder(Component.literal("Tutup"), btn -> this.onClose())
                .bounds(centerX - 50, boxY + BOX_HEIGHT - 28, 100, 20)
                .build());

        updateButtonState();
    }

    private void onRecordButtonClicked() {
        switch (state) {
            case IDLE, SAVED, FAILURE -> {
                state = State.RECORDING;
                recordingStartTime = System.currentTimeMillis();
                recordingSecondsElapsed = 0;
                statusMessage = "";
                ModNetwork.sendToServer(new C2SVoiceActionPacket(doorPos, false,
                        C2SVoiceActionPacket.Action.REGISTER_START));
            }
            case RECORDING -> {
                enterProcessing();
                ModNetwork.sendToServer(new C2SVoiceActionPacket(doorPos, false,
                        C2SVoiceActionPacket.Action.STOP));
            }
            case PROCESSING -> {
            }
        }
        updateButtonState();
    }

    private void enterProcessing() {
        state = State.PROCESSING;
        processingTicks = 0;
    }

    private void updateButtonState() {
        if (recordButton == null) return;

        switch (state) {
            case IDLE -> {
                recordButton.active = true;
                recordButton.setMessage(Component.literal("⏺ Mulai Rekam").withStyle(ChatFormatting.GREEN));
            }
            case RECORDING -> {
                recordButton.active = recordingSecondsElapsed >= MIN_STOP_SECONDS;
                recordButton.setMessage(Component.literal("⏹ Berhenti & Simpan")
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            }
            case PROCESSING -> {
                recordButton.active = false;
                recordButton.setMessage(Component.literal("Menyimpan...").withStyle(ChatFormatting.GRAY));
            }
            case SAVED -> {
                recordButton.active = true;
                recordButton.setMessage(Component.literal("⏺ Rekam Sample Lagi").withStyle(ChatFormatting.GREEN));
            }
            case FAILURE -> {
                recordButton.active = true;
                recordButton.setMessage(Component.literal("Coba Lagi").withStyle(ChatFormatting.YELLOW));
            }
        }
    }

    public void onServerStatus(S2CStatusPacket packet) {
        switch (packet.getStatus()) {
            case RECORDING_START -> {
                if (state != State.RECORDING) {
                    state = State.RECORDING;
                    recordingStartTime = System.currentTimeMillis();
                }
            }
            // Juga datang saat server berhenti sendiri karena batas maxSeconds tercapai.
            case RECORDING_STOP -> {
                if (state == State.RECORDING) {
                    enterProcessing();
                }
            }
            case REGISTER_OK -> {
                state = State.SAVED;
                savedSamples = packet.getSeconds();
                statusMessage = packet.getMessage();
            }
            case REGISTER_FAIL -> {
                state = State.FAILURE;
                statusMessage = packet.getMessage().isEmpty() ? "Gagal menyimpan sample." : packet.getMessage();
            }
            default -> {
            }
        }
        updateButtonState();
    }

    @Override
    public void tick() {
        super.tick();

        if (state == State.RECORDING) {
            if (System.currentTimeMillis() - lastAudioPacketTime > 120) {
                targetAudioLevel = Math.max(0.0f, targetAudioLevel - 0.15f);
            }
            smoothedAudioLevel += (targetAudioLevel - smoothedAudioLevel) * 0.4f;

            if (recordingStartTime > 0) {
                int elapsed = (int) ((System.currentTimeMillis() - recordingStartTime) / 1000);
                if (elapsed != recordingSecondsElapsed) {
                    recordingSecondsElapsed = elapsed;
                    updateButtonState();
                }
            }
        } else {
            smoothedAudioLevel = 0.0f;
            targetAudioLevel = 0.0f;
            if (state == State.PROCESSING && ++processingTicks >= PROCESSING_TIMEOUT_TICKS) {
                state = State.FAILURE;
                statusMessage = "Server tidak menjawab. Cek chat atau server Python.";
                updateButtonState();
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        int centerX = this.width / 2;
        int boxX = centerX - BOX_WIDTH / 2;
        int boxY = this.height / 2 - BOX_HEIGHT / 2;

        graphics.fill(boxX, boxY, boxX + BOX_WIDTH, boxY + BOX_HEIGHT, 0xD0101824);
        graphics.renderOutline(boxX, boxY, BOX_WIDTH, BOX_HEIGHT, 0xFF4A90E2);

        graphics.drawCenteredString(this.font, Component.literal("Daftarkan Suara")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), centerX, boxY + 10, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, Component.literal("Member: " + memberName)
                .withStyle(ChatFormatting.WHITE), centerX, boxY + 24, 0xFFFFFFFF);

        String samples = savedSamples < 0 ? "-" : String.valueOf(savedSamples);
        int samplesColor = savedSamples >= RECOMMENDED_SAMPLES ? 0xFF55FF55 : 0xFFB0B0B0;
        graphics.drawCenteredString(this.font, Component.literal(
                "Sample tersimpan: " + samples + " (disarankan " + RECOMMENDED_SAMPLES + "+)"),
                centerX, boxY + 36, samplesColor);

        int y = boxY + 54;
        switch (state) {
            case IDLE -> drawWrapped(graphics, Component.literal(
                    "Bicara alami " + GOOD_SECONDS + "-15 detik per sample, kalimat bebas. "
                            + "Pakai mic dan ruangan yang sama seperti saat membuka pintu."),
                    centerX, y, 0xFFE0E0E0);
            case RECORDING -> renderRecording(graphics, centerX, boxX, y);
            case PROCESSING -> graphics.drawCenteredString(this.font,
                    Component.literal("Mengirim & membuat voiceprint..."), centerX, y, 0xFF55FFFF);
            case SAVED -> drawWrapped(graphics, Component.literal("✓ " + statusMessage), centerX, y, 0xFF55FF55);
            case FAILURE -> drawWrapped(graphics, Component.literal(statusMessage), centerX, y, 0xFFFF5555);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderRecording(GuiGraphics graphics, int centerX, int boxX, int y) {
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        boolean longEnough = recordingSecondsElapsed >= GOOD_SECONDS;
        int color = longEnough ? 0xFF55FF55 : 0xFFFF5555;
        graphics.drawCenteredString(this.font, Component.literal(
                (blink ? "● " : "○ ") + String.format("Merekam: %02d / %02d detik", recordingSecondsElapsed, maxSeconds))
                .withStyle(ChatFormatting.BOLD), centerX, y, color);

        // Progress bar menuju batas maksimum, dengan penanda durasi yang disarankan.
        int barX = boxX + 20;
        int barWidth = BOX_WIDTH - 40;
        int barY = y + 14;
        float progress = Math.min(1f, (System.currentTimeMillis() - recordingStartTime) / (maxSeconds * 1000f));
        graphics.fill(barX, barY, barX + barWidth, barY + 6, 0xFF2A3340);
        graphics.fill(barX, barY, barX + (int) (barWidth * progress), barY + 6, color);
        int markX = barX + barWidth * GOOD_SECONDS / maxSeconds;
        graphics.fill(markX, barY - 2, markX + 1, barY + 8, 0xFFFFFFFF);

        String hint = longEnough ? "Cukup. Berhenti kapan saja." : "Terus bicara...";
        graphics.drawCenteredString(this.font, Component.literal(hint), centerX, barY + 10, 0xFFB0B0B0);

        // Sound Meter Real-Time
        int meterWidth = 140;
        int meterHeight = 6;
        int meterX = centerX - (meterWidth / 2);
        int meterY = barY + 22;

        boolean hasAudio = smoothedAudioLevel > 0.05f;
        Component statusComp = hasAudio
                ? Component.literal("\uD83D\uDD0A Suara Masuk: " + (int)(smoothedAudioLevel * 100) + "%").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                : Component.literal("\uD83D\uDD07 Menunggu Suara...").withStyle(ChatFormatting.GRAY);
        graphics.drawCenteredString(this.font, statusComp, centerX, meterY, 0xFFFFFFFF);

        int barVuY = meterY + 10;
        graphics.fill(meterX - 1, barVuY - 1, meterX + meterWidth + 1, barVuY + meterHeight + 1, 0xFF1E293B);
        graphics.renderOutline(meterX - 1, barVuY - 1, meterWidth + 2, meterHeight + 2, 0xFF475569);

        int fillWidth = (int) (meterWidth * Math.min(1.0f, smoothedAudioLevel));
        if (fillWidth > 0) {
            int barColor = smoothedAudioLevel < 0.6f ? 0xFF22C55E : (smoothedAudioLevel < 0.85f ? 0xFFF59E0B : 0xFFEF4444);
            graphics.fill(meterX, barVuY, meterX + fillWidth, barVuY + meterHeight, barColor);
        }
    }

    private void drawWrapped(GuiGraphics graphics, Component text, int centerX, int y, int color) {
        List<FormattedCharSequence> lines = this.font.split(text, BOX_WIDTH - 24);
        for (int i = 0; i < Math.min(lines.size(), 5); i++) {
            graphics.drawCenteredString(this.font, lines.get(i), centerX, y + i * 10, color);
        }
    }

    @Override
    public void onClose() {
        if (state == State.RECORDING) {
            ModNetwork.sendToServer(new C2SVoiceActionPacket(doorPos, false, C2SVoiceActionPacket.Action.CANCEL));
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
