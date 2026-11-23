package com.dsh.wavescope;

/** Min/max waveform envelope for the full imported file. */
public final class WaveformData {
    public final float[] minL;
    public final float[] maxL;
    public final float[] minR;
    public final float[] maxR;
    public final int buckets;
    public final int sampleRate;
    public final int channels;
    public final long durationUs;
    public final long totalFrames;

    public WaveformData(float[] minL, float[] maxL, float[] minR, float[] maxR,
                        int buckets, int sampleRate, int channels,
                        long durationUs, long totalFrames) {
        this.minL = minL;
        this.maxL = maxL;
        this.minR = minR;
        this.maxR = maxR;
        this.buckets = buckets;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.durationUs = durationUs;
        this.totalFrames = totalFrames;
    }
}
