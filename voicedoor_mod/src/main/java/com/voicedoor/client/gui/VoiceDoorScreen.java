package com.voicedoor.client.gui;

import com.voicedoor.config.VoiceDoorConfig;
import com.voicedoor.network.C2SVoiceActionPacket;
import com.voicedoor.network.ModNetwork;
import com.voicedoor.network.S2CStatusPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * GUI interaktif di Minecraft untuk merekam dan menghentikan rekaman suara secara manual.
 * Menampilkan status deteksi lengkap: suara + password.
 */
@OnlyIn(Dist.CLIENT)
public class VoiceDoorScreen extends Screen {

    public enum State {
        IDLE,
        RECORDING,
        PROCESSING,
        SUCCESS,
        FAILURE
    }

    private final BlockPos doorPos;
    private final boolean isGarage;
    private final String allowedVoices;
    private String phrase;

    private State state = State.IDLE;
    private String statusMessage = "";
    private long recordingStartTime = 0;
    private int recordingSecondsElapsed = 0;
    private int autoCloseTicks = 0;

    // Detail deteksi dari server
    private String detectedSpeaker = "";
    private String transcript = "";
    private boolean voiceOk = false;
    private boolean passwordOk = false;

    private Button recordButton;
    private Button cancelButton;

    // Real-time audio volume visualizer
    private float targetAudioLevel = 0.0f;
    private float smoothedAudioLevel = 0.0f;
    private long lastAudioPacketTime = 0;

    public VoiceDoorScreen(BlockPos doorPos, boolean isGarage, String allowedVoices, String phrase) {
        super(Component.literal("VoiceDoor Security"));
        this.doorPos = doorPos;
        this.isGarage = isGarage;
        this.allowedVoices = allowedVoices;
        this.phrase = phrase == null ? "" : phrase;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // Tombol Utama: Mulai / Berhenti Rekam
        this.recordButton = Button.builder(Component.literal("Mulai Rekam"), btn -> onRecordButtonClicked())
                .bounds(centerX - 80, centerY + 30, 160, 24)
                .build();
        this.addRenderableWidget(this.recordButton);

        // Tombol Batal / Tutup
        this.cancelButton = Button.builder(Component.literal("Tutup"), btn -> this.onClose())
                .bounds(centerX - 50, centerY + 60, 100, 20)
                .build();
        this.addRenderableWidget(this.cancelButton);

        updateButtonState();
    }

    private void repositionWidgets() {
        if (recordButton == null || cancelButton == null) return;
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        boolean showDetection = (state == State.SUCCESS || state == State.FAILURE);
        int boxHeight = showDetection ? 260 : (state == State.RECORDING ? 240 : 190);
        int boxY = centerY - (boxHeight / 2);

        recordButton.setX(centerX - 80);
        recordButton.setY(boxY + boxHeight - 56);
        cancelButton.setX(centerX - 50);
        cancelButton.setY(boxY + boxHeight - 28);
    }

    public void onAudioLevel(float level) {
        this.targetAudioLevel = level;
        this.lastAudioPacketTime = System.currentTimeMillis();
    }

    private void onRecordButtonClicked() {
        if (state == State.IDLE || state == State.FAILURE) {
            // Reset detection detail saat mulai baru
            detectedSpeaker = "";
            transcript = "";
            voiceOk = false;
            passwordOk = false;

            // Mulai Merekam
            state = State.RECORDING;
            recordingStartTime = System.currentTimeMillis();
            recordingSecondsElapsed = 0;
            statusMessage = "Merekam suara... Silakan berbicara!";
            ModNetwork.sendToServer(new C2SVoiceActionPacket(doorPos, isGarage, C2SVoiceActionPacket.Action.START));
            updateButtonState();
        } else if (state == State.RECORDING) {
            // Berhenti Merekam & Kirim
            state = State.PROCESSING;
            statusMessage = "Memproses & memverifikasi suara...";
            ModNetwork.sendToServer(new C2SVoiceActionPacket(doorPos, isGarage, C2SVoiceActionPacket.Action.STOP));
            updateButtonState();
        }
    }

