package com.dsh.wavescope;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent display / analysis parameters. No audio format is ever configured here. */
public final class AppConfig {

    public static final int MODE_XT = 0;
    public static final int MODE_XY = 1;
    public static final int MODE_FFT = 2;
    public static final int MODE_RAW = 3;

    public static final int[] FFT_SIZES = {1024, 2048, 4096, 8192, 16384};

    public float gain = 1.0f;
    public float timePerDivMs = 2.0f;
    public int fftSize = 4096;
    public boolean logFrequency = true;
    public float triggerLevel = 0.0f;
    public boolean triggerRising = true;
    public boolean autoTrigger = true;
    public int mode = MODE_XT;

    /** XY phosphor afterglow, milliseconds. 0 = no persistence. */
    public float xyPersistenceMs = 220.0f;
    /** Cubic interpolation between raw samples for the XY vector trace. */
    public boolean xySmooth = true;
    /** Mirror the XY horizontal axis (X -> -X). */
    public boolean xyMirrorX = false;
    /** Mirror the XY vertical axis (Y -> -Y). */
    public boolean xyMirrorY = false;
    public boolean xySwap = false;
    public boolean xyPixelMode = false;
    public float xyPixelSizeDp = 2.5f;
    public int targetFps = 60;
    public boolean showMeasurements = true;
    /** Waterfall scroll speed multiplier (0 = paused). */
    public float waterfallSpeed = 1.0f;
    /** Black/white levels for the waterfall heatmap (0..1). */
    public float waterfallBlack = 0.0f;
    public float waterfallWhite = 1.0f;

    public static AppConfig defaults() {
        return new AppConfig();
    }

    public void sanitize() {
        if (gain < 0.2f) gain = 0.2f;
        if (gain > 20.0f) gain = 20.0f;
        if (timePerDivMs < 0.1f) timePerDivMs = 0.1f;
        if (timePerDivMs > 100.0f) timePerDivMs = 100.0f;
        if (triggerLevel < -1.0f) triggerLevel = -1.0f;
        if (triggerLevel > 1.0f) triggerLevel = 1.0f;
        if (xyPersistenceMs < 0.0f) xyPersistenceMs = 0.0f;
        if (xyPersistenceMs > 2000.0f) xyPersistenceMs = 2000.0f;
        boolean validFft = false;
        for (int size : FFT_SIZES) {
            if (size == fftSize) {
                validFft = true;
                break;
            }
        }
        if (!validFft) {
            fftSize = 4096;
        }
        if (mode == MODE_RAW || mode < MODE_XT || mode > MODE_RAW) {
            mode = MODE_XT;
        }
    }

    public AppConfig copy() {
        AppConfig c = new AppConfig();
        c.gain = gain;
        c.timePerDivMs = timePerDivMs;
        c.fftSize = fftSize;
        c.logFrequency = logFrequency;
        c.triggerLevel = triggerLevel;
        c.triggerRising = triggerRising;
        c.autoTrigger = autoTrigger;
        c.mode = mode;
        c.xyPersistenceMs = xyPersistenceMs;
        c.xySmooth = xySmooth;
        c.xyMirrorX = xyMirrorX;
        c.xyMirrorY = xyMirrorY;
        c.xySwap = xySwap;
        c.xyPixelMode = xyPixelMode;
        c.xyPixelSizeDp = xyPixelSizeDp;
        c.targetFps = targetFps;
        c.showMeasurements = showMeasurements;
        c.waterfallSpeed = waterfallSpeed;
        c.waterfallBlack = waterfallBlack;
        c.waterfallWhite = waterfallWhite;
        return c;
    }

    public static AppConfig load(Context context) {
        SharedPreferences p = context.getSharedPreferences("wavescope", Context.MODE_PRIVATE);
        AppConfig c = defaults();
        c.gain = p.getFloat("gain", c.gain);
        c.timePerDivMs = p.getFloat("timePerDiv", c.timePerDivMs);
        c.fftSize = p.getInt("fftSize", c.fftSize);
        c.logFrequency = p.getBoolean("logFreq", c.logFrequency);
        c.triggerLevel = p.getFloat("trigger", c.triggerLevel);
        c.triggerRising = p.getBoolean("triggerRising", c.triggerRising);
        c.autoTrigger = p.getBoolean("autoTrigger", c.autoTrigger);
        c.mode = p.getInt("mode", c.mode);
        c.xyPersistenceMs = p.getFloat("xyPersistence", c.xyPersistenceMs);
        c.xySmooth = p.getBoolean("xySmooth", c.xySmooth);
        c.xyMirrorX = p.getBoolean("xyMirrorX", c.xyMirrorX);
        c.xyMirrorY = p.getBoolean("xyMirrorY",
                p.getBoolean("xyFlipY", c.xyMirrorY));
        c.xySwap = p.getBoolean("xySwap", c.xySwap);
        c.xyPixelMode = p.getBoolean("xyPixelMode", c.xyPixelMode);
        c.xyPixelSizeDp = p.getFloat("xyPixelSizeDp", c.xyPixelSizeDp);
        c.targetFps = p.getInt("targetFps", c.targetFps);
        c.showMeasurements = p.getBoolean("showMeasurements", c.showMeasurements);
        c.waterfallSpeed = p.getFloat("waterfallSpeed", c.waterfallSpeed);
        c.waterfallBlack = p.getFloat("waterfallBlack", c.waterfallBlack);
        c.waterfallWhite = p.getFloat("waterfallWhite", c.waterfallWhite);
        c.sanitize();
        return c;
    }

    public void save(Context context) {
        sanitize();
        SharedPreferences p = context.getSharedPreferences("wavescope", Context.MODE_PRIVATE);
        p.edit()
                .putFloat("gain", gain)
                .putFloat("timePerDiv", timePerDivMs)
                .putInt("fftSize", fftSize)
                .putBoolean("logFreq", logFrequency)
                .putFloat("trigger", triggerLevel)
                .putBoolean("triggerRising", triggerRising)
                .putBoolean("autoTrigger", autoTrigger)
                .putInt("mode", mode)
                .putFloat("xyPersistence", xyPersistenceMs)
                .putBoolean("xySmooth", xySmooth)
                .putBoolean("xyMirrorX", xyMirrorX)
                .putBoolean("xyMirrorY", xyMirrorY)
                .putBoolean("xySwap", xySwap)
                .putBoolean("xyPixelMode", xyPixelMode)
                .putFloat("xyPixelSizeDp", xyPixelSizeDp)
                .putInt("targetFps", targetFps)
                .putBoolean("showMeasurements", showMeasurements)
                .putFloat("waterfallSpeed", waterfallSpeed)
                .putFloat("waterfallBlack", waterfallBlack)
                .putFloat("waterfallWhite", waterfallWhite)
                .apply();
    }
}
