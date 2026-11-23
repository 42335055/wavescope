package com.dsh.wavescope;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Re-decodes an imported file and computes a true high-resolution waterfall
 * raster for export.  Unlike the on-screen overview this does not enlarge the
 * 4k-wide preview: every output time column is a real FFT frame (up to the
 * configured analysis cap).  Audio stays at its native sample rate.
 */
public final class HighResWaterfallExporter {

    public interface Listener {
        void onProgress(int percent, String message);

        boolean isCanceled();
    }

    private static final int MAX_ANALYSIS_COLUMNS = 131072;
    private static final long MAX_RAW_BYTES = 900L * 1024L * 1024L;
    private static final long MAX_PCM_BYTES = 500L * 1024L * 1024L;
    private static final long MAX_OUTPUT_PIXELS = 300_000_000L;

    private final Context context;
    private final Uri uri;
    private final int outputWidth;
    private final int outputHeight;
    private final long requestedStartUs;
    private final long requestedEndUs;
    private final String displayName;
    private final Listener listener;

    private File pcmFile;
    private File rawFile;

    private int sampleRate;
    private int channels;
    private long totalFrames;
    private long durationUs;
    private int analysisColumns;
    private long segmentStartFrame;
    private long segmentFrames;
    private long segmentStartUs;
    private long segmentEndUs;
    private int plotLeft;
    private int plotTop;
    private int plotWidthPx;
    private int plotHeightPx;
    private int baseFontPx;
    private int fftSizeUsed;
    private double exportSpeedPps;

    public HighResWaterfallExporter(Context context, Uri uri,
                                   int outputWidth, int outputHeight,
                                   long startUs, long endUs,
                                   String displayName, Listener listener) {
        this.context = context.getApplicationContext();
        this.uri = uri;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        this.requestedStartUs = startUs;
        this.requestedEndUs = endUs;
        this.displayName = displayName == null || displayName.length() == 0
                ? "音频" : displayName;
        this.listener = listener;
    }

    public void export(OutputStream pngOut) throws Exception {
        if (uri == null || pngOut == null) {
            throw new IOException("导出参数无效");
        }
        if (outputWidth <= 0 || outputHeight <= 0) {
            throw new IOException("导出尺寸无效");
        }
        if ((long) outputWidth * outputHeight > MAX_OUTPUT_PIXELS) {
            throw new IOException("单次导出像素上限为 3 亿，请减小宽度或高度");
        }
        pcmFile = new File(context.getCacheDir(), "ws-hi-pcm-" + System.nanoTime() + ".raw");
        rawFile = new File(context.getCacheDir(), "ws-hi-img-" + System.nanoTime() + ".raw");
        try {
            postProgress(1, "解码音频");
            decodeToPcm(pcmFile);
            if (isCanceled()) {
                throw new IOException("导出已取消");
            }
            if (totalFrames <= 0L || sampleRate <= 0 || channels <= 0) {
                throw new IOException("音频没有解码出样本");
            }
            long fileDurationUs = totalFrames * 1_000_000L / sampleRate;
            long startUs = Math.max(0L, Math.min(requestedStartUs, fileDurationUs));
            long endUs = requestedEndUs > startUs
                    ? Math.min(requestedEndUs, fileDurationUs) : fileDurationUs;
            if (endUs - startUs < 20_000L) {
                throw new IOException("选中片段太短，至少需要 0.02 秒");
            }
            long startFrame = Math.round(startUs * (double) sampleRate / 1_000_000.0);
            long endFrame = Math.round(endUs * (double) sampleRate / 1_000_000.0);
            startFrame = Math.max(0L, Math.min(startFrame, totalFrames - 1L));
            endFrame = Math.max(startFrame + 1L, Math.min(endFrame, totalFrames));
            segmentStartFrame = startFrame;
            segmentFrames = endFrame - startFrame;
            segmentStartUs = startFrame * 1_000_000L / sampleRate;
            segmentEndUs = endFrame * 1_000_000L / sampleRate;
            computeLayout();
            analysisColumns = (int) Math.min(Math.min((long) plotWidthPx,
                    MAX_ANALYSIS_COLUMNS), Math.max(1L, segmentFrames));
            if (analysisColumns < 1) {
                analysisColumns = 1;
            }
            postProgress(20, "计算高分辨率瀑布");
            buildRawRaster();
            if (isCanceled()) {
                throw new IOException("导出已取消");
            }
            postProgress(88, "编码 PNG");
            writePng(pngOut);
            postProgress(100, "完成");
        } finally {
            if (pcmFile != null) {
                //noinspection ResultOfMethodCallIgnored
                pcmFile.delete();
            }
            if (rawFile != null) {
                //noinspection ResultOfMethodCallIgnored
                rawFile.delete();
            }
        }
    }