    private void updateButtonState() {
        if (recordButton == null) return;

        repositionWidgets();

        switch (state) {
            case IDLE -> {
                recordButton.active = true;
                recordButton.setMessage(Component.literal("\u23FA Mulai Rekam").withStyle(ChatFormatting.GREEN));
            }
            case RECORDING -> {
                recordButton.active = true;
                recordButton.setMessage(Component.literal("\u23F9 Berhenti & Kirim").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            }
            case PROCESSING -> {
                recordButton.active = false;
                recordButton.setMessage(Component.literal("Memverifikasi...").withStyle(ChatFormatting.GRAY));
            }
            case SUCCESS -> {
                recordButton.active = false;
                recordButton.setMessage(Component.literal("\u2713 Pintu Terbuka!").withStyle(ChatFormatting.GREEN));
            }
            case FAILURE -> {
                recordButton.active = true;
                recordButton.setMessage(Component.literal("\u23FA Coba Lagi").withStyle(ChatFormatting.YELLOW));
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
                if (!packet.getMessage().isEmpty()) {
                    this.phrase = packet.getMessage();
                }
                statusMessage = phrase.isEmpty() ? "Bicaralah sekarang..." : "Ucapkan frasa di bawah:";
                updateButtonState();
            }
            case RECORDING_STOP -> {
                state = State.PROCESSING;
                statusMessage = "Memproses audio...";
                updateButtonState();
            }
            case VERIFICATION_OK -> {
                state = State.SUCCESS;
                detectedSpeaker = packet.getDetectedSpeaker();
                transcript = packet.getTranscript();
                voiceOk = packet.isVoiceOk();
                passwordOk = packet.isPasswordOk();
                statusMessage = "Verifikasi Berhasil! (Kemiripan: " + packet.getMessage() + "%)";
                autoCloseTicks = 60; // ~3 detik lalu tutup otomatis
                updateButtonState();
            }
            case VERIFICATION_FAIL -> {
                state = State.FAILURE;
                detectedSpeaker = packet.getDetectedSpeaker();
                transcript = packet.getTranscript();
                voiceOk = packet.isVoiceOk();
                passwordOk = packet.isPasswordOk();
                statusMessage = "Ditolak: " + (packet.getMessage().isEmpty() ? "Suara tidak cocok" : packet.getMessage());
                updateButtonState();
            }
        }
    }

    @Override
    public void tick() {
        super.tick();

        if (state == State.RECORDING) {
            if (recordingStartTime > 0) {
                recordingSecondsElapsed = (int) ((System.currentTimeMillis() - recordingStartTime) / 1000);
            }
            if (System.currentTimeMillis() - lastAudioPacketTime > 120) {
                targetAudioLevel = Math.max(0.0f, targetAudioLevel - 0.15f);
            }
            smoothedAudioLevel += (targetAudioLevel - smoothedAudioLevel) * 0.4f;
        } else {
            smoothedAudioLevel = 0.0f;
            targetAudioLevel = 0.0f;
        }

        if (autoCloseTicks > 0) {
            autoCloseTicks--;
            if (autoCloseTicks == 0) {
                this.onClose();
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Background dim
        this.renderBackground(graphics);

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        boolean showDetection = (state == State.SUCCESS || state == State.FAILURE);
        int boxWidth = 300;
        int boxHeight = showDetection ? 260 : (state == State.RECORDING ? 240 : 190);
        int boxX = centerX - (boxWidth / 2);
        int boxY = centerY - (boxHeight / 2);

        // Kotak panel tengah dengan outline
        int borderColor = state == State.SUCCESS ? 0xFF16A34A :
                           state == State.FAILURE ? 0xFFDC2626 : 0xFF4A90E2;
        graphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xD0101824);
        graphics.renderOutline(boxX, boxY, boxWidth, boxHeight, borderColor);

        // Header Title
        String titleStr = isGarage ? "Voice Garage Door" : "Voice Security Door";
        graphics.drawCenteredString(this.font, Component.literal(titleStr).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                centerX, boxY + 10, 0xFFFFFFFF);

        // Subheader Allowed voices
        String voices = allowedVoices.isEmpty() ? "-" : allowedVoices;
        graphics.drawCenteredString(this.font, Component.literal("Suara Terdaftar: " + voices).withStyle(ChatFormatting.DARK_GRAY),
                centerX, boxY + 24, 0xFFB0B0B0);

        // Password hint
        String password = VoiceDoorConfig.doorPassword();
        int currY = boxY + 38;
        if (!password.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.literal("\uD83D\uDD11 Password: \"" + password + "\"").withStyle(ChatFormatting.AQUA),
                    centerX, currY, 0xFF55FFFF);
            currY += 14;
        }

        // Frasa jika ada
        if (!phrase.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.literal("Frasa: \"" + phrase + "\"")
                    .withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC), centerX, currY, 0xFFFFFF55);
            currY += 14;
        }

