package com.dsh.wavescope;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Reconstructs a stereo signal from a red/green spectrogram waterfall using an
 * inverse STFT with phase propagation.  The input image is cropped to the plot
 * region recorded in the PNG metadata (or inferred from the export layout).
 */
public final class SpectrogramAudioSynth {

    public interface Listener {
        void onProgress(int percent, String message);

        boolean isCanceled();
    }

    private final Context context;
    private final Uri uri;
    private final SpectrogramPng.Info info;
    private final float speedPps;
    private final Listener listener;
    private volatile boolean canceled;

    public SpectrogramAudioSynth(Context context, Uri uri, SpectrogramPng.Info info,
                                 float speedPps, Listener listener) {
        this.context = context.getApplicationContext();
        this.uri = uri;
        this.info = info;
        this.speedPps = speedPps;
        this.listener = listener;
    }

    private boolean isCanceled() {
        return canceled || (listener != null && listener.isCanceled());
    }

    private void postProgress(int percent, String message) {
        if (listener != null) {
            listener.onProgress(Math.max(0, Math.min(100, percent)), message);
        }
    }

    public File synthesize() throws Exception {
        if (speedPps <= 0.01f) throw new IOException("速度必须大于 0");
        int sampleRate = Math.max(8000, Math.min(192000, info.sampleRate));
        int n = nextPowerOfTwo(Math.max(1024, Math.min(8192, info.fftSize)));
        int bins = n / 2;
        int memorySample = 1;
        while ((long) (info.imageWidth / memorySample) * (info.imageHeight / memorySample)
                > 4_000_000L || info.plotWidth / memorySample > 16384) {
            memorySample <<= 1;
            if (memorySample > 64) break;
        }
        int maxSampleForOverlap = Math.max(1,
                (int) Math.floor(n * 0.48 * speedPps / sampleRate));
        if (maxSampleForOverlap < 1) {
            throw new IOException("速度太低，无法以当前 FFT 长度重建；请提高速度");
        }
        int maxPow2 = 1;
        while ((maxPow2 << 1) <= maxSampleForOverlap) maxPow2 <<= 1;
        int sample = Math.max(1, Math.min(memorySample, maxPow2));
        postProgress(2, "读取频谱图");
        Bitmap plot = SpectrogramPng.decodePlot(context, uri, info, sample);
        if (plot.getWidth() < 2 || plot.getHeight() < 2) {
            plot.recycle();
            throw new IOException("频谱绘图区太小");
        }
        int plotW = plot.getWidth();
        int plotH = plot.getHeight();
        int[] pixels = new int[plotW * plotH];
        plot.getPixels(pixels, 0, plotW, 0, 0, plotW, plotH);
        plot.recycle();

        long hop = Math.max(1L, Math.round((double) sampleRate * sample / speedPps));
        if (hop > n) {
            throw new IOException("速度太低，帧间隔超过 FFT 窗口");
        }
        long totalSamples = (long) (plotW - 1) * hop + n;
        long maxSamples = (long) sampleRate * 180L;
        if (totalSamples > maxSamples) {
            throw new IOException("还原音频超过 3 分钟上限，请提高速度");
        }
        if (totalSamples > Integer.MAX_VALUE - 16) {
            throw new IOException("音频太长");
        }
        int outLen = (int) totalSamples;
        float[] outL = new float[outLen];
        float[] outR = new float[outLen];
        float[] window = new float[n];
        double windowSq = 0.0;
        for (int i = 0; i < n; i++) {
            double w = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1));
            window[i] = (float) w;
            windowSq += w * w;
        }
        float overlapScale = (float) (hop / Math.max(1.0, windowSq));
        float[] reL = new float[n];
        float[] imL = new float[n];
        float[] reR = new float[n];
        float[] imR = new float[n];
        double[] phaseL = new double[bins + 1];
        double[] phaseR = new double[bins + 1];
        double[] omega = new double[bins + 1];
        Random random = new Random(0x51A7C0DEL);
        for (int b = 1; b <= bins; b++) {
            phaseL[b] = random.nextDouble() * Math.PI * 2.0;
            phaseR[b] = random.nextDouble() * Math.PI * 2.0;
            omega[b] = 2.0 * Math.PI * b * hop / n;
        }
        float fMin = 20.0f;
        float fMax = Math.max(fMin + 1.0f, sampleRate / 2.0f);
        double logRatio = Math.log(fMax / fMin);
        double df = sampleRate / (double) n;
        int lastPct = -1;
        for (int x = 0; x < plotW; x++) {
            if (isCanceled()) {
                throw new IOException("已取消");
            }
            java.util.Arrays.fill(reL, 0f);
            java.util.Arrays.fill(imL, 0f);
            java.util.Arrays.fill(reR, 0f);
            java.util.Arrays.fill(imR, 0f);
            for (int b = 1; b < bins; b++) {
                double f = b * df;
                if (f < fMin || f > fMax) continue;
                int row = plotH - 1 - (int) Math.round(
                        (plotH - 1) * (Math.log(f / fMin) / logRatio));
                if (row < 0) row = 0;
                if (row >= plotH) row = plotH - 1;
                int color = pixels[row * plotW + x];
                float ampL = levelToAmp((color >> 16) & 0xFF);
                float ampR = levelToAmp((color >> 8) & 0xFF);
                reL[b] = (float) (ampL * Math.cos(phaseL[b]));
                imL[b] = (float) (ampL * Math.sin(phaseL[b]));
                reR[b] = (float) (ampR * Math.cos(phaseR[b]));
                imR[b] = (float) (ampR * Math.sin(phaseR[b]));
                reL[n - b] = reL[b];
                imL[n - b] = -imL[b];
                reR[n - b] = reR[b];
                imR[n - b] = -imR[b];
            }
            inverse(reL, imL);
            inverse(reR, imR);
            int offset = (int) ((long) x * hop);
            for (int i = 0; i < n && offset + i < outLen; i++) {
                float w = window[i];
                outL[offset + i] += reL[i] * w * overlapScale;
                outR[offset + i] += reR[i] * w * overlapScale;
            }
            for (int b = 1; b <= bins; b++) {
                phaseL[b] += omega[b];
                phaseR[b] += omega[b];
            }
            int pct = 5 + (int) (88L * (x + 1) / plotW);
            if (pct != lastPct) {
                lastPct = pct;
                postProgress(pct, "逆变换合成声音");
            }
        }
        postProgress(95, "写入 WAV");
        float peak = 0f;
        for (int i = 0; i < outLen; i++) {
            float a = Math.abs(outL[i]);
            if (a > peak) peak = a;
            a = Math.abs(outR[i]);
            if (a > peak) peak = a;
        }
        float gain = peak > 0.000001f ? 0.85f / peak : 0f;
        File out = new File(context.getCacheDir(),
                "image-audio-" + System.nanoTime() + ".wav");
        try (BufferedOutputStream stream = new BufferedOutputStream(
                new FileOutputStream(out), 1 << 16)) {
            writeWavHeader(stream, sampleRate, outLen);
            byte[] buffer = new byte[1 << 16];
            int p = 0;
            for (int i = 0; i < outLen; i++) {
                int l = Math.round(outL[i] * gain * 32767f);
                int r = Math.round(outR[i] * gain * 32767f);
                l = Math.max(-32768, Math.min(32767, l));
                r = Math.max(-32768, Math.min(32767, r));
                if (p >= buffer.length - 4) {
                    stream.write(buffer, 0, p);
                    p = 0;
                }
                buffer[p++] = (byte) (l & 0xFF);
                buffer[p++] = (byte) ((l >> 8) & 0xFF);
                buffer[p++] = (byte) (r & 0xFF);
                buffer[p++] = (byte) ((r >> 8) & 0xFF);
                if (isCanceled()) {
                    stream.close();
                    out.delete();
                    throw new IOException("已取消");
                }
            }
            if (p > 0) stream.write(buffer, 0, p);
        } catch (Exception e) {
            out.delete();
            throw e;
        }
        postProgress(100, "完成");
        return out;
    }

    private static int nextPowerOfTwo(int v) {
        int p = 1;
        while (p < v) p <<= 1;
        return p;
    }

    private static float levelToAmp(int color) {
        float t = color / 255.0f;
        if (t <= 0f) return 1e-5f;
        t = (float) Math.pow(t, 1.0 / 1.3);
        float db = -100.0f + t * 100.0f;
        return (float) Math.pow(10.0, db / 20.0);
    }

    private static void inverse(float[] re, float[] im) {
        int n = re.length;
        for (int i = 0; i < n; i++) im[i] = -im[i];
        Fft.transform(re, im);
        float inv = 1.0f / n;
        for (int i = 0; i < n; i++) {
            re[i] *= inv;
            im[i] = -im[i] * inv;
        }
    }

    private static void writeWavHeader(BufferedOutputStream out, int sampleRate, int frames)
            throws IOException {
        int dataBytes = frames * 4;
        int riffSize = 36 + dataBytes;
        byte[] h = new byte[44];
        h[0] = 'R'; h[1] = 'I'; h[2] = 'F'; h[3] = 'F';
        putIntLE(h, 4, riffSize);
        h[8] = 'W'; h[9] = 'A'; h[10] = 'V'; h[11] = 'E';
        h[12] = 'f'; h[13] = 'm'; h[14] = 't'; h[15] = ' ';
        putIntLE(h, 16, 16);
        h[20] = 1; // PCM
        h[21] = 0;
        h[22] = 2; // stereo
        h[23] = 0;
        putIntLE(h, 24, sampleRate);
        putIntLE(h, 28, sampleRate * 4);
        h[32] = 4;
        h[33] = 0;
        h[34] = 16;
        h[35] = 0;
        h[36] = 'd'; h[37] = 'a'; h[38] = 't'; h[39] = 'a';
        putIntLE(h, 40, dataBytes);
        out.write(h);
    }

    private static void putIntLE(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }
}