    private boolean isCanceled() {
        return listener != null && listener.isCanceled();
    }

    private void postProgress(int percent, String message) {
        if (listener != null) {
            listener.onProgress(Math.max(0, Math.min(100, percent)), message);
        }
    }

    private void decodeToPcm(File outFile) throws Exception {
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        BufferedOutputStream out = null;
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
                throw new IOException("文件中没有音轨");
            }
            extractor.selectTrack(trackIndex);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                throw new IOException("无法读取音频编码");
            }
            sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;
            channels = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 0;
            long containerDuration = inputFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? inputFormat.getLong(MediaFormat.KEY_DURATION) : 0L;
            if (sampleRate <= 0 || channels <= 0) {
                throw new IOException("音轨缺少采样信息");
            }
            if (channels > 2) {
                throw new IOException("瀑布导出目前支持单/立体声");
            }
            if (containerDuration > 0L) {
                long estimate = (long) ((double) containerDuration * sampleRate / 1_000_000.0)
                        * channels * 2L;
                if (estimate > MAX_PCM_BYTES) {
                    throw new IOException("音频太长，无法生成高分辨率瀑布");
                }
            }

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();
            out = new BufferedOutputStream(new FileOutputStream(outFile), 1 << 16);

            boolean inputDone = false;
            boolean outputDone = false;
            boolean formatSeen = false;
            int currentRate = sampleRate;
            int currentChannels = channels;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            long framesWritten = 0L;
            long lastProgress = 0L;
            short[] shortBuffer = new short[8192];
            float[] floatBuffer = new float[4096];
            byte[] bytes = new byte[16384];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (!isCanceled()) {
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
                            || (newEncoding != AudioFormat.ENCODING_PCM_16BIT
                            && newEncoding != AudioFormat.ENCODING_PCM_FLOAT)) {
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
                            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
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
                if (now - lastProgress > 220L) {
                    lastProgress = now;
                    int percent = 1;
                    if (containerDuration > 0L) {
                        long pos = extractor.getSampleTime();
                        if (pos > 0L) {
                            percent = 1 + (int) Math.min(18L, pos * 18L / containerDuration);
                        }
                    }
                    postProgress(percent, "解码音频");
                }
                if (outputDone) {
                    break;
                }
            }
            if (out != null) {
                out.flush();
                out.close();
                out = null;
            }
            if (isCanceled()) {
                throw new IOException("导出已取消");
            }
            if (framesWritten <= 0L) {
                throw new IOException("没有解码出音频样本");
            }
            sampleRate = currentRate;
            channels = currentChannels;
            totalFrames = framesWritten;
            durationUs = containerDuration > 0L
                    ? containerDuration
                    : framesWritten * 1_000_000L / sampleRate;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
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

    private void computeLayout() throws IOException {
        baseFontPx = Math.max(10, Math.min(38, outputHeight / 48));
        int left = Math.min(baseFontPx * 5, Math.max(24, outputWidth / 4));
        int top = Math.min(baseFontPx * 4, Math.max(20, outputHeight / 4));
        int bottom = Math.min(baseFontPx * 3, Math.max(16, outputHeight / 5));
        int right = Math.min(baseFontPx / 2 + 2, Math.max(6, outputWidth / 40));
        plotLeft = left;
        plotTop = top;
        plotWidthPx = outputWidth - left - right;
        plotHeightPx = outputHeight - top - bottom;
        if (plotWidthPx < 48 || plotHeightPx < 48) {
            throw new IOException("尺寸太小，无法容纳坐标轴，请增大宽度或高度");
        }
        fftSizeUsed = plotHeightPx > 2048 ? 8192 : 4096;
    }

    private void buildRawRaster() throws Exception {
        final int height = plotHeightPx;
        final int columns = analysisColumns;
        final int fftSize = fftSizeUsed;
        final int bins = fftSize / 2;
        final float[] window = new float[fftSize];
        double windowSum = 0.0;
        for (int i = 0; i < fftSize; i++) {
            double w = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (fftSize - 1));
            window[i] = (float) w;
            windowSum += w;
        }
        final double df = sampleRate / (double) fftSize;
        final float fMin = 20.0f;
        final float fMax = Math.max(fMin + 1.0f, sampleRate / 2.0f);
        final double logRatio = Math.log(fMax / fMin);
        final boolean stereo = channels >= 2;

        int[] rowB0 = new int[height];
        int[] rowB1 = new int[height];
        boolean[] rowInterp = new boolean[height];
        float[] rowBinPos = new float[height];
        for (int y = 0; y < height; y++) {
            double tCenter = (height - 1 - y) / (double) Math.max(1, height - 1);
            double tLow = (height - 1 - (y + 0.5)) / (double) Math.max(1, height - 1);
            double tHigh = (height - 1 - (y - 0.5)) / (double) Math.max(1, height - 1);
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

        long rawBytes = (long) columns * height * 3L;
        if (rawBytes > MAX_RAW_BYTES) {
            throw new IOException("请求的瀑布尺寸过大（约 "
                    + (rawBytes / 1024L / 1024L) + " MB 临时数据），请降低高度或宽度");
        }
        long free = context.getCacheDir().getUsableSpace();
        if (free > 0 && free < rawBytes + 64L * 1024L * 1024L) {
            throw new IOException("缓存空间不足，无法生成瀑布大图");
        }

        int blockCols = (int) Math.max(1L, Math.min((long) columns,
                8_000_000L / Math.max(1L, height * 3L)));
        blockCols = Math.min(blockCols, columns);
        if (blockCols > 4096) {
            blockCols = 4096;
        }
        byte[] block = new byte[blockCols * height * 3];
        short[] samples = new short[fftSize * channels];
        byte[] readBytes = new byte[fftSize * channels * 2];
        float[] re = new float[fftSize];
        float[] im = new float[fftSize];
        float[] binDbL = new float[bins + 2];
        float[] binDbR = new float[bins + 2];

        RandomAccessFile pcm = null;
        RandomAccessFile raw = null;
        try {
            pcm = new RandomAccessFile(pcmFile, "r");
            raw = new RandomAccessFile(rawFile, "rw");
            raw.setLength(rawBytes);
            long doneCols = 0L;
            long lastPercent = -1L;

            for (int colStart = 0; colStart < columns; colStart += blockCols) {
                if (isCanceled()) {
                    throw new IOException("导出已取消");
                }
                int currentBlock = Math.min(blockCols, columns - colStart);
                Arrays.fill(block, (byte) 0);

                for (int lc = 0; lc < currentBlock; lc++) {
                    if ((lc & 7) == 0 && isCanceled()) {
                        throw new IOException("导出已取消");
                    }
                    int col = colStart + lc;
                    Arrays.fill(samples, (short) 0);
                    long center = segmentStartFrame
                            + (long) ((col + 0.5) / (double) columns * segmentFrames);
                    long start = center - fftSize / 2L;
                    long readStart = start;
                    int destFrame = 0;
                    if (readStart < 0L) {
                        destFrame = (int) Math.min(fftSize, -readStart);
                        readStart = 0L;
                    }
                    int framesToRead = 0;
                    if (readStart < totalFrames) {
                        framesToRead = (int) Math.min(fftSize - destFrame,
                                totalFrames - readStart);
                        int byteCount = framesToRead * channels * 2;
                        pcm.seek(readStart * channels * 2L);
                        int totalRead = 0;
                        while (totalRead < byteCount) {
                            int r = pcm.read(readBytes, totalRead, byteCount - totalRead);
                            if (r <= 0) {
                                break;
                            }
                            totalRead += r;
                        }
                        if (totalRead > 0) {
                            ByteBuffer bb = ByteBuffer.wrap(readBytes, 0, totalRead)
                                    .order(ByteOrder.LITTLE_ENDIAN);
                            ShortBuffer sb = bb.asShortBuffer();
                            int count = sb.remaining();
                            for (int i = 0; i < count; i++) {
                                samples[destFrame * channels + i] = sb.get();
                            }
                        }
                    }

                    for (int i = 0; i < fftSize; i++) {
                        float l = samples[i * channels] / 32768.0f * window[i];
                        re[i] = l;
                        if (stereo) {
                            im[i] = samples[i * channels + 1] / 32768.0f * window[i];
                        } else {
                            im[i] = 0f;
                        }
                    }
                    Fft.transform(re, im);

                    Arrays.fill(binDbL, -120.0f);
                    Arrays.fill(binDbR, -120.0f);
                    for (int k = 1; k <= bins; k++) {
                        double f = k * df;
                        if (f < fMin || f > fMax) {
                            continue;
                        }
                        int nk = fftSize - k;
                        float a = re[k];
                        float b = im[k];
                        float c = re[nk];
                        float d = im[nk];
                        float lr = 0.5f * (a + c);
                        float li = 0.5f * (b - d);
                        float rr = 0.5f * (b + d);
                        float ri = 0.5f * (a - c);
                        float magL = (float) Math.sqrt(lr * lr + li * li);
                        float magR = (float) Math.sqrt(rr * rr + ri * ri);
                        float norm = (k == bins) ? 1.0f : 2.0f;
                        float ampL = magL * norm / Math.max(1.0f, (float) windowSum);
                        float ampR = magR * norm / Math.max(1.0f, (float) windowSum);
                        binDbL[k] = 20.0f * (float) Math.log10(Math.max(ampL, 1e-7f));
                        binDbR[k] = 20.0f * (float) Math.log10(Math.max(ampR, 1e-7f));
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
                            for (int k = from; k <= to; k++) {
                                if (binDbL[k] > lo) lo = binDbL[k];
                                if (stereo && binDbR[k] > hi) hi = binDbR[k];
                            }
                        }
                        int red = level(lo);
                        int green = stereo ? level(hi) : 0;
                        int p = (y * blockCols + lc) * 3;
                        block[p] = (byte) red;
                        block[p + 1] = (byte) green;
                        block[p + 2] = 0;
                    }
                }

                for (int y = 0; y < height; y++) {
                    raw.seek(((long) y * columns + colStart) * 3L);
                    raw.write(block, y * blockCols * 3, currentBlock * 3);
                }
                doneCols += currentBlock;
                long percent = 20L + 68L * doneCols / Math.max(1L, columns);
                if (percent != lastPercent) {
                    lastPercent = percent;
                    postProgress((int) percent, "计算高分辨率瀑布");
                }
            }
        } finally {
            if (pcm != null) {
                try {
                    pcm.close();
                } catch (Exception ignored) {
                }
            }
            if (raw != null) {
                try {
                    raw.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static final int BG_R = 5;
    private static final int BG_G = 10;
    private static final int BG_B = 15;
    private static final int GRID_R = 18;
    private static final int GRID_G = 32;
    private static final int GRID_B = 40;
    private static final int AXIS_R = 42;
    private static final int AXIS_G = 63;
    private static final int AXIS_B = 78;
    private static final int TEXT_R = 219;
    private static final int TEXT_G = 233;
    private static final int TEXT_B = 234;
    private static final int MUTED_R = 143;
    private static final int MUTED_G = 162;
    private static final int MUTED_B = 176;
    private static final int CYAN_R = 59;
    private static final int CYAN_G = 224;
    private static final int CYAN_B = 207;

    private static final class Label {
        final int[] pixels;
        final int width;
        final int height;
        int x;
        int y;

        Label(int[] pixels, int width, int height) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
        }
    }

    private static final class Tick {
        final int x;
        final int y;

        Tick(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    private void writePng(OutputStream out) throws Exception {
        final RandomAccessFile raw = new RandomAccessFile(rawFile, "r");
        final int width = outputWidth;
        final int height = outputHeight;
        final int left = plotLeft;
        final int top = plotTop;
        final int plotW = plotWidthPx;
        final int plotH = plotHeightPx;
        final int plotBottom = top + plotH;
        final int plotRight = left + plotW;
        final int columns = analysisColumns;
        final byte[] sourceRow = new byte[columns * 3];
        final byte[] bgRow = new byte[width * 3];
        for (int i = 0; i < bgRow.length; i += 3) {
            bgRow[i] = (byte) BG_R;
            bgRow[i + 1] = (byte) BG_G;
            bgRow[i + 2] = (byte) BG_B;
        }
        final float[] frequencyMarks = {
                20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f
        };
        final int axisFont = Math.max(9, Math.round(baseFontPx * 0.66f));
        final int labelH = axisFont + Math.round(axisFont * 0.45f);
        final float nyquist = Math.max(21f, sampleRate / 2f);
        final float fMin = 20f;
        final double logRatio = Math.log(nyquist / fMin);

        List<Label> labels = new ArrayList<>();
        List<Tick> freqTicks = new ArrayList<>();
        List<Tick> timeTicks = new ArrayList<>();
        List<Tick> minorTimeTicks = new ArrayList<>();
        exportSpeedPps = plotW / Math.max(0.000001,
                (segmentEndUs - segmentStartUs) / 1_000_000.0);

        int headerW = Math.min(plotW, Math.max(1600, baseFontPx * 80));
        int headerH = Math.max(12, top - 4);
        Label header = makeHeaderLabel(headerW, headerH, baseFontPx);
        header.x = left;
        header.y = 2;
        labels.add(header);

        float lastFreqLabelY = -100000f;
        for (float f : frequencyMarks) {
            if (f <= fMin || f >= nyquist) {
                continue;
            }
            int y = top + plotH - 1 - (int) Math.round(
                    (plotH - 1) * (Math.log(f / fMin) / logRatio));
            if (y < top || y >= plotBottom) {
                continue;
            }
            freqTicks.add(new Tick(left, y));
            if (Math.abs(y - lastFreqLabelY) < labelH * 1.18f) {
                continue;
            }
            String text = f >= 1000f
                    ? String.format(Locale.US, "%.0fk", f / 1000f)
                    : String.format(Locale.US, "%.0f", f);
            Label l = makeTextLabel(text, axisFont, MUTED_R, MUTED_G, MUTED_B,
                    Math.max(24, left - 6), labelH, Paint.Align.RIGHT, 6);
            l.x = 0;
            l.y = Math.max(0, Math.min(height - l.height, y - l.height / 2));
            labels.add(l);
            lastFreqLabelY = y;
        }

        long rangeUs = segmentEndUs - segmentStartUs;
        double rangeSeconds = rangeUs / 1_000_000.0;
        double[] timeSteps = {0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1, 2, 5,
                10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 14400};
        int targetTicks = Math.max(4, Math.min(60, plotW / Math.max(64, axisFont * 5)));
        double step = timeSteps[timeSteps.length - 1];
        for (double candidate : timeSteps) {
            if (rangeSeconds / candidate <= targetTicks) {
                step = candidate;
                break;
            }
        }
        int tickCount = (int) Math.floor(rangeSeconds / step + 0.0001);
        for (int i = 0; i <= tickCount; i++) {
            double t = i * step;
            if (t > rangeSeconds + 0.0001) {
                break;
            }
            int x = left + (int) Math.round((plotW - 1) * (t / Math.max(0.0001, rangeSeconds)));
            x = Math.max(left, Math.min(plotRight - 1, x));
            timeTicks.add(new Tick(x, plotBottom));
            String text = formatAxisTime(segmentStartUs + Math.round(t * 1_000_000.0),
                    step < 0.1);
            Paint measurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            measurePaint.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
            measurePaint.setTextSize(axisFont);
            int lw = Math.max(36, Math.round(measurePaint.measureText(text)) + 12);
            Label l = makeTextLabel(text, axisFont, MUTED_R, MUTED_G, MUTED_B,
                    lw, labelH, Paint.Align.CENTER, 4);
            l.x = Math.max(0, Math.min(width - lw, x - lw / 2));
            l.y = Math.min(height - l.height, plotBottom + 4);
            labels.add(l);
        }

        double minorStep = step / 10.0;
        double pixelPerMinor = (plotW - 1) / Math.max(1.0, rangeSeconds / minorStep);
        if (pixelPerMinor < 3.0) {
            minorStep = step / 5.0;
            pixelPerMinor = (plotW - 1) / Math.max(1.0, rangeSeconds / minorStep);
        }
        if (pixelPerMinor >= 3.0) {
            int minorCount = (int) Math.floor(rangeSeconds / minorStep + 0.0001);
            for (int i = 0; i <= minorCount; i++) {
                double t = i * minorStep;
                int x = left + (int) Math.round((plotW - 1)
                        * (t / Math.max(0.0001, rangeSeconds)));
                minorTimeTicks.add(new Tick(x, plotBottom));
            }
        }

        final List<Label> finalLabels = labels;
        final List<Tick> finalFreqTicks = freqTicks;
        final List<Tick> finalTimeTicks = timeTicks;
        final List<Tick> finalMinorTimeTicks = minorTimeTicks;
        final long stepX = ((long) (columns - 1) << 16) / Math.max(1, plotW - 1);
        final String metadata = String.format(Locale.US,
                "speed_pps=%.6f;plot_left=%d;plot_top=%d;plot_width=%d;plot_height=%d;"
                        + "sample_rate=%d;fft_size=%d;channels=%d;duration_us=%d;"
                        + "freq_min=20;freq_max=%.6f;export_width=%d;export_height=%d",
                exportSpeedPps, left, top, plotW, plotH, sampleRate, fftSizeUsed,
                channels, segmentEndUs - segmentStartUs, sampleRate / 2.0,
                width, height);
        final boolean segment = segmentStartUs > 0L
                || segmentEndUs < totalFrames * 1_000_000L / Math.max(1, sampleRate) - 1000L;
        final long fullDurationUs = totalFrames * 1_000_000L / Math.max(1, sampleRate);

        try {
            RgbPngWriter writer = new RgbPngWriter(out, width, height,
                    (y, rgb) -> {
                        System.arraycopy(bgRow, 0, rgb, 0, bgRow.length);
                        for (Label label : finalLabels) {
                            copyLabel(rgb, width, y, label);
                        }
                        if (y >= top && y < plotBottom) {
                            raw.readFully(sourceRow);
                            if (columns == plotW) {
                                System.arraycopy(sourceRow, 0, rgb, left * 3,
                                        sourceRow.length);
                            } else {
                                long fxFixed = 0L;
                                int base = left * 3;
                                for (int x = 0; x < plotW; x++) {
                                    int x0 = (int) (fxFixed >> 16);
                                    if (x0 >= columns) x0 = columns - 1;
                                    int x1 = Math.min(columns - 1, x0 + 1);
                                    int f = (int) (fxFixed & 0xFFFF);
                                    int a = x0 * 3;
                                    int b = x1 * 3;
                                    int inv = 65536 - f;
                                    int p = base + x * 3;
                                    rgb[p] = (byte) (((sourceRow[a] & 0xFF) * inv
                                            + (sourceRow[b] & 0xFF) * f) >> 16);
                                    rgb[p + 1] = (byte) (((sourceRow[a + 1] & 0xFF) * inv
                                            + (sourceRow[b + 1] & 0xFF) * f) >> 16);
                                    rgb[p + 2] = 0;
                                    fxFixed += stepX;
                                    if (fxFixed > ((long) (columns - 1) << 16)) {
                                        fxFixed = (long) (columns - 1) << 16;
                                    }
                                }
                            }
                            setPixel(rgb, width, left - 1, y, AXIS_R, AXIS_G, AXIS_B);
                            setPixel(rgb, width, plotRight, y, AXIS_R, AXIS_G, AXIS_B);
                        }
                        if (y == top - 2) {
                            drawHLine(rgb, width, left, plotRight - 1, y,
                                    CYAN_R, CYAN_G, CYAN_B);
                        }
                        if (y == top - 1) {
                            drawHLine(rgb, width, left - 1, plotRight, y,
                                    AXIS_R, AXIS_G, AXIS_B);
                        }
                        if (y == plotBottom) {
                            drawHLine(rgb, width, left - 1, plotRight, y,
                                    AXIS_R, AXIS_G, AXIS_B);
                        }
                        if (y >= top && y < plotBottom) {
                            for (Tick t : finalFreqTicks) {
                                if (t.y == y) {
                                    drawHLine(rgb, width, Math.max(0, left - 7),
                                            Math.max(0, left - 2), y,
                                            AXIS_R, AXIS_G, AXIS_B);
                                }
                            }
                            for (Tick t : finalTimeTicks) {
                                if (t.x >= left && t.x < plotRight) {
                                    setPixel(rgb, width, t.x, y,
                                            GRID_R, GRID_G, GRID_B);
                                }
                            }
                        }
                        if (y >= plotBottom + 1 && y <= plotBottom + 2) {
                            for (Tick t : finalMinorTimeTicks) {
                                setPixel(rgb, width, t.x, y, AXIS_R, AXIS_G, AXIS_B);
                            }
                        }
                        if (y >= plotBottom + 1 && y <= plotBottom + 3) {
                            for (Tick t : finalTimeTicks) {
                                setPixel(rgb, width, t.x, y, AXIS_R, AXIS_G, AXIS_B);
                            }
                        }
                    },
                    new RgbPngWriter.Listener() {
                        @Override
                        public void onProgress(int percent) {
                            postProgress(88 + percent * 12 / 100, "编码 PNG");
                        }

                        @Override
                        public boolean isCanceled() {
                            return HighResWaterfallExporter.this.isCanceled();
                        }
                    }, metadata);
            writer.write();
        } finally {
            try {
                raw.close();
            } catch (Exception ignored) {
            }
        }
    }

    private Label makeHeaderLabel(int width, int height, int fontPx) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(BG_R, BG_G, BG_B));
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        int pad = Math.max(6, fontPx / 2);
        float mainSize = Math.max(9, fontPx * 0.95f);
        float metaSize = Math.max(8, fontPx * 0.60f);
        paint.setTextSize(mainSize);
        paint.setColor(Color.rgb(TEXT_R, TEXT_G, TEXT_B));
        String line1 = TextUtils.ellipsize(displayName, paint, w - pad * 2,
                TextUtils.TruncateAt.MIDDLE).toString();
        Paint.FontMetrics fm = paint.getFontMetrics();
        float y1 = pad - fm.ascent;
        canvas.drawText(line1, pad, y1, paint);

        long fullDurationUs = totalFrames * 1_000_000L / Math.max(1, sampleRate);
        boolean segment = segmentStartUs > 0L
                || segmentEndUs < fullDurationUs - 1000L;
        String range = segment
                ? "片段 " + formatClock(segmentStartUs) + "–" + formatClock(segmentEndUs)
                + " / 全长 " + formatClock(fullDurationUs)
                : "全文件 " + formatClock(fullDurationUs);
        String meta = rateLabel(sampleRate) + " · " + channels + " ch · FFT "
                + fftSizeUsed + " · LOG · " + range;
        paint.setTextSize(metaSize);
        paint.setColor(Color.rgb(MUTED_R, MUTED_G, MUTED_B));
        meta = TextUtils.ellipsize(meta, paint, w - pad * 2, TextUtils.TruncateAt.END).toString();
        float y2 = y1 + metaSize + pad * 0.55f;
        if (y2 < h - pad * 0.2f) {
            canvas.drawText(meta, pad, y2, paint);
        }
        String speed = String.format(Locale.US, "SPEED %.1f px/s", exportSpeedPps);
        float y3 = y2 + metaSize + pad * 0.30f;
        if (y3 < h - pad * 0.2f) {
            paint.setColor(Color.rgb(CYAN_R, CYAN_G, CYAN_B));
            canvas.drawText(speed, pad, y3, paint);
        }
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        bitmap.recycle();
        return new Label(pixels, w, h);
    }

    private Label makeTextLabel(String text, int fontSize, int r, int g, int b,
                                int width, int height, Paint.Align align, int pad) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(BG_R, BG_G, BG_B));
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        paint.setTextSize(Math.max(8, fontSize));
        paint.setColor(Color.rgb(r, g, b));
        paint.setTextAlign(align);
        String out = TextUtils.ellipsize(text, paint, Math.max(8, w - pad * 2),
                TextUtils.TruncateAt.END).toString();
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = (h - (fm.descent - fm.ascent)) / 2f - fm.ascent;
        float x = align == Paint.Align.LEFT ? pad
                : (align == Paint.Align.RIGHT ? w - pad : w / 2f);
        canvas.drawText(out, x, baseline, paint);
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        bitmap.recycle();
        return new Label(pixels, w, h);
    }

    private void copyLabel(byte[] rgb, int width, int y, Label label) {
        int ly = y - label.y;
        if (ly < 0 || ly >= label.height) {
            return;
        }
        int rowStart = ly * label.width;
        for (int x = 0; x < label.width; x++) {
            int dx = label.x + x;
            if (dx < 0 || dx >= width) {
                continue;
            }
            int color = label.pixels[rowStart + x];
            int p = dx * 3;
            rgb[p] = (byte) ((color >> 16) & 0xFF);
            rgb[p + 1] = (byte) ((color >> 8) & 0xFF);
            rgb[p + 2] = (byte) (color & 0xFF);
        }
    }

    private void drawHLine(byte[] rgb, int width, int x0, int x1, int y,
                           int r, int g, int b) {
        if (x1 < x0) {
            int t = x0;
            x0 = x1;
            x1 = t;
        }
        for (int x = Math.max(0, x0); x <= Math.min(width - 1, x1); x++) {
            int p = x * 3;
            rgb[p] = (byte) r;
            rgb[p + 1] = (byte) g;
            rgb[p + 2] = (byte) b;
        }
    }

    private void setPixel(byte[] rgb, int width, int x, int y,
                          int r, int g, int b) {
        if (x < 0 || x >= width || y < 0 || y >= outputHeight) {
            return;
        }
        int p = x * 3;
        rgb[p] = (byte) r;
        rgb[p + 1] = (byte) g;
        rgb[p + 2] = (byte) b;
    }

    private String rateLabel(int rate) {
        if (rate >= 1000) {
            return String.format(Locale.US, "%.1f kHz", rate / 1000.0);
        }
        return rate + " Hz";
    }

    private String formatClock(long us) {
        long cs = Math.max(0L, us / 10_000L);
        long h = cs / 360000L;
        long m = (cs % 360000L) / 6000L;
        long s = (cs % 6000L) / 100L;
        long c = cs % 100L;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, c);
        }
        return String.format(Locale.US, "%02d:%02d.%02d", m, s, c);
    }

    private String formatAxisTime(long us, boolean centis) {
        long units = Math.max(0L, Math.round(us / (centis ? 10_000.0 : 100_000.0)));
        long perSecond = centis ? 100L : 10L;
        long totalSeconds = units / perSecond;
        long fraction = units % perSecond;
        long h = totalSeconds / 3600L;
        long m = (totalSeconds % 3600L) / 60L;
        long s = totalSeconds % 60L;
        if (centis) {
            if (h > 0) return String.format(Locale.US, "%d:%02d:%02d.%02d",
                    h, m, s, fraction);
            return String.format(Locale.US, "%02d:%02d.%02d", m, s, fraction);
        }
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d.%d", h, m, s, fraction);
        }
        return String.format(Locale.US, "%02d:%02d.%d", m, s, fraction);
    }

    private int level(float db) {
        float t = (db + 100.0f) / 100.0f;
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        t = (float) Math.pow(t, 1.3);
        return Math.round(255f * t);
    }
}
