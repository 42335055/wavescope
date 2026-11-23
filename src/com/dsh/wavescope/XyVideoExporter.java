package com.dsh.wavescope;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.List;
import java.util.Locale;

/**
 * Decodes an imported file and renders its XY phase portrait to an H.264
 * video with the original audio (re-encoded as AAC for container
 * compatibility). MP4 uses MediaMuxer; MKV uses the bundled Matroska writer.
 * Audio samples stay at their native rate; frame rate and size are output
 * parameters only.
 */
public final class XyVideoExporter {

    public interface Listener {
        void onProgress(int percent, String message);

        boolean isCanceled();
    }

    public static final int FORMAT_MP4 = 1;
    public static final int FORMAT_MKV = 2;
    public static final int CODEC_AVC = 1;
    public static final int CODEC_HEVC = 2;
    public static final int ENCODER_AUTO = 0;
    public static final int ENCODER_HARDWARE = 1;
    public static final int ENCODER_SOFTWARE = 2;

    private static final long MAX_PCM_BYTES = 500L * 1024L * 1024L;
    private static final float PERSISTENCE_MS = 300.0f;

    private final Context context;
    private final Uri uri;
    private final int width;
    private final int height;
    private final int fps;
    private final int format;
    private final int codec;
    private final int encoderMode;
    private final float gain;
    private final boolean mirrorX;
    private final boolean mirrorY;
    private final Listener listener;

    private int sampleRate;
    private int channels;
    private long totalAudioFrames;
    private long totalVideoFrames;

    private File pcmFile;
    private File outputFile;
    private volatile boolean canceled;
    private AudioData audioData;

    private static final class AudioSample {
        final byte[] data;
        final long ptsUs;
        AudioSample(byte[] data, long ptsUs) {
            this.data = data;
            this.ptsUs = ptsUs;
        }
    }

    private static final class AudioData {
        MediaFormat format;
        byte[] csd;
        int sampleRate;
        int channels;
        final List<AudioSample> samples = new ArrayList<>();
    }

    public XyVideoExporter(Context context, Uri uri, int width, int height, int fps,
                           int format, int codec, int encoderMode,
                           float gain, boolean mirrorX, boolean mirrorY,
                           Listener listener) {
        this.context = context.getApplicationContext();
        this.uri = uri;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.format = format;
        this.codec = codec == CODEC_HEVC ? CODEC_HEVC : CODEC_AVC;
        this.encoderMode = encoderMode;
        this.gain = Math.max(0.05f, Math.min(20f, gain));
        this.mirrorX = mirrorX;
        this.mirrorY = mirrorY;
        this.listener = listener;
    }

    public File export() throws Exception {
        if (uri == null || width <= 1 || height <= 1 || fps <= 0) {
            throw new IOException("视频参数无效");
        }
        if ((width & 1) != 0 || (height & 1) != 0) {
            throw new IOException("视频宽高必须是偶数");
        }
        if ((long) width * height > 8_000_000L) {
            throw new IOException("视频分辨率最多约 800 万像素");
        }
        String suffix = format == FORMAT_MKV ? ".mkv" : ".mp4";
        pcmFile = new File(context.getCacheDir(), "xy-video-pcm-" + System.nanoTime() + ".raw");
        outputFile = new File(context.getCacheDir(), "xy-video-" + System.nanoTime() + suffix);
        try {
            postProgress(1, "解码音频");
            decodeToPcm();
            if (isCanceled()) throw new IOException("导出已取消");
            if (sampleRate <= 0 || totalAudioFrames < 2) {
                throw new IOException("没有解码出音频样本");
            }
            totalVideoFrames = Math.max(1L,
                    (long) Math.ceil(totalAudioFrames * (double) fps / sampleRate));
            postProgress(12, "编码原音轨");
            encodeAac();
            if (isCanceled()) throw new IOException("导出已取消");
            postProgress(20, "初始化编码器");
            encodeVideo();
            postProgress(98, "完成视频");
            return outputFile;
        } catch (Exception e) {
            if (outputFile != null) outputFile.delete();
            throw e;
        } finally {
            if (pcmFile != null) pcmFile.delete();
        }
    }

    private boolean isCanceled() {
        return canceled || (listener != null && listener.isCanceled());
    }

    private void postProgress(int percent, String message) {
        if (listener != null) {
            listener.onProgress(Math.max(0, Math.min(100, percent)), message);
        }
    }

