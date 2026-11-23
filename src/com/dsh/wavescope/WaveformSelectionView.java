package com.dsh.wavescope;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/** Waveform overview with draggable start/end selection handles. */
public final class WaveformSelectionView extends View {

    public interface SelectionListener {
        void onSelectionChanged(long startUs, long endUs);
    }

    private static final float[] STEPS = {
            0.1f, 0.2f, 0.5f, 1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f,
            300f, 600f, 900f, 1800f, 3600f
    };

    private final Paint bgPaint = new Paint();
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waveRightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rulerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF handleRect = new RectF();

    private final float density;
    private final float rulerHeight;
    private final float contentLeft;
    private final float contentRight;
    private final float waveformBottom;

    private WaveformData data;
    private float startFraction = 0f;
    private float endFraction = 1f;
    private float cursorFraction = 0f;
    private float waveScale = 1f;
    private int dragMode; // 1 = start, 2 = end, 0 = none
    private SelectionListener listener;

    public WaveformSelectionView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        rulerHeight = dp(36);
        contentLeft = dp(16);
        contentRight = getWidth() - dp(16);
        waveformBottom = getHeight() - dp(4);

        setBackgroundColor(Color.BLACK);
        bgPaint.setColor(Color.rgb(4, 8, 13));
        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeWidth(dp(1.0f));
        wavePaint.setColor(Color.rgb(126, 169, 187));
        waveRightPaint.setStyle(Paint.Style.STROKE);
        waveRightPaint.setStrokeWidth(dp(0.8f));
        waveRightPaint.setColor(Color.argb(55, 90, 210, 150));
        centerPaint.setStyle(Paint.Style.STROKE);
        centerPaint.setStrokeWidth(dp(0.8f));
        centerPaint.setColor(Color.rgb(28, 42, 53));
        rulerPaint.setStyle(Paint.Style.STROKE);
        rulerPaint.setStrokeWidth(dp(0.8f));
        rulerPaint.setColor(Color.rgb(54, 65, 75));
        labelPaint.setColor(Color.rgb(143, 160, 173));
        labelPaint.setTextSize(sp(8.5f));
        dimPaint.setColor(Color.argb(155, 0, 0, 0));
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(Color.WHITE);
        cursorPaint.setStyle(Paint.Style.FILL);
        cursorPaint.setColor(Color.rgb(255, 86, 86));
    }

    public void setData(WaveformData waveformData) {
        data = waveformData;
        startFraction = 0f;
        endFraction = 1f;
        cursorFraction = 0f;
        float peak = 0.05f;
        if (data != null) {
            for (int i = 0; i < data.buckets; i++) {
                peak = Math.max(peak, Math.max(
                        Math.max(Math.abs(data.minL[i]), data.maxL[i]),
                        Math.max(Math.abs(data.minR[i]), data.maxR[i])));
            }
            waveScale = 0.94f / Math.min(1.0f, peak);
        }
        notifySelection();
        invalidate();
    }

    public void setSelectionListener(SelectionListener l) {
        listener = l;
    }

    public long getStartUs() {
        if (data == null) return 0L;
        return Math.round(startFraction * data.durationUs);
    }

    public long getEndUs() {
        if (data == null) return 0L;
        return Math.round(endFraction * data.durationUs);
    }

    public void setSelection(long startUs, long endUs) {
        if (data == null || data.durationUs <= 0L) return;
        startFraction = clamp(startUs / (float) data.durationUs, 0f, 1f);
        endFraction = clamp(endUs / (float) data.durationUs, 0f, 1f);
        if (endFraction < startFraction + minGap()) {
            endFraction = Math.min(1f, startFraction + minGap());
        }
        notifySelection();
        invalidate();
    }

    public void selectAll() {
        startFraction = 0f;
        endFraction = 1f;
        cursorFraction = 0f;
        notifySelection();
        invalidate();
    }

    private float minGap() {
        if (data == null || data.durationUs <= 0L) return 0f;
        return Math.max(0.002f, 0.05f / (data.durationUs / 1_000_000f));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // contentRight and waveformBottom are derived on demand.
    }

    private float left() {
        return dp(16);
    }

    private float right() {
        return getWidth() - dp(16);
    }

    private float waveTop() {
        return rulerHeight;
    }

    private float waveBottom() {
        return getHeight() - dp(4);
    }

    private float xForFraction(float fraction) {
        return left() + (right() - left()) * clamp(fraction, 0f, 1f);
    }

    private float fractionForX(float x) {
        return clamp((x - left()) / Math.max(1f, right() - left()), 0f, 1f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = left();
        float right = right();
        float top = waveTop();
        float bottom = waveBottom();
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
        if (data == null) {
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setColor(Color.rgb(143, 160, 173));
            canvas.drawText("正在准备波形…", getWidth() / 2f, getHeight() / 2f, labelPaint);
            return;
        }

        drawRuler(canvas, left, right, top);
        canvas.save();
        canvas.clipRect(left, top, right, bottom);
        drawWaveform(canvas, left, right, top, bottom);
        drawSelection(canvas, left, right, top, bottom);
        canvas.restore();
    }

    private void drawRuler(Canvas canvas, float left, float right, float top) {
        float duration = data.durationUs / 1_000_000f;
        if (duration <= 0f) return;
        float step = STEPS[STEPS.length - 1];
        for (float s : STEPS) {
            if (duration / s <= 8f) {
                step = s;
                break;
            }
        }
        int count = (int) Math.floor(duration / step + 0.0001f);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        labelPaint.setColor(Color.rgb(143, 160, 173));
        for (int i = 0; i <= count; i++) {
            float seconds = i * step;
            float x = left + (right - left) * (seconds / duration);
            if (x > right + 1f) break;
            canvas.drawLine(x, top - dp(5), x, top, rulerPaint);
            labelPaint.setTextAlign(i == 0 ? Paint.Align.LEFT
                    : (i == count ? Paint.Align.RIGHT : Paint.Align.CENTER));
            canvas.drawText(formatRuler(seconds), x, dp(13), labelPaint);
            if (i > 0) {
                canvas.drawLine(x, top, x, getHeight(), rulerPaint);
            }
        }
    }

    private void drawWaveform(Canvas canvas, float left, float right, float top,
                              float bottom) {
        float center = (top + bottom) / 2f;
        float half = (bottom - top) * 0.42f;
        canvas.drawLine(left, center, right, center, centerPaint);
        int width = Math.max(1, (int) (right - left));
        int buckets = data.buckets;
        boolean stereo = data.channels >= 2;
        for (int x = 0; x < width; x++) {
            int b0 = (int) ((long) x * buckets / width);
            int b1 = (int) ((long) (x + 1) * buckets / width);
            if (b1 <= b0) b1 = b0 + 1;
            if (b1 > buckets) b1 = buckets;
            float lo = 0f;
            float hi = 0f;
            float rLo = 0f;
            float rHi = 0f;
            for (int b = b0; b < b1; b++) {
                lo = Math.min(lo, data.minL[b]);
                hi = Math.max(hi, data.maxL[b]);
                if (stereo) {
                    rLo = Math.min(rLo, data.minR[b]);
                    rHi = Math.max(rHi, data.maxR[b]);
                }
            }
            float px = left + x;
            canvas.drawLine(px, center - hi * half * waveScale,
                    px, center - lo * half * waveScale, wavePaint);
            if (stereo) {
                canvas.drawLine(px, center - rHi * half * waveScale,
                        px, center - rLo * half * waveScale, waveRightPaint);
            }
        }
    }

    private void drawSelection(Canvas canvas, float left, float right, float top,
                               float bottom) {
        float sx = xForFraction(startFraction);
        float ex = xForFraction(endFraction);
        canvas.drawRect(left, top, sx, bottom, dimPaint);
        canvas.drawRect(ex, top, right, bottom, dimPaint);
        canvas.drawLine(sx, top, sx, bottom, handlePaint);
        canvas.drawLine(ex, top, ex, bottom, handlePaint);
        drawGrip(canvas, sx, (top + bottom) / 2f);
        drawGrip(canvas, ex, (top + bottom) / 2f);
        float cx = xForFraction(cursorFraction);
        canvas.drawLine(cx, top, cx, bottom, cursorPaint);
        canvas.drawCircle(cx, bottom, dp(3.8f), cursorPaint);
    }

    private void drawGrip(Canvas canvas, float x, float centerY) {
        handleRect.set(x - dp(4.5f), centerY - dp(24f),
                x + dp(4.5f), centerY + dp(24f));
        canvas.drawRoundRect(handleRect, dp(4.5f), dp(4.5f), handlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (data == null || data.durationUs <= 0L) return false;
        float x = event.getX();
        float sx = xForFraction(startFraction);
        float ex = xForFraction(endFraction);
        float slop = dp(30);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (Math.abs(x - sx) <= slop && Math.abs(x - sx) <= Math.abs(x - ex)) {
                    dragMode = 1;
                } else if (Math.abs(x - ex) <= slop) {
                    dragMode = 2;
                } else {
                    dragMode = (Math.abs(x - sx) < Math.abs(x - ex)) ? 1 : 2;
                    moveHandle(fractionForX(x));
                }
                cursorFraction = fractionForX(x);
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragMode != 0) {
                    float f = fractionForX(x);
                    cursorFraction = f;
                    moveHandle(f);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragMode = 0;
                getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void moveHandle(float fraction) {
        float gap = minGap();
        if (dragMode == 1) {
            startFraction = clamp(fraction, 0f, Math.max(0f, endFraction - gap));
        } else if (dragMode == 2) {
            endFraction = clamp(fraction, Math.min(1f, startFraction + gap), 1f);
        }
        notifySelection();
        invalidate();
    }

    private void notifySelection() {
        if (listener != null && data != null) {
            listener.onSelectionChanged(getStartUs(), getEndUs());
        }
    }

    private String formatRuler(float seconds) {
        int total = (int) Math.floor(seconds + 0.0001f);
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private float dp(float v) {
        return v * density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }

    private static float clamp(float v, float min, float max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
