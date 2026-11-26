package com.dsh.wavescope;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Locale;

/**
 * Streams a decoded file at its native sample rate into an AudioTrack and the
 * visualisation pipeline. Decoding and analysis never call a resampler.
 */
public final class FileAudioPlayer {

    public interface Listener {
        void onPreparing();

        void onReady(int sampleRate, int channels, long durationUs);

        void onPlayingChanged(boolean playing);

        void onProgress(long positionUs, long durationUs);

        void onCompleted();

        void onError(String message);
    }

    private final Context context;
    private final AudioPipeline pipeline;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object stateLock = new Object();

    private volatile boolean stopRequested;
    private volatile boolean paused;
    private volatile AudioTrack audioTrack;
    private volatile AudioTrack clockTrack;
    private volatile int clockRate = 48000;
    private volatile long clockBaseFrames;
    private volatile long clockStartUs;
    private volatile boolean released;

    private Thread thread;
    private Uri currentUri;
    private int generation;
    private boolean playing;

    public FileAudioPlayer(Context context, AudioPipeline pipeline, Listener listener) {
        this.context = context.getApplicationContext();
        this.pipeline = pipeline;
        this.listener = listener;
    }

    public Uri getCurrentUri() {
        return currentUri;
    }

    public boolean isPlaying() {
        return playing && !paused;
    }

    public boolean isPaused() {
        return paused;
    }

    public long getPositionUs() {
        AudioTrack track = clockTrack;
        if (track == null) return 0L;
        int head = track.getPlaybackHeadPosition();
        long frames = clockBaseFrames + head;
        return clockStartUs + frames * 1_000_000L / Math.max(1, clockRate);
    }

    public void open(Uri uri) {
        openAt(uri, 0L);
    }

    public void openAt(Uri uri, long startUs) {
        if (uri == null) {
            return;
        }
        synchronized (stateLock) {
            stopLocked();
            generation++;
            final int gen = generation;
            currentUri = uri;
            paused = false;
            stopRequested = false;
            playing = false;
            pipeline.clear();
            postPreparing(gen);
            final long start = Math.max(0L, startUs);
            thread = new Thread(() -> runPlayback(uri, gen, start), "WaveScope-File");
            thread.start();
        }
    }

    public void pause() {
        if (thread == null) {
            return;
        }
        paused = true;
        AudioTrack track = audioTrack;
        if (track != null) {
            try {
                track.pause();
            } catch (IllegalStateException ignored) {
            }
        }
        playing = false;
        final int gen;
        synchronized (stateLock) {
            gen = generation;
        }
        main.post(() -> {
            if (!released && gen == generation) {
                listener.onPlayingChanged(false);
            }
        });
    }

    public void resume() {
        if (thread == null) {
            return;
        }
        paused = false;
        playing = true;
        AudioTrack track = audioTrack;
        if (track != null) {
            try {
                track.play();
            } catch (IllegalStateException ignored) {
            }
        }
        final int gen;
        synchronized (stateLock) {
            gen = generation;
        }
        main.post(() -> {
            if (!released && gen == generation) {
                listener.onPlayingChanged(true);
            }
        });
    }

    public void stop() {
        synchronized (stateLock) {
            generation++;
            stopLocked();
            paused = false;
            playing = false;
            currentUri = null;
        }
    }

    public void release() {
        released = true;
        synchronized (stateLock) {
            generation++;
            stopLocked();
        }
    }

    private void stopLocked() {
        stopRequested = true;
        AudioTrack track = audioTrack;
        if (track != null) {
            try {
                track.pause();
                track.flush();
            } catch (IllegalStateException ignored) {
            }
        }
        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runPlayback(Uri uri, int gen, long startUs) {
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        AudioTrack track = null;
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
            if (startUs > 0L) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }
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
                throw new IOException("当前支持单声道 / 立体声，检测到 " + channels + " 声道");
            }
            pipeline.configure(rate, channels);
            postReady(gen, rate, channels, durationUs);