        // Status text / Timer
        int statusY = currY + 4;
        if (state == State.RECORDING) {
            boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
            Component timerComp = Component.literal((blink ? "\u25CF " : "\u25CB ") + String.format("Merekam: %02d detik", recordingSecondsElapsed))
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
            graphics.drawCenteredString(this.font, timerComp, centerX, statusY, 0xFFFF5555);

            // Visualizer Suara Real-Time
            renderAudioVisualizer(graphics, centerX, statusY + 12, smoothedAudioLevel);
        } else if (state == State.PROCESSING) {
            graphics.drawCenteredString(this.font, Component.literal("Memverifikasi dengan model AI...").withStyle(ChatFormatting.AQUA),
                    centerX, statusY, 0xFF55FFFF);
        } else if (state == State.IDLE) {
            graphics.drawCenteredString(this.font, Component.literal("Tekan Mulai untuk berbicara.")
                    .withStyle(ChatFormatting.WHITE), centerX, statusY, 0xFFE0E0E0);
            if (!password.isEmpty()) {
                graphics.drawCenteredString(this.font, Component.literal("Ucapkan password lalu tekan Berhenti.")
                        .withStyle(ChatFormatting.GRAY), centerX, statusY + 12, 0xFFA0A0A0);
            }
        }

        // ---- HASIL DETEKSI (setelah verifikasi selesai) ----
        if (showDetection) {
            int detY = statusY + 4;

            // Garis pemisah
            graphics.fill(boxX + 10, detY, boxX + boxWidth - 10, detY + 1, 0x40FFFFFF);
            detY += 6;

            // Header deteksi
            graphics.drawCenteredString(this.font,
                    Component.literal("\u2550\u2550\u2550 Hasil Deteksi \u2550\u2550\u2550").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD),
                    centerX, detY, 0xFFFFFFFF);
            detY += 14;

            // 1. Deteksi Suara
            String voiceIcon = voiceOk ? "\u2713" : "\u2717";
            ChatFormatting voiceColor = voiceOk ? ChatFormatting.GREEN : ChatFormatting.RED;
            String speakerInfo = detectedSpeaker.isEmpty() ? "Tidak dikenali" : detectedSpeaker;
            Component voiceLine = Component.literal(voiceIcon + " Suara: ").withStyle(voiceColor, ChatFormatting.BOLD)
                    .append(Component.literal(voiceOk ? speakerInfo + " \u2014 COCOK" : speakerInfo + " \u2014 TIDAK COCOK")
                            .withStyle(voiceColor));
            graphics.drawCenteredString(this.font, voiceLine, centerX, detY, 0xFFFFFFFF);
            detY += 14;

            // 2. Deteksi Password
            if (!password.isEmpty()) {
                String passIcon = passwordOk ? "\u2713" : "\u2717";
                ChatFormatting passColor = passwordOk ? ChatFormatting.GREEN : ChatFormatting.RED;
                Component passLine = Component.literal(passIcon + " Password: ").withStyle(passColor, ChatFormatting.BOLD)
                        .append(Component.literal(passwordOk ? "BENAR" : "SALAH").withStyle(passColor));
                graphics.drawCenteredString(this.font, passLine, centerX, detY, 0xFFFFFFFF);
                detY += 14;
            }

