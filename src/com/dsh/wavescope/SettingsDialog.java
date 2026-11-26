package com.dsh.wavescope;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Bottom-sheet style parameter panel. */
public final class SettingsDialog {

    public interface OnChanged {
        void onChanged(AppConfig config);
    }

    private SettingsDialog() {
    }

    public static void show(Activity activity, AppConfig config, OnChanged onChanged) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final float density = activity.getResources().getDisplayMetrics().density;
        final int textColor = activity.getColor(R.color.text);
        final int secondary = activity.getColor(R.color.text_secondary);
        final int muted = activity.getColor(R.color.text_muted);
        final int cyan = activity.getColor(R.color.cyan);
        final Typeface mono = Typeface.create("monospace", Typeface.NORMAL);
        final Typeface sans = Typeface.create("sans-serif", Typeface.NORMAL);
        final Typeface sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_sheet);
        int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
        int fixedHeight = Math.min(screenHeight - dp(density, 40), dp(density, 700));
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(dp(density, 420), fixedHeight)));

        View handle = new View(activity);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(activity.getColor(R.color.stroke_bright));
        handleBg.setCornerRadius(dp(density, 2));
        handle.setBackground(handleBg);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(density, 38), dp(density, 3));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.topMargin = dp(density, 10);
        root.addView(handle, handleLp);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(density, 22), dp(density, 12), dp(density, 22), dp(density, 8));
        TextView title = text(activity, "显示参数", 17, textColor, sansMedium);
        title.setLetterSpacing(0.02f);
        header.addView(title);
        TextView subtitle = text(activity, "采样率由音频源决定 · 本应用不降采样", 10, muted, mono);
        subtitle.setPadding(0, dp(density, 4), 0, 0);
        header.addView(subtitle);
        root.addView(header);

        final ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollLp);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(density, 22), dp(density, 2), dp(density, 22), dp(density, 16));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Gain.
        section(content, "垂直增益", "VERTICAL GAIN", activity, density, cyan, muted, mono);
        final TextView gainValue = valueText(activity, "", density, cyan, mono);
        LinearLayout gainRow = sliderHeader(activity, density, "增益倍数", gainValue, textColor, secondary, sans);
        content.addView(gainRow);
        final SeekBar gainBar = seekBar(activity, density, cyan);
        content.addView(gainBar);
        gainBar.setMax(1000);
        gainBar.setProgress(gainToProgress(config.gain));

        // Timebase.
        section(content, "时基", "TIME / DIV", activity, density, cyan, muted, mono);
        final TextView timeValue = valueText(activity, "", density, cyan, mono);
        LinearLayout timeRow = sliderHeader(activity, density, "XT 时基", timeValue, textColor, secondary, sans);
        content.addView(timeRow);
        final SeekBar timeBar = seekBar(activity, density, cyan);
        content.addView(timeBar);
        timeBar.setMax(1000);
        timeBar.setProgress(timeToProgress(config.timePerDivMs));

        // Trigger.
        section(content, "触发", "TRIGGER", activity, density, cyan, muted, mono);
        LinearLayout autoRow = new LinearLayout(activity);
        autoRow.setOrientation(LinearLayout.HORIZONTAL);
        autoRow.setGravity(Gravity.CENTER_VERTICAL);
        autoRow.setPadding(0, dp(density, 8), 0, dp(density, 2));
        TextView autoLabel = text(activity, "自动触发", 13, textColor, sans);
        autoRow.addView(autoLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Switch autoSwitch = new Switch(activity);
        autoSwitch.setChecked(config.autoTrigger);
        autoSwitch.setTextColor(secondary);
        styleSwitch(autoSwitch, cyan, activity);
        autoRow.addView(autoSwitch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(autoRow);

        final TextView triggerValue = valueText(activity, "", density, cyan, mono);
        LinearLayout triggerRow = sliderHeader(activity, density, "触发电平", triggerValue, textColor, secondary, sans);
        content.addView(triggerRow);
        final SeekBar triggerBar = seekBar(activity, density, cyan);
        content.addView(triggerBar);
        triggerBar.setMax(2000);
        triggerBar.setProgress(Math.round((config.triggerLevel + 1.0f) * 1000.0f));

        final TextView[] edgeChips = new TextView[2];
        LinearLayout edgeRow = chipRow(activity, density);
        edgeChips[0] = chip(activity, "上升沿", density, cyan, mono);
        edgeChips[1] = chip(activity, "下降沿", density, cyan, mono);
        edgeRow.addView(edgeChips[0], chipLp(density));
        edgeRow.addView(edgeChips[1], chipLp(density));
        content.addView(edgeRow);

        // XY phosphor / vector.
        section(content, "XY 相图", "SCATTER · ALL SAMPLES", activity, density, cyan, muted, mono);
        final TextView persistenceValue = valueText(activity, "", density, cyan, mono);
        LinearLayout persistenceRow = sliderHeader(activity, density, "余辉", persistenceValue,
                textColor, secondary, sans);
        content.addView(persistenceRow);
        final SeekBar persistenceBar = seekBar(activity, density, cyan);
        content.addView(persistenceBar);
        persistenceBar.setMax(2000);
        persistenceBar.setProgress(Math.round(config.xyPersistenceMs));

        LinearLayout xyMirrorXRow = new LinearLayout(activity);
        xyMirrorXRow.setOrientation(LinearLayout.HORIZONTAL);
        xyMirrorXRow.setGravity(Gravity.CENTER_VERTICAL);
        xyMirrorXRow.setPadding(0, dp(density, 8), 0, 0);
        TextView xyMirrorXLabel = text(activity, "左右镜像", 13, textColor, sans);
        xyMirrorXRow.addView(xyMirrorXLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Switch xyMirrorXSwitch = new Switch(activity);
        xyMirrorXSwitch.setChecked(config.xyMirrorX);
        styleSwitch(xyMirrorXSwitch, cyan, activity);
        xyMirrorXRow.addView(xyMirrorXSwitch);
        content.addView(xyMirrorXRow);

        LinearLayout xyMirrorYRow = new LinearLayout(activity);
        xyMirrorYRow.setOrientation(LinearLayout.HORIZONTAL);
        xyMirrorYRow.setGravity(Gravity.CENTER_VERTICAL);
        xyMirrorYRow.setPadding(0, dp(density, 8), 0, 0);
        TextView xyMirrorYLabel = text(activity, "上下镜像", 13, textColor, sans);
        xyMirrorYRow.addView(xyMirrorYLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Switch xyMirrorYSwitch = new Switch(activity);
        xyMirrorYSwitch.setChecked(config.xyMirrorY);
        styleSwitch(xyMirrorYSwitch, cyan, activity);
        xyMirrorYRow.addView(xyMirrorYSwitch);
        content.addView(xyMirrorYRow);

        LinearLayout xySwapRow = new LinearLayout(activity);
        xySwapRow.setOrientation(LinearLayout.HORIZONTAL);
        xySwapRow.setGravity(Gravity.CENTER_VERTICAL);
        xySwapRow.setPadding(0, dp(density, 8), 0, 0);
        TextView xySwapLabel = text(activity, "交换 XY", 13, textColor, sans);
        xySwapRow.addView(xySwapLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Switch xySwapSwitch = new Switch(activity);
        xySwapSwitch.setChecked(config.xySwap);
        styleSwitch(xySwapSwitch, cyan, activity);
        xySwapRow.addView(xySwapSwitch);
        content.addView(xySwapRow);

        TextView xyNote = text(activity,
                "XY 不再使用时间窗口：每个采集到的样本都会以散点进入余辉图层，不连接相邻采样点。余辉越长，散点保留越久；左右/上下镜像只改变显示方向，不改变数据。",
                10, muted, sans);
        xyNote.setLineSpacing(dp(density, 3), 1.0f);
        xyNote.setPadding(0, dp(density, 8), 0, 0);
        content.addView(xyNote);

        // FFT.
        section(content, "频谱", "FFT", activity, density, cyan, muted, mono);
        final TextView[] fftChips = new TextView[AppConfig.FFT_SIZES.length];
        LinearLayout fftRow = chipRow(activity, density);
        for (int i = 0; i < AppConfig.FFT_SIZES.length; i++) {
            final int index = i;
            TextView chip = chip(activity, String.valueOf(AppConfig.FFT_SIZES[i]), density, cyan, mono);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            chip.setOnClickListener(v -> {
                config.fftSize = AppConfig.FFT_SIZES[index];
                syncChips(fftChips, index);
                onChanged.onChanged(config);
            });
            fftChips[i] = chip;
            fftRow.addView(chip, chipLp(density));
        }
        content.addView(fftRow);

        LinearLayout logRow = new LinearLayout(activity);
        logRow.setOrientation(LinearLayout.HORIZONTAL);
        logRow.setGravity(Gravity.CENTER_VERTICAL);
        logRow.setPadding(0, dp(density, 10), 0, 0);
        TextView logLabel = text(activity, "对数频率轴", 13, textColor, sans);
        logRow.addView(logLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Switch logSwitch = new Switch(activity);
        logSwitch.setChecked(config.logFrequency);
        styleSwitch(logSwitch, cyan, activity);
        logRow.addView(logSwitch);
        content.addView(logRow);

        // Render / XY pixel performance.
        section(content, "渲染", "RENDER", activity, density, cyan, muted, mono);
        final int[] fpsValues = {30, 60, 90, 120, 0};
        final String[] fpsLabels = {"30", "60", "90", "120", "不限"};
        final TextView[] fpsChips = new TextView[fpsValues.length];
        LinearLayout fpsRow = chipRow(activity, density);
        for (int i = 0; i < fpsValues.length; i++) {
            final int index = i;
            final int value = fpsValues[i];
            TextView chip = chip(activity, fpsLabels[i], density, cyan, mono);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            chip.setOnClickListener(v -> {
                config.targetFps = value;
                syncChips(fpsChips, index);
                onChanged.onChanged(config);
            });
            fpsChips[i] = chip;
            fpsRow.addView(chip, chipLp(density));
        }
        content.addView(fpsRow);

        LinearLayout pixelRow = new LinearLayout(activity);
        pixelRow.setOrientation(LinearLayout.HORIZONTAL);
        pixelRow.setGravity(Gravity.CENTER_VERTICAL);
        pixelRow.setPadding(0, dp(density, 10), 0, 0);
        TextView pixelLabel = text(activity, "XY 像素点阵", 13, textColor, sans);
        pixelRow.addView(pixelLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Switch pixelSwitch = new Switch(activity);
        pixelSwitch.setChecked(config.xyPixelMode);
        styleSwitch(pixelSwitch, cyan, activity);
        pixelRow.addView(pixelSwitch);
        content.addView(pixelRow);

        LinearLayout measureRow = new LinearLayout(activity);
        measureRow.setOrientation(LinearLayout.HORIZONTAL);
        measureRow.setGravity(Gravity.CENTER_VERTICAL);
        measureRow.setPadding(0, dp(density, 8), 0, 0);
        TextView measureLabel = text(activity, "显示波形测量", 13, textColor, sans);
        measureRow.addView(measureLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Switch measureSwitch = new Switch(activity);
        measureSwitch.setChecked(config.showMeasurements);
        styleSwitch(measureSwitch, cyan, activity);
        measureRow.addView(measureSwitch);
        content.addView(measureRow);

        final TextView pixelSizeValue = valueText(activity, "", density, cyan, mono);
        content.addView(sliderHeader(activity, density, "像素大小", pixelSizeValue,
                textColor, secondary, sans));
        final SeekBar pixelSizeBar = seekBar(activity, density, cyan);
        pixelSizeBar.setMax(70);
        pixelSizeBar.setProgress(Math.round((config.xyPixelSizeDp - 1.0f) * 10f));
        content.addView(pixelSizeBar);

        TextView renderNote = text(activity,
                "目标帧率限制重绘频率，可降低发热与耗电；像素点阵把 XY 散点吸附到像素网格，形成数字示波器的马赛克效果。",
                10, muted, sans);
        renderNote.setLineSpacing(dp(density, 3), 1.0f);
        renderNote.setPadding(0, dp(density, 8), 0, 0);
        content.addView(renderNote);

        TextView note = text(activity,
                "采集链路保持原始采样率：导入文件按解码得到的原生采样率播放与分析，麦克风按设备原生率，媒体音频按系统混音率。FFT 窗口大小不是采样率转换；XY 也不对音频降采样。",
                10, muted, sans);
        note.setLineSpacing(dp(density, 3), 1.0f);
        note.setPadding(0, dp(density, 14), 0, 0);
        content.addView(note);

        pixelSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.xyPixelMode = isChecked;
            onChanged.onChanged(config);
        });
        measureSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.showMeasurements = isChecked;
            onChanged.onChanged(config);
        });
        pixelSizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                config.xyPixelSizeDp = 1.0f + progress / 10.0f;
                pixelSizeValue.setText(String.format(Locale.US, "%.1f px", config.xyPixelSizeDp));
                onChanged.onChanged(config);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Handlers.
        gainBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                config.gain = progressToGain(progress);
                gainValue.setText(String.format(Locale.US, "× %.2f", config.gain));
                onChanged.onChanged(config);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        timeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                config.timePerDivMs = progressToTime(progress);
                timeValue.setText(formatTimebase(config.timePerDivMs));
                onChanged.onChanged(config);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        triggerBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                config.triggerLevel = progress / 1000.0f - 1.0f;
                triggerValue.setText(String.format(Locale.US, "%+.2f", config.triggerLevel));
                onChanged.onChanged(config);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        autoSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.autoTrigger = isChecked;
            onChanged.onChanged(config);
        });
        logSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.logFrequency = isChecked;
            onChanged.onChanged(config);
        });
        for (int i = 0; i < 2; i++) {
            final boolean rising = i == 0;
            edgeChips[i].setOnClickListener(v -> {
                config.triggerRising = rising;
                syncChips(edgeChips, rising ? 0 : 1);
                onChanged.onChanged(config);
            });
        }
        persistenceBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                config.xyPersistenceMs = progress;
                persistenceValue.setText(formatPersistence(config.xyPersistenceMs));
                onChanged.onChanged(config);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        xyMirrorXSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.xyMirrorX = isChecked;
            onChanged.onChanged(config);
        });
        xyMirrorYSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.xyMirrorY = isChecked;
            onChanged.onChanged(config);
        });
        xySwapSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.xySwap = isChecked;
            onChanged.onChanged(config);
        });
        // Footer buttons.
        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(density, 22), dp(density, 8), dp(density, 22), dp(density, 16));
        TextView reset = footerButton(activity, "恢复默认", false, density, textColor, cyan);
        TextView done = footerButton(activity, "完成", true, density, textColor, cyan);
        footer.addView(reset, new LinearLayout.LayoutParams(0, dp(density, 46), 1f));
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(0, dp(density, 46), 1.35f);
        doneLp.leftMargin = dp(density, 10);
        footer.addView(done, doneLp);
        root.addView(footer);

        // Initial labels and selected states.
        gainValue.setText(String.format(Locale.US, "× %.2f", config.gain));
        timeValue.setText(formatTimebase(config.timePerDivMs));
        triggerValue.setText(String.format(Locale.US, "%+.2f", config.triggerLevel));
        persistenceValue.setText(formatPersistence(config.xyPersistenceMs));
        syncChips(edgeChips, config.triggerRising ? 0 : 1);
        int selectedFft = 0;
        for (int i = 0; i < AppConfig.FFT_SIZES.length; i++) {
            if (AppConfig.FFT_SIZES[i] == config.fftSize) {
                selectedFft = i;
                break;
            }
        }
        syncChips(fftChips, selectedFft);
        int selectedFps = 0;
        for (int i = 0; i < fpsValues.length; i++) {
            if (fpsValues[i] == config.targetFps) { selectedFps = i; break; }
        }
        syncChips(fpsChips, selectedFps);
        pixelSizeValue.setText(String.format(Locale.US, "%.1f px", config.xyPixelSizeDp));

        reset.setOnClickListener(v -> {
            AppConfig defaults = AppConfig.defaults();
            config.gain = defaults.gain;
            config.timePerDivMs = defaults.timePerDivMs;
            config.fftSize = defaults.fftSize;
            config.logFrequency = defaults.logFrequency;
            config.triggerLevel = defaults.triggerLevel;
            config.triggerRising = defaults.triggerRising;
            config.autoTrigger = defaults.autoTrigger;
            config.xyPersistenceMs = defaults.xyPersistenceMs;
            config.xyMirrorX = defaults.xyMirrorX;
            config.xyMirrorY = defaults.xyMirrorY;
            config.xySwap = defaults.xySwap;
            config.xyPixelMode = defaults.xyPixelMode;
            config.xyPixelSizeDp = defaults.xyPixelSizeDp;
            config.targetFps = defaults.targetFps;
            config.showMeasurements = defaults.showMeasurements;

            gainBar.setProgress(gainToProgress(config.gain));
            timeBar.setProgress(timeToProgress(config.timePerDivMs));
            triggerBar.setProgress(Math.round((config.triggerLevel + 1.0f) * 1000.0f));
            persistenceBar.setProgress(Math.round(config.xyPersistenceMs));
            autoSwitch.setChecked(config.autoTrigger);
            logSwitch.setChecked(config.logFrequency);
            xyMirrorXSwitch.setChecked(config.xyMirrorX);
            xyMirrorYSwitch.setChecked(config.xyMirrorY);
            xySwapSwitch.setChecked(config.xySwap);
            pixelSwitch.setChecked(config.xyPixelMode);
            measureSwitch.setChecked(config.showMeasurements);
            pixelSizeBar.setProgress(Math.round((config.xyPixelSizeDp - 1.0f) * 10f));
            int resetFps = 0;
            for (int i = 0; i < fpsValues.length; i++) {
                if (fpsValues[i] == config.targetFps) { resetFps = i; break; }
            }
            syncChips(fpsChips, resetFps);
            pixelSizeValue.setText(String.format(Locale.US, "%.1f px", config.xyPixelSizeDp));
            gainValue.setText(String.format(Locale.US, "× %.2f", config.gain));
            timeValue.setText(formatTimebase(config.timePerDivMs));
            triggerValue.setText(String.format(Locale.US, "%+.2f", config.triggerLevel));
            persistenceValue.setText(formatPersistence(config.xyPersistenceMs));
                        syncChips(edgeChips, 0);
            int defaultIndex = 0;
            for (int i = 0; i < AppConfig.FFT_SIZES.length; i++) {
                if (AppConfig.FFT_SIZES[i] == config.fftSize) {
                    defaultIndex = i;
                    break;
                }
            }
            syncChips(fftChips, defaultIndex);
            onChanged.onChanged(config);
            Toast.makeText(activity, "已恢复默认显示参数（采样率不变）", Toast.LENGTH_SHORT).show();
        });
        done.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(root);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int sheetWidth = Math.min(screenWidth, dp(density, 560));
            window.setLayout(sheetWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = 0.72f;
            window.setAttributes(lp);
            if (Build.VERSION.SDK_INT >= 21) {
                window.setStatusBarColor(Color.TRANSPARENT);
            }
        }
    }

    private static int dp(float density, float value) {
        return Math.round(value * density);
    }

    private static TextView text(Context context, String s, float sp, int color, Typeface tf) {
        TextView tv = new TextView(context);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setTypeface(tf);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    private static TextView valueText(Context context, String s, float density, int color, Typeface tf) {
        TextView tv = text(context, s, 11, color, tf);
        tv.setGravity(Gravity.END);
        return tv;
    }

    private static LinearLayout sliderHeader(Context c, float density, String label, TextView value,
                                             int textColor, int secondary, Typeface sans) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(density, 10), 0, dp(density, 2));
        TextView name = text(c, label, 12, secondary, sans);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value);
        return row;
    }

    private static SeekBar seekBar(Context c, float density, int color) {
        SeekBar bar = new SeekBar(c);
        bar.setMax(1000);
        bar.setPadding(0, dp(density, 4), 0, dp(density, 4));
        try {
            bar.setProgressTintList(ColorStateList.valueOf(color));
            bar.setThumbTintList(ColorStateList.valueOf(color));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(c.getColor(R.color.stroke)));
        } catch (Exception ignored) {
        }
        return bar;
    }

    private static void section(LinearLayout parent, String title, String code, Activity activity,
                                float density, int cyan, int muted, Typeface mono) {
        View line = new View(activity);
        line.setBackgroundColor(activity.getColor(R.color.stroke_soft));
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(density, 1));
        lineLp.topMargin = dp(density, 14);
        parent.addView(line, lineLp);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(density, 13), 0, 0);
        TextView titleView = text(activity, title, 13, activity.getColor(R.color.text),
                Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(titleView);
        TextView codeView = text(activity, code, 8.5f, cyan, mono);
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        codeLp.leftMargin = dp(density, 8);
        row.addView(codeView, codeLp);
        parent.addView(row);
    }

    private static LinearLayout chipRow(Context c, float density) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(density, 8), 0, 0);
        return row;
    }

    private static TextView chip(Context c, String s, float density, int cyan, Typeface mono) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tv.setTextColor(c.getColorStateList(R.color.seg_text));
        tv.setTypeface(mono);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundResource(R.drawable.seg_item);
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setPadding(dp(density, 4), 0, dp(density, 4), 0);
        return tv;
    }

    private static LinearLayout.LayoutParams chipLp(float density) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(density, 34), 1f);
        lp.rightMargin = dp(density, 4);
        lp.leftMargin = dp(density, 4);
        return lp;
    }

    private static void syncChips(TextView[] chips, int selected) {
        for (int i = 0; i < chips.length; i++) {
            if (chips[i] != null) {
                chips[i].setSelected(i == selected);
            }
        }
    }

    private static TextView footerButton(Context c, String s, boolean primary, float density,
                                         int textColor, int cyan) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setGravity(Gravity.CENTER);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setClickable(true);
        tv.setFocusable(true);
        if (primary) {
            tv.setTextColor(c.getColor(R.color.action_text));
            tv.setBackgroundResource(R.drawable.btn_action);
        } else {
            tv.setTextColor(c.getColorStateList(R.color.ghost_text));
            tv.setBackgroundResource(R.drawable.btn_ghost);
        }
        return tv;
    }

    private static void styleSwitch(Switch sw, int cyan, Context c) {
        try {
            sw.setThumbTintList(ColorStateList.valueOf(cyan));
            sw.setTrackTintList(ColorStateList.valueOf(c.getColor(R.color.stroke_bright)));
        } catch (Exception ignored) {
        }
    }

    private static int gainToProgress(float gain) {
        float min = 0.2f;
        float max = 20.0f;
        float t = (float) (Math.log(Math.max(min, Math.min(max, gain)) / min) / Math.log(max / min));
        return Math.round(t * 1000.0f);
    }

    private static float progressToGain(int progress) {
        float min = 0.2f;
        float max = 20.0f;
        float t = progress / 1000.0f;
        return (float) (min * Math.pow(max / min, t));
    }

    private static int timeToProgress(float ms) {
        float min = 0.1f;
        float max = 100.0f;
        float t = (float) (Math.log(Math.max(min, Math.min(max, ms)) / min) / Math.log(max / min));
        return Math.round(t * 1000.0f);
    }

    private static float progressToTime(int progress) {
        float min = 0.1f;
        float max = 100.0f;
        float t = progress / 1000.0f;
        float value = (float) (min * Math.pow(max / min, t));
        return Math.round(value * 100.0f) / 100.0f;
    }

    private static String formatPersistence(float ms) {
        if (ms < 1.0f) {
            return "关闭";
        }
        return Math.round(ms) + " ms";
    }

    private static String formatTimebase(float ms) {
        if (ms < 1.0f) {
            return String.format(Locale.US, "%.0f µs", ms * 1000.0f);
        }
        if (ms < 10.0f) {
            return String.format(Locale.US, "%.2f ms", ms);
        }
        return String.format(Locale.US, "%.1f ms", ms);
    }
}
