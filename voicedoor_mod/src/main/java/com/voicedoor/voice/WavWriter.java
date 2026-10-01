package com.voicedoor.voice;

/**
 * Membungkus PCM mentah menjadi WAV (RIFF) 44 byte header.
 *
 * <p>Dipisahkan dari {@link VoiceChatPlugin} supaya bisa diuji sendiri dan supaya
 * penulisan header tidak bercampur dengan pengelolaan sesi. Semua nilai multi-byte
 * little-endian, sesuai spesifikasi RIFF.
 */
public final class WavWriter {

    /** Ukuran header WAV kanonis: RIFF (12) + fmt (24) + data (8). */
    public static final int HEADER_BYTES = 44;

    private WavWriter() {
    }

    /**
     * @param pcmLittleEndian byte PCM yang sudah little-endian
     * @param sampleRate      misal 48000
     * @param channels        1 untuk mono
     * @param bitsPerSample   16
     */
    public static byte[] wrap(byte[] pcmLittleEndian, int sampleRate, int channels, int bitsPerSample) {
        int dataSize = pcmLittleEndian.length;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        byte[] out = new byte[HEADER_BYTES + dataSize];
        int p = 0;

        // Chunk RIFF
        p = ascii(out, p, "RIFF");
        p = int32(out, p, 36 + dataSize);   // ukuran sisa file setelah field ini
        p = ascii(out, p, "WAVE");

        // Sub-chunk fmt
        p = ascii(out, p, "fmt ");
        p = int32(out, p, 16);              // panjang sub-chunk fmt untuk PCM
        p = int16(out, p, 1);               // format 1 = PCM tak terkompresi
        p = int16(out, p, channels);
        p = int32(out, p, sampleRate);
        p = int32(out, p, byteRate);
        p = int16(out, p, blockAlign);
        p = int16(out, p, bitsPerSample);

        // Sub-chunk data
        p = ascii(out, p, "data");
        p = int32(out, p, dataSize);

        System.arraycopy(pcmLittleEndian, 0, out, p, dataSize);
        return out;
    }

    private static int ascii(byte[] out, int pos, String text) {
        for (int i = 0; i < text.length(); i++) {
            out[pos++] = (byte) text.charAt(i);
        }
        return pos;
    }

    private static int int32(byte[] out, int pos, int value) {
        out[pos++] = (byte) (value & 0xFF);
        out[pos++] = (byte) ((value >> 8) & 0xFF);
        out[pos++] = (byte) ((value >> 16) & 0xFF);
        out[pos++] = (byte) ((value >> 24) & 0xFF);
        return pos;
    }

    private static int int16(byte[] out, int pos, int value) {
        out[pos++] = (byte) (value & 0xFF);
        out[pos++] = (byte) ((value >> 8) & 0xFF);
        return pos;
    }
}