            // 3. Apa yang terdengar (transcript)
            String displayTranscript = (transcript != null && !transcript.trim().isEmpty())
                    ? "\"" + (transcript.length() > 32 ? transcript.substring(0, 32) + "..." : transcript) + "\""
                    : "(tidak terdeteksi / hening)";
            Component transcriptLine = Component.literal("\uD83D\uDDE3\uFE0F Terdengar: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(displayTranscript).withStyle(ChatFormatting.WHITE));
            graphics.drawCenteredString(this.font, transcriptLine, centerX, detY, 0xFFFFFFFF);
            detY += 14;

            // 4. Verdict besar
            detY += 2;
            if (state == State.SUCCESS) {
                graphics.drawCenteredString(this.font,
                        Component.literal("\uD83D\uDD13 PINTU TERBUKA").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                        centerX, detY, 0xFF55FF55);
            } else {
                graphics.drawCenteredString(this.font,
                        Component.literal("\uD83D\uDD12 AKSES DITOLAK").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                        centerX, detY, 0xFFFF5555);
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderAudioVisualizer(GuiGraphics graphics, int centerX, int y, float level) {
        int meterWidth = 160;
        int meterHeight = 7;
        int meterX = centerX - (meterWidth / 2);

        // 1. Status Teks Mikrofon
        boolean hasAudio = level > 0.05f;
        Component statusComp = hasAudio
                ? Component.literal("\uD83D\uDD0A Suara Masuk: " + (int)(level * 100) + "%").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                : Component.literal("\uD83D\uDD07 Menunggu Suara... (Bicaralah)").withStyle(ChatFormatting.GRAY);
        graphics.drawCenteredString(this.font, statusComp, centerX, y, 0xFFFFFFFF);

        // 2. VU Meter Bar
        int barY = y + 11;
        graphics.fill(meterX - 1, barY - 1, meterX + meterWidth + 1, barY + meterHeight + 1, 0xFF1E293B);
        graphics.renderOutline(meterX - 1, barY - 1, meterWidth + 2, meterHeight + 2, 0xFF475569);

        int fillWidth = (int) (meterWidth * Math.min(1.0f, level));
        if (fillWidth > 0) {
            int barColor = level < 0.6f ? 0xFF22C55E : (level < 0.85f ? 0xFFF59E0B : 0xFFEF4444);
            graphics.fill(meterX, barY, meterX + fillWidth, barY + meterHeight, barColor);
        }

        // 3. Multi-bar Animated Equalizer (13 bars)
        int eqY = barY + meterHeight + 4;
        int barCount = 13;
        int singleBarWidth = 4;
        int gap = 3;
        int totalEqWidth = barCount * singleBarWidth + (barCount - 1) * gap;
        int startEqX = centerX - (totalEqWidth / 2);
        long time = System.currentTimeMillis();

        for (int i = 0; i < barCount; i++) {
            int bx = startEqX + i * (singleBarWidth + gap);
            int maxH = 14;
            int bh = 2; // baseline
            if (hasAudio) {
                double wave = Math.abs(Math.sin((time / 110.0) + (i * 0.55)));
                bh = Math.max(2, (int) (level * maxH * wave * 1.3));
                if (bh > maxH) bh = maxH;
            }
            int by = eqY + (maxH - bh);
            int eqColor = hasAudio ? 0xFF38BDF8 : 0xFF64748B;
            graphics.fill(bx, by, bx + singleBarWidth, eqY + maxH, eqColor);
        }
    }

    @Override
    public void onClose() {
        if (state == State.RECORDING) {
            ModNetwork.sendToServer(new C2SVoiceActionPacket(doorPos, isGarage, C2SVoiceActionPacket.Action.CANCEL));
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
