package com.dsh.wavescope;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Locale;

/**
 * Decodes an imported file once and builds a full-duration spectrogram overview.
 * The spectrogram is a display raster; no audio is resampled.
 */
public final class FileSpectrogramAnalyzer {

    public interface Listener {
        void onProgress(int percent, String message);

        void onComplete(Bitmap bitmap, WaveformData waveform,
                        int sampleRate, int channels, long durationUs);

        void onError(String message);
    }

    private static final int FFT_SIZE = 2048;
    private static final int IMAGE_HEIGHT = 384;
    private static final int MIN_COLUMNS = 256;
    private static final int MAX_COLUMNS = 4096;
    private static final long MAX_PCM_BYTES = 500L * 1024L * 1024L;

    private final Context context;
    private final Uri uri;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean canceled;
    private Thread thread;
    private int session;

    public FileSpectrogramAnalyzer(Context context, Uri uri, Listener listener) {
        this.context = context.getApplicationContext();
        this.uri = uri;
        this.listener = listener;
    }

    public void start() {
        canceled = false;
        session++;
        final int mySession = session;
        thread = new Thread(() -> run(mySession), "WaveScope-Spectrum");
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        thread.start();
    }

    public void cancel() {
        canceled = true;
        session++;
        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
        }
    }

    private void run(int mySession) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        File pcm = new File(context.getCacheDir(), "ws-spectrum-" + System.nanoTime() + ".pcm");
        try {
            DecodedInfo info = decodeToPcm(pcm, mySession);
            if (canceled || mySession != session) {
                return;
            }
            if (info == null || info.frames <= 0) {
                throw new IOException("音频解码后没有样本");
            }
            WaveformData waveform = null;
            try {
                waveform = buildWaveform(pcm, info, mySession);
            } catch (Exception ignored) {
            }
            if (canceled || mySession != session) {
                return;
            }
            postProgress(mySession, 56, "计算全文件频谱");
            Bitmap bitmap = buildSpectrogram(pcm, info, mySession);
            if (bitmap == null || canceled || mySession != session) {
                if (bitmap != null) {
                    bitmap.recycle();
                }
                return;
            }
            final Bitmap result = bitmap;
            final WaveformData waveformResult = waveform;
            main.post(() -> {
                if (!canceled && mySession == session) {
                    listener.onComplete(result, waveformResult,
                            info.sampleRate, info.channels, info.durationUs);
                } else {
                    result.recycle();
                }
            });
        } catch (Exception e) {
            if (!canceled && mySession == session) {
                String msg = e.getMessage();
                if (msg == null || msg.length() == 0) {
                    msg = e.getClass().getSimpleName();
                }
                final String error = msg;
                main.post(() -> {
                    if (!canceled && mySession == session) {
                        listener.onError(error);
                    }
                });
            }
        } catch (OutOfMemoryError e) {
            if (!canceled && mySession == session) {
                main.post(() -> {
                    if (!canceled && mySession == session) {
                        listener.onError("内存不足，无法生成全文件频谱");
                    }
                });
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            pcm.delete();
        }
    }

    private DecodedInfo decodeToPcm(File outFile, int mySession) throws Exception {
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(context, uri, null);
            int trackIndex = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat fmt = extractor.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    inputFormat = fmt;
                    break;
                }
            }
            if (trackIndex < 0 || inputFormat == null) {
                throw new IOException("文件中没有可识别的音轨");
            }
            extractor.selectTrack(trackIndex);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                throw new IOException("无法读取音频编码");
            }
            int rate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;
            int channels = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 0;
            long durationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? inputFormat.getLong(MediaFormat.KEY_DURATION) : 0L;
            if (rate <= 0 || channels <= 0) {
                throw new IOException("音轨缺少采样率或声道信息");
            }
            if (channels > 2) {
                throw new IOException("全频谱概览目前支持单/立体声文件");
            }
            if (durationUs > 0L) {
                long estimated = (long) ((double) durationUs * rate / 1_000_000.0)
                        * channels * 2L;
                if (estimated > MAX_PCM_BYTES) {
                    throw new IOException("文件太长，全频谱概览最多支持约 500 MB 的原始 PCM");
                }
            }

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();

            BufferedOutputStream out = new BufferedOutputStream(
                    new FileOutputStream(outFile), 1 << 16);
            boolean inputDone = false;
            boolean outputDone = false;
            boolean formatSeen = false;
            int currentRate = rate;
            int currentChannels = channels;
            int pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT;
            long framesWritten = 0L;
            long lastProgress = 0L;
            short[] shortBuffer = new short[8192];
            float[] floatBuffer = new float[4096];
            byte[] bytes = new byte[16384];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (!canceled && mySession == session) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(5000);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        if (inBuf == null) {
                            throw new IOException("解码器输入缓冲区不可用");
                        }
                        int sampleSize = extractor.readSampleData(inBuf, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, 5000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    int newRate = of.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                            ? of.getInteger(MediaFormat.KEY_SAMPLE_RATE) : currentRate;
                    int newChannels = of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                            ? of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : currentChannels;
                    int newEncoding = of.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ? of.getInteger(MediaFormat.KEY_PCM_ENCODING) : pcmEncoding;
                    if (newChannels > 2 || newRate <= 0
                            || (newEncoding != android.media.AudioFormat.ENCODING_PCM_16BIT
                            && newEncoding != android.media.AudioFormat.ENCODING_PCM_FLOAT)) {
                        throw new IOException("不支持的解码输出格式");
                    }
                    if (formatSeen && (newRate != currentRate || newChannels != currentChannels
                            || newEncoding != pcmEncoding)) {
                        throw new IOException("音轨中途改变了采样格式");
                    }
                    currentRate = newRate;
                    currentChannels = newChannels;
                    pcmEncoding = newEncoding;
                    formatSeen = true;
                } else if (outIndex >= 0) {
                    if (info.size > 0) {
                        ByteBuffer buffer = codec.getOutputBuffer(outIndex);
                        if (buffer != null) {
                            buffer.position(info.offset);
                            buffer.order(ByteOrder.nativeOrder());
                            int frames;
                            int samples;
                            if (pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                                frames = info.size / 4 / currentChannels;
                                samples = frames * currentChannels;
                                if (floatBuffer.length < samples) {
                                    floatBuffer = new float[samples];
                                }
                                FloatBuffer fb = buffer.asFloatBuffer();
                                fb.get(floatBuffer, 0, samples);
                                if (shortBuffer.length < samples) {
                                    shortBuffer = new short[samples];
                                }
                                for (int i = 0; i < samples; i++) {
                                    float v = floatBuffer[i];
                                    if (v > 1f) v = 1f;
                                    if (v < -1f) v = -1f;
                                    shortBuffer[i] = (short) Math.round(v * 32767.0f);
                                }
                            } else {
                                samples = info.size / 2;
                                frames = samples / currentChannels;
                                samples = frames * currentChannels;
                                if (shortBuffer.length < samples) {
                                    shortBuffer = new short[samples];
                                }
                                ShortBuffer sb = buffer.asShortBuffer();
                                sb.get(shortBuffer, 0, samples);
                            }
                            if (frames > 0) {
                                int byteCount = samples * 2;
                                if (bytes.length < byteCount) {
                                    bytes = new byte[byteCount];
                                }
                                int p = 0;
                                for (int i = 0; i < samples; i++) {
                                    short v = shortBuffer[i];
                                    bytes[p++] = (byte) (v & 0xFF);
                                    bytes[p++] = (byte) ((v >> 8) & 0xFF);
                                }
                                out.write(bytes, 0, byteCount);
                                framesWritten += frames;
                            }
                        }
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outIndex, false);
                    if (eos) {
                        outputDone = true;
                    }
                }

                long now = SystemClock.elapsedRealtime();
                if (now - lastProgress > 180L) {
                    lastProgress = now;
                    int percent = 2;
                    if (durationUs > 0L) {
                        // Extractor sample time gives a good rough decode position.
                        long pos = extractor.getSampleTime();
                        if (pos > 0L) {
                            percent = 2 + (int) Math.min(50L, pos * 50L / durationUs);
                        }
                    } else {
                        percent = 2;
                    }
                    postProgress(mySession, percent, "解码音频");
                }
                if (outputDone) {
                    break;
                }
            }
            out.flush();
            out.close();
            if (canceled || mySession != session) {
                return null;
            }
            if (framesWritten <= 0L) {
                throw new IOException("没有解码出音频样本");
            }
            if (durationUs <= 0L) {
                durationUs = framesWritten * 1_000_000L / currentRate;
            }
            DecodedInfo result = new DecodedInfo();
            result.sampleRate = currentRate;
            result.channels = currentChannels;
            result.durationUs = durationUs;
            result.frames = framesWritten;
            return result;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                }
                try {
                    codec.release();
                } catch (Exception ignored) {
                }
            }
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private WaveformData buildWaveform(File pcmFile, DecodedInfo info, int mySession)
            throws Exception {
        final int buckets = 4096;
        float[] minL = new float[buckets];
        float[] maxL = new float[buckets];
        float[] minR = new float[buckets];
        float[] maxR = new float[buckets];
        final boolean stereo = info.channels >= 2;
        byte[] buffer = new byte[8192 * info.channels * 2];
        try (RandomAccessFile raf = new RandomAccessFile(pcmFile, "r")) {
            for (int b = 0; b < buckets; b++) {
                long start = b * info.frames / buckets;
                long end = (b + 1L) * info.frames / buckets;
                if (end <= start) {
                    continue;
                }
                raf.seek(start * info.channels * 2L);
                long remaining = end - start;
                float loL = 0f;
                float hiL = 0f;
                float loR = 0f;
                float hiR = 0f;
                while (remaining > 0) {
                    int frames = (int) Math.min(8192L, remaining);
                    int bytes = frames * info.channels * 2;
                    int readTotal = 0;
                    while (readTotal < bytes) {
                        int r = raf.read(buffer, readTotal, bytes - readTotal);
                        if (r <= 0) {
                            break;
                        }
                        readTotal += r;
                    }
                    int samples = readTotal / 2;
                    for (int i = 0; i + info.channels - 1 < samples; i += info.channels) {
                        int vl = (short) ((buffer[i * 2] & 0xFF)
                                | (buffer[i * 2 + 1] << 8));
                        float l = vl / 32768.0f;
                        if (l < loL) loL = l;
                        if (l > hiL) hiL = l;
                        if (stereo) {
                            int vr = (short) ((buffer[(i + 1) * 2] & 0xFF)
                                    | (buffer[(i + 1) * 2 + 1] << 8));
                            float r = vr / 32768.0f;
                            if (r < loR) loR = r;
                            if (r > hiR) hiR = r;
                        }
                    }
                    remaining -= frames;
                }
                minL[b] = loL;
                maxL[b] = hiL;
                minR[b] = loR;
                maxR[b] = hiR;
                if ((b & 255) == 0) {
                    int percent = 52 + (int) (4L * b / buckets);
                    postProgress(mySession, percent, "生成波形");
                }
            }
        }
        return new WaveformData(minL, maxL, minR, maxR, buckets,
                info.sampleRate, info.channels, info.durationUs, info.frames);
    }

    private Bitmap buildSpectrogram(File pcmFile, DecodedInfo info, int mySession)
            throws Exception {
        int columns = (int) Math.ceil(info.durationUs / 1_000_000.0 * 20.0);
        columns = Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, columns));
        columns = (int) Math.min(columns, Math.max(1L, info.frames));
        if (columns < 8) {
            columns = 8;
        }
        int height = IMAGE_HEIGHT;
        int[] pixels = new int[columns * height];

        int n = FFT_SIZE;
        int bins = n / 2;
        float[] window = new float[n];
        double windowSum = 0.0;
        for (int i = 0; i < n; i++) {
            double w = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1));
            window[i] = (float) w;
            windowSum += w;
        }
        float[] reL = new float[n];
        float[] imL = new float[n];
        float[] reR = new float[n];
        float[] imR = new float[n];
        short[] samples = new short[n * info.channels];
        byte[] readBytes = new byte[n * info.channels * 2];
        float fMin = 20.0f;
        float fMax = Math.max(fMin + 1.0f, info.sampleRate / 2.0f);
        double logRatio = Math.log(fMax / fMin);
        double df = info.sampleRate / (double) n;
        boolean stereo = info.channels >= 2;

        // Precompute a frequency row map.  At low frequencies one FFT bin spans
        // many display rows; interpolating between bins avoids the hard
        // horizontal stair-step bands in the overview.
        int[] rowB0 = new int[height];
        int[] rowB1 = new int[height];
        boolean[] rowInterp = new boolean[height];
        float[] rowBinPos = new float[height];
        for (int y = 0; y < height; y++) {
            double tCenter = (height - 1 - y) / (double) (height - 1);
            double tLow = (height - 1 - (y + 0.5)) / (double) (height - 1);
            double tHigh = (height - 1 - (y - 0.5)) / (double) (height - 1);
            tCenter = Math.max(0.0, Math.min(1.0, tCenter));
            tLow = Math.max(0.0, Math.min(1.0, tLow));
            tHigh = Math.max(0.0, Math.min(1.0, tHigh));
            float fCenter = (float) Math.exp(Math.log(fMin) + tCenter * logRatio);
            float fLow = (float) Math.exp(Math.log(fMin) + tLow * logRatio);
            float fHigh = (float) Math.exp(Math.log(fMin) + tHigh * logRatio);
            rowBinPos[y] = fCenter / (float) df;
            rowInterp[y] = (fHigh - fLow) / (float) df <= 1.5f;
            int b0 = (int) Math.floor(fLow / (float) df);
            int b1 = (int) Math.ceil(fHigh / (float) df);
            if (b0 < 1) b0 = 1;
            if (b1 < b0) b1 = b0;
            if (b0 > bins) b0 = bins;
            if (b1 > bins) b1 = bins;
            rowB0[y] = b0;
            rowB1[y] = b1;
        }
        float[] binDbL = new float[bins + 2];
        float[] binDbR = new float[bins + 2];

        try (RandomAccessFile raf = new RandomAccessFile(pcmFile, "r")) {
            for (int col = 0; col < columns; col++) {
                if (canceled || mySession != session) {
                    return null;
                }
                Arrays.fill(samples, (short) 0);
                Arrays.fill(binDbL, -120.0f);
                Arrays.fill(binDbR, -120.0f);
                long center = (long) ((col + 0.5) / (double) columns * info.frames);
                long start = center - n / 2L;
                int destFrame = 0;
                int availableFrames = 0;
                long readStart = start;
                if (readStart < 0L) {
                    destFrame = (int) Math.min(n, -readStart);
                    readStart = 0L;
                }
                if (readStart < info.frames) {
                    availableFrames = (int) Math.min(n - destFrame, info.frames - readStart);
                    raf.seek(readStart * info.channels * 2L);
                    int byteCount = availableFrames * info.channels * 2;
                    int readTotal = 0;
                    while (readTotal < byteCount) {
                        int read = raf.read(readBytes, readTotal, byteCount - readTotal);
                        if (read <= 0) {
                            break;
                        }
                        readTotal += read;
                    }
                    if (readTotal > 0) {
                        ByteBuffer bb = ByteBuffer.wrap(readBytes, 0, readTotal)
                                .order(ByteOrder.LITTLE_ENDIAN);
                        ShortBuffer sb = bb.asShortBuffer();
                        int count = sb.remaining();
                        for (int i = 0; i < count; i++) {
                            samples[destFrame * info.channels + i] = sb.get();
                        }
                    }
                }
                for (int i = 0; i < n; i++) {
                    float l = samples[i * info.channels] / 32768.0f * window[i];
                    reL[i] = l;
                    imL[i] = 0f;
                    if (stereo) {
                        float r = samples[i * info.channels + 1] / 32768.0f * window[i];
                        reR[i] = r;
                        imR[i] = 0f;
                    }
                }
                Fft.transform(reL, imL);
                if (stereo) {
                    Fft.transform(reR, imR);
                }
                for (int b = 1; b <= bins; b++) {
                    float norm = (b == bins) ? 1.0f : 2.0f;
                    float invWindow = 1.0f / Math.max(1.0f, (float) windowSum);
                    float ampL = (float) Math.sqrt(reL[b] * reL[b] + imL[b] * imL[b])
                            * norm * invWindow;
                    binDbL[b] = 20.0f * (float) Math.log10(Math.max(ampL, 1e-7f));
                    if (stereo) {
                        float ampR = (float) Math.sqrt(reR[b] * reR[b] + imR[b] * imR[b])
                                * norm * invWindow;
                        binDbR[b] = 20.0f * (float) Math.log10(Math.max(ampR, 1e-7f));
                    }
                }
                for (int y = 0; y < height; y++) {
                    float lo;
                    float hi;
                    if (rowInterp[y]) {
                        float pos = rowBinPos[y];
                        if (pos < 1.0f) pos = 1.0f;
                        if (pos > bins) pos = bins;
                        int lower = Math.max(1, Math.min(bins, (int) Math.floor(pos)));
                        int upper = Math.min(bins, lower + 1);
                        float frac = pos - lower;
                        lo = binDbL[lower] + (binDbL[upper] - binDbL[lower]) * frac;
                        hi = stereo
                                ? binDbR[lower] + (binDbR[upper] - binDbR[lower]) * frac
                                : -100.0f;
                    } else {
                        lo = -120.0f;
                        hi = -120.0f;
                        int from = rowB0[y];
                        int to = rowB1[y];
                        for (int b = from; b <= to; b++) {
                            if (binDbL[b] > lo) lo = binDbL[b];
                            if (stereo && binDbR[b] > hi) hi = binDbR[b];
                        }
                    }
                    int red = level(lo);
                    int green = stereo ? level(hi) : 0;
                    pixels[y * columns + col] = 0xFF000000 | (red << 16) | (green << 8);
                }
                if ((col & 31) == 0) {
                    int percent = 56 + (int) (43L * col / Math.max(1, columns - 1));
                    postProgress(mySession, percent, "计算全文件频谱");
                }
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(columns, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, columns, 0, 0, columns, height);
        postProgress(mySession, 100, "全文件频谱完成");
        return bitmap;
    }

    private int level(float db) {
        float t = (db + 100.0f) / 100.0f;
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        t = (float) Math.pow(t, 1.3);
        return Math.round(255f * t);
    }

    private void postProgress(int mySession, int percent, String message) {
        final int p = Math.max(0, Math.min(100, percent));
        main.post(() -> {
            if (!canceled && mySession == session) {
                listener.onProgress(p, message);
            }
        });
    }

    private static final class DecodedInfo {
        int sampleRate;
        int channels;
        long durationUs;
        long frames;
    }
}
