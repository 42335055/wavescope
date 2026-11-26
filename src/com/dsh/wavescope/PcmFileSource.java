package com.dsh.wavescope;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Random-access 16-bit PCM decoded from an imported file. Lets the FFT worker
 * take a new sliding window at the real playback position instead of reusing
 * the decoder's burst-sized pipeline chunks.
 */
final class PcmFileSource {
    final int sampleRate;
    final int channels;
    final long totalFrames;
    private final File file;
    private final RandomAccessFile raf;
    private final Object lock = new Object();
    private byte[] bytes = new byte[0];

    PcmFileSource(File file, int sampleRate, int channels) throws IOException {
        this.file = file;
        this.sampleRate = Math.max(1, sampleRate);
        this.channels = Math.max(1, channels);
        this.raf = new RandomAccessFile(file, "r");
        this.totalFrames = file.length() / (2L * this.channels);
    }

    /** Fills dst with the window ending at positionUs; returns frames read. */
    int readWindow(long positionUs, int frames, short[] dst) throws IOException {
        long end = Math.max(0L, positionUs) * sampleRate / 1_000_000L;
        if (end > totalFrames) end = totalFrames;
        long start = Math.max(0L, end - frames);
        int count = (int) Math.min(frames, end - start);
        int samples = count * channels;
        if (samples <= 0) return 0;
        int need = samples * 2;
        synchronized (lock) {
            if (bytes.length < need) bytes = new byte[need];
            raf.seek(start * channels * 2L);
            raf.readFully(bytes, 0, need);
            for (int i = 0; i < samples; i++) {
                int lo = bytes[i * 2] & 0xFF;
                int hi = bytes[i * 2 + 1];
                dst[i] = (short) (lo | (hi << 8));
            }
        }
        return count;
    }

    void close() {
        try { raf.close(); } catch (IOException ignored) {}
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