    private void decodeToPcm() throws Exception {
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
            if (mime == null) throw new IOException("无法读取音频编码");
            sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;
            channels = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 0;
            long durationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? inputFormat.getLong(MediaFormat.KEY_DURATION) : 0L;
            if (sampleRate <= 0 || channels <= 0) {
                throw new IOException("音轨缺少采样信息");
            }
            if (channels > 2) {
                throw new IOException("XY 视频目前支持单/立体声");
            }
            if (durationUs > 0L) {
                long estimate = (long) ((double) durationUs * sampleRate / 1_000_000.0)
                        * channels * 2L;
                if (estimate > MAX_PCM_BYTES) {
                    throw new IOException("音频太长，无法生成视频");
                }
            }

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();
            out = new BufferedOutputStream(new FileOutputStream(pcmFile), 1 << 16);

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
                        if (inBuf == null) throw new IOException("解码器输入缓冲区不可用");
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
                                if (floatBuffer.length < samples) floatBuffer = new float[samples];
                                FloatBuffer fb = buffer.asFloatBuffer();
                                fb.get(floatBuffer, 0, samples);
                                if (shortBuffer.length < samples) shortBuffer = new short[samples];
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
                                if (shortBuffer.length < samples) shortBuffer = new short[samples];
                                ShortBuffer sb = buffer.asShortBuffer();
                                sb.get(shortBuffer, 0, samples);
                            }
                            if (frames > 0) {
                                int byteCount = samples * 2;
                                if (bytes.length < byteCount) bytes = new byte[byteCount];
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
                    if (eos) outputDone = true;
                }
                long now = SystemClock.elapsedRealtime();
                if (now - lastProgress > 220L) {
                    lastProgress = now;
                    int percent = 1;
                    if (durationUs > 0L) {
                        long pos = extractor.getSampleTime();
                        if (pos > 0L) percent = 1 + (int) Math.min(10L, pos * 10L / durationUs);
                    }
                    postProgress(percent, "解码音频");
                }
                if (outputDone) break;
            }
            out.flush();
            out.close();
            out = null;
            if (isCanceled()) throw new IOException("导出已取消");
            if (framesWritten <= 0) throw new IOException("没有解码出音频样本");
            sampleRate = currentRate;
            channels = currentChannels;
            totalAudioFrames = framesWritten;
        } finally {
            if (out != null) {
                try { out.close(); } catch (Exception ignored) {}
            }
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            if (extractor != null) {
                try { extractor.release(); } catch (Exception ignored) {}
            }
        }
    }

    private void encodeAac() throws Exception {
        MediaCodec codec = null;
        RandomAccessFile pcm = null;
        try {
            MediaFormat fmt = MediaFormat.createAudioFormat("audio/mp4a-latm",
                    sampleRate, channels);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 192000);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            try {
                fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            } catch (Exception ignored) {}
            codec = MediaCodec.createEncoderByType("audio/mp4a-latm");
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            AudioData data = new AudioData();
            data.sampleRate = sampleRate;
            data.channels = channels;
            pcm = new RandomAccessFile(pcmFile, "r");
            int frameBytes = channels * 2;
            int framesPerChunk = 4096;
            byte[] chunk = new byte[framesPerChunk * frameBytes];
            long framesFed = 0L;
            long totalBytes = totalAudioFrames * frameBytes;
            long bytesFed = 0L;
            boolean inputDone = false;
            boolean outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long lastPct = -1L;
            while (!outputDone && !isCanceled()) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(5000);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        if (inBuf == null) throw new IOException("AAC 输入缓冲区不可用");
                        inBuf.clear();
                        long remaining = totalBytes - bytesFed;
                        int want = (int) Math.min(chunk.length, remaining);
                        int read = 0;
                        if (want > 0) {
                            pcm.readFully(chunk, 0, want);
                            read = want;
                        }
                        if (read <= 0) {
                            codec.queueInputBuffer(inIndex, 0, 0,
                                    framesFed * 1_000_000L / sampleRate,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            inBuf.put(chunk, 0, read);
                            long pts = framesFed * 1_000_000L / sampleRate;
                            codec.queueInputBuffer(inIndex, 0, read, pts, 0);
                            framesFed += read / frameBytes;
                            bytesFed += read;
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, 5000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    data.format = of;
                    data.csd = csdBytes(of);
                } else if (outIndex >= 0) {
                    if (info.size > 0
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                        if (outBuf != null) {
                            outBuf.position(info.offset);
                            outBuf.limit(info.offset + info.size);
                            byte[] sample = new byte[info.size];
                            outBuf.get(sample);
                            data.samples.add(new AudioSample(sample, info.presentationTimeUs));
                            if (data.format == null) {
                                data.format = codec.getOutputFormat();
                                data.csd = csdBytes(data.format);
                            }
                        }
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outIndex, false);
                    if (eos) outputDone = true;
                }
                long percent = 12L + 8L * Math.min(totalBytes, bytesFed)
                        / Math.max(1L, totalBytes);
                if (percent != lastPct) {
                    lastPct = percent;
                    postProgress((int) percent, "编码原音轨");
                }
            }
            if (isCanceled()) throw new IOException("导出已取消");
            if (data.format == null || data.samples.isEmpty()) {
                throw new IOException("原音轨编码失败");
            }
            if (data.csd == null || data.csd.length == 0) {
                data.csd = buildAacCsd(sampleRate, channels);
            }
            try {
                // csdBytes() consumes the MediaFormat ByteBuffer while reading it;
                // restore it so MediaMuxer.addTrack() can still see the AAC config.
                data.format.setByteBuffer("csd-0", ByteBuffer.wrap(data.csd));
            } catch (Exception ignored) {
            }
            // Normalize timestamps to the AAC frame grid so muxers always see
            // strictly increasing PTS even when an encoder omits priming info.
            for (int si = 0; si < data.samples.size(); si++) {
                AudioSample old = data.samples.get(si);
                long pts = si * 1024L * 1_000_000L / Math.max(1, data.sampleRate);
                data.samples.set(si, new AudioSample(old.data, pts));
            }
            audioData = data;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            if (pcm != null) {
                try { pcm.close(); } catch (Exception ignored) {}
            }
        }
    }

    private byte[] buildAacCsd(int rate, int channelCount) {
        int[] rates = {96000, 88200, 64000, 48000, 44100, 32000, 24000,
                22050, 16000, 12000, 11025, 8000, 7350};
        int index = 4;
        for (int i = 0; i < rates.length; i++) {
            if (rates[i] == rate) {
                index = i;
                break;
            }
        }
        int channels = Math.max(1, Math.min(7, channelCount));
        int config = (2 << 11) | (index << 7) | (channels << 3);
        return new byte[]{(byte) ((config >> 8) & 0xFF), (byte) (config & 0xFF)};
    }

    private static final class EncoderChoice {
        String name;
        int colorFormat;
        MediaCodecInfo.CodecCapabilities caps;
        int bitrate;
    }

    private String videoMime() {
        return codec == CODEC_HEVC ? "video/hevc" : "video/avc";
    }

    private boolean isSoftwareEncoder(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) {
            return info.isSoftwareOnly();
        }
        String name = info.getName() == null ? "" : info.getName().toLowerCase(Locale.US);
        return name.contains("google") || name.contains("software") || name.contains("sw.");
    }

    private String codecLabel() {
        return codec == CODEC_HEVC ? "H.265/HEVC" : "H.264/AVC";
    }

    private EncoderChoice findEncoder() throws IOException {
        int checkW = width;
        int checkH = height;
        EncoderChoice best = null;
        int bestScore = -1;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            boolean avc = false;
            for (String type : info.getSupportedTypes()) {
                if (videoMime().equalsIgnoreCase(type)) {
                    avc = true;
                    break;
                }
            }
            if (!avc) continue;
            MediaCodecInfo.CodecCapabilities caps;
            try {
                caps = info.getCapabilitiesForType(videoMime());
            } catch (Exception e) {
                continue;
            }
            MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
            if (vc == null) continue;
            try {
                if (!vc.isSizeSupported(checkW, checkH)) continue;
                if (encoderMode != ENCODER_SOFTWARE) {
                    android.util.Range<Double> rates =
                            vc.getSupportedFrameRatesFor(checkW, checkH);
                    if (rates != null && !rates.contains((double) fps)) {
                        continue;
                    }
                }
            } catch (Exception e) {
                continue;
            }
            boolean software = isSoftwareEncoder(info);
            if (encoderMode == ENCODER_SOFTWARE && !software) continue;
            if (encoderMode == ENCODER_HARDWARE && software) continue;
            int color = chooseColorFormat(caps.colorFormats);
            if (color == 0) continue;
            EncoderChoice choice = new EncoderChoice();
            choice.name = info.getName();
            choice.colorFormat = color;
            choice.caps = caps;
            long desired = (long) width * height * fps / 8L;
            desired = Math.max(4_000_000L, Math.min(80_000_000L, desired));
            try {
                android.util.Range<Integer> br = vc.getBitrateRange();
                desired = Math.max(br.getLower(), Math.min(br.getUpper(), (int) desired));
            } catch (Exception ignored) {}
            choice.bitrate = (int) desired;
            int score = 1;
            if (software) {
                score = encoderMode == ENCODER_SOFTWARE ? 3 : 0;
            } else {
                score = 2;
            }
            if (score > bestScore) {
                bestScore = score;
                best = choice;
            }
        }
        if (best != null) return best;
        throw new IOException("当前设备不支持 " + width + "×" + height
                + " @ " + fps + " fps 的 H.264 编码");
    }

    private int chooseColorFormat(int[] formats) {
        boolean semi = false;
        boolean planar = false;
        boolean flexible = false;
        for (int f : formats) {
            if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) semi = true;
            if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) planar = true;
            if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) flexible = true;
        }
        if (semi) return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
        if (planar) return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
        if (flexible) return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
        return 0;
    }

    private final class MuxSink {
        private MediaMuxer mp4;
        private MatroskaH264Writer mkv;
        private OutputStream mkvOut;
        private int videoTrack = -1;
        private int audioTrack = -1;
        private int audioIndex;
        private long lastAudioPts = -1L;
        private boolean finished;

        MuxSink(MediaFormat videoFormat) throws IOException {
            if (format == FORMAT_MP4) {
                mp4 = new MediaMuxer(outputFile.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                videoTrack = mp4.addTrack(videoFormat);
                if (audioData != null) {
                    audioTrack = mp4.addTrack(audioData.format);
                }
                mp4.start();
            } else {
                byte[] videoCsd = codec == CODEC_HEVC
                        ? csdBytes(videoFormat) : videoCsdBytes(videoFormat);
                if (videoCsd == null || videoCsd.length == 0) {
                    throw new IOException("编码器没有输出 " + codecLabel() + " 参数集");
                }
                byte[] audioCsd = audioData != null ? audioData.csd : null;
                String videoCodecId = codec == CODEC_HEVC
                        ? "V_MPEGH/ISO/HEVC" : "V_MPEG4/ISO/AVC";
                mkvOut = new BufferedOutputStream(new FileOutputStream(outputFile), 1 << 20);
                mkv = new MatroskaH264Writer(mkvOut, width, height, fps,
                        videoCsd, totalVideoFrames * 1_000_000L / fps,
                        audioData != null ? audioData.sampleRate : 0,
                        audioData != null ? audioData.channels : 0,
                        audioCsd, videoCodecId);
            }
        }

        void writeVideo(byte[] sample, long ptsUs, int flags) throws IOException {
            flushAudio(ptsUs);
            boolean key = (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
            if (format == FORMAT_MP4) {
                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                bi.set(0, sample.length, ptsUs,
                        flags & MediaCodec.BUFFER_FLAG_KEY_FRAME);
                mp4.writeSampleData(videoTrack, ByteBuffer.wrap(sample), bi);
            } else if (mkv != null) {
                mkv.writeSample(sample, ptsUs, key);
            }
        }

        private void flushAudio(long untilUs) throws IOException {
            if (audioData == null) return;
            while (audioIndex < audioData.samples.size()) {
                AudioSample sample = audioData.samples.get(audioIndex);
                if (sample.ptsUs > untilUs) break;
                long pts = Math.max(0L, sample.ptsUs);
                if (pts <= lastAudioPts) pts = lastAudioPts + 1L;
                lastAudioPts = pts;
                if (format == FORMAT_MP4 && audioTrack >= 0) {
                    MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                    bi.set(0, sample.data.length, pts, 0);
                    mp4.writeSampleData(audioTrack, ByteBuffer.wrap(sample.data), bi);
                } else if (mkv != null) {
                    mkv.writeAudioSample(sample.data, pts);
                }
                audioIndex++;
            }
        }

        void finish() throws IOException {
            if (finished) return;
            flushAudio(Long.MAX_VALUE);
            finished = true;
            if (mp4 != null) {
                mp4.stop();
                mp4.release();
                mp4 = null;
            }
            if (mkv != null) {
                mkv.finish();
                mkv = null;
            }
            if (mkvOut != null) {
                mkvOut.close();
                mkvOut = null;
            }
        }

        void abort() {
            if (finished) return;
            finished = true;
            if (mp4 != null) {
                try { mp4.stop(); } catch (Exception ignored) {}
                try { mp4.release(); } catch (Exception ignored) {}
                mp4 = null;
            }
            if (mkv != null) {
                try { mkv.finish(); } catch (Exception ignored) {}
                mkv = null;
            }
            if (mkvOut != null) {
                try { mkvOut.close(); } catch (Exception ignored) {}
                mkvOut = null;
            }
        }
    }

    private void encodeVideo() throws Exception {
        EncoderChoice choice = findEncoder();
        MediaFormat fmt = MediaFormat.createVideoFormat(videoMime(), width, height);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, choice.colorFormat);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, choice.bitrate);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        try {
            fmt.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        } catch (Exception ignored) {}
        try {
            fmt.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        } catch (Exception ignored) {}

        MediaCodec codec = null;
        MuxSink sink = null;
        RandomAccessFile pcm = null;
        Bitmap frame = null;
        Bitmap phosphor = null;
        try {
            codec = MediaCodec.createByCodecName(choice.name);
            try {
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (Exception first) {
                try { codec.release(); } catch (Exception ignored) {}
                codec = MediaCodec.createByCodecName(choice.name);
                MediaFormat retry = MediaFormat.createVideoFormat(videoMime(), width, height);
                retry.setInteger(MediaFormat.KEY_COLOR_FORMAT, choice.colorFormat);
                retry.setInteger(MediaFormat.KEY_BIT_RATE, choice.bitrate);
                retry.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                retry.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                codec.configure(retry, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            codec.start();
            frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            phosphor = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas frameCanvas = new Canvas(frame);
            Canvas phosphorCanvas = new Canvas(phosphor);
            phosphorCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            int[] argb = new int[width * height];
            byte[] yuv = new byte[width * height * 3 / 2];
            short[] audio = new short[Math.max(8192,
                    (int) Math.min(65536L, sampleRate / Math.max(1, fps) + 16))];
            float[] points = new float[Math.max(16, audio.length * 2)];
            Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
            glow.setStyle(Paint.Style.STROKE);
            glow.setStrokeCap(Paint.Cap.ROUND);
            glow.setStrokeWidth(Math.max(2f, Math.min(width, height) * 0.010f));
            glow.setColor(Color.YELLOW);
            glow.setAlpha(22);
            Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
            core.setStyle(Paint.Style.STROKE);
            core.setStrokeCap(Paint.Cap.ROUND);
            core.setStrokeWidth(Math.max(1f, Math.min(width, height) * 0.0026f));
            core.setColor(Color.YELLOW);
            core.setAlpha(220);
            Paint bg = new Paint();
            bg.setColor(Color.rgb(5, 9, 15));
            Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
            grid.setStyle(Paint.Style.STROKE);
            grid.setStrokeWidth(Math.max(1f, Math.min(width, height) * 0.0012f));
            grid.setColor(Color.rgb(32, 64, 74));
            Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
            axis.setStyle(Paint.Style.STROKE);
            axis.setStrokeWidth(Math.max(1f, Math.min(width, height) * 0.0016f));
            axis.setColor(Color.rgb(54, 94, 104));

            pcm = new RandomAccessFile(pcmFile, "r");
            long lastAudioFrame = 0L;
            long monoPrevious = 0L;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int lastProgressPercent = -1;

            for (long frameIndex = 0; frameIndex < totalVideoFrames; frameIndex++) {
                if (isCanceled()) throw new IOException("导出已取消");
                long targetAudio = Math.min(totalAudioFrames,
                        Math.round((frameIndex + 1.0) * sampleRate / fps));
                long newFrames = Math.max(0L, targetAudio - lastAudioFrame);
                if (newFrames > 0) {
                    if (audio.length < newFrames * channels) {
                        audio = new short[(int) newFrames * channels];
                    }
                    int bytes = (int) (newFrames * channels * 2);
                    byte[] raw = new byte[bytes];
                    pcm.readFully(raw);
                    ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                    ShortBuffer sb = bb.asShortBuffer();
                    sb.get(audio, 0, (int) (newFrames * channels));
                    lastAudioFrame = targetAudio;
                }

                // Fade the phosphor layer by elapsed video time.
                int fadeAlpha = 0;
                if (PERSISTENCE_MS > 1f) {
                    double tau = PERSISTENCE_MS / 3000.0;
                    double loss = 1.0 - Math.exp(-(1.0 / fps) / tau);
                    fadeAlpha = (int) Math.round(loss * 255.0);
                    if (fadeAlpha < 1) fadeAlpha = 1;
                }
                if (fadeAlpha >= 250) {
                    phosphorCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                } else if (fadeAlpha > 0) {
                    phosphorCanvas.drawColor(Color.argb(fadeAlpha, 0, 0, 0),
                            PorterDuff.Mode.DST_OUT);
                }

                if (newFrames > 0) {
                    if (points.length < newFrames * 2) {
                        points = new float[(int) newFrames * 2];
                    }
                    float half = Math.min(width, height) * 0.41f;
                    float cx = width / 2f;
                    float cy = height / 2f;
                    int count = 0;
                    for (int i = 0; i < newFrames; i++) {
                        int idx = (int) i * channels;
                        float xv;
                        float yv;
                        if (channels >= 2) {
                            xv = audio[idx] / 32768.0f;
                            yv = audio[idx + 1] / 32768.0f;
                        } else {
                            xv = monoPrevious / 32768.0f;
                            yv = audio[idx] / 32768.0f;
                            monoPrevious = audio[idx];
                        }
                        if (mirrorX) xv = -xv;
                        if (mirrorY) yv = -yv;
                        points[count * 2] = cx + xv * gain * half;
                        points[count * 2 + 1] = cy - yv * gain * half;
                        count++;
                    }
                    if (count > 0) {
                        phosphorCanvas.drawPoints(points, 0, count * 2, glow);
                        phosphorCanvas.drawPoints(points, 0, count * 2, core);
                    }
                }

                frameCanvas.drawRect(0, 0, width, height, bg);
                drawVideoGrid(frameCanvas, grid, axis);
                frameCanvas.drawBitmap(phosphor, 0, 0, null);
                frame.getPixels(argb, 0, width, 0, 0, width, height);

                int inputIndex = codec.dequeueInputBuffer(10000);
                if (inputIndex < 0) throw new IOException("视频编码器输入超时");
                long pts = frameIndex * 1_000_000L / fps;
                boolean flexible = choice.colorFormat
                        == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
                if (flexible) {
                    android.media.Image image = codec.getInputImage(inputIndex);
                    if (image == null) throw new IOException("无法获取编码输入图像");
                    fillImage(image, argb, yuv);
                    codec.queueInputBuffer(inputIndex, 0, width * height * 3 / 2, pts, 0);
                } else {
                    ByteBuffer buffer = codec.getInputBuffer(inputIndex);
                    if (buffer == null) throw new IOException("无法获取编码输入缓冲区");
                    buffer.clear();
                    fillYuv(argb, yuv, choice.colorFormat);
                    buffer.put(yuv);
                    codec.queueInputBuffer(inputIndex, 0, yuv.length, pts, 0);
                }

                int outIndex;
                while ((outIndex = codec.dequeueOutputBuffer(info, 0)) >= 0) {
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (sink == null) {
                            sink = new MuxSink(codec.getOutputFormat());
                        }
                    } else if (outIndex >= 0) {
                        ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset);
                            outBuf.limit(info.offset + info.size);
                            byte[] sample = sampleForMuxer(outBuf, info.size);
                            if (sink == null) {
                                sink = new MuxSink(codec.getOutputFormat());
                            }
                            sink.writeVideo(sample, info.presentationTimeUs, info.flags);
                        }
                        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        codec.releaseOutputBuffer(outIndex, false);
                        if (eos) outputDone = true;
                    }
                }
                int progressPercent = 20 + (int) (72L * (frameIndex + 1) / totalVideoFrames);
                if (progressPercent != lastProgressPercent
                        || frameIndex == totalVideoFrames - 1) {
                    lastProgressPercent = progressPercent;
                    postProgress(progressPercent, "渲染并编码 XY 视频");
                }
            }

            // Signal EOS and drain the remaining encoder output.
            int inIndex = codec.dequeueInputBuffer(10000);
            if (inIndex >= 0) {
                codec.queueInputBuffer(inIndex, 0, 0,
                        totalVideoFrames * 1_000_000L / fps,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            long deadline = SystemClock.elapsedRealtime() + 30000L;
            while (!outputDone && SystemClock.elapsedRealtime() < deadline && !isCanceled()) {
                int outIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (sink == null) {
                        sink = new MuxSink(codec.getOutputFormat());
                    }
                } else if (outIndex >= 0) {
                    ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        byte[] sample = sampleForMuxer(outBuf, info.size);
                        if (sink == null) {
                            sink = new MuxSink(codec.getOutputFormat());
                        }
                        sink.writeVideo(sample, info.presentationTimeUs, info.flags);
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outIndex, false);
                    if (eos) outputDone = true;
                }
            }
            if (sink == null || !outputDone) {
                throw new IOException("编码器没有完整输出视频帧");
            }
            sink.finish();
            if (outputFile == null || !outputFile.exists() || outputFile.length() == 0) {
                throw new IOException("视频文件生成失败");
            }
            postProgress(96, "封装视频");
        } finally {
            if (sink != null) sink.abort();
            try { if (codec != null) codec.stop(); } catch (Exception ignored) {}
            try { if (codec != null) codec.release(); } catch (Exception ignored) {}
            try { if (pcm != null) pcm.close(); } catch (Exception ignored) {}
            if (frame != null) frame.recycle();
            if (phosphor != null) phosphor.recycle();
        }
    }

    private byte[] videoCsdBytes(MediaFormat format) {
        ByteArrayOutputStream combined = new ByteArrayOutputStream();
        for (String key : new String[]{"csd-0", "csd-1"}) {
            try {
                ByteBuffer buf = format.getByteBuffer(key);
                if (buf == null) continue;
                buf.position(0);
                byte[] data = new byte[buf.remaining()];
                buf.get(data);
                if (data.length == 0) continue;
                if (data[0] == 1) {
                    return data; // already an AVCDecoderConfigurationRecord
                }
                boolean hasStart = data.length >= 3
                        && data[0] == 0 && data[1] == 0
                        && (data[2] == 1 || (data.length >= 4 && data[2] == 0 && data[3] == 1));
                if (!hasStart) {
                    combined.write(new byte[]{0, 0, 0, 1});
                }
                combined.write(data);
            } catch (Exception ignored) {
            }
        }
        byte[] all = combined.toByteArray();
        if (all.length == 0) return null;
        return toAvcC(all);
    }

    private byte[] csdBytes(MediaFormat format) {
        try {
            ByteBuffer csd = format.getByteBuffer("csd-0");
            if (csd == null) return null;
            csd.position(0);
            byte[] data = new byte[csd.remaining()];
            csd.get(data);
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] toAvcC(byte[] data) {
        if (data.length > 0 && data[0] == 1) return data;
        List<byte[]> spsList = new ArrayList<>();
        List<byte[]> ppsList = new ArrayList<>();
        int i = 0;
        while (i + 3 < data.length) {
            int start;
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                start = i + 3;
            } else if (i + 4 < data.length && data[i] == 0 && data[i + 1] == 0
                    && data[i + 2] == 0 && data[i + 3] == 1) {
                start = i + 4;
            } else {
                i++;
                continue;
            }
            int next = findStartCode(data, start);
            int end = next >= 0 ? next : data.length;
            if (end <= start) break;
            int type = data[start] & 0x1F;
            byte[] nal = new byte[end - start];
            System.arraycopy(data, start, nal, 0, nal.length);
            if (type == 7) spsList.add(nal);
            else if (type == 8) ppsList.add(nal);
            i = end;
        }
        if (spsList.isEmpty() || ppsList.isEmpty()) return data;
        byte[] sps = spsList.get(0);
        byte[] pps = ppsList.get(0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1);
        out.write(sps[1]);
        out.write(sps[2]);
        out.write(sps[3]);
        out.write(0xFF);
        out.write(0xE1);
        out.write((sps.length >> 8) & 0xFF);
        out.write(sps.length & 0xFF);
        out.write(sps, 0, sps.length);
        out.write(1);
        out.write((pps.length >> 8) & 0xFF);
        out.write(pps.length & 0xFF);
        out.write(pps, 0, pps.length);
        return out.toByteArray();
    }

    private byte[] sampleForMuxer(ByteBuffer buffer, int size) {
        byte[] data = new byte[size];
        buffer.get(data, 0, size);
        if (format == FORMAT_MP4) {
            // MediaMuxer expects Annex B samples and performs the conversion to
            // length-prefixed NAL units itself.  Passing AVCC here results in a
            // double length prefix and an unplayable video.
            if (isAnnexB(data)) return data;
            if (isAvcc(data)) return avccToAnnexB(data);
            return data;
        }
        if (isAnnexB(data)) return annexBToAvcc(data);
        return data;
    }

    private boolean isAvcc(byte[] data) {
        int pos = 0;
        while (pos + 4 <= data.length) {
            int len = ((data[pos] & 0xFF) << 24)
                    | ((data[pos + 1] & 0xFF) << 16)
                    | ((data[pos + 2] & 0xFF) << 8)
                    | (data[pos + 3] & 0xFF);
            if (len <= 0 || pos + 4 + len > data.length) return false;
            pos += 4 + len;
        }
        return pos == data.length && data.length > 4;
    }

    private byte[] avccToAnnexB(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        int pos = 0;
        while (pos + 4 <= data.length) {
            int len = ((data[pos] & 0xFF) << 24)
                    | ((data[pos + 1] & 0xFF) << 16)
                    | ((data[pos + 2] & 0xFF) << 8)
                    | (data[pos + 3] & 0xFF);
            if (len <= 0 || pos + 4 + len > data.length) break;
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(1);
            out.write(data, pos + 4, len);
            pos += 4 + len;
        }
        return out.toByteArray();
    }

    private byte[] sampleAvcc(ByteBuffer buffer, int size) {
        byte[] data = new byte[size];
        buffer.get(data, 0, size);
        if (isAnnexB(data)) {
            return annexBToAvcc(data);
        }
        return data;
    }

    private boolean isAnnexB(byte[] data) {
        if (data.length >= 4 && data[0] == 0 && data[1] == 0
                && data[2] == 0 && data[3] == 1) {
            return true;
        }
        return data.length >= 3 && data[0] == 0 && data[1] == 0 && data[2] == 1;
    }

    private byte[] annexBToAvcc(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        int pos = 0;
        while (pos < data.length) {
            int start = findStartCode(data, pos);
            if (start < 0) break;
            int payload = start + startCodeLength(data, start);
            int next = findStartCode(data, payload);
            int end = next >= 0 ? next : data.length;
            while (end > payload && data[end - 1] == 0) {
                end--;
            }
            if (end > payload) {
                int len = end - payload;
                out.write((len >> 24) & 0xFF);
                out.write((len >> 16) & 0xFF);
                out.write((len >> 8) & 0xFF);
                out.write(len & 0xFF);
                out.write(data, payload, len);
            }
            pos = end;
        }
        return out.toByteArray();
    }

    private int findStartCode(byte[] data, int from) {
        for (int i = from; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) return i;
                if (i + 3 < data.length && data[i + 2] == 0 && data[i + 3] == 1) return i;
            }
        }
        return -1;
    }

    private int startCodeLength(byte[] data, int at) {
        return at + 3 < data.length && data[at + 2] == 0 && data[at + 3] == 1 ? 4 : 3;
    }

    private void fillYuv(int[] argb, byte[] yuv, int colorFormat) {
        int w = width;
        int h = height;
        int ySize = w * h;
        boolean semi = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
        int quarter = ySize / 4;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int color = argb[row + x];
                int r = (color >> 16) & 0xFF;
                int g = (color >> 8) & 0xFF;
                int b = color & 0xFF;
                int yy = (int) (0.299f * r + 0.587f * g + 0.114f * b);
                yuv[y * w + x] = (byte) Math.max(0, Math.min(255, yy));
            }
        }
        int uv = ySize;
        for (int y = 0; y < h / 2; y++) {
            for (int x = 0; x < w / 2; x++) {
                int r = 0, g = 0, b = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int color = argb[(y * 2 + dy) * w + x * 2 + dx];
                        r += (color >> 16) & 0xFF;
                        g += (color >> 8) & 0xFF;
                        b += color & 0xFF;
                    }
                }
                r /= 4; g /= 4; b /= 4;
                int u = (int) (-0.169f * r - 0.331f * g + 0.5f * b + 128);
                int v = (int) (0.5f * r - 0.419f * g - 0.081f * b + 128);
                u = Math.max(0, Math.min(255, u));
                v = Math.max(0, Math.min(255, v));
                int idx = y * (w / 2) + x;
                if (semi) {
                    yuv[uv++] = (byte) u;
                    yuv[uv++] = (byte) v;
                } else {
                    yuv[ySize + idx] = (byte) u;
                    yuv[ySize + quarter + idx] = (byte) v;
                }
            }
        }
    }

    private void fillImage(android.media.Image image, int[] argb, byte[] yuv) {
        fillYuv(argb, yuv, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
        int w = width;
        int h = height;
        android.media.Image.Plane[] planes = image.getPlanes();
        ByteBuffer yPlane = planes[0].getBuffer();
        int yRow = planes[0].getRowStride();
        int yPixel = planes[0].getPixelStride();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                yPlane.put(y * yRow + x * yPixel, yuv[y * w + x]);
            }
        }
        int uvW = w / 2;
        int uvH = h / 2;
        byte[] u = new byte[uvW * uvH];
        byte[] v = new byte[uvW * uvH];
        int ySize = w * h;
        System.arraycopy(yuv, ySize, u, 0, u.length);
        System.arraycopy(yuv, ySize + u.length, v, 0, v.length);
        for (int p = 1; p <= 2; p++) {
            ByteBuffer buf = planes[p].getBuffer();
            int rowStride = planes[p].getRowStride();
            int pixelStride = planes[p].getPixelStride();
            byte[] src = p == 1 ? u : v;
            for (int y = 0; y < uvH; y++) {
                for (int x = 0; x < uvW; x++) {
                    buf.put(y * rowStride + x * pixelStride, src[y * uvW + x]);
                }
            }
        }
    }

    private void drawVideoGrid(Canvas canvas, Paint grid, Paint axis) {
        float margin = Math.min(width, height) * 0.08f;
        RectF plot = new RectF(margin, margin, width - margin, height - margin);
        float cx = plot.centerX();
        float cy = plot.centerY();
        float rx = plot.width() / 2f;
        float ry = plot.height() / 2f;
        canvas.drawRect(plot, axis);
        canvas.drawLine(plot.left, cy, plot.right, cy, grid);
        canvas.drawLine(cx, plot.top, cx, plot.bottom, grid);
        for (int i = 1; i <= 3; i++) {
            RectF r = new RectF(cx - rx * i / 3f, cy - ry * i / 3f,
                    cx + rx * i / 3f, cy + ry * i / 3f);
            canvas.drawOval(r, i == 2 ? axis : grid);
        }
    }
}