            MediaCodec decoder = MediaCodec.createDecoderByType(mime);
            codec = decoder;
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            boolean inputDone = false;
            boolean outputDone = false;
            int currentRate = rate;
            int currentChannels = channels;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            long framesInTrack = 0;
            long baseFrames = 0;
            long progressBaseUs = startUs;
            long lastProgressAt = 0;
            boolean playStarted = false;
            ByteBuffer outputBuffer = null;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean outputFormatKnown = false;

            while (!stopRequested) {
                if (paused) {
                    sleep(15);
                    continue;
                }

                if (!inputDone) {
                    int inIndex = decoder.dequeueInputBuffer(5000);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = decoder.getInputBuffer(inIndex);
                        if (inBuf == null) {
                            throw new IOException("解码器输入缓冲区不可用");
                        }
                        int sampleSize = extractor.readSampleData(inBuf, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = decoder.dequeueOutputBuffer(info, 5000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = decoder.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        currentRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        currentChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                    if (pcmEncoding != AudioFormat.ENCODING_PCM_16BIT
                            && pcmEncoding != AudioFormat.ENCODING_PCM_FLOAT) {
                        throw new IOException("不支持的 PCM 编码格式");
                    }
                    if (currentChannels > 2 || currentRate <= 0) {
                        throw new IOException("解码器输出格式不受支持");
                    }
                    if (!outputFormatKnown) {
                        outputFormatKnown = true;
                        pipeline.configure(currentRate, currentChannels);
                        postReady(gen, currentRate, currentChannels, durationUs);
                    }
                    if (track != null) {
                        releaseTrack(track);
                        track = null;
                        baseFrames += framesInTrack;
                        framesInTrack = 0;
                    }
                } else if (outIndex >= 0) {
                    if (info.size > 0) {
                        if (track == null) {
                            track = createTrack(currentRate, currentChannels, pcmEncoding);
                            audioTrack = track;
                            clockTrack = track;
                            clockRate = currentRate;
                            clockBaseFrames = baseFrames;
                            clockStartUs = startUs;
                            if (track == null) {
                                throw new IOException("无法创建原生采样率输出（" + currentRate + " Hz）");
                            }
                            track.play();
                            if (!playStarted) {
                                playStarted = true;
                                playing = true;
                                postPlaying(gen, true);
                            }
                        }
                        outputBuffer = decoder.getOutputBuffer(outIndex);
                        if (outputBuffer != null && info.size > 0) {
                            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                int frames = info.size / 4 / currentChannels;
                                ensureFloatBuffer(frames * currentChannels);
                                outputBuffer.position(info.offset);
                                outputBuffer.order(ByteOrder.nativeOrder());
                                FloatBuffer fb = outputBuffer.asFloatBuffer();
                                fb.get(floatScratch, 0, frames * currentChannels);
                                int skipFrames = 0;
                                long pts = info.presentationTimeUs;
                                if (startUs > 0L && pts >= 0L && pts < startUs) {
                                    skipFrames = (int) Math.min(frames,
                                            Math.round((startUs - pts) * (double) currentRate
                                                    / 1_000_000.0));
                                }
                                int skipSamples = skipFrames * currentChannels;
                                int writeSamples = frames * currentChannels - skipSamples;
                                if (writeSamples > 0) {
                                    int writtenFrames = writeFloat(track, floatScratch,
                                            skipSamples, writeSamples, currentChannels);
                                    framesInTrack += writtenFrames;
                                }
                            } else {
                                int samples = info.size / 2;
                                int frames = samples / currentChannels;
                                ensureShortBuffer(samples);
                                outputBuffer.position(info.offset);
                                outputBuffer.order(ByteOrder.nativeOrder());
                                ShortBuffer sb = outputBuffer.asShortBuffer();
                                sb.get(shortScratch, 0, samples);
                                int skipFrames = 0;
                                long pts = info.presentationTimeUs;
                                if (startUs > 0L && pts >= 0L && pts < startUs) {
                                    skipFrames = (int) Math.min(frames,
                                            Math.round((startUs - pts) * (double) currentRate
                                                    / 1_000_000.0));
                                }
                                int skipSamples = skipFrames * currentChannels;
                                int writeSamples = frames * currentChannels - skipSamples;
                                if (writeSamples > 0) {
                                    int writtenFrames = writeShort(track, shortScratch,
                                            skipSamples, writeSamples, currentChannels);
                                    framesInTrack += writtenFrames;
                                }
                            }
                        }
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(outIndex, false);
                    if (eos) {
                        outputDone = true;
                    }
                }

                long now = SystemClock.uptimeMillis();
                if (now - lastProgressAt >= 220) {
                    lastProgressAt = now;
                    postProgress(gen, track, framesInTrack, baseFrames, progressBaseUs, currentRate, durationUs);
                }

                if (outputDone && track != null) {
                    break;
                }
                if (outputDone && track == null) {
                    break;
                }
            }

            if (stopRequested) {
                return;
            }

            if (track != null) {
                drainAndFinish(gen, track, framesInTrack, baseFrames, progressBaseUs, currentRate, durationUs);
            } else {
                postCompleted(gen);
            }
        } catch (Exception e) {
            if (!stopRequested) {
                String msg = e.getMessage();
                if (msg == null || msg.length() == 0) {
                    msg = e.getClass().getSimpleName();
                }
                postError(gen, "音频解码失败：" + msg);
            }
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
            if (track != null) {
                releaseTrack(track);
            }
            if (audioTrack == track) {
                audioTrack = null;
            }
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Exception ignored) {
                }
            }
            playing = false;
            postPlaying(gen, false);
        }
    }

    private void drainAndFinish(int gen, AudioTrack track, long framesInTrack, long baseFrames,
                                long progressBaseUs, int rate, long durationUs) {
        long lastProgressAt = 0;
        long deadline = SystemClock.uptimeMillis()
                + Math.max(1500L, framesInTrack * 1000L / Math.max(1, rate) + 1500L);
        while (!stopRequested && SystemClock.uptimeMillis() < deadline) {
            if (paused) {
                sleep(20);
                continue;
            }
            long head = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            long now = SystemClock.uptimeMillis();
            if (now - lastProgressAt > 200) {
                lastProgressAt = now;
                postProgress(gen, track, framesInTrack, baseFrames, progressBaseUs, rate, durationUs);
            }
            if (head >= framesInTrack) {
                break;
            }
            sleep(25);
        }
        if (!stopRequested) {
            main.post(() -> {
                if (!released && gen == generation) {
                    listener.onProgress(durationUs, durationUs);
                    listener.onPlayingChanged(false);
                    listener.onCompleted();
                }
            });
        }
    }

    private void postProgress(int gen, AudioTrack track, long framesInTrack,
                              long baseFrames, long progressBaseUs,
                              int rate, long durationUs) {
        if (track == null || rate <= 0) {
            return;
        }
        long head = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        long us = progressBaseUs + (baseFrames + head) * 1_000_000L / rate;
        if (durationUs > 0 && us > durationUs) {
            us = durationUs;
        }
        final long progressUs = us;
        main.post(() -> {
            if (!released && gen == generation) {
                listener.onProgress(progressUs, durationUs);
            }
        });
    }

    private AudioTrack createTrack(int rate, int channels, int encoding) {
        int channelMask = channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO
                : AudioFormat.CHANNEL_OUT_MONO;
        int minBuffer = AudioTrack.getMinBufferSize(rate, channelMask, encoding);
        int bytesPerFrame = (encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2) * channels;
        if (minBuffer <= 0) {
            minBuffer = rate / 10 * bytesPerFrame;
        }
        int bufferSize = Math.max(minBuffer * 2, Math.max(8192, rate / 5 * bytesPerFrame));
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(rate)
                .setChannelMask(channelMask)
                .build();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            try {
                track.release();
            } catch (Exception ignored) {
            }
            return null;
        }
        return track;
    }

    private void releaseTrack(AudioTrack track) {
        try {
            track.pause();
        } catch (Exception ignored) {
        }
        try {
            track.flush();
        } catch (Exception ignored) {
        }
        try {
            track.stop();
        } catch (Exception ignored) {
        }
        try {
            track.release();
        } catch (Exception ignored) {
        }
        if (clockTrack == track) {
            clockTrack = null;
        }
        if (audioTrack == track) {
            audioTrack = null;
        }
    }

    private int writeShort(AudioTrack track, short[] data, int startSample,
                           int samples, int channels) {
        int offset = startSample;
        int end = startSample + samples;
        int chunk = Math.max(channels, 1024 * channels);
        while (offset < end && !stopRequested) {
            int count = Math.min(end - offset, chunk);
            count -= count % channels;
            if (count <= 0) {
                break;
            }
            int written;
            try {
                written = track.write(data, offset, count, AudioTrack.WRITE_BLOCKING);
            } catch (IllegalStateException e) {
                break;
            }
            int usable = written - (written % channels);
            if (usable <= 0) {
                break;
            }
            // Feed the visualiser in ~23 ms slices instead of one large decoder
            // buffer.  Playback itself remains bit-for-bit at the source rate.
            pipeline.append(data, offset, usable);
            offset += usable;
        }
        return (offset - startSample) / channels;
    }

    private int writeFloat(AudioTrack track, float[] data, int startSample,
                           int samples, int channels) {
        int offset = startSample;
        int end = startSample + samples;
        int chunk = Math.max(channels, 1024 * channels);
        while (offset < end && !stopRequested) {
            int count = Math.min(end - offset, chunk);
            count -= count % channels;
            if (count <= 0) {
                break;
            }
            int written;
            try {
                written = track.write(data, offset, count, AudioTrack.WRITE_BLOCKING);
            } catch (IllegalStateException e) {
                break;
            }
            int usable = written - (written % channels);
            if (usable <= 0) {
                break;
            }
            convertFloatToShort(data, offset, usable);
            pipeline.append(shortScratch, 0, usable);
            offset += usable;
        }
        return (offset - startSample) / channels;
    }

    private void convertFloatToShort(float[] src, int srcOffset, int count) {
        ensureShortBuffer(count);
        for (int i = 0; i < count; i++) {
            float v = src[srcOffset + i];
            if (v > 1.0f) v = 1.0f;
            if (v < -1.0f) v = -1.0f;
            shortScratch[i] = (short) Math.round(v * 32767.0f);
        }
    }

    private void ensureShortBuffer(int size) {
        // Re-allocation happens only for unusually large decoder output chunks.
        // The field is not shared, so a local trade-off is fine here.
        if (shortScratch.length < size) {
            shortScratch = new short[size];
        }
    }

    private void ensureFloatBuffer(int size) {
        if (floatScratch.length < size) {
            floatScratch = new float[size];
        }
    }

    private short[] shortScratch = new short[8192];
    private float[] floatScratch = new float[4096];

    private void postPreparing(int gen) {
        main.post(() -> {
            if (!released && gen == generation) {
                listener.onPreparing();
            }
        });
    }

    private void postReady(int gen, int rate, int channels, long durationUs) {
        main.post(() -> {
            if (!released && gen == generation) {
                listener.onReady(rate, channels, durationUs);
            }
        });
    }

    private void postPlaying(int gen, boolean p) {
        main.post(() -> {
            if (!released && gen == generation) {
                listener.onPlayingChanged(p);
            }
        });
    }

    private void postProgress(int gen, long posUs, long durationUs) {
        main.post(() -> {
            if (!released && gen == generation) {
                listener.onProgress(posUs, durationUs);
            }
        });
    }

    private void postCompleted(int gen) {
        main.post(() -> {
            if (!released && gen == generation) {
                listener.onCompleted();
            }
        });
    }

    private void postError(int gen, String message) {
        main.post(() -> {
            if (!released && gen == generation) {
                listener.onError(message);
            }
        });
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
