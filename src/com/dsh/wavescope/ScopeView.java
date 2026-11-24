package com.dsh.wavescope;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Renders a true-PCM oscilloscope, Lissajous view and FFT spectrum.
 * It only reads from {@link AudioPipeline}; it never converts the sample rate.
 */
public final class ScopeView extends View {

    public interface OnSeekListener {
        void onSeek(long positionUs);
    }

    public interface AnalysisListener {
        void onAnalysis(float peakDb, float dominantHz, int sampleRate, int channels, boolean hasSignal);
    }

    private static final int MODE_XT = AppConfig.MODE_XT;
    private static final int MODE_XY = AppConfig.MODE_XY;
    private static final int MODE_FFT = AppConfig.MODE_FFT;
    private static final int MODE_RAW = AppConfig.MODE_RAW;
    private static final float RAW_WINDOW_MS = 1000.0f;

    private static final int MAX_RENDER_FRAMES = 300000;
    private static final int XY_MAX_COPY_FRAMES = 65536;
    private static final float DB_FLOOR = -100.0f;
    private static final float[] ZOOM_LEVELS = {1.0f, 2.0f, 4.0f, 8.0f};
    private static final int MAX_CHART_PICKS = 24;

    private AudioPipeline pipeline;
    private AnalysisListener analysisListener;
    private OnSeekListener seekListener;

    private int mode = MODE_XT;
    private float gain = 1.0f;
    private float timePerDivMs = 2.0f;
    private int fftSize = 4096;
    private boolean logFrequency = true;
    private float triggerLevel = 0.0f;
    private boolean triggerRising = true;
    private boolean autoTrigger = true;
    private boolean frozen;

    private boolean pickMode;
    private boolean trackMode;
    private int chartZoomIndex;
    private float zoomX = 1.0f;
    private float zoomY = 1.0f;
    private float fftZoomCenterHz = 1000.0f;
    private short[] freezeBuffer = new short[0];
    private int freezeFrames;
    private int freezeRate;
    private int freezeChannels;
    private GestureDetector doubleTapDetector;
    private final List<ChartPick> chartPicks = new ArrayList<>();
    private float pointerU = -1.0f;
    private float pointerV = -1.0f;
    private boolean pointerVisible;
    private float touchDownX;
    private float touchDownY;
    private boolean fitValid;
    private float fitNormA;
    private float fitNormB;
    private float fitR2;
    private String fitEquation = "";
    private int fitCount;

    private float xyPersistenceMs = 320.0f;
    private boolean xySmooth = true;
    private boolean xyMirrorX = false;
    private boolean xyMirrorY = false;
    private boolean xySwap = false;
    private boolean xyPixelMode = false;
    private float xyPixelSizeDp = 2.5f;
    private boolean showMeasurements = true;
    private boolean preRenderFft = true;
    private boolean spectrumCacheValid;
    private long spectrumCacheAt;
    private float waterfallSpeed = 1.0f;
    private float waterfallBlack = 0.0f;
    private float waterfallWhite = 1.0f;
    private float waterfallScrollAccum;

    private String hintTitle = "等待音频信号";
    private String hintBody = "导入音频 · 监听麦克风 · 捕获媒体音频";

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridMajorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zeroPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint traceL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint traceR = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowR = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint addL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint addR = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint addGlowL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint addGlowR = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint triggerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint modePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint monoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tinyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintBodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintDashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint xyGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint xyCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint xyBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayMarkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF overlayBox = new RectF();
    private final RectF historyPlot = new RectF();
    private Bitmap specHistoryBitmap;
    private Bitmap specHistoryScratch;
    private Canvas specHistoryCanvas;
    private Canvas specHistoryScratchCanvas;
    private int specHistoryW;
    private int specHistoryH;
    private int[] specHistoryRow = new int[0];

    private final RectF plot = new RectF();
    private final RectF ringRect = new RectF();
    private final Path pathL = new Path();
    private final Path pathR = new Path();
    private final Path fillPathL = new Path();
    private final Path fillPathR = new Path();
    private final Path xyPath = new Path();
    private float[] xyScatter = new float[0];
    private float[] curveYL = new float[4096];
    private float[] curveYR = new float[4096];

    private short[] frameBuffer = new short[0];

    private Bitmap fullSpectrogram;
    private Bitmap fullSpectrogramScaled;
    private int fullSpecScaledW;
    private int fullSpecScaledH;
    private int fullSpectrogramRate;
    private long fullSpectrogramDurationUs;
    private long playbackPositionUs;
    private boolean waterfallFull = true;
    private boolean scrubbingFull;
    private long fpsWindowStartNs;
    private int fpsFrameCount;
    private float fpsValue;
    private String fpsText = "FPS --";
    private long fpsLastDrawNs;
    private String measureLine1 = "";
    private String measureLine2 = "";
    private long measureUpdatedAt;
    private int measureLastStart = -1;
    private int measureLastVisible = -1;
    private final Paint fullSpectrogramPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint xyBlackPaint = new Paint();
    private final Paint historyBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap xyPhosphor;
    private Canvas xyPhosphorCanvas;
    private int xyPhosphorW;
    private int xyPhosphorH;
    private short[] xyBuffer = new short[0];
    private float[] xyPointX = new float[0];
    private float[] xyPointY = new float[0];
    private long xyNextFrame = -1L;
    private int xyGeneration = -1;
    private int xyRate;
    private int xyChannels;
    private long xyLastFadeAt;
    private long xyLastDrawAt;
    private boolean xyForceStart = true;
    private volatile boolean drawnOnce;

    private float[] fftRe = new float[0];
    private float[] fftIm = new float[0];
    private float[] window = new float[0];
    private float[] smoothL = new float[0];
    private float[] smoothR = new float[0];
    private float windowSum;
    private boolean spectrumInitialized;
    private int spectrumRate;
    private int spectrumChannels;
    private float lastDominantHz;
    private float lastPeakDb = -120.0f;
    private boolean lastHadSignal;

    private Bitmap frozenBitmap;
    private int frozenWidth;
    private int frozenHeight;
    private long lastAnalysisAt;
    private int lastAnalysisRate;
    private int lastAnalysisChannels;

    private final float density;
    private final float scaledDensity;
    private final int colorText;
    private final int colorSecondary;
    private final int colorMuted;
    private final int colorCyan;
    private final int colorAmber;
    private final int colorGrid;
    private final int colorGridMajor;
    private final int colorScopeBg;

    private LinearGradient spectrumGradient;

    private static final class ChartPick {
        float u;
        float v;
        float xValue;
        float yValue;
        float xFit;
        float yFit;

        ChartPick(float u, float v, float xValue, float yValue, float xFit, float yFit) {
            this.u = u;
            this.v = v;
            this.xValue = xValue;
            this.yValue = yValue;
            this.xFit = xFit;
            this.yFit = yFit;
        }
    }

    public ScopeView(Context context) {
        this(context, null);
    }

    public ScopeView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ScopeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;
        scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        colorText = context.getColor(R.color.text);
        colorSecondary = context.getColor(R.color.text_secondary);
        colorMuted = context.getColor(R.color.text_muted);
        colorCyan = context.getColor(R.color.cyan);
        colorAmber = context.getColor(R.color.amber);
        colorGrid = context.getColor(R.color.grid);
        colorGridMajor = context.getColor(R.color.grid_major);
        colorScopeBg = context.getColor(R.color.scope_bg);

