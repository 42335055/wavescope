package com.dsh.wavescope;

import android.os.Process;
import android.os.SystemClock;

/**
 * Instantaneous multi-resolution spectrum. At 16384 points the audible range
 * is split into 10 log-frequency bands with progressively shorter FFT windows,
 * then merged with short crossfades at the band edges.
 */
final class SpectrumWorker implements Runnable {
    static final class Frame {
        final float[] left;
        final float[] right;
        final int bins;
        final int sampleRate;
        final int channels;
        final int fftSize;
        final int generation;
        final float peakDb;
        final float dominantHz;

        Frame(float[] left, float[] right, int bins, int sampleRate, int channels,
              int fftSize, int generation, float peakDb, float dominantHz) {
            this.left = left;
            this.right = right;
            this.bins = bins;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.fftSize = fftSize;
            this.generation = generation;
            this.peakDb = peakDb;
            this.dominantHz = dominantHz;
        }
    }

    // 10 bands, 11 edges. The low end keeps more frequency detail; the high
    // end uses short windows for transients.
    private static final float[] EDGES = {
            0f, 40f, 80f, 160f, 320f, 640f,
            1280f, 2560f, 5120f, 10240f, 20000f
    };
    private static final int[] BAND_N = {
            16384, 8192, 4096, 2048, 1024,
            1024, 1024, 1024, 1024, 1024
    };
    private static final int[] UNIQUE_N = {16384, 8192, 4096, 2048, 1024};

    private static final class Band {
        final int n;
        final int bins;
        final float[] window;
        final float[] re;
        final float[] im;
        final float[] left;
        final float[] right;
        float sum = 1.0f;

        Band(int n) {
            this.n = n;
            this.bins = n / 2;
            this.window = new float[n];
            this.re = new float[n];
            this.im = new float[n];
            this.left = new float[bins + 1];
            this.right = new float[bins + 1];
        }
    }

    private final Object lock = new Object();
    private final Thread thread;
    private volatile boolean running = true;
    private volatile boolean enabled;
    private volatile AudioPipeline pipeline;
    private volatile int fftSize = 4096;
    private volatile Frame latest;
    private volatile PcmFileSource pcmSource;
    private volatile long positionUs;
    private volatile float fps;
    private long fpsStart;
    private int fpsFrames;

    private short[] pcm = new short[0];
    private final Band[] bands = new Band[UNIQUE_N.length];

    SpectrumWorker() {
        thread = new Thread(this, "WaveScope-FFT");
        thread.setDaemon(true);
        thread.start();
    }

    void setPipeline(AudioPipeline pipeline) {
        this.pipeline = pipeline;
        synchronized (lock) {
            latest = null;
        }
    }

