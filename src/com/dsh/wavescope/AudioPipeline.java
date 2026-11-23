package com.dsh.wavescope;

/**
 * Lock-protected ring buffer holding the most recent raw interleaved PCM.
 * Nothing is resampled: every captured / decoded sample is appended as-is.
 */
public final class AudioPipeline {

    private static final int CAPACITY_SAMPLES = 1 << 20; // 1,048,576 interleaved samples

    private final short[] ring = new short[CAPACITY_SAMPLES];
    private final Object lock = new Object();

    private long writtenSamples;
    private int sampleRate;
    private int channelCount = 1;
    private boolean hasData;
    private int generation;

    public void configure(int sampleRate, int channels) {
        synchronized (lock) {
            this.sampleRate = sampleRate > 0 ? sampleRate : 0;
            this.channelCount = channels > 0 ? channels : 1;
            this.writtenSamples = 0;
            this.hasData = false;
            this.generation++;
        }
    }

    public void append(short[] samples, int offset, int length) {
        if (samples == null || length <= 0) {
            return;
        }
        synchronized (lock) {
            int channels = channelCount;
            if (channels < 1) {
                channels = 1;
            }
            int usable = length - (length % channels);
            if (usable <= 0) {
                return;
            }
            int safeOffset = Math.max(0, offset);
            if (safeOffset > samples.length) {
                return;
            }
            usable = Math.min(usable, samples.length - safeOffset);
            usable -= usable % channels;
            if (usable <= 0) {
                return;
            }

            for (int i = 0; i < usable; i++) {
                int index = (int) (writtenSamples & (CAPACITY_SAMPLES - 1));
                ring[index] = samples[safeOffset + i];
                writtenSamples++;
            }
            hasData = true;
        }
    }

    /**
     * Copies the newest whole frames (up to maxFrames) into dst.
     *
     * @return number of frames copied
     */
    public int copyLatest(short[] dst, int maxFrames) {
        if (dst == null || maxFrames <= 0) {
            return 0;
        }
        synchronized (lock) {
            int channels = channelCount;
            if (channels < 1) {
                channels = 1;
            }
            long availableSamples = Math.min(writtenSamples, CAPACITY_SAMPLES);
            int availableFrames = (int) (availableSamples / channels);
            int frames = Math.min(maxFrames, availableFrames);
            int samples = frames * channels;
            if (samples <= 0 || dst.length < samples) {
                return 0;
            }

            long start = writtenSamples - samples;
            for (int i = 0; i < samples; i++) {
                int index = (int) ((start + i) & (CAPACITY_SAMPLES - 1));
                dst[i] = ring[index];
            }
            return frames;
        }
    }

    public int getSampleRate() {
        synchronized (lock) {
            return sampleRate;
        }
    }

    public int getChannelCount() {
        synchronized (lock) {
            return channelCount;
        }
    }

    public int getGeneration() {
        synchronized (lock) {
            return generation;
        }
    }

    public long getTotalFrames() {
        synchronized (lock) {
            int channels = Math.max(1, channelCount);
            return writtenSamples / channels;
        }
    }

    public long getEarliestFrame() {
        synchronized (lock) {
            int channels = Math.max(1, channelCount);
            long available = Math.min(writtenSamples, CAPACITY_SAMPLES);
            return (writtenSamples - available) / channels;
        }
    }

    /**
     * Copies frames from an absolute frame position.  Used by the persistent
     * XY renderer so that every captured frame is eventually drawn instead of
     * showing only the latest fixed window.
     */
    public int copyFramesFrom(long startFrame, short[] dst, int maxFrames) {
        if (dst == null || maxFrames <= 0) {
            return 0;
        }
        synchronized (lock) {
            int channels = Math.max(1, channelCount);
            long available = Math.min(writtenSamples, CAPACITY_SAMPLES);
            long earliest = (writtenSamples - available) / channels;
            long totalFrames = writtenSamples / channels;
            long from = Math.max(startFrame, earliest);
            if (from >= totalFrames) {
                return 0;
            }
            int frames = (int) Math.min(Math.min(totalFrames - from, maxFrames),
                    dst.length / channels);
            if (frames <= 0) {
                return 0;
            }
            long startSample = from * channels;
            int samples = frames * channels;
            for (int i = 0; i < samples; i++) {
                int index = (int) ((startSample + i) & (CAPACITY_SAMPLES - 1));
                dst[i] = ring[index];
            }
            return frames;
        }
    }

    public boolean hasData() {
        synchronized (lock) {
            return hasData;
        }
    }

    public void clear() {
        synchronized (lock) {
            writtenSamples = 0;
            hasData = false;
            generation++;
        }
    }
}