        Typeface mono = Typeface.create("monospace", Typeface.NORMAL);
        Typeface monoBold = Typeface.create("monospace", Typeface.BOLD);
        Typeface sans = Typeface.create("sans-serif", Typeface.NORMAL);
        Typeface sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(0.7f));
        gridPaint.setColor(colorGrid);

        gridMajorPaint.setStyle(Paint.Style.STROKE);
        gridMajorPaint.setStrokeWidth(dp(0.9f));
        gridMajorPaint.setColor(colorGridMajor);

        zeroPaint.setStyle(Paint.Style.STROKE);
        zeroPaint.setStrokeWidth(dp(1.0f));
        zeroPaint.setColor(Color.argb(120, 60, 96, 112));

        traceL.setStyle(Paint.Style.STROKE);
        traceL.setStrokeWidth(dp(1.35f));
        traceL.setColor(Color.RED);
        traceL.setStrokeCap(Paint.Cap.ROUND);
        traceL.setStrokeJoin(Paint.Join.ROUND);

        traceR.setStyle(Paint.Style.STROKE);
        traceR.setStrokeWidth(dp(1.2f));
        traceR.setColor(Color.GREEN);
        traceR.setStrokeCap(Paint.Cap.ROUND);
        traceR.setStrokeJoin(Paint.Join.ROUND);

        glowL.setStyle(Paint.Style.STROKE);
        glowL.setStrokeWidth(dp(5.0f));
        glowL.setColor(Color.RED);
        glowL.setAlpha(24);
        glowL.setStrokeCap(Paint.Cap.ROUND);
        glowL.setStrokeJoin(Paint.Join.ROUND);

        glowR.setStyle(Paint.Style.STROKE);
        glowR.setStrokeWidth(dp(4.0f));
        glowR.setColor(Color.GREEN);
        glowR.setAlpha(20);
        glowR.setStrokeCap(Paint.Cap.ROUND);
        glowR.setStrokeJoin(Paint.Join.ROUND);

        PorterDuffXfermode add = new PorterDuffXfermode(PorterDuff.Mode.ADD);

        addL.setStyle(Paint.Style.STROKE);
        addL.setStrokeWidth(dp(1.35f));
        addL.setColor(Color.RED);
        addL.setStrokeCap(Paint.Cap.ROUND);
        addL.setStrokeJoin(Paint.Join.ROUND);
        addL.setXfermode(add);

        addR.setStyle(Paint.Style.STROKE);
        addR.setStrokeWidth(dp(1.2f));
        addR.setColor(Color.GREEN);
        addR.setStrokeCap(Paint.Cap.ROUND);
        addR.setStrokeJoin(Paint.Join.ROUND);
        addR.setXfermode(add);

        addGlowL.setStyle(Paint.Style.STROKE);
        addGlowL.setStrokeWidth(dp(5.0f));
        addGlowL.setColor(Color.RED);
        addGlowL.setAlpha(30);
        addGlowL.setStrokeCap(Paint.Cap.ROUND);
        addGlowL.setStrokeJoin(Paint.Join.ROUND);
        addGlowL.setXfermode(add);

        addGlowR.setStyle(Paint.Style.STROKE);
        addGlowR.setStrokeWidth(dp(4.0f));
        addGlowR.setColor(Color.GREEN);
        addGlowR.setAlpha(30);
        addGlowR.setStrokeCap(Paint.Cap.ROUND);
        addGlowR.setStrokeJoin(Paint.Join.ROUND);
        addGlowR.setXfermode(add);

        triggerPaint.setStyle(Paint.Style.STROKE);
        triggerPaint.setStrokeWidth(dp(0.8f));
        triggerPaint.setColor(colorAmber);
        triggerPaint.setAlpha(135);
        triggerPaint.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(4)}, 0));

        markerPaint.setStyle(Paint.Style.STROKE);
        markerPaint.setStrokeWidth(dp(0.9f));
        markerPaint.setColor(colorAmber);
        markerPaint.setAlpha(160);
        markerPaint.setPathEffect(new DashPathEffect(new float[]{dp(2), dp(3)}, 0));

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.RED);

        titlePaint.setTypeface(sansMedium);
        titlePaint.setTextSize(sp(8.5f));
        titlePaint.setColor(colorMuted);
        titlePaint.setAntiAlias(true);

        modePaint.setTypeface(monoBold);
        modePaint.setTextSize(sp(17.0f));
        modePaint.setColor(colorCyan);
        modePaint.setAntiAlias(true);

        monoPaint.setTypeface(mono);
        monoPaint.setTextSize(sp(8.0f));
        monoPaint.setColor(colorSecondary);
        monoPaint.setAntiAlias(true);

        labelPaint.setTypeface(mono);
        labelPaint.setTextSize(sp(7.5f));
        labelPaint.setColor(colorMuted);
        labelPaint.setAntiAlias(true);

        tinyPaint.setTypeface(mono);
        tinyPaint.setTextSize(sp(6.8f));
        tinyPaint.setColor(Color.alpha(180) | (colorMuted & 0x00FFFFFF));
        tinyPaint.setAntiAlias(true);

        hintTitlePaint.setTypeface(sansMedium);
        hintTitlePaint.setTextSize(sp(13.0f));
        hintTitlePaint.setColor(colorText);
        hintTitlePaint.setAntiAlias(true);

        hintBodyPaint.setTypeface(sans);
        hintBodyPaint.setTextSize(sp(9.5f));
        hintBodyPaint.setColor(colorSecondary);
        hintBodyPaint.setAntiAlias(true);

        hintDashPaint.setStyle(Paint.Style.STROKE);
        hintDashPaint.setStrokeWidth(dp(0.7f));
        hintDashPaint.setColor(Color.argb(95, 88, 130, 146));
        hintDashPaint.setPathEffect(new DashPathEffect(new float[]{dp(2), dp(5)}, 0));

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(colorCyan);

        xyGlowPaint.setStyle(Paint.Style.STROKE);
        xyGlowPaint.setStrokeWidth(dp(4.2f));
        xyGlowPaint.setColor(Color.YELLOW);
        xyGlowPaint.setAlpha(16);
        xyGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        xyGlowPaint.setStrokeJoin(Paint.Join.ROUND);

        xyCorePaint.setStyle(Paint.Style.STROKE);
        xyCorePaint.setStrokeWidth(dp(1.25f));
        xyCorePaint.setColor(Color.YELLOW);
        xyCorePaint.setAlpha(150);
        xyCorePaint.setStrokeCap(Paint.Cap.ROUND);
        xyCorePaint.setStrokeJoin(Paint.Join.ROUND);

        xyBitmapPaint.setFilterBitmap(false);
        xyBitmapPaint.setDither(false);

        overlayLinePaint.setStyle(Paint.Style.STROKE);
        overlayLinePaint.setStrokeWidth(dp(1.1f));
        overlayLinePaint.setColor(colorAmber);
        overlayLinePaint.setAlpha(220);
        overlayLinePaint.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(3)}, 0));
        overlayLinePaint.setStrokeCap(Paint.Cap.ROUND);

        overlayTextPaint.setTypeface(mono);
        overlayTextPaint.setTextSize(sp(8.0f));
        overlayTextPaint.setColor(context.getColor(R.color.cyan_bright));
        overlayTextPaint.setAntiAlias(true);

        overlayBgPaint.setStyle(Paint.Style.FILL);
        xyBlackPaint.setStyle(Paint.Style.FILL);
        xyBlackPaint.setColor(Color.BLACK);
        overlayBgPaint.setColor(Color.argb(210, 7, 16, 25));

        overlayMarkerPaint.setStyle(Paint.Style.STROKE);
        overlayMarkerPaint.setStrokeWidth(dp(1.2f));
        overlayMarkerPaint.setColor(colorAmber);
        overlayMarkerPaint.setAntiAlias(true);

        doubleTapDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (plot.width() <= 0 || plot.height() <= 0
                                || !plot.contains(e.getX(), e.getY())) {
                            return false;
                        }
                        resetChartZoom();
                        return true;
                    }
                });

        setClickable(true);
        setFocusable(true);
        setContentDescription("音频可视化显示区域");
        applyXyPaintStyle();
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    public void setPipeline(AudioPipeline pipeline) {
        this.pipeline = pipeline;
        spectrumInitialized = false;
        lastAnalysisAt = 0;
        resetXyState();
        invalidate();
    }

    public void setAnalysisListener(AnalysisListener listener) {
        this.analysisListener = listener;
    }

    public void setHint(String title, String body) {
        this.hintTitle = title == null ? "" : title;
        this.hintBody = body == null ? "" : body;
        invalidate();
    }

    public void setMode(int mode) {
        if (this.mode != mode) {
            boolean touchesXy = this.mode == MODE_XY || mode == MODE_XY;
            this.mode = mode;
            spectrumInitialized = false;
            spectrumCacheValid = false;
            if (touchesXy) {
                resetXyState();
                if (mode != MODE_XY) {
                    releaseXyPhosphor();
                }
            }
            resetChartTools();
            if (frozen) {
                releaseFrozenBitmap();
            }
            invalidate();
        }
    }

    public int getMode() {
        return mode;
    }

    public void setGain(float gain) {
        float next = Math.max(0.2f, Math.min(20.0f, gain));
        if (Math.abs(next - this.gain) > 0.0001f) {
            this.gain = next;
            resetXyState();
        }
        if (frozen) {
            releaseFrozenBitmap();
        }
        invalidate();
    }

    public void setXyPersistenceMs(float ms) {
        this.xyPersistenceMs = Math.max(0.0f, Math.min(2000.0f, ms));
        invalidate();
    }

    public void setXySmooth(boolean smooth) {
        if (this.xySmooth != smooth) {
            this.xySmooth = smooth;
            resetXyState();
            invalidate();
        }
    }

    public void setXyMirrorX(boolean mirror) {
        if (this.xyMirrorX != mirror) {
            this.xyMirrorX = mirror;
            resetXyState();
            invalidate();
        }
    }

    public void setXyMirrorY(boolean mirror) {
        if (this.xyMirrorY != mirror) {
            this.xyMirrorY = mirror;
            resetXyState();
            invalidate();
        }
    }

    public void setXySwap(boolean swap) {
        if (this.xySwap != swap) {
            this.xySwap = swap;
            resetXyState();
            invalidate();
        }
    }

    public void setPreRenderFft(boolean enabled) {
        if (preRenderFft != enabled) {
            preRenderFft = enabled;
            spectrumCacheValid = false;
            invalidate();
        }
    }

    public void setShowMeasurements(boolean show) {
        if (showMeasurements != show) {
            showMeasurements = show;
            invalidate();
        }
    }

    public void setXyPixelMode(boolean pixel) {
        if (xyPixelMode != pixel) {
            xyPixelMode = pixel;
            applyXyPaintStyle();
            resetXyState();
            invalidate();
        }
    }

    public void setXyPixelSizeDp(float sizeDp) {
        float v = Math.max(1.0f, Math.min(8.0f, sizeDp));
        if (Math.abs(xyPixelSizeDp - v) > 0.001f) {
            xyPixelSizeDp = v;
            applyXyPaintStyle();
            resetXyState();
            invalidate();
        }
    }

    private void applyXyPaintStyle() {
        float cell = dp(xyPixelSizeDp);
        if (xyPixelMode) {
            xyCorePaint.setStrokeCap(Paint.Cap.SQUARE);
            xyGlowPaint.setStrokeCap(Paint.Cap.SQUARE);
            xyCorePaint.setStrokeWidth(cell);
            xyGlowPaint.setStrokeWidth(cell * 2.4f);
        } else {
            xyCorePaint.setStrokeCap(Paint.Cap.ROUND);
            xyGlowPaint.setStrokeCap(Paint.Cap.ROUND);
            xyCorePaint.setStrokeWidth(dp(1.25f));
            xyGlowPaint.setStrokeWidth(dp(4.2f));
        }
    }

    public void setWaterfallSpeed(float speed) {
        waterfallSpeed = Math.max(0.0f, Math.min(5.0f, speed));
    }

    public void setWaterfallLevels(float black, float white) {
        float b = Math.max(0.0f, Math.min(0.9f, black));
        float w = Math.max(0.1f, Math.min(1.0f, white));
        if (w < b + 0.05f) w = Math.min(1.0f, b + 0.05f);
        if (Math.abs(b - waterfallBlack) < 0.0001f
                && Math.abs(w - waterfallWhite) < 0.0001f) {
            return;
        }
        waterfallBlack = b;
        waterfallWhite = w;
        float scale = 1.0f / Math.max(0.05f, w - b);
        float offset = -b * scale * 255.0f;
        ColorMatrix matrix = new ColorMatrix(new float[]{
                scale, 0, 0, 0, offset,
                0, scale, 0, 0, offset,
                0, 0, scale, 0, offset,
                0, 0, 0, 1, 0
        });
        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(matrix);
        fullSpectrogramPaint.setColorFilter(filter);
        historyBitmapPaint.setColorFilter(filter);
        invalidate();
    }

    public void setTimePerDivMs(float ms) {
        this.timePerDivMs = Math.max(0.1f, Math.min(100.0f, ms));
        if (frozen) {
            releaseFrozenBitmap();
        }
        invalidate();
    }

    public void setFftSize(int size) {
        if (size < 256) {
            size = 256;
        }
        if (this.fftSize != size) {
            this.fftSize = size;
            spectrumInitialized = false;
            resetSpectrumHistory();
            if (frozen) {
                releaseFrozenBitmap();
            }
            invalidate();
        }
    }

    public void setLogFrequency(boolean log) {
        if (this.logFrequency != log) {
            this.logFrequency = log;
            resetSpectrumHistory();
            if (frozen) {
                releaseFrozenBitmap();
            }
            invalidate();
        }
    }

    public void setTriggerLevel(float level) {
        this.triggerLevel = Math.max(-1.0f, Math.min(1.0f, level));
        if (frozen) {
            releaseFrozenBitmap();
        }
        invalidate();
    }

    public void setTriggerRising(boolean rising) {
        this.triggerRising = rising;
        if (frozen) {
            releaseFrozenBitmap();
        }
        invalidate();
    }

    public void setAutoTrigger(boolean auto) {
        this.autoTrigger = auto;
        if (frozen) {
            releaseFrozenBitmap();
        }
        invalidate();
    }

    public boolean isFrozen() {
        return frozen;
    }

    private void captureFreezeData() {
        if (pipeline == null) {
            freezeFrames = 0;
            return;
        }
        int rate = pipeline.getSampleRate();
        int ch = Math.max(1, pipeline.getChannelCount());
        if (rate <= 0 || ch <= 0) {
            freezeFrames = 0;
            return;
        }
        int want;
        if (mode == MODE_FFT) {
            want = Math.max(fftSize, 16);
        } else if (mode == MODE_XY) {
            want = XY_MAX_COPY_FRAMES;
        } else if (mode == MODE_RAW) {
            int base = Math.round(rate * RAW_WINDOW_MS / 1000.0f);
            int cap = Math.max(4096, Math.min(65536, (int) (plot.width() * 16f)));
            want = Math.min(base, cap);
        } else {
            int base = Math.round(rate * timePerDivMs * 10.0f / 1000.0f);
            want = Math.min(MAX_RENDER_FRAMES, Math.max(4096, base * 2));
        }
        want = Math.max(2, Math.min(MAX_RENDER_FRAMES, want));
        int needed = want * ch;
        if (freezeBuffer.length < needed) {
            freezeBuffer = new short[needed];
        }
        freezeFrames = pipeline.copyLatest(freezeBuffer, want);
        freezeRate = rate;
        freezeChannels = ch;
    }

    public void setFrozen(boolean frozen) {
        if (this.frozen == frozen) {
            return;
        }
        this.frozen = frozen;
        if (frozen) {
            captureFreezeData();
            invalidate();
        } else {
            freezeFrames = 0;
            releaseFrozenBitmap();
            invalidate();
        }
    }

    public void resetAnalysis() {
        spectrumInitialized = false;
        spectrumCacheValid = false;
        lastPeakDb = -120.0f;
        lastDominantHz = 0.0f;
        lastHadSignal = false;
        lastAnalysisAt = 0;
        releaseFrozenBitmap();
        frozen = false;
        resetXyState();
        resetChartTools();
        invalidate();
    }

    public boolean hasDrawnData() {
        return drawnOnce;
    }

    public float getLastPeakDb() {
        return lastPeakDb;
    }

    /**
     * True when a new frame would actually add XY samples (or for the other
     * modes, where every frame is animated). Used to avoid idle 60 Hz redraws.
     */
    public boolean shouldDrawXyFrame() {
        if (mode != MODE_XY || pipeline == null) {
            return true;
        }
        if (!pipeline.hasData()) {
            return false;
        }
        long total = pipeline.getTotalFrames();
        return xyNextFrame < 0L || total > xyNextFrame;
    }

    public void setFullSpectrogram(Bitmap bitmap, int sampleRate, long durationUs) {
        if (fullSpectrogram != null) {
            fullSpectrogram.recycle();
        }
        if (fullSpectrogramScaled != null) {
            fullSpectrogramScaled.recycle();
            fullSpectrogramScaled = null;
            fullSpecScaledW = 0;
            fullSpecScaledH = 0;
        }
        fullSpectrogram = bitmap;
        fullSpectrogramRate = sampleRate;
        fullSpectrogramDurationUs = durationUs;
        playbackPositionUs = 0L;
        waterfallFull = true;
        resetSpectrumHistory();
        invalidate();
    }

    public void clearFullSpectrogram() {
        if (fullSpectrogram != null) {
            fullSpectrogram.recycle();
            fullSpectrogram = null;
        }
        if (fullSpectrogramScaled != null) {
            fullSpectrogramScaled.recycle();
            fullSpectrogramScaled = null;
            fullSpecScaledW = 0;
            fullSpecScaledH = 0;
        }
        fullSpectrogramRate = 0;
        fullSpectrogramDurationUs = 0L;
        playbackPositionUs = 0L;
        resetSpectrumHistory();
        invalidate();
    }

    public boolean hasFullSpectrogram() {
        return fullSpectrogram != null;
    }

    public Bitmap getFullSpectrogramBitmap() {
        return fullSpectrogram;
    }

    public int getFullSpectrogramSampleRate() {
        return fullSpectrogramRate;
    }

    public long getFullSpectrogramDurationUs() {
        return fullSpectrogramDurationUs;
    }

    public void setPlaybackPositionUs(long positionUs) {
        playbackPositionUs = Math.max(0L, positionUs);
        if (fullSpectrogram != null && mode == MODE_FFT) {
            invalidate();
        }
    }

    public void setWaterfallFull(boolean full) {
        if (waterfallFull == full) return;
        waterfallFull = full;
        if (!full) {
            resetSpectrumHistory();
        }
        invalidate();
    }

    public boolean isWaterfallFull() {
        return waterfallFull;
    }

    public void setOnSeekListener(OnSeekListener listener) {
        this.seekListener = listener;
    }

    public boolean isPickMode() {
        return pickMode;
    }

    public void setPickMode(boolean enabled) {
        if (pickMode == enabled) {
            return;
        }
        pickMode = enabled;
        if (enabled) {
            trackMode = false;
        }
        invalidate();
    }

    public boolean isTrackMode() {
        return trackMode;
    }

    public void setTrackMode(boolean enabled) {
        if (trackMode == enabled) {
            return;
        }
        trackMode = enabled;
        if (enabled) {
            pickMode = false;
        } else {
            pointerVisible = false;
        }
        invalidate();
    }

    public int getChartPickCount() {
        return chartPicks.size();
    }

    public void clearChartPicks() {
        chartPicks.clear();
        fitValid = false;
        fitEquation = "";
        fitCount = 0;
        invalidate();
    }

    public float getChartZoom() {
        return Math.max(zoomX, zoomY);
    }

    public float cycleChartZoom() {
        chartZoomIndex = (chartZoomIndex + 1) % ZOOM_LEVELS.length;
        zoomX = ZOOM_LEVELS[chartZoomIndex];
        zoomY = ZOOM_LEVELS[chartZoomIndex];
        chartPicks.clear();
        fitValid = false;
        fitEquation = "";
        fitCount = 0;
        pointerVisible = false;
        if (mode == MODE_XY) {
            resetXyState();
        }
        if (mode == MODE_FFT) {
            int rate = pipeline == null ? 0 : pipeline.getSampleRate();
            float max = rate > 0 ? rate / 2.0f : 24000.0f;
            if (lastDominantHz > 1.0f && lastDominantHz < max) {
                fftZoomCenterHz = lastDominantHz;
            } else if (max > 20.0f) {
                fftZoomCenterHz = logFrequency ? (float) Math.sqrt(20.0 * max) : max / 2.0f;
            }
            resetSpectrumHistory();
        }
        invalidate();
        return getChartZoom();
    }

    public void resetChartZoom() {
        chartZoomIndex = 0;
        zoomX = 1.0f;
        zoomY = 1.0f;
        chartPicks.clear();
        fitValid = false;
        fitEquation = "";
        fitCount = 0;
        pointerVisible = false;
        if (mode == MODE_XY) {
            resetXyState();
        }
        resetSpectrumHistory();
        invalidate();
    }

    public void resetChartTools() {
        pickMode = false;
        trackMode = false;
        pointerVisible = false;
        chartPicks.clear();
        fitValid = false;
        fitEquation = "";
        fitCount = 0;
        chartZoomIndex = 0;
        zoomX = 1.0f;
        zoomY = 1.0f;
        if (mode == MODE_XY) {
            resetXyState();
        }
        resetSpectrumHistory();
        invalidate();
    }

    public void refreshFrozenChart() {
        if (frozen) {
            releaseFrozenBitmap();
            invalidate();
        }
    }

    public boolean fitChartPicks() {
        if (chartPicks.size() < 2) {
            return false;
        }
        float sumU = 0.0f;
        float sumV = 0.0f;
        float sumUu = 0.0f;
        float sumUv = 0.0f;
        float minU = 1.0f;
        float maxU = 0.0f;
        for (ChartPick p : chartPicks) {
            sumU += p.u;
            sumV += p.v;
            sumUu += p.u * p.u;
            sumUv += p.u * p.v;
            if (p.u < minU) minU = p.u;
            if (p.u > maxU) maxU = p.u;
        }
        int n = chartPicks.size();
        float meanU = sumU / n;
        float meanV = sumV / n;
        float sxx = sumUu - n * meanU * meanU;
        if (Math.abs(sxx) < 1e-7f || maxU - minU < 1e-5f) {
            return false;
        }
        float sxy = sumUv - n * meanU * meanV;
        float a = sxy / sxx;
        float b = meanV - a * meanU;
        float sst = 0.0f;
        float sse = 0.0f;
        for (ChartPick p : chartPicks) {
            sst += (p.v - meanV) * (p.v - meanV);
            float predicted = a * p.u + b;
            sse += (p.v - predicted) * (p.v - predicted);
        }
        float r2 = sst < 1e-9f ? 1.0f : Math.max(0.0f, Math.min(1.0f, 1.0f - sse / sst));
        fitNormA = a;
        fitNormB = b;
        fitR2 = r2;
        fitValid = true;
        fitCount = n;
        fitEquation = buildFitEquation();
        invalidate();
        return true;
    }

    private String buildFitEquation() {
        float sx = 0.0f;
        float sy = 0.0f;
        float sxx = 0.0f;
        float sxy = 0.0f;
        for (ChartPick p : chartPicks) {
            sx += p.xFit;
            sy += p.yFit;
            sxx += p.xFit * p.xFit;
            sxy += p.xFit * p.yFit;
        }
        int n = Math.max(1, chartPicks.size());
        float denom = sxx - n * (sx / n) * (sx / n);
        if (Math.abs(denom) < 1e-8f) {
            return String.format(Locale.US, "R² = %.4f", fitR2);
        }
        float m = (sxy - n * (sx / n) * (sy / n)) / denom;
        float b = sy / n - m * sx / n;
        String xName;
        if (mode == MODE_FFT) {
            xName = logFrequency ? "log10(f)" : "f";
        } else if (mode == MODE_XY) {
            xName = "X";
        } else if (mode == MODE_RAW) {
            xName = "t";
        } else {
            xName = "t";
        }
        return String.format(Locale.US, "y = %.3g·%s %+.3g  R²=%.4f", m, xName, b, fitR2);
    }

    private void addChartPick(float u, float v) {
        if (chartPicks.size() >= MAX_CHART_PICKS) {
            return;
        }
        for (int i = 0; i < chartPicks.size(); i++) {
            ChartPick p = chartPicks.get(i);
            float dx = (p.u - u) * plot.width();
            float dy = (p.v - v) * plot.height();
            if (dx * dx + dy * dy < dp(16) * dp(16)) {
                chartPicks.remove(i);
                fitValid = false;
                fitEquation = "";
                invalidate();
                return;
            }
        }
        ChartPick p = createChartPick(u, v);
        chartPicks.add(p);
        fitValid = false;
        fitEquation = "";
        invalidate();
    }

    private ChartPick createChartPick(float u, float v) {
        float xValue;
        float yValue;
        float xFit;
        float yFit;
        if (mode == MODE_FFT) {
            int rate = pipeline == null ? 0 : pipeline.getSampleRate();
            float maxHz = rate > 0 ? spectrumViewMax(rate) : 24000.0f;
            float minHz = rate > 0 ? spectrumViewMin(rate) : 20.0f;
            float hz = freqFromU(u, minHz, maxHz);
            float db = dbFromV(v);
            xValue = hz;
            yValue = db;
            xFit = logFrequency ? (hz > 0 ? (float) Math.log10(hz) : -6.0f) : hz;
            yFit = db;
        } else if (mode == MODE_XY) {
            float effX = Math.max(0.01f, gain * zoomX);
            float effY = Math.max(0.01f, gain * zoomY);
            xValue = (2.0f * u - 1.0f) / effX;
            yValue = (1.0f - 2.0f * v) / effY;
            if (xyMirrorX) {
                xValue = -xValue;
            }
            if (xyMirrorY) {
                yValue = -yValue;
            }
            xFit = xValue;
            yFit = yValue;
        } else {
            float windowMs = chartWindowMs();
            xValue = (u - 1.0f) * windowMs;
            yValue = (1.0f - 2.0f * v) / Math.max(0.01f, gain * zoomY);
            xFit = xValue;
            yFit = yValue;
        }
        return new ChartPick(u, v, xValue, yValue, xFit, yFit);
    }

    private String chartPickReadout(ChartPick p) {
        if (mode == MODE_FFT) {
            return String.format(Locale.US, "f %s   %+.1f dB", formatFreq(p.xValue), p.yValue);
        }
        if (mode == MODE_XY) {
            return String.format(Locale.US, "X %+.3f   Y %+.3f", p.xValue, p.yValue);
        }
        if (mode == MODE_RAW || mode == MODE_XT) {
            return String.format(Locale.US, "t %+.2f ms   A %+.3f", p.xValue, p.yValue);
        }
        return "";
    }

    private float chartWindowMs() {
        if (mode == MODE_RAW) {
            return RAW_WINDOW_MS / zoomX;
        }
        return timePerDivMs * 10.0f / zoomX;
    }

    private float dbFromV(float v) {
        float floor = currentDbFloor();
        return -v * (-floor);
    }

    private float vFromDb(float db) {
        float floor = currentDbFloor();
        return clamp(db / floor, 0.0f, 1.0f);
    }

    private float currentDbFloor() {
        return DB_FLOOR / Math.max(1.0f, zoomY);
    }

    private float xyAxisMaxX() {
        return 1.0f / Math.max(0.01f, gain * zoomX);
    }

    private float xyAxisMaxY() {
        return 1.0f / Math.max(0.01f, gain * zoomY);
    }

    private float freqFromU(float u, float minHz, float maxHz) {
        float t = clamp(u, 0.0f, 1.0f);
        if (logFrequency && minHz > 0.0f) {
            return (float) (minHz * Math.pow(maxHz / minHz, t));
        }
        return minHz + t * (maxHz - minHz);
    }

    private void drawChartOverlay(Canvas canvas) {
        if (pipeline == null || plot.width() <= 0 || plot.height() <= 0) {
            return;
        }
        overlayTextPaint.setTextSize(sp(8.0f));
        if (!chartPicks.isEmpty()) {
            canvas.save();
            canvas.clipRect(plot);
            for (int i = 0; i < chartPicks.size(); i++) {
                ChartPick p = chartPicks.get(i);
                float x = plot.left + p.u * plot.width();
                float y = plot.top + p.v * plot.height();
                overlayMarkerPaint.setColor(colorAmber);
                canvas.drawCircle(x, y, dp(4.2f), overlayMarkerPaint);
                overlayTextPaint.setColor(colorAmber);
                overlayTextPaint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(String.valueOf(i + 1), x + dp(6), y - dp(4), overlayTextPaint);
            }
            if (fitValid) {
                float minU = 1.0f;
                float maxU = 0.0f;
                for (ChartPick p : chartPicks) {
                    if (p.u < minU) minU = p.u;
                    if (p.u > maxU) maxU = p.u;
                }
                float v1 = fitNormA * minU + fitNormB;
                float v2 = fitNormA * maxU + fitNormB;
                canvas.drawLine(plot.left + minU * plot.width(), plot.top + v1 * plot.height(),
                        plot.left + maxU * plot.width(), plot.top + v2 * plot.height(),
                        overlayLinePaint);
            }
            canvas.restore();
        }

        if (trackMode && pointerVisible) {
            float x = plot.left + pointerU * plot.width();
            float y = plot.top + pointerV * plot.height();
            overlayLinePaint.setColor(colorAmber);
            overlayLinePaint.setAlpha(170);
            canvas.drawLine(x, plot.top, x, plot.bottom, overlayLinePaint);
            canvas.drawLine(plot.left, y, plot.right, y, overlayLinePaint);
            overlayMarkerPaint.setColor(colorAmber);
            canvas.drawCircle(x, y, dp(3.2f), overlayMarkerPaint);
            ChartPick tracked = createChartPick(pointerU, pointerV);
            drawReadoutBox(canvas, chartPickReadout(tracked), x, y);
        }

        if (pickMode || fitValid) {
            String line;
            if (fitValid) {
                line = "拟合 " + fitEquation + "  (" + fitCount + " 点)";
            } else {
                line = "选点 " + chartPicks.size() + "/" + MAX_CHART_PICKS
                        + " · 点按图表添加，点已选点删除";
            }
            overlayTextPaint.setColor(fitValid ? colorAmber : colorSecondary);
            overlayTextPaint.setTextAlign(Paint.Align.LEFT);
            float tw = overlayTextPaint.measureText(line);
            float bx = plot.left + dp(5);
            float by = plot.bottom - dp(8);
            float bw = Math.min(tw + dp(12), plot.width() - dp(10));
            overlayBox.set(bx, by - overlayTextPaint.getTextSize() - dp(7),
                    bx + bw, by + dp(3));
            overlayBgPaint.setColor(Color.argb(205, 5, 13, 20));
            canvas.drawRoundRect(overlayBox, dp(5), dp(5), overlayBgPaint);
            canvas.save();
            canvas.clipRect(overlayBox);
            canvas.drawText(line, bx + dp(6), by - dp(3), overlayTextPaint);
            canvas.restore();
        }
    }

    private void drawReadoutBox(Canvas canvas, String text, float nearX, float nearY) {
        if (text == null || text.length() == 0) {
            return;
        }
        overlayTextPaint.setTextSize(sp(8.0f));
        overlayTextPaint.setTextAlign(Paint.Align.LEFT);
        overlayTextPaint.setColor(colorText);
        float tw = overlayTextPaint.measureText(text);
        float bw = tw + dp(12);
        float bh = overlayTextPaint.getTextSize() + dp(9);
        float bx = nearX + dp(10);
        float by = nearY - dp(15);
        if (bx + bw > plot.right) {
            bx = nearX - bw - dp(10);
        }
        if (bx < plot.left) {
            bx = plot.left + dp(4);
        }
        if (by - bh < plot.top) {
            by = nearY + bh + dp(8);
        }
        if (by > plot.bottom) {
            by = plot.bottom - dp(4);
        }
        overlayBox.set(bx, by - bh, bx + bw, by);
        overlayBgPaint.setColor(Color.argb(225, 5, 13, 20));
        canvas.drawRoundRect(overlayBox, dp(5), dp(5), overlayBgPaint);
        canvas.drawText(text, bx + dp(6), by - dp(6), overlayTextPaint);
    }

    private void moveFullCursor(float x) {
        float fraction = clamp((x - historyPlot.left) / historyPlot.width(), 0f, 1f);
        playbackPositionUs = (long) (fraction * fullSpectrogramDurationUs);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getPointerCount() > 1) {
            return true;
        }
        if (doubleTapDetector != null) {
            doubleTapDetector.onTouchEvent(event);
        }
        if (mode == MODE_FFT && fullSpectrogram != null && waterfallFull
                && !pickMode && !trackMode && historyPlot.width() > 0
                && fullSpectrogramDurationUs > 0L) {
            float x = event.getX();
            float y = event.getY();
            boolean inside = x >= historyPlot.left && x <= historyPlot.right
                    && y >= historyPlot.top && y <= historyPlot.bottom;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (inside) {
                        scrubbingFull = true;
                        moveFullCursor(x);
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (scrubbingFull) {
                        moveFullCursor(x);
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (scrubbingFull) {
                        moveFullCursor(x);
                        scrubbingFull = false;
                        if (seekListener != null) {
                            seekListener.onSeek(playbackPositionUs);
                        }
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    scrubbingFull = false;
                    return true;
                default:
                    break;
            }
        }
        if ((pickMode || trackMode) && plot.width() > 0 && plot.height() > 0) {
            float x = event.getX();
            float y = event.getY();
            boolean inside = x >= plot.left && x <= plot.right
                    && y >= plot.top && y <= plot.bottom;
            float u = clamp((x - plot.left) / plot.width(), 0.0f, 1.0f);
            float v = clamp((y - plot.top) / plot.height(), 0.0f, 1.0f);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchDownX = x;
                    touchDownY = y;
                    if (trackMode && inside) {
                        pointerU = u;
                        pointerV = v;
                        pointerVisible = true;
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (trackMode && inside) {
                        pointerU = u;
                        pointerV = v;
                        pointerVisible = true;
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (pickMode && inside && Math.abs(x - touchDownX) < dp(14)
                            && Math.abs(y - touchDownY) < dp(14)) {
                        addChartPick(u, v);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private void drawZoomBadge(Canvas canvas, float xZoom, float yZoom) {
        if (xZoom <= 1.01f && yZoom <= 1.01f) {
            return;
        }
        String text = String.format(Locale.US, "X%.1f  Y%.1f", xZoom, yZoom);
        overlayTextPaint.setTextSize(sp(7.8f));
        overlayTextPaint.setTextAlign(Paint.Align.RIGHT);
        overlayTextPaint.setColor(colorCyan);
        float x = getWidth() - dp(14);
        float y = dp(21);
        float tw = overlayTextPaint.measureText(text);
        overlayBgPaint.setColor(Color.argb(190, 5, 13, 20));
        overlayBox.set(x - tw - dp(9), y - overlayTextPaint.getTextSize() - dp(3),
                x + dp(2), y + dp(3));
        canvas.drawRoundRect(overlayBox, dp(4), dp(4), overlayBgPaint);
        canvas.drawText(text, x, y, overlayTextPaint);
        overlayTextPaint.setTextAlign(Paint.Align.LEFT);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        updateFpsCounter();
        if (frozen) {
            drawScene(canvas, width, height, true);
            drawChartOverlay(canvas);
            drawZoomBadge(canvas, zoomX, zoomY);
            drawFpsBadge(canvas);
            return;
        }
        drawScene(canvas, width, height, false);
        drawChartOverlay(canvas);
        drawZoomBadge(canvas, zoomX, zoomY);
        drawFpsBadge(canvas);
    }

    private void updateFpsCounter() {
        long now = System.nanoTime();
        if (fpsWindowStartNs == 0L || now - fpsLastDrawNs > 1_000_000_000L) {
            fpsWindowStartNs = now;
            fpsFrameCount = 0;
        }
        fpsLastDrawNs = now;
        fpsFrameCount++;
        long elapsed = now - fpsWindowStartNs;
        if (elapsed >= 500_000_000L) {
            fpsValue = fpsFrameCount * 1_000_000_000.0f / elapsed;
            fpsText = String.format(Locale.US, "FPS %.0f", fpsValue);
            fpsWindowStartNs = now;
            fpsFrameCount = 0;
        }
    }

    private void drawFpsBadge(Canvas canvas) {
        if (plot.width() <= 0 || plot.height() <= 0) {
            return;
        }
        String text = fpsText;
        overlayTextPaint.setTextSize(sp(7.6f));
        overlayTextPaint.setTextAlign(Paint.Align.RIGHT);
        overlayTextPaint.setColor(colorCyan);
        float x = plot.right - dp(7);
        float y = plot.top + dp(11);
        float tw = overlayTextPaint.measureText(text);
        overlayBgPaint.setColor(Color.argb(190, 5, 13, 20));
        overlayBox.set(x - tw - dp(8), y - overlayTextPaint.getTextSize() - dp(3),
                x + dp(3), y + dp(3));
        canvas.drawRoundRect(overlayBox, dp(4), dp(4), overlayBgPaint);
        canvas.drawText(text, x, y, overlayTextPaint);
        overlayTextPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawScene(Canvas canvas, int width, int height, boolean freezePass) {
        if (pipeline == null) {
            return;
        }
        if (mode == MODE_XY) {
            canvas.drawColor(Color.BLACK);
        }
        int sampleRate;
        int channels;
        int frames;
        boolean hasSignal;
        if (freezePass) {
            sampleRate = freezeRate > 0 ? freezeRate : pipeline.getSampleRate();
            channels = Math.max(1, freezeChannels);
            frames = freezeFrames;
            hasSignal = frames >= 2;
            if (hasSignal && mode != MODE_XY) {
                int needed = frames * channels;
                if (frameBuffer.length < needed) {
                    frameBuffer = new short[needed];
                }
                System.arraycopy(freezeBuffer, 0, frameBuffer, 0, needed);
            }
        } else {
            sampleRate = pipeline.getSampleRate();
            channels = Math.max(1, pipeline.getChannelCount());
            int desired = desiredFrames(sampleRate);
            frames = 0;
            if (mode == MODE_XY) {
                // The XY renderer consumes every new frame from an absolute cursor
                // and accumulates it on a phosphor layer. It deliberately does not
                // use the XT time window.
                hasSignal = pipeline.hasData();
            } else {
                int want = desired;
                if (mode == MODE_FFT && fftSize > want) {
                    want = fftSize;
                } else if (mode == MODE_XT && autoTrigger) {
                    want = Math.min(MAX_RENDER_FRAMES, Math.max(desired + 64, desired * 2));
                } else if (mode == MODE_RAW) {
                    want = Math.min(MAX_RENDER_FRAMES,
                            Math.max(2, rawWindowFrames(sampleRate)));
                }
                want = Math.max(want, 2);
                if (sampleRate > 0) {
                    frames = copyFrames(want, channels);
                }
                hasSignal = frames > 0;
            }
        }
        lastHadSignal = hasSignal;

        int plotBottomPad = mode == MODE_FFT ? (int) dp(38) : (int) dp(30);
        plot.set(dp(14), dp(36), width - dp(14), height - plotBottomPad);
        historyPlot.setEmpty();
        if (mode == MODE_FFT) {
            plot.left += dp(27);
            float top = dp(36);
            float bottom = height - dp(38);
            float available = bottom - top;
            if (fullSpectrogram != null && waterfallFull) {
                float labelGap = dp(24);
                float historyH = Math.max(dp(46), available * 0.30f);
                float specBottom = bottom - labelGap - historyH;
                if (specBottom - top < dp(80)) {
                    specBottom = top + dp(80);
                    historyH = bottom - labelGap - specBottom;
                }
                if (historyH < dp(28)) {
                    historyH = dp(28);
                }
                specBottom = bottom - labelGap - historyH;
                plot.set(plot.left, top, plot.right, specBottom);
                historyPlot.set(plot.left, specBottom + labelGap, plot.right, bottom);
            } else {
                float gap = dp(8);
                float historyH = Math.max(dp(56), available * 0.30f);
                if (available - historyH - gap < dp(76)) {
                    historyH = Math.max(dp(34), available - gap - dp(76));
                }
                historyH = Math.min(historyH, Math.max(dp(34), available - gap - dp(48)));
                float specBottom = bottom - historyH - gap;
                plot.set(plot.left, top, plot.right, specBottom);
                historyPlot.set(plot.left, specBottom + gap, plot.right, bottom);
            }
        }
        if (mode == MODE_XY) {
            float topReserved = dp(38);
            float bottomReserved = dp(31);
            float availableW = width - dp(26);
            float availableH = height - topReserved - bottomReserved;
            float size = Math.min(availableW, availableH);
            if (size < dp(40)) {
                size = Math.max(dp(40), Math.min(availableW, availableH));
            }
            float cx = width / 2.0f;
            float cy = topReserved + availableH / 2.0f;
            plot.set(cx - size / 2.0f, cy - size / 2.0f,
                    cx + size / 2.0f, cy + size / 2.0f);
        }
        if (plot.width() < 20 || plot.height() < 20) {
            return;
        }

        drawChrome(canvas, sampleRate, channels, hasSignal);

        if (mode == MODE_FFT) {
            drawSpectrumGrid(canvas);
            if (fullSpectrogram != null && waterfallFull) {
                if (hasSignal && sampleRate > 0) {
                    drawSpectrum(canvas, frames, channels, sampleRate);
                }
                drawFullSpectrogram(canvas, sampleRate);
                notifyAnalysis(sampleRate, channels, hasSignal, lastPeakDb, 0.0f);
                return;
            }
            if (!hasSignal || sampleRate <= 0) {
                drawNoSignal(canvas);
                notifyAnalysis(sampleRate, channels, false, -120.0f, 0.0f);
                return;
            }
            drawSpectrum(canvas, frames, channels, sampleRate);
            drawSpectrumHistory(canvas, sampleRate);
        } else if (mode == MODE_XY) {
            drawXyGrid(canvas);
            if (!hasSignal || sampleRate <= 0) {
                drawNoSignal(canvas);
                notifyAnalysis(sampleRate, channels, false, -120.0f, 0.0f);
                return;
            }
            if (freezePass) {
                drawFrozenXy(canvas, sampleRate, channels);
            } else {
                drawXy(canvas, sampleRate, channels);
            }
        } else if (mode == MODE_RAW) {
            drawRawGrid(canvas);
            if (!hasSignal || sampleRate <= 0) {
                drawNoSignal(canvas);
                notifyAnalysis(sampleRate, channels, false, -120.0f, 0.0f);
                return;
            }
            drawRaw(canvas, frames, channels, sampleRate);
        } else {
            drawXtGrid(canvas);
            if (!hasSignal || sampleRate <= 0) {
                drawNoSignal(canvas);
                notifyAnalysis(sampleRate, channels, false, -120.0f, 0.0f);
                return;
            }
            drawXt(canvas, frames, channels, sampleRate);
        }

        drawBottomLegend(canvas, sampleRate, channels);
        drawnOnce = true;
    }

    private void drawChrome(Canvas canvas, int sampleRate, int channels, boolean hasSignal) {
        String code;
        String description;
        if (mode == MODE_XY) {
            code = "XY";
            if (channels >= 2) {
                description = "SCATTER  ·  L → X  R → Y";
            } else {
                description = "SCATTER  ·  PHASE PORTRAIT";
            }
        } else if (mode == MODE_FFT) {
            code = "FFT";
            description = "SPECTRUM  ·  HISTORY";
        } else if (mode == MODE_RAW) {
            code = "RAW";
            description = "PCM SIGNAL  ·  ALL SAMPLES";
        } else {
            code = "XT";
            description = autoTrigger ? "TIME DOMAIN  ·  AUTO TRIGGER" : "TIME DOMAIN  ·  FREE RUN";
        }
        canvas.drawText(code, dp(14), dp(25), modePaint);
        float codeWidth = modePaint.measureText(code);
        canvas.drawText(description, dp(14) + codeWidth + dp(7), dp(24), titlePaint);

        canvas.drawLine(dp(14), dp(33), getWidth() - dp(14), dp(33), gridMajorPaint);
    }

    private void drawXtGrid(Canvas canvas) {
        final int cols = 10;
        final int rows = 8;
        for (int i = 0; i <= cols; i++) {
            float x = plot.left + plot.width() * i / cols;
            canvas.drawLine(x, plot.top, x, plot.bottom, i == 0 || i == cols ? gridMajorPaint : gridPaint);
        }
        for (int i = 0; i <= rows; i++) {
            float y = plot.top + plot.height() * i / rows;
            canvas.drawLine(plot.left, y, plot.right, y, i == 0 || i == rows ? gridMajorPaint : gridPaint);
        }
        float zeroY = plot.centerY();
        canvas.drawLine(plot.left, zeroY, plot.right, zeroY, zeroPaint);

        for (int i = 1; i < cols; i++) {
            float x = plot.left + plot.width() * i / cols;
            canvas.drawLine(x, plot.bottom - dp(4), x, plot.bottom, gridMajorPaint);
        }
    }

    private void drawXyGrid(Canvas canvas) {
        // Pure black plotting area with a planar Cartesian grid.
        canvas.drawRect(plot, xyBlackPaint);
        float cx = plot.centerX();
        float cy = plot.centerY();

        // Faint quarter grid.
        for (int i = 1; i < 4; i++) {
            float fx = plot.left + plot.width() * i / 4.0f;
            float fy = plot.top + plot.height() * i / 4.0f;
            canvas.drawLine(fx, plot.top, fx, plot.bottom, gridPaint);
            canvas.drawLine(plot.left, fy, plot.right, fy, gridPaint);
        }

        // X/Y axes.
        canvas.drawLine(plot.left, cy, plot.right, cy, gridMajorPaint);
        canvas.drawLine(cx, plot.top, cx, plot.bottom, gridMajorPaint);

        // Tick marks along both axes.
        for (int i = 1; i < 4; i++) {
            float fx = plot.left + plot.width() * i / 4.0f;
            float fy = plot.top + plot.height() * i / 4.0f;
            canvas.drawLine(fx, cy - dp(4), fx, cy + dp(4), gridMajorPaint);
            canvas.drawLine(cx - dp(4), fy, cx + dp(4), fy, gridMajorPaint);
        }

        // Arrowheads: +X to the right, +Y upward.
        Path arrow = new Path();
        arrow.moveTo(plot.right - dp(2), cy);
        arrow.lineTo(plot.right - dp(10), cy - dp(5));
        arrow.lineTo(plot.right - dp(10), cy + dp(5));
        arrow.close();
        canvas.drawPath(arrow, gridMajorPaint);
        arrow.reset();
        arrow.moveTo(cx, plot.top + dp(2));
        arrow.lineTo(cx - dp(5), plot.top + dp(10));
        arrow.lineTo(cx + dp(5), plot.top + dp(10));
        arrow.close();
        canvas.drawPath(arrow, gridMajorPaint);

        tinyPaint.setTextAlign(Paint.Align.CENTER);
        boolean scaled = zoomX > 1.0f || zoomY > 1.0f
                || Math.abs(gain - 1.0f) > 0.01f;
        String hiX = scaled ? String.format(Locale.US, "+%.2f", xyAxisMaxX()) : "+1";
        String loX = scaled ? String.format(Locale.US, "-%.2f", xyAxisMaxX()) : "-1";
        String hiY = scaled ? String.format(Locale.US, "+%.2f", xyAxisMaxY()) : "+1";
        String loY = scaled ? String.format(Locale.US, "-%.2f", xyAxisMaxY()) : "-1";
        String top = xyMirrorY ? loY : hiY;
        String bottom = xyMirrorY ? hiY : loY;
        String right = xyMirrorX ? loX : hiX;
        String left = xyMirrorX ? hiX : loX;
        canvas.drawText(top, cx - dp(12), plot.top + dp(10), tinyPaint);
        canvas.drawText(bottom, cx - dp(12), plot.bottom - dp(4), tinyPaint);
        canvas.drawText(right, plot.right - dp(14), cy - dp(7), tinyPaint);
        canvas.drawText(left, plot.left + dp(14), cy - dp(7), tinyPaint);
        canvas.drawText("0", cx + dp(8), cy - dp(6), tinyPaint);
        tinyPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawSpectrumGrid(Canvas canvas) {
        float floor = currentDbFloor();
        for (int i = 0; i <= 5; i++) {
            float db = floor * i / 5.0f;
            float y = dbToY(db);
            Paint p = (i == 0 || i == 5) ? gridMajorPaint : gridPaint;
            canvas.drawLine(plot.left, y, plot.right, y, p);
            labelPaint.setTextAlign(Paint.Align.RIGHT);
            String label = db == 0.0f ? "0" : String.format(Locale.US, "%.0f", db);
            float baseline = i == 0 ? y + dp(8.0f) : y + dp(2.7f);
            canvas.drawText(label, plot.left - dp(4), baseline, labelPaint);
        }
        labelPaint.setTextAlign(Paint.Align.LEFT);

        int rate = pipeline == null ? 0 : pipeline.getSampleRate();
        if (rate <= 0) {
            return;
        }
        float nyquist = rate / 2.0f;
        float viewMin = spectrumViewMin(rate);
        float viewMax = spectrumViewMax(rate);
        boolean fullSpec = fullSpectrogram != null;
        float axisY = fullSpec
                ? plot.bottom + dp(12)
                : (historyPlot.isEmpty() ? plot.bottom + dp(12) : historyPlot.bottom + dp(12));
        float gridBottom = fullSpec
                ? plot.bottom
                : (historyPlot.isEmpty() ? plot.bottom : historyPlot.bottom);
        if (logFrequency) {
            float fMin = viewMin;
            float[] marks = {20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f};
            float lastLabelX = -1000f;
            for (float f : marks) {
                if (f < fMin || f > viewMax) {
                    continue;
                }
                float x = freqToX(f, fMin, viewMax);
                canvas.drawLine(x, plot.top, x, gridBottom, gridPaint);
                String text = shortFreq(f);
                float w = labelPaint.measureText(text);
                if (x - w / 2 > lastLabelX + dp(6) && x - w / 2 >= plot.left
                        && x + w / 2 <= plot.right) {
                    labelPaint.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText(text, x, axisY, labelPaint);
                    lastLabelX = x + w / 2;
                }
            }
            String edge = shortFreq(viewMax);
            float edgeW = labelPaint.measureText(edge);
            if (plot.right - edgeW > lastLabelX + dp(6)) {
                labelPaint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(edge, plot.right, axisY, labelPaint);
            }
            labelPaint.setTextAlign(Paint.Align.LEFT);
            if (viewMin > fMin + 0.5f) {
                labelPaint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(shortFreq(viewMin), plot.left, axisY, labelPaint);
            }
            labelPaint.setTextAlign(Paint.Align.LEFT);
        } else {
            for (int i = 0; i <= 4; i++) {
                float x = plot.left + plot.width() * i / 4.0f;
                canvas.drawLine(x, plot.top, x, gridBottom,
                        i == 0 || i == 4 ? gridMajorPaint : gridPaint);
                labelPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(shortFreq(viewMin + (viewMax - viewMin) * i / 4.0f),
                        x, axisY, labelPaint);
            }
            labelPaint.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawXt(Canvas canvas, int frames, int channels, int sampleRate) {
        int visible = Math.min(desiredFrames(sampleRate), frames);
        if (visible < 2) {
            drawNoSignal(canvas);
            return;
        }
        int start = frames - visible;
        if (autoTrigger && frames > visible + 8) {
            int pretrigger = Math.max(1, visible / 8);
            int maxTrigger = frames - Math.max(2, visible - pretrigger) - 1;
            int trigger = findTrigger(frames, channels, pretrigger, maxTrigger);
            if (trigger >= 0) {
                start = trigger - pretrigger;
                if (start < 0) {
                    start = 0;
                }
                if (start + visible > frames) {
                    start = frames - visible;
                }
            }
        }

        float centerY = plot.centerY();
        float halfH = plot.height() / 2.0f - dp(2);
        int stereo = channels >= 2 ? 1 : 0;

        canvas.save();
        canvas.clipRect(plot);

        triggerPaint.setColor(colorAmber);
        float ty = centerY - clamp(triggerLevel, -1.0f, 1.0f) * gain * zoomY * halfH;
        if (ty < plot.top) ty = plot.top;
        if (ty > plot.bottom) ty = plot.bottom;
        canvas.drawLine(plot.left, ty, plot.right, ty, triggerPaint);
        markerPaint.setColor(colorAmber);
        canvas.drawLine(plot.left, ty, plot.left + dp(9), ty, markerPaint);
        labelPaint.setTextAlign(Paint.Align.LEFT);

        boolean envelope = visible > plot.width() * 2.0f;
        if (envelope) {
            drawXtEnvelope(canvas, start, visible, channels, stereo, centerY, halfH);
        } else {
            pathL.reset();
            pathR.reset();
            int points = visible;
            for (int i = 0; i < points; i++) {
                float x = plot.left + (plot.width()) * i / Math.max(1, points - 1);
                int idx = (start + i) * channels;
                float l = frameBuffer[idx] / 32768.0f;
                float y = centerY - clamp(l * gain * zoomY, -1.0f, 1.0f) * halfH;
                if (i == 0) {
                    pathL.moveTo(x, y);
                } else {
                    pathL.lineTo(x, y);
                }
                if (stereo == 1) {
                    float r = frameBuffer[idx + 1] / 32768.0f;
                    float y2 = centerY - clamp(r * gain * zoomY, -1.0f, 1.0f) * halfH;
                    if (i == 0) {
                        pathR.moveTo(x, y2);
                    } else {
                        pathR.lineTo(x, y2);
                    }
                }
            }
            if (stereo == 1) {
                canvas.drawPath(pathR, addGlowR);
            }
            canvas.drawPath(pathL, addGlowL);
            if (stereo == 1) {
                canvas.drawPath(pathR, addR);
            }
            canvas.drawPath(pathL, addL);
        }
        canvas.restore();

        if (showMeasurements) {
            drawXtMeasurements(canvas, start, visible, channels, sampleRate);
        }
        float peak = computePeakDb(frames, channels);
        notifyAnalysis(sampleRate, channels, true, peak, 0.0f);
    }

    private void drawXtMeasurements(Canvas canvas, int start, int visible,
                                    int channels, int sampleRate) {
        if (visible < 2 || sampleRate <= 0) return;
        long now = SystemClock.uptimeMillis();
        if (measureLine1.isEmpty() || now - measureUpdatedAt >= 200L
                || start != measureLastStart || visible != measureLastVisible) {
            measureLastStart = start;
            measureLastVisible = visible;
            measureUpdatedAt = now;
            float min = 1f;
            float max = -1f;
            double sum = 0.0;
            double sumSq = 0.0;
            int positive = 0;
            int crossings = 0;
            boolean prevPositive = false;
            for (int i = 0; i < visible; i++) {
                int idx = (start + i) * channels;
                float v = clamp(frameBuffer[idx] / 32768.0f, -1f, 1f);
                if (v < min) min = v;
                if (v > max) max = v;
                sum += v;
                sumSq += v * v;
                boolean pos = v >= 0f;
                if (pos) positive++;
                if (i > 0 && pos && !prevPositive) crossings++;
                prevPositive = pos;
            }
            float vpp = max - min;
            float avg = (float) (sum / visible);
            float rms = (float) Math.sqrt(sumSq / visible);
            float freq = crossings * sampleRate / (float) visible;
            float periodMs = freq > 0.01f ? 1000.0f / freq : 0f;
            float duty = positive * 100.0f / visible;
            measureLine1 = String.format(Locale.US,
                    "FREQ %.1f Hz   VPP %.2f   RMS %.2f", freq, vpp, rms);
            measureLine2 = String.format(Locale.US,
                    "MAX %.2f  MIN %.2f  AVG %+.2f  DUTY %.0f%%  T %.2f ms",
                    max, min, avg, duty, periodMs);
        }
        String line1 = measureLine1;
        String line2 = measureLine2;
        overlayTextPaint.setTextSize(sp(7.4f));
        overlayTextPaint.setTextAlign(Paint.Align.LEFT);
        overlayTextPaint.setColor(colorCyan);
        float x = plot.left + dp(7);
        float y = plot.top + dp(10);
        float w = Math.max(overlayTextPaint.measureText(line1),
                overlayTextPaint.measureText(line2));
        overlayBgPaint.setColor(Color.argb(200, 5, 13, 20));
        overlayBox.set(x - dp(5), y - overlayTextPaint.getTextSize() - dp(10),
                x + w + dp(5), y + dp(12));
        canvas.drawRoundRect(overlayBox, dp(5), dp(5), overlayBgPaint);
        canvas.drawText(line1, x, y, overlayTextPaint);
        canvas.drawText(line2, x, y + dp(12), overlayTextPaint);
    }

    private void drawXtEnvelope(Canvas canvas, int start, int visible, int channels,
                                int stereo, float centerY, float halfH) {
        int plotWidth = Math.max(1, (int) plot.width());
        for (int pass = 0; pass < 2; pass++) {
            Paint pL = pass == 0 ? glowL : traceL;
            Paint pR = pass == 0 ? glowR : traceR;
            for (int px = 0; px < plotWidth; px++) {
                int from = start + (int) ((long) visible * px / plotWidth);
                int to = start + (int) ((long) visible * (px + 1) / plotWidth);
                if (to <= from) to = from + 1;
                if (to > frameBuffer.length / channels) to = frameBuffer.length / channels;
                int minL = 32767;
                int maxL = -32768;
                int minR = 32767;
                int maxR = -32768;
                for (int i = from; i < to; i++) {
                    int idx = i * channels;
                    int l = frameBuffer[idx];
                    if (l < minL) minL = l;
                    if (l > maxL) maxL = l;
                    if (stereo == 1) {
                        int r = frameBuffer[idx + 1];
                        if (r < minR) minR = r;
                        if (r > maxR) maxR = r;
                    }
                }
                float x = plot.left + (plot.width() * px) / Math.max(1, plotWidth - 1);
                float y1 = centerY - clamp((maxL / 32768.0f) * gain * zoomY, -1.0f, 1.0f) * halfH;
                float y2 = centerY - clamp((minL / 32768.0f) * gain * zoomY, -1.0f, 1.0f) * halfH;
                canvas.drawLine(x, y1, x, y2, pL);
                if (stereo == 1 && maxR >= minR) {
                    float yr1 = centerY - clamp((maxR / 32768.0f) * gain * zoomY, -1.0f, 1.0f) * halfH;
                    float yr2 = centerY - clamp((minR / 32768.0f) * gain * zoomY, -1.0f, 1.0f) * halfH;
                    canvas.drawLine(x, yr1, x, yr2, pR);
                }
            }
        }
    }

    private int rawWindowFrames(int sampleRate) {
        if (sampleRate <= 0) {
            return 2;
        }
        int frames = Math.round(sampleRate * (RAW_WINDOW_MS / zoomX) / 1000.0f);
        // Screen-space budget: never scan/render more than 16 samples per
        // horizontal pixel.  This is a display cap, not an audio rate change.
        int cap = Math.max(4096, Math.min(65536, (int) (plot.width() * 16f)));
        frames = Math.min(frames, cap);
        return Math.max(16, Math.min(MAX_RENDER_FRAMES, frames));
    }

    private void drawRawGrid(Canvas canvas) {
        drawXtGrid(canvas);
        labelPaint.setColor(colorMuted);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        boolean stereo = pipeline != null && pipeline.getChannelCount() >= 2;
        canvas.drawText(stereo ? "LEFT" : "SIGNAL",
                plot.left + dp(5), plot.top + dp(11), labelPaint);
        if (stereo) {
            labelPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("RIGHT", plot.right - dp(5), plot.top + dp(11), labelPaint);
        }
        labelPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawRaw(Canvas canvas, int frames, int channels, int sampleRate) {
        int visible = Math.min(rawWindowFrames(sampleRate), frames);
        if (visible < 2) {
            drawNoSignal(canvas);
            return;
        }
        int start = frames - visible;
        float centerY = plot.centerY();
        float halfH = plot.height() / 2.0f - dp(2);
        float yGain = gain * zoomY;
        int stereo = channels >= 2 ? 1 : 0;
        float peakDb = -120.0f;

        canvas.save();
        canvas.clipRect(plot);
        boolean envelope = visible > plot.width() * 2.0f;
        if (envelope) {
            // One pass over the visible samples, bucketed into screen columns.
            // Build both channel paths once, then draw glow/core; this avoids
            // scanning the whole window twice and issuing 4 lines per pixel.
            int plotWidth = Math.max(1, (int) plot.width());
            pathL.reset();
            pathR.reset();
            int peakAbs = 0;
            for (int px = 0; px < plotWidth; px++) {
                int from = start + (int) ((long) visible * px / plotWidth);
                int to = start + (int) ((long) visible * (px + 1) / plotWidth);
                if (to <= from) to = from + 1;
                if (from >= frames) break;
                if (to > frames) to = frames;
                int minL = 32767;
                int maxL = -32768;
                int minR = 32767;
                int maxR = -32768;
                for (int i = from; i < to; i++) {
                    int idx = i * channels;
                    int l = frameBuffer[idx];
                    if (l < minL) minL = l;
                    if (l > maxL) maxL = l;
                    int al = l < 0 ? -l : l;
                    if (al > peakAbs) peakAbs = al;
                    if (stereo == 1) {
                        int r = frameBuffer[idx + 1];
                        if (r < minR) minR = r;
                        if (r > maxR) maxR = r;
                        int ar = r < 0 ? -r : r;
                        if (ar > peakAbs) peakAbs = ar;
                    }
                }
                float x = plot.left + (plot.width() * px) / Math.max(1, plotWidth - 1);
                float y1 = centerY - clamp((maxL / 32768.0f) * yGain, -1.0f, 1.0f) * halfH;
                float y2 = centerY - clamp((minL / 32768.0f) * yGain, -1.0f, 1.0f) * halfH;
                pathL.moveTo(x, y1);
                pathL.lineTo(x, y2);
                if (stereo == 1) {
                    float yr1 = centerY - clamp((maxR / 32768.0f) * yGain,
                            -1.0f, 1.0f) * halfH;
                    float yr2 = centerY - clamp((minR / 32768.0f) * yGain,
                            -1.0f, 1.0f) * halfH;
                    pathR.moveTo(x, yr1);
                    pathR.lineTo(x, yr2);
                }
            }
            if (stereo == 1) canvas.drawPath(pathR, addR);
            canvas.drawPath(pathL, addL);
            if (peakAbs > 0) {
                peakDb = 20.0f * (float) Math.log10(peakAbs / 32768.0f);
            }
        } else {
            pathL.reset();
            pathR.reset();
            for (int i = 0; i < visible; i++) {
                int idx = (start + i) * channels;
                float x = plot.left + (plot.width() * i) / Math.max(1, visible - 1);
                float y = centerY - clamp((frameBuffer[idx] / 32768.0f) * yGain,
                        -1.0f, 1.0f) * halfH;
                if (i == 0) pathL.moveTo(x, y); else pathL.lineTo(x, y);
                if (stereo == 1) {
                    float yr = centerY - clamp((frameBuffer[idx + 1] / 32768.0f) * yGain,
                            -1.0f, 1.0f) * halfH;
                    if (i == 0) pathR.moveTo(x, yr); else pathR.lineTo(x, yr);
                }
            }
            if (stereo == 1) canvas.drawPath(pathR, addR);
            canvas.drawPath(pathL, addL);
            peakDb = computePeakDb(visible, channels);
        }
        canvas.restore();

        int newest = (frames - 1) * channels;
        float leftV = frameBuffer[newest] / 32768.0f;
        String readout = String.format(Locale.US, "L %+.3f", leftV);
        if (stereo == 1) {
            float rightV = frameBuffer[newest + 1] / 32768.0f;
            readout += String.format(Locale.US, "   R %+.3f", rightV);
        }
        monoPaint.setColor(colorSecondary);
        monoPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(readout, plot.left + dp(5), plot.top + dp(24), monoPaint);

        labelPaint.setColor(colorMuted);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        float rawSeconds = sampleRate > 0 ? visible / (float) sampleRate : 0f;
        canvas.drawText(String.format(Locale.US, "−%.2f s", rawSeconds),
                plot.left, plot.bottom + dp(12), labelPaint);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(String.format(Locale.US, "−%.2f s", rawSeconds / 2.0f),
                plot.centerX(), plot.bottom + dp(12), labelPaint);
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("now", plot.right, plot.bottom + dp(12), labelPaint);
        labelPaint.setTextAlign(Paint.Align.LEFT);

        notifyAnalysis(sampleRate, channels, true, peakDb, 0.0f);
    }

    private void drawFrozenXy(Canvas canvas, int sampleRate, int channels) {
        int count = Math.min(freezeFrames, freezeBuffer.length / channels);
        if (count < 2) {
            return;
        }
        int needed = count * 2;
        if (xyScatter.length < needed) {
            xyScatter = new float[needed];
        }
        float halfX = plot.width() / 2.0f - dp(2);
        float halfY = plot.height() / 2.0f - dp(2);
        float cx = plot.centerX();
        float cy = plot.centerY();
        int delay = channels >= 2 ? 0 : Math.max(1, Math.round(sampleRate * 0.0005f));
        for (int i = 0; i < count; i++) {
            int idx = i * channels;
            float xv;
            float yv;
            if (channels >= 2) {
                xv = freezeBuffer[idx] / 32768.0f;
                yv = freezeBuffer[idx + 1] / 32768.0f;
            } else {
                int delayed = Math.max(0, i - delay);
                xv = freezeBuffer[delayed] / 32768.0f;
                yv = freezeBuffer[idx] / 32768.0f;
            }
            if (xySwap) {
                float tmp = xv;
                xv = yv;
                yv = tmp;
            }
            if (xyMirrorX) xv = -xv;
            if (xyMirrorY) yv = -yv;
            float px = cx + xv * gain * zoomX * halfX;
            float py = cy - yv * gain * zoomY * halfY;
            if (xyPixelMode) {
                float cell = dp(xyPixelSizeDp);
                px = Math.round(px / cell) * cell;
                py = Math.round(py / cell) * cell;
            }
            xyScatter[i * 2] = px;
            xyScatter[i * 2 + 1] = py;
        }
        canvas.save();
        canvas.clipRect(plot);
        canvas.drawPoints(xyScatter, 0, count * 2, xyGlowPaint);
        canvas.drawPoints(xyScatter, 0, count * 2, xyCorePaint);
        canvas.restore();
        notifyAnalysis(sampleRate, channels, true, -120.0f, 0.0f);
    }

    private void drawXy(Canvas canvas, int sampleRate, int channels) {
        if (pipeline == null || sampleRate <= 0) {
            drawNoSignal(canvas);
            return;
        }
        int generation = pipeline.getGeneration();
        if (generation != xyGeneration || sampleRate != xyRate || channels != xyChannels) {
            resetXyState();
            xyGeneration = generation;
            xyRate = sampleRate;
            xyChannels = channels;
        }

        int bitmapW = Math.max(2, (int) Math.ceil(plot.width()));
        int bitmapH = Math.max(2, (int) Math.ceil(plot.height()));
        ensureXyPhosphor(bitmapW, bitmapH);
        long now = SystemClock.uptimeMillis();
        long total = pipeline.getTotalFrames();
        long earliest = pipeline.getEarliestFrame();
        if (total < 2) {
            drawXyPhosphor(canvas);
            return;
        }

        if (xyNextFrame < 0L) {
            long preRoll = Math.min(2048L, Math.max(64L, sampleRate / 50L));
            xyNextFrame = Math.max(earliest, total - preRoll);
            xyForceStart = true;
        }

        long backlog = total - Math.max(xyNextFrame, earliest);
        boolean longGap = xyLastDrawAt > 0L && now - xyLastDrawAt > 450L;
        if (backlog > 200000L
                || (longGap && backlog > Math.max(4096L, sampleRate / 20L))) {
            // If the UI was away for a while, don't try to rasterise seconds of
            // stale samples in one frame. Start a fresh short pre-roll.
            xyNextFrame = Math.max(earliest, total - Math.max(512L, sampleRate / 50L));
            xyForceStart = true;
        }

        long from = Math.max(xyNextFrame, earliest);
        if (from >= total) {
            drawXyPhosphor(canvas);
            return;
        }

        int monoDelay = channels >= 2 ? 0 : Math.max(1, Math.round(sampleRate * 0.0005f));
        long readFrom = Math.max(earliest, from - monoDelay - 2L);
        if (total - readFrom > XY_MAX_COPY_FRAMES) {
            readFrom = Math.max(earliest, total - Math.max(4096L, sampleRate / 20L));
            xyForceStart = true;
            from = Math.max(from, readFrom);
        }
        int wantFrames = (int) Math.min((long) XY_MAX_COPY_FRAMES, total - readFrom);
        if (wantFrames < 2) {
            drawXyPhosphor(canvas);
            return;
        }
        int neededSamples = wantFrames * channels;
        if (xyBuffer.length < neededSamples) {
            xyBuffer = new short[neededSamples];
        }
        int copied = pipeline.copyFramesFrom(readFrom, xyBuffer, wantFrames);
        if (pipeline.getGeneration() != generation) {
            resetXyState();
            drawXyPhosphor(canvas);
            return;
        }
        if (copied < 2) {
            drawXyPhosphor(canvas);
            return;
        }

        int fromIndex = (int) Math.max(0L, from - readFrom);
        if (fromIndex >= copied) {
            xyNextFrame = readFrom + copied;
            drawXyPhosphor(canvas);
            return;
        }
        int context = Math.max(0, fromIndex - 2);
        if (channels < 2) {
            context = Math.max(context, monoDelay);
        }
        if (context >= copied - 1) {
            context = Math.max(0, copied - 2);
        }
        int count = copied - context;
        if (count < 2) {
            xyNextFrame = readFrom + copied;
            drawXyPhosphor(canvas);
            return;
        }
        ensureXyPointArrays(count);

        float halfX = xyPhosphorW / 2.0f - dp(2);
        float halfY = xyPhosphorH / 2.0f - dp(2);
        float cx = xyPhosphorW / 2.0f;
        float cy = xyPhosphorH / 2.0f;
        for (int k = 0; k < count; k++) {
            int i = context + k;
            int idx = i * channels;
            float xv;
            float yv;
            if (channels >= 2) {
                xv = xyBuffer[idx] / 32768.0f;
                yv = xyBuffer[idx + 1] / 32768.0f;
            } else {
                int delayed = i - monoDelay;
                if (delayed < 0) {
                    delayed = 0;
                }
                xv = xyBuffer[delayed] / 32768.0f;
                yv = xyBuffer[idx] / 32768.0f;
            }
            if (xySwap) {
                float tmp = xv;
                xv = yv;
                yv = tmp;
            }
            if (xyMirrorX) {
                xv = -xv;
            }
            if (xyMirrorY) {
                yv = -yv;
            }
            float px = cx + xv * gain * zoomX * halfX;
            float py = cy - yv * gain * zoomY * halfY;
            if (xyPixelMode) {
                float cell = dp(xyPixelSizeDp);
                px = Math.round(px / cell) * cell;
                py = Math.round(py / cell) * cell;
            }
            xyPointX[k] = px;
            xyPointY[k] = py;
        }

        boolean forceStart = xyForceStart;
        if (forceStart) {
            clearXyPhosphor();
            xyForceStart = false;
            xyLastFadeAt = now;
        } else {
            fadeXyPhosphor(now);
        }

        int startIndex = forceStart ? 0 : Math.max(0, fromIndex - context);
        if (startIndex >= count) {
            startIndex = count - 1;
        }
        int pointCount = count - startIndex;
        ensureXyScatter(pointCount);
        for (int p = 0; p < pointCount; p++) {
            xyScatter[p * 2] = xyPointX[startIndex + p];
            xyScatter[p * 2 + 1] = xyPointY[startIndex + p];
        }
        if (xyPhosphorCanvas != null) {
            // Scatter plot: no segments between successive samples.
            xyPhosphorCanvas.drawPoints(xyScatter, 0, pointCount * 2, xyGlowPaint);
            xyPhosphorCanvas.drawPoints(xyScatter, 0, pointCount * 2, xyCorePaint);
        }
        drawXyPhosphor(canvas);

        xyNextFrame = readFrom + copied;
        xyLastDrawAt = now;
        float peak = computePeakForBuffer(xyBuffer, copied, channels);
        notifyAnalysis(sampleRate, channels, true, peak, 0.0f);
    }

    private void ensureXyPhosphor(int width, int height) {
        if (xyPhosphor != null && xyPhosphorW == width && xyPhosphorH == height) {
            return;
        }
        releaseXyPhosphor();
        xyPhosphorW = width;
        xyPhosphorH = height;
        try {
            xyPhosphor = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            xyPhosphorCanvas = new Canvas(xyPhosphor);
            xyPhosphorCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        } catch (OutOfMemoryError e) {
            xyPhosphor = null;
            xyPhosphorCanvas = null;
        }
        xyForceStart = true;
    }

    private void ensureXyPointArrays(int count) {
        if (xyPointX.length < count) {
            xyPointX = new float[count];
            xyPointY = new float[count];
        }
    }

    private void ensureXyScatter(int points) {
        int needed = points * 2;
        if (xyScatter.length < needed) {
            xyScatter = new float[needed];
        }
    }

    private void appendXySegment(Path path, float[] xs, float[] ys, int count, int j) {
        int i1 = j - 1;
        int i2 = j;
        float x1 = xs[i1];
        float y1 = ys[i1];
        float x2 = xs[i2];
        float y2 = ys[i2];
        float dx = x2 - x1;
        float dy = y2 - y1;
        if (!xySmooth || dx * dx + dy * dy < dp(1.8f) * dp(1.8f)) {
            path.lineTo(x2, y2);
            return;
        }
        int i0 = Math.max(0, j - 2);
        int i3 = Math.min(count - 1, j + 1);
        float x0 = xs[i0];
        float y0 = ys[i0];
        float x3 = xs[i3];
        float y3 = ys[i3];
        for (int k = 1; k <= 2; k++) {
            float t = k / 2.0f;
            float t2 = t * t;
            float t3 = t2 * t;
            float px = 0.5f * ((2.0f * x1)
                    + (-x0 + x2) * t
                    + (2.0f * x0 - 5.0f * x1 + 4.0f * x2 - x3) * t2
                    + (-x0 + 3.0f * x1 - 3.0f * x2 + x3) * t3);
            float py = 0.5f * ((2.0f * y1)
                    + (-y0 + y2) * t
                    + (2.0f * y0 - 5.0f * y1 + 4.0f * y2 - y3) * t2
                    + (-y0 + 3.0f * y1 - 3.0f * y2 + y3) * t3);
            path.lineTo(px, py);
        }
    }

    private void fadeXyPhosphor(long now) {
        if (xyPhosphorCanvas == null) {
            return;
        }
        if (xyPersistenceMs <= 1.0f) {
            clearXyPhosphor();
            xyLastFadeAt = now;
            return;
        }
        long since = xyLastFadeAt <= 0L ? 16L : now - xyLastFadeAt;
        if (since <= 0L) {
            return;
        }
        if (since > 500L) {
            since = 500L;
        }
        double tauSeconds = Math.max(1.0f, xyPersistenceMs) / 3000.0;
        double keepLoss = 1.0 - Math.exp(-(since / 1000.0) / tauSeconds);
        int alpha = (int) Math.round(255.0 * keepLoss);
        if (alpha <= 0) {
            alpha = 1;
        }
        if (alpha >= 246) {
            clearXyPhosphor();
        } else {
            xyPhosphorCanvas.drawColor(Color.argb(alpha, 0, 0, 0), PorterDuff.Mode.DST_OUT);
        }
        xyLastFadeAt = now;
    }

    private void clearXyPhosphor() {
        if (xyPhosphorCanvas != null) {
            xyPhosphorCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        }
    }

    private void drawXyPhosphor(Canvas canvas) {
        if (xyPhosphor == null) {
            return;
        }
        canvas.save();
        canvas.clipRect(plot);
        if (xyPhosphorW == (int) Math.ceil(plot.width())
                && xyPhosphorH == (int) Math.ceil(plot.height())) {
            canvas.drawBitmap(xyPhosphor, plot.left, plot.top, xyBitmapPaint);
        } else {
            canvas.drawBitmap(xyPhosphor, null, plot, xyBitmapPaint);
        }
        canvas.restore();
    }

    private float computePeakForBuffer(short[] data, int frames, int channels) {
        int max = 0;
        int samples = Math.min(frames * channels, data.length);
        for (int i = 0; i < samples; i++) {
            int v = data[i];
            if (v < 0) v = -v;
            if (v > max) max = v;
        }
        if (max <= 0) {
            return -120.0f;
        }
        return 20.0f * (float) Math.log10(max / 32768.0f);
    }

    public String buildExportCsv() {
        if (pipeline == null) {
            return null;
        }
        int rate = pipeline.getSampleRate();
        int channels = Math.max(1, pipeline.getChannelCount());
        if (rate <= 0) {
            return null;
        }
        String modeName;
        if (mode == MODE_FFT) {
            modeName = "FFT";
        } else if (mode == MODE_XY) {
            modeName = "XY";
        } else if (mode == MODE_RAW) {
            modeName = "RAW";
        } else {
            modeName = "XT";
        }
        StringBuilder sb = new StringBuilder(1 << 20);
        sb.append("# AudioVisualizer 1.0.30 data export\n");
        sb.append("# generated=")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append("\n");
        sb.append("# mode=").append(modeName).append("\n");
        sb.append("# sample_rate_hz=").append(rate).append("\n");
        sb.append("# channels=").append(channels).append("\n");
        sb.append("# gain=").append(gain).append("\n");
        sb.append("# zoom_x=").append(zoomX).append("\n");
        sb.append("# zoom_y=").append(zoomY).append("\n");
        if (mode == MODE_FFT) {
            sb.append("# fft_size=").append(fftSize).append("\n");
            sb.append("# db_floor=").append(currentDbFloor()).append("\n");
        }
        sb.append("# picks=").append(chartPicks.size()).append("\n");
        for (int i = 0; i < chartPicks.size(); i++) {
            ChartPick p = chartPicks.get(i);
            sb.append("# pick_").append(i + 1).append(": u=")
                    .append(p.u).append(",v=").append(p.v)
                    .append(",x=").append(p.xValue)
                    .append(",y=").append(p.yValue).append("\n");
        }
        if (fitValid) {
            sb.append("# fit=").append(fitEquation).append("\n");
        }

        if (mode == MODE_FFT) {
            int bins = fftSize / 2;
            if (smoothL.length <= bins) {
                return null;
            }
            sb.append("frequency_hz,left_db");
            if (channels >= 2) {
                sb.append(",right_db");
            }
            sb.append("\n");
            float df = rate / (float) fftSize;
            for (int b = 1; b <= bins; b++) {
                sb.append(b * df).append(',').append(smoothL[b]);
                if (channels >= 2) {
                    sb.append(',').append(smoothR[b]);
                }
                sb.append('\n');
            }
            return sb.toString();
        }

        int maxFrames = Math.min(Math.max(1, rate * 3), 300000);
        short[] buf = new short[maxFrames * channels];
        int got = pipeline.copyLatest(buf, maxFrames);
        if (got <= 0) {
            return null;
        }
        sb.append("# segment=latest_raw, frames=").append(got).append("\n");
        sb.append("sample_index,time_s");
        if (channels >= 2) {
            sb.append(",left,right");
        } else {
            sb.append(",amplitude");
        }
        sb.append("\n");
        for (int i = 0; i < got; i++) {
            sb.append(i).append(',').append(i / (double) rate);
            if (channels >= 2) {
                sb.append(',').append(buf[i * channels] / 32768.0)
                        .append(',').append(buf[i * channels + 1] / 32768.0);
            } else {
                sb.append(',').append(buf[i] / 32768.0);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private void resetSpectrumHistory() {
        waterfallScrollAccum = 0f;
        releaseSpectrumHistory();
    }

    private void releaseSpectrumHistory() {
        if (specHistoryBitmap != null) {
            specHistoryBitmap.recycle();
            specHistoryBitmap = null;
        }
        if (specHistoryScratch != null) {
            specHistoryScratch.recycle();
            specHistoryScratch = null;
        }
        specHistoryCanvas = null;
        specHistoryScratchCanvas = null;
        specHistoryW = 0;
        specHistoryH = 0;
    }

    private void ensureSpectrumHistory(int width, int height) {
        if (specHistoryBitmap != null && specHistoryW == width && specHistoryH == height) {
            return;
        }
        releaseSpectrumHistory();
        specHistoryW = width;
        specHistoryH = height;
        try {
            specHistoryBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            specHistoryScratch = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            specHistoryCanvas = new Canvas(specHistoryBitmap);
            specHistoryScratchCanvas = new Canvas(specHistoryScratch);
            specHistoryBitmap.eraseColor(Color.BLACK);
            specHistoryScratch.eraseColor(Color.BLACK);
            specHistoryRow = new int[width];
        } catch (OutOfMemoryError e) {
            releaseSpectrumHistory();
        }
    }

    private void drawFullSpectrogram(Canvas canvas, int sampleRate) {
        if (fullSpectrogram == null || fullSpectrogram.isRecycled()) {
            return;
        }
        canvas.save();
        canvas.clipRect(historyPlot);
        int targetW = Math.max(2, (int) Math.ceil(historyPlot.width()));
        int targetH = Math.max(2, (int) Math.ceil(historyPlot.height()));
        if (fullSpectrogramScaled == null
                || fullSpecScaledW != targetW || fullSpecScaledH != targetH) {
            if (fullSpectrogramScaled != null) {
                fullSpectrogramScaled.recycle();
                fullSpectrogramScaled = null;
            }
            try {
                fullSpectrogramScaled = Bitmap.createScaledBitmap(
                        fullSpectrogram, targetW, targetH, true);
                fullSpecScaledW = targetW;
                fullSpecScaledH = targetH;
            } catch (OutOfMemoryError e) {
                fullSpectrogramScaled = null;
            }
        }
        if (fullSpectrogramScaled != null) {
            canvas.drawBitmap(fullSpectrogramScaled, historyPlot.left,
                    historyPlot.top, fullSpectrogramPaint);
        } else {
            canvas.drawBitmap(fullSpectrogram, null, historyPlot, fullSpectrogramPaint);
        }
        canvas.restore();
        canvas.drawRect(historyPlot, gridMajorPaint);

        overlayTextPaint.setTextSize(sp(7.0f));
        overlayTextPaint.setTextAlign(Paint.Align.LEFT);
        overlayTextPaint.setColor(colorSecondary);
        canvas.drawText("FULL FILE SPECTRUM", historyPlot.left + dp(5),
                historyPlot.top + dp(10), overlayTextPaint);

        int rate = fullSpectrogramRate > 0 ? fullSpectrogramRate : sampleRate;
        float nyquist = Math.max(1.0f, rate / 2.0f);
        float fMin = 20.0f;
        if (nyquist > fMin) {
            float[] marks = {100f, 1000f, 10000f};
            labelPaint.setColor(colorMuted);
            labelPaint.setTextAlign(Paint.Align.RIGHT);
            for (float f : marks) {
                if (f <= fMin || f >= nyquist) {
                    continue;
                }
                float t = (float) (Math.log(f / fMin) / Math.log(nyquist / fMin));
                float y = historyPlot.bottom - t * historyPlot.height();
                canvas.drawText(shortFreq(f), historyPlot.left - dp(3), y + dp(2.5f), labelPaint);
            }
            labelPaint.setTextAlign(Paint.Align.LEFT);
        }

        long duration = fullSpectrogramDurationUs;
        if (duration <= 0L) {
            duration = 1L;
        }
        float fraction = clamp(playbackPositionUs / (float) duration, 0.0f, 1.0f);
        float cursorX = historyPlot.left + fraction * historyPlot.width();
        markerPaint.setColor(Color.WHITE);
        markerPaint.setAlpha(225);
        markerPaint.setPathEffect(null);
        canvas.drawLine(cursorX, historyPlot.top, cursorX, historyPlot.bottom, markerPaint);
        markerPaint.setColor(colorAmber);
        markerPaint.setAlpha(160);
        markerPaint.setPathEffect(new DashPathEffect(new float[]{dp(2), dp(3)}, 0));

        overlayTextPaint.setTextSize(sp(6.8f));
        overlayTextPaint.setTextAlign(Paint.Align.CENTER);
        overlayTextPaint.setColor(colorMuted);
        double durationSec = Math.max(0.001, duration / 1_000_000.0);
        double[] timeSteps = {0.1, 0.2, 0.5, 1, 2, 5, 10, 15, 30, 60,
                120, 300, 600, 900, 1800, 3600};
        String edgeLabel = formatDurationShort(duration);
        float edgeW = overlayTextPaint.measureText(edgeLabel);
        int maxLabels = Math.max(4, Math.min(24,
                (int) (historyPlot.width() / Math.max(1f, edgeW + dp(2)))));
        double timeStep = timeSteps[timeSteps.length - 1];
        for (double candidate : timeSteps) {
            if (durationSec / candidate <= maxLabels) {
                timeStep = candidate;
                break;
            }
        }
        double minorStep = timeStep / 5.0;
        int minorCount = (int) Math.floor(durationSec / minorStep + 0.0001);
        for (int i = 1; i <= minorCount; i++) {
            double t = i * minorStep;
            if (t >= durationSec) break;
            float mx = historyPlot.left + (float) (t / durationSec) * historyPlot.width();
            canvas.drawLine(mx, historyPlot.bottom - dp(3), mx, historyPlot.bottom,
                    gridPaint);
        }
        int majorCount = (int) Math.floor(durationSec / timeStep + 0.0001);
        for (int i = 1; i <= majorCount; i++) {
            double t = i * timeStep;
            if (t >= durationSec - timeStep * 0.3) break;
            float x = historyPlot.left + (float) (t / durationSec) * historyPlot.width();
            if (x < historyPlot.left + edgeW + dp(10)
                    || x > historyPlot.right - edgeW - dp(10)) {
                continue;
            }
            canvas.drawLine(x, historyPlot.top, x, historyPlot.bottom, gridPaint);
            canvas.drawLine(x, historyPlot.bottom - dp(5), x, historyPlot.bottom,
                    gridMajorPaint);
            String label = formatDurationShort(Math.round(t * 1_000_000.0));
            float lw = overlayTextPaint.measureText(label);
            if (x - lw / 2 < historyPlot.left + dp(2)
                    || x + lw / 2 > historyPlot.right - dp(2)) {
                continue;
            }
            canvas.drawText(label, x, historyPlot.bottom + dp(12), overlayTextPaint);
        }
        overlayTextPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("00:00", historyPlot.left, historyPlot.bottom + dp(12), overlayTextPaint);
        overlayTextPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(edgeLabel, historyPlot.right,
                historyPlot.bottom + dp(12), overlayTextPaint);
        overlayTextPaint.setTextAlign(Paint.Align.LEFT);
        overlayTextPaint.setTextSize(sp(8.0f));
    }

    private String formatDurationShort(long us) {
        long totalSeconds = Math.max(0L, us / 1_000_000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void drawSpectrumHistory(Canvas canvas, int sampleRate) {
        if (historyPlot.width() < 12 || historyPlot.height() < 12 || sampleRate <= 0) {
            return;
        }
        if (fullSpectrogram != null && waterfallFull) {
            drawFullSpectrogram(canvas, sampleRate);
            return;
        }
        int width = Math.max(2, (int) Math.ceil(historyPlot.width()));
        int height = Math.max(2, (int) Math.ceil(historyPlot.height()));
        ensureSpectrumHistory(width, height);
        if (specHistoryBitmap == null || smoothL.length < 2) {
            return;
        }
        buildSpectrumHistoryRow(sampleRate, width);
        if (specHistoryRow.length != width) {
            return;
        }
        waterfallScrollAccum += Math.max(0.0f, waterfallSpeed);
        int rows = (int) waterfallScrollAccum;
        if (rows > 0) {
            waterfallScrollAccum -= rows;
            rows = Math.min(rows, height);
            if (specHistoryScratchCanvas != null) {
                specHistoryScratchCanvas.drawBitmap(specHistoryBitmap, 0, -rows, null);
                for (int r = 0; r < rows; r++) {
                    specHistoryScratch.setPixels(specHistoryRow, 0, width,
                            0, height - rows + r, width, 1);
                }
                Bitmap temp = specHistoryBitmap;
                specHistoryBitmap = specHistoryScratch;
                specHistoryScratch = temp;
                Canvas tc = specHistoryCanvas;
                specHistoryCanvas = specHistoryScratchCanvas;
                specHistoryScratchCanvas = tc;
            }
        }

        canvas.save();
        canvas.clipRect(historyPlot);
        canvas.drawBitmap(specHistoryBitmap, historyPlot.left, historyPlot.top,
                historyBitmapPaint);
        canvas.restore();
        canvas.drawRect(historyPlot, gridMajorPaint);

        overlayTextPaint.setColor(colorMuted);
        overlayTextPaint.setTextAlign(Paint.Align.LEFT);
        overlayTextPaint.setTextSize(sp(7.0f));
        canvas.drawText("HISTORY", historyPlot.left + dp(5), historyPlot.top + dp(10), overlayTextPaint);
        overlayTextPaint.setTextSize(sp(8.0f));
        overlayTextPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("now", historyPlot.right - dp(5), historyPlot.bottom - dp(4), overlayTextPaint);
        overlayTextPaint.setTextSize(sp(7.0f));
        overlayTextPaint.setColor(Color.GREEN);
        canvas.drawText("R", historyPlot.right - dp(5), historyPlot.top + dp(10), overlayTextPaint);
        overlayTextPaint.setColor(Color.RED);
        canvas.drawText("L", historyPlot.right - dp(19), historyPlot.top + dp(10), overlayTextPaint);
        overlayTextPaint.setTextSize(sp(8.0f));
        overlayTextPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void buildSpectrumHistoryRow(int sampleRate, int width) {
        int bins = fftSize / 2;
        float df = sampleRate / (float) fftSize;
        float maxHz = spectrumViewMax(sampleRate);
        float minHz = spectrumViewMin(sampleRate);
        float floor = currentDbFloor();
        int stereo = pipeline != null && pipeline.getChannelCount() >= 2 ? 1 : 0;
        for (int x = 0; x < width; x++) {
            float u0 = x / (float) Math.max(1, width - 1);
            float u1 = (x + 1) / (float) Math.max(1, width - 1);
            float f0 = freqFromU(u0, minHz, maxHz);
            float f1 = freqFromU(u1, minHz, maxHz);
            int b0 = Math.max(0, (int) Math.floor(f0 / df));
            int b1 = Math.max(b0, (int) Math.ceil(f1 / df));
            b1 = Math.min(b1, bins);
            float dbL;
            float dbR;
            float binSpan = (f1 - f0) / df;
            if (binSpan <= 2.0f) {
                float pos = (f0 + f1) * 0.5f / df;
                if (pos < 0f) pos = 0f;
                if (pos > bins) pos = bins;
                int lower = Math.max(0, Math.min(bins, (int) Math.floor(pos)));
                int upper = Math.min(bins, lower + 1);
                float frac = pos - lower;
                dbL = smoothL[lower] + (smoothL[upper] - smoothL[lower]) * frac;
                if (stereo == 1) {
                    dbR = smoothR[lower] + (smoothR[upper] - smoothR[lower]) * frac;
                } else {
                    dbR = floor;
                }
            } else {
                dbL = floor;
                dbR = floor;
                for (int b = b0; b <= b1; b++) {
                    if (smoothL[b] > dbL) dbL = smoothL[b];
                    if (stereo == 1 && smoothR[b] > dbR) dbR = smoothR[b];
                }
            }
            int red = historyLevel(dbL, floor);
            int green = historyLevel(dbR, floor);
            specHistoryRow[x] = Color.rgb(red, green, 0);
        }
    }

    private int historyLevel(float db, float floor) {
        float t = clamp((db - floor) / Math.max(1.0f, -floor), 0.0f, 1.0f);
        t = (float) Math.pow(t, 1.35);
        return Math.round(255.0f * t);
    }

    private void drawSpectrum(Canvas canvas, int frames, int channels, int sampleRate) {
        ensureSpectrum(sampleRate, channels);
        int n = fftSize;
        int bins = n / 2;
        int stereo = channels >= 2 ? 1 : 0;
        int used = Math.min(frames, n);
        int offset = frames - used;
        float peak = computePeakDb(frames, channels);
        long now = SystemClock.uptimeMillis();
        boolean recompute = !preRenderFft || !spectrumCacheValid
                || now - spectrumCacheAt >= 33L;

        if (recompute) {
        for (int ch = 0; ch <= stereo; ch++) {
            for (int i = 0; i < n; i++) {
                if (i < used) {
                    int idx = (offset + i) * channels + ch;
                    fftRe[i] = (frameBuffer[idx] / 32768.0f) * window[i];
                } else {
                    fftRe[i] = 0.0f;
                }
                fftIm[i] = 0.0f;
            }
            Fft.transform(fftRe, fftIm);
            float[] smooth = ch == 0 ? smoothL : smoothR;
            for (int b = 0; b <= bins; b++) {
                float re = fftRe[b];
                float im = fftIm[b];
                float mag = (float) Math.sqrt(re * re + im * im);
                float norm = (b == 0 || b == bins) ? 1.0f : 2.0f;
                float amp = mag * norm / Math.max(1.0f, windowSum);
                float db = 20.0f * (float) Math.log10(Math.max(amp, 1e-7f));
                if (db < -120.0f) db = -120.0f;
                if (db > 0.0f) db = 0.0f;
                float old = smooth[b];
                if (!spectrumInitialized || old == 0.0f) {
                    smooth[b] = db;
                } else if (db >= old) {
                    smooth[b] = old * 0.30f + db * 0.70f;
                } else {
                    smooth[b] = old * 0.72f + db * 0.28f;
                }
            }
            spectrumInitialized = true;
        }
        spectrumCacheValid = true;
        spectrumCacheAt = now;
        }

        float fMin = spectrumViewMin(sampleRate);
        float fMax = spectrumViewMax(sampleRate);
        int plotWidth = Math.max(1, (int) plot.width());
        ensureCurveArrays(plotWidth);
        buildSpectrumCurve(smoothL, bins, fMin, fMax, sampleRate, curveYL, plotWidth);
        if (stereo == 1) {
            buildSpectrumCurve(smoothR, bins, fMin, fMax, sampleRate, curveYR, plotWidth);
        }

        int curveLen = Math.min(plotWidth, curveYL.length);
        pathL.reset();
        pathR.reset();
        for (int i = 0; i < curveLen; i++) {
            float x = plot.left + (curveLen <= 1 ? 0 : plot.width() * i / (curveLen - 1));
            float y = curveYL[i];
            if (i == 0) {
                pathL.moveTo(x, y);
            } else {
                pathL.lineTo(x, y);
            }
        }
        if (stereo == 1) {
            for (int i = 0; i < curveLen; i++) {
                float x = plot.left + (curveLen <= 1 ? 0 : plot.width() * i / (curveLen - 1));
                float y = curveYR[i];
                if (i == 0) {
                    pathR.moveTo(x, y);
                } else {
                    pathR.lineTo(x, y);
                }
            }
        }

        canvas.save();
        canvas.clipRect(plot);
        if (stereo == 1) {
            canvas.drawPath(pathR, addGlowR);
            canvas.drawPath(pathR, addR);
        }
        canvas.drawPath(pathL, addGlowL);
        canvas.drawPath(pathL, addL);

        float peakHz = findPeakHz(bins, sampleRate, stereo);
        lastDominantHz = peakHz;
        if (peakHz > 0 && peak > -90.0f) {
            drawPeakMarker(canvas, peakHz, fMin, fMax);
        }
        canvas.restore();

        notifyAnalysis(sampleRate, channels, true, peak, lastDominantHz);
    }

    private void buildSpectrumCurve(float[] smooth, int bins, float fMin, float nyquist,
                                    int sampleRate, float[] outY, int plotWidth) {
        float df = sampleRate / (float) fftSize;
        int maxBin = Math.max(1, bins - 1);
        for (int i = 0; i < plotWidth; i++) {
            float x0 = plot.left + plot.width() * i / Math.max(1, plotWidth - 1);
            float x1 = plot.left + plot.width() * (i + 1) / Math.max(1, plotWidth - 1);
            float f0 = xToFreq(x0, fMin, nyquist);
            float f1 = xToFreq(x1, fMin, nyquist);
            if (f1 < f0) {
                float t = f0;
                f0 = f1;
                f1 = t;
            }
            float binSpan = (f1 - f0) / df;
            int b0 = Math.max(0, (int) Math.floor(f0 / df));
            int b1 = Math.max(b0, (int) Math.ceil(f1 / df));
            if (b0 > maxBin) {
                b0 = maxBin;
            }
            if (b1 > maxBin) {
                b1 = maxBin;
            }
            float db;
            if (binSpan <= 2.0f) {
                float pos = (f0 + f1) * 0.5f / df;
                if (pos < 0f) pos = 0f;
                if (pos > maxBin) pos = maxBin;
                int lower = Math.max(0, Math.min(maxBin, (int) Math.floor(pos)));
                int upper = Math.min(maxBin, lower + 1);
                float frac = pos - lower;
                db = smooth[lower] + (smooth[upper] - smooth[lower]) * frac;
            } else {
                db = -120.0f;
                for (int b = b0; b <= b1; b++) {
                    if (smooth[b] > db) {
                        db = smooth[b];
                    }
                }
            }
            float floor = currentDbFloor();
            if (db < floor) {
                db = floor;
            }
            outY[i] = dbToY(db);
        }
    }

    private void drawPeakMarker(Canvas canvas, float hz, float fMin, float nyquist) {
        if (hz <= 0) {
            return;
        }
        float x = freqToX(hz, fMin, nyquist);
        if (x < plot.left || x > plot.right) {
            return;
        }
        markerPaint.setColor(colorAmber);
        markerPaint.setPathEffect(new DashPathEffect(new float[]{dp(2), dp(3)}, 0));
        canvas.drawLine(x, plot.top, x, plot.bottom, markerPaint);
        String text = formatFreq(hz);
        monoPaint.setTextAlign(Paint.Align.LEFT);
        monoPaint.setColor(colorAmber);
        float tw = monoPaint.measureText(text);
        float tx = x + dp(5);
        if (tx + tw > plot.right) {
            tx = x - tw - dp(5);
        }
        if (tx < plot.left) {
            tx = plot.left + dp(2);
        }
        canvas.drawText(text, tx, plot.top + dp(12), monoPaint);
        dotPaint.setColor(colorAmber);
        canvas.drawCircle(x, plot.top + dp(16.5f), dp(1.8f), dotPaint);
        dotPaint.setColor(colorCyan);
    }

    private float findPeakHz(int bins, int sampleRate, int stereo) {
        int maxBin = Math.max(1, bins - 1);
        float df = sampleRate / (float) fftSize;
        int startBin = Math.max(1, (int) Math.ceil(20.0f / df));
        float best = -120.0f;
        int bestBin = -1;
        float[] bestArr = smoothL;
        for (int b = startBin; b <= maxBin; b++) {
            if (smoothL[b] > best) {
                best = smoothL[b];
                bestBin = b;
                bestArr = smoothL;
            }
            if (stereo == 1 && smoothR[b] > best) {
                best = smoothR[b];
                bestBin = b;
                bestArr = smoothR;
            }
        }
        if (bestBin < 1) {
            return 0.0f;
        }
        float delta = 0.0f;
        if (bestBin > 1 && bestBin < maxBin) {
            float a = bestArr[bestBin - 1];
            float b = bestArr[bestBin];
            float c = bestArr[bestBin + 1];
            float denom = a - 2.0f * b + c;
            if (Math.abs(denom) > 1e-5f) {
                delta = 0.5f * (a - c) / denom;
                if (delta > 0.5f) delta = 0.5f;
                if (delta < -0.5f) delta = -0.5f;
            }
        }
        float hz = (bestBin + delta) * df;
        if (hz < 0) hz = 0;
        return hz;
    }

    private void drawNoSignal(Canvas canvas) {
        hintDashPaint.setColor(Color.argb(70, 88, 130, 146));
        float midY = plot.centerY();
        canvas.drawLine(plot.left + dp(10), midY, plot.right - dp(10), midY, hintDashPaint);
        canvas.drawCircle(plot.centerX(), midY, dp(3), dotPaint);

        int width = Math.max(1, (int) (plot.width() * 0.86f));
        String title = hintTitle == null || hintTitle.length() == 0 ? "等待音频信号" : hintTitle;
        String body = hintBody == null ? "" : hintBody;
        hintTitlePaint.setTextSize(sp(13.0f));
        StaticLayout titleLayout = new StaticLayout(title, new TextPaint(hintTitlePaint), width,
                Layout.Alignment.ALIGN_CENTER, 1.0f, dp(2), false);
        hintBodyPaint.setTextSize(sp(9.0f));
        StaticLayout bodyLayout = new StaticLayout(body, new TextPaint(hintBodyPaint), width,
                Layout.Alignment.ALIGN_CENTER, 1.0f, dp(3), false);
        int totalHeight = titleLayout.getHeight()
                + (body.length() > 0 ? (int) dp(8) + bodyLayout.getHeight() : 0);
        float top = midY - totalHeight / 2.0f + dp(8);
        canvas.save();
        canvas.translate(plot.centerX() - width / 2.0f, top);
        titleLayout.draw(canvas);
        if (body.length() > 0) {
            canvas.translate(0, titleLayout.getHeight() + dp(8));
            bodyLayout.draw(canvas);
        }
        canvas.restore();

        tinyPaint.setTextAlign(Paint.Align.CENTER);
        tinyPaint.setColor(Color.argb(130, 255, 179, 92));
        canvas.drawText("NO SIGNAL", plot.centerX(), midY - totalHeight / 2.0f - dp(4), tinyPaint);
        tinyPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawBottomLegend(Canvas canvas, int sampleRate, int channels) {
        labelPaint.setColor(colorMuted);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        String left;
        if (mode == MODE_FFT) {
            left = "FFT " + fftSize + "  ·  Δf "
                    + (sampleRate > 0 ? formatSmallHz((float) sampleRate / fftSize) : "—")
                    + (logFrequency ? "  ·  LOG" : "  ·  LIN");
        } else if (mode == MODE_XY) {
            String persist = xyPersistenceMs < 1.0f
                    ? "NO GLOW" : ("GLOW " + Math.round(xyPersistenceMs) + " ms");
            left = "ALL SAMPLES  ·  " + persist;
        } else if (mode == MODE_RAW) {
            left = "RAW PCM  ·  " + String.format(Locale.US, "%.2f s rolling",
                    sampleRate > 0 ? rawWindowFrames(sampleRate) / (float) sampleRate : 0.0f);
        } else {
            float effMs = timePerDivMs / zoomX;
            left = formatTimebase(effMs) + "/div  ·  "
                    + String.format(Locale.US, "%.0f ms window", effMs * 10.0f);
        }
        if (zoomX > 1.001f || zoomY > 1.001f) {
            left = left + "  ·  " + String.format(Locale.US, "ZOOM X%.1f Y%.1f",
                    zoomX, zoomY);
        }
        canvas.drawText(left, dp(14), getHeight() - dp(7), labelPaint);

        float x = getWidth() - dp(14);
        monoPaint.setTextAlign(Paint.Align.RIGHT);
        if (mode == MODE_XY) {
            monoPaint.setColor(Color.YELLOW);
            canvas.drawText("L + R", x, getHeight() - dp(7), monoPaint);
            float w = monoPaint.measureText("L + R");
            traceL.setAlpha(255);
            traceL.setColor(Color.YELLOW);
            canvas.drawLine(x - w - dp(13), getHeight() - dp(10),
                    x - w - dp(4), getHeight() - dp(10), traceL);
            traceL.setColor(Color.RED);
        } else if (channels >= 2) {
            monoPaint.setColor(Color.GREEN);
            canvas.drawText("CH R", x, getHeight() - dp(7), monoPaint);
            float rw = monoPaint.measureText("CH R");
            traceR.setAlpha(255);
            canvas.drawLine(x - rw - dp(13), getHeight() - dp(10), x - rw - dp(4), getHeight() - dp(10), traceR);
            x -= rw + dp(17);
            monoPaint.setColor(Color.RED);
            canvas.drawText("CH L", x, getHeight() - dp(7), monoPaint);
            float lw = monoPaint.measureText("CH L");
            canvas.drawLine(x - lw - dp(13), getHeight() - dp(10), x - lw - dp(4), getHeight() - dp(10), traceL);
        } else {
            monoPaint.setColor(Color.RED);
            canvas.drawText("MONO", x, getHeight() - dp(7), monoPaint);
        }
        monoPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void notifyAnalysis(int sampleRate, int channels, boolean signal,
                                float peakDb, float dominantHz) {
        lastPeakDb = peakDb;
        lastHadSignal = signal;
        lastAnalysisRate = sampleRate;
        lastAnalysisChannels = channels;
        if (signal) {
            lastDominantHz = dominantHz;
        }
        long now = SystemClock.uptimeMillis();
        if (analysisListener == null || now - lastAnalysisAt < 200) {
            return;
        }
        lastAnalysisAt = now;
        analysisListener.onAnalysis(peakDb, mode == MODE_FFT ? lastDominantHz : 0.0f,
                sampleRate, channels, signal);
    }

    private int desiredFrames(int sampleRate) {
        if (sampleRate <= 0) {
            return 2;
        }
        double windowMs = timePerDivMs * 10.0;
        if (mode == MODE_XT) {
            windowMs /= zoomX;
        }
        long frames = Math.round((double) sampleRate * windowMs / 1000.0);
        if (frames < 16) frames = 16;
        if (frames > MAX_RENDER_FRAMES) frames = MAX_RENDER_FRAMES;
        return (int) frames;
    }

    private int copyFrames(int wantFrames, int channels) {
        int wantSamples = wantFrames * channels;
        if (frameBuffer.length < wantSamples) {
            frameBuffer = new short[wantSamples];
        }
        return pipeline.copyLatest(frameBuffer, wantFrames);
    }

    private int findTrigger(int frames, int channels, int pretrigger, int maxIndex) {
        int ch = 0;
        int end = Math.min(frames - 1, maxIndex);
        if (end < 1) {
            return -1;
        }
        float level = triggerLevel;
        for (int i = end; i >= 1; i--) {
            float prev = frameBuffer[(i - 1) * channels + ch] / 32768.0f;
            float cur = frameBuffer[i * channels + ch] / 32768.0f;
            boolean crossed = triggerRising
                    ? (prev < level && cur >= level)
                    : (prev > level && cur <= level);
            if (crossed) {
                return i;
            }
        }
        return -1;
    }

    private float computePeakDb(int frames, int channels) {
        int max = 0;
        int samples = frames * channels;
        int limit = Math.min(samples, frameBuffer.length);
        for (int i = 0; i < limit; i++) {
            int v = frameBuffer[i];
            if (v < 0) v = -v;
            if (v > max) max = v;
        }
        if (max <= 0) {
            return -120.0f;
        }
        return 20.0f * (float) Math.log10(max / 32768.0f);
    }

    private void ensureCurveArrays(int width) {
        if (curveYL.length < width) {
            // This is a display raster buffer, not an audio sample buffer.
            curveYL = new float[width];
            curveYR = new float[width];
        }
    }

    private void ensureSpectrum(int sampleRate, int channels) {
        if (fftRe.length != fftSize) {
            fftRe = new float[fftSize];
            fftIm = new float[fftSize];
            window = new float[fftSize];
            windowSum = 0.0f;
            for (int i = 0; i < fftSize; i++) {
                double hann = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (fftSize - 1));
                window[i] = (float) hann;
                windowSum += window[i];
            }
            int bins = fftSize / 2 + 1;
            smoothL = new float[bins];
            smoothR = new float[bins];
            spectrumInitialized = false;
            spectrumCacheValid = false;
        }
        if (spectrumRate != sampleRate || spectrumChannels != channels) {
            spectrumInitialized = false;
            spectrumCacheValid = false;
            spectrumRate = sampleRate;
            spectrumChannels = channels;
            for (int i = 0; i < smoothL.length; i++) {
                smoothL[i] = 0.0f;
                smoothR[i] = 0.0f;
            }
            resetSpectrumHistory();
        }
    }

    private float dbToY(float db) {
        float floor = currentDbFloor();
        float t = (clamp(db, floor, 0.0f) - floor) / Math.max(1.0f, -floor);
        return plot.bottom - t * plot.height();
    }

    private float spectrumBaseMin(float maxHz) {
        if (!logFrequency) {
            return 0.0f;
        }
        if (maxHz <= 1.0f) {
            return 0.0f;
        }
        return Math.min(20.0f, Math.max(1.0f, maxHz / 1000.0f));
    }

    private float spectrumViewMin(int sampleRate) {
        if (sampleRate <= 0 || zoomX <= 1.0f) {
            return sampleRate > 0 ? spectrumBaseMin(sampleRate / 2.0f) : 20.0f;
        }
        float baseMax = sampleRate / 2.0f;
        float baseMin = spectrumBaseMin(baseMax);
        float center = clamp(fftZoomCenterHz, baseMin, baseMax);
        if (logFrequency && baseMin > 0.0f) {
            double logMin = Math.log(baseMin);
            double logMax = Math.log(baseMax);
            double logCenter = Math.log(Math.max(baseMin, center));
            double half = (logMax - logMin) / (2.0 * zoomX);
            return (float) Math.exp(Math.max(logMin, logCenter - half));
        }
        double half = (baseMax - baseMin) / (2.0 * zoomX);
        return (float) Math.max(baseMin, center - half);
    }

    private float spectrumViewMax(int sampleRate) {
        if (sampleRate <= 0) {
            return 24000.0f;
        }
        float baseMax = sampleRate / 2.0f;
        if (zoomX <= 1.0f) {
            return baseMax;
        }
        float baseMin = spectrumBaseMin(baseMax);
        float center = clamp(fftZoomCenterHz, baseMin, baseMax);
        if (logFrequency && baseMin > 0.0f) {
            double logMin = Math.log(baseMin);
            double logMax = Math.log(baseMax);
            double logCenter = Math.log(Math.max(baseMin, center));
            double half = (logMax - logMin) / (2.0 * zoomX);
            return (float) Math.exp(Math.min(logMax, logCenter + half));
        }
        double half = (baseMax - baseMin) / (2.0 * zoomX);
        return (float) Math.min(baseMax, center + half);
    }

    private float xToFreq(float x, float fMin, float nyquist) {
        float t = clamp((x - plot.left) / Math.max(1.0f, plot.width()), 0.0f, 1.0f);
        if (logFrequency) {
            if (fMin <= 0.0f) {
                return t * nyquist;
            }
            return (float) (fMin * Math.pow(nyquist / fMin, t));
        }
        return t * nyquist;
    }

    private float freqToX(float f, float fMin, float nyquist) {
        if (f <= fMin) {
            return plot.left;
        }
        if (f >= nyquist) {
            return plot.right;
        }
        if (logFrequency && fMin > 0) {
            return plot.left + plot.width()
                    * (float) (Math.log(f / fMin) / Math.log(nyquist / fMin));
        }
        return plot.left + plot.width() * (f / Math.max(1.0f, nyquist));
    }

    private String formatRate(int rate) {
        return String.format(Locale.US, "%.1f kHz", rate / 1000.0f);
    }

    private String channelLabel(int channels) {
        return channels >= 2 ? channels + " CH" : "MONO";
    }

    private String formatTimebase(float ms) {
        if (ms < 1.0f) {
            return String.format(Locale.US, "%.0f µs", ms * 1000.0f);
        }
        if (ms < 10.0f) {
            return String.format(Locale.US, "%.2f ms", ms);
        }
        return String.format(Locale.US, "%.1f ms", ms);
    }

    private String formatDelay(float ms) {
        if (ms < 1.0f) {
            return String.format(Locale.US, "%.0f µs", ms * 1000.0f);
        }
        return String.format(Locale.US, "%.2f ms", ms);
    }

    private String formatFreq(float hz) {
        if (hz < 1000.0f) {
            return String.format(Locale.US, "%.1f Hz", hz);
        }
        if (hz < 10000.0f) {
            return String.format(Locale.US, "%.2f kHz", hz / 1000.0f);
        }
        return String.format(Locale.US, "%.1f kHz", hz / 1000.0f);
    }

    private String shortFreq(float hz) {
        if (hz >= 1000.0f) {
            if (hz >= 10000.0f) {
                return String.format(Locale.US, "%.0fk", hz / 1000.0f);
            }
            return String.format(Locale.US, "%.1fk", hz / 1000.0f);
        }
        return String.format(Locale.US, "%.0f", hz);
    }

    private String formatSmallHz(float hz) {
        if (hz < 10.0f) {
            return String.format(Locale.US, "%.2f Hz", hz);
        }
        if (hz < 1000.0f) {
            return String.format(Locale.US, "%.1f Hz", hz);
        }
        return String.format(Locale.US, "%.2f kHz", hz / 1000.0f);
    }

    private void releaseFrozenBitmap() {
        if (frozenBitmap != null) {
            frozenBitmap.recycle();
            frozenBitmap = null;
        }
        frozenWidth = 0;
        frozenHeight = 0;
    }

    private float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private float dp(float value) {
        return value * density;
    }

    private float sp(float value) {
        return value * scaledDensity;
    }

    private void resetXyState() {
        xyNextFrame = -1L;
        xyGeneration = -1;
        xyRate = 0;
        xyChannels = 0;
        xyForceStart = true;
        xyLastFadeAt = 0L;
        xyLastDrawAt = 0L;
        if (xyPhosphorCanvas != null) {
            xyPhosphorCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        }
    }

    private void releaseXyPhosphor() {
        if (xyPhosphor != null) {
            xyPhosphor.recycle();
            xyPhosphor = null;
        }
        xyPhosphorCanvas = null;
        xyPhosphorW = 0;
        xyPhosphorH = 0;
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseFrozenBitmap();
        releaseXyPhosphor();
        if (fullSpectrogram != null) {
            fullSpectrogram.recycle();
            fullSpectrogram = null;
        }
        super.onDetachedFromWindow();
    }
}