    void setFftSize(int size) {
        if (size >= 256 && size != fftSize) {
            fftSize = size;
            synchronized (lock) {
                latest = null;
            }
        }
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    void setPcmSource(PcmFileSource source) {
        pcmSource = source;
        synchronized (lock) {
            latest = null;
        }
    }

    void setPositionUs(long positionUs) {
        this.positionUs = Math.max(0L, positionUs);
    }

    Frame getLatest() {
        return latest;
    }

    float getFps() {
        return fps;
    }

    void stop() {
        running = false;
        thread.interrupt();
        try {
            thread.join(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
        long next = SystemClock.uptimeMillis();
        while (running) {
            long now = SystemClock.uptimeMillis();
            if (!enabled || pipeline == null) {
                next = now;
                sleepQuietly(20L);
                continue;
            }
            try {
                computeOnce();
            } catch (Throwable ignored) {
                // Never let a source/format race kill the worker.
            }
            next += 16L;
            long wait = next - SystemClock.uptimeMillis();
            if (wait < 1L) {
                next = SystemClock.uptimeMillis();
                wait = 1L;
            }
            sleepQuietly(wait);
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Band band(int n) {
        for (int i = 0; i < UNIQUE_N.length; i++) {
            if (UNIQUE_N[i] == n) {
                if (bands[i] == null) {
                    bands[i] = new Band(n);
                    bands[i].sum = buildWindow(bands[i].window);
                }
                return bands[i];
            }
        }
        return null;
    }

    private float buildWindow(float[] w) {
        double sum = 0.0;
        for (int i = 0; i < w.length; i++) {
            double v = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (w.length - 1));
            w[i] = (float) v;
            sum += v;
        }
        return (float) Math.max(1.0, sum);
    }

    private void computeOnce() {
        AudioPipeline p = pipeline;
        if (p == null) return;
        int n = fftSize;
        int channels;
        int rate;
        int generation;
        int used;
        PcmFileSource source = pcmSource;
        if (source != null) {
            channels = Math.max(1, source.channels);
            rate = source.sampleRate;
            generation = p.getGeneration();
            if (pcm.length < n * channels) pcm = new short[n * channels];
            try {
                used = source.readWindow(positionUs, n, pcm);
            } catch (Exception e) {
                used = 0;
            }
        } else {
            channels = Math.max(1, p.getChannelCount());
            rate = p.getSampleRate();
            generation = p.getGeneration();
            if (pcm.length < n * channels) pcm = new short[n * channels];
            used = p.copyLatest(pcm, n);
        }
        if (rate <= 0 || used <= 0) return;

        int max = 0;
        for (int i = 0; i < used * channels; i++) {
            int v = pcm[i];
            if (v < 0) v = -v;
            if (v > max) max = v;
        }

        int bins = n / 2;
        float[] outL;
        float[] outR;
        if (n == 16384) {
            for (int un : UNIQUE_N) {
                Band b = band(un);
                if (b != null) computeBand(b, used, channels);
            }
            outL = new float[bins + 1];
            outR = new float[bins + 1];
            for (int i = 0; i <= bins; i++) {
                float f = i * (rate / (float) n);
                outL[i] = merge(f, rate, true);
                outR[i] = merge(f, rate, false);
            }
        } else {
            Band b = band(n);
            if (b == null) return;
            computeBand(b, used, channels);
            outL = new float[bins + 1];
            outR = new float[bins + 1];
            System.arraycopy(b.left, 0, outL, 0, bins + 1);
            System.arraycopy(b.right, 0, outR, 0, bins + 1);
        }

        float peak = max <= 0 ? -120.0f
                : 20.0f * (float) Math.log10(max / 32768.0f);
        int bestBin = 0;
        float best = -120.0f;
        for (int i = 1; i < bins; i++) {
            if (outL[i] > best) {
                best = outL[i];
                bestBin = i;
            }
        }
        if (channels >= 2) {
            for (int i = 1; i < bins; i++) {
                if (outR[i] > best) {
                    best = outR[i];
                    bestBin = i;
                }
            }
        }
        float dominant = bestBin <= 0 ? 0.0f : bestBin * (rate / (float) n);
        Frame frame = new Frame(outL, outR, bins, rate, channels, n,
                generation, peak, dominant);
        synchronized (lock) {
            latest = frame;
        }
        long now = SystemClock.uptimeMillis();
        if (fpsStart == 0L) fpsStart = now;
        fpsFrames++;
        long elapsed = now - fpsStart;
        if (elapsed >= 500L) {
            fps = fpsFrames * 1000.0f / elapsed;
            fpsStart = now;
            fpsFrames = 0;
        }
    }

    private void computeBand(Band b, int used, int channels) {
        int n = b.n;
        int start = Math.max(0, used - n);
        int count = used - start;
        for (int ch = 0; ch < channels; ch++) {
            for (int i = 0; i < n; i++) {
                if (i < count) {
                    int idx = (start + i) * channels + ch;
                    b.re[i] = (pcm[idx] / 32768.0f) * b.window[i];
                } else {
                    b.re[i] = 0.0f;
                }
                b.im[i] = 0.0f;
            }
            Fft.transform(b.re, b.im);
            float[] dst = ch == 0 ? b.left : b.right;
            for (int k = 0; k <= b.bins; k++) {
                float rr = b.re[k];
                float ii = b.im[k];
                float mag = (float) Math.sqrt(rr * rr + ii * ii);
                float norm = (k == 0 || k == b.bins) ? 1.0f : 2.0f;
                float amp = mag * norm / b.sum;
                float db = 20.0f * (float) Math.log10(Math.max(amp, 1e-7f));
                if (db < -120.0f) db = -120.0f;
                if (db > 0.0f) db = 0.0f;
                dst[k] = db;
            }
        }
        if (channels < 2) {
            System.arraycopy(b.left, 0, b.right, 0, b.bins + 1);
        }
    }

    private float merge(float f, int rate, boolean left) {
        int bandIndex = 0;
        while (bandIndex < EDGES.length - 2 && f >= EDGES[bandIndex + 1]) {
            bandIndex++;
        }
        int n = BAND_N[bandIndex];
        Band b = band(n);
        float v = bandValue(b, rate, f, left);
        float start = EDGES[bandIndex];
        if (bandIndex > 0 && BAND_N[bandIndex] != BAND_N[bandIndex - 1]) {
            float lo = start * 0.90f;
            float hi = start * 1.10f;
            if (f >= lo && f <= hi) {
                float t = (f - lo) / Math.max(1f, hi - lo);
                Band prev = band(BAND_N[bandIndex - 1]);
                if (prev != null) {
                    v = v * t + bandValue(prev, rate, f, left) * (1f - t);
                }
            }
        }
        float end = EDGES[bandIndex + 1];
        if (bandIndex + 1 < BAND_N.length && BAND_N[bandIndex] != BAND_N[bandIndex + 1]) {
            float lo = end * 0.90f;
            float hi = end * 1.10f;
            if (f >= lo && f <= hi) {
                float t = (f - lo) / Math.max(1f, hi - lo);
                Band next = band(BAND_N[bandIndex + 1]);
                if (next != null) {
                    v = v * (1f - t) + bandValue(next, rate, f, left) * t;
                }
            }
        }
        return v;
    }

    private float bandValue(Band b, int rate, float f, boolean left) {
        if (b == null) return -120.0f;
        float pos = f * b.n / Math.max(1f, rate);
        if (pos <= 0f) return (left ? b.left : b.right)[0];
        if (pos >= b.bins) return (left ? b.left : b.right)[b.bins];
        int lo = (int) pos;
        int hi = Math.min(b.bins, lo + 1);
        float t = pos - lo;
        float[] a = left ? b.left : b.right;
        return a[lo] * (1f - t) + a[hi] * t;
    }
}
