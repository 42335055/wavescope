package com.dsh.wavescope;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Color;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.text.InputType;
import android.graphics.Typeface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {

    private static final int SRC_NONE = 0;
    private static final int SRC_FILE = 1;
    private static final int SRC_MIC = 2;
    private static final int SRC_MEDIA = 3;

    private static final int REQ_OPEN_AUDIO = 1101;
    private static final int REQ_MIC_PERMISSION = 1102;
    private static final int REQ_MEDIA_PERMISSIONS = 1103;
    private static final int REQ_MEDIA_PROJECTION = 1104;
    private static final int REQ_OPEN_SPECTRUM_IMAGE = 1105;

    private final AudioPipeline pipeline = new AudioPipeline();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private AppConfig config;

    private ScopeView scope;
    private TextView tvStatus;
    private TextView tabFile;
    private TextView tabMic;
    private TextView tabMedia;
    private TextView tabImageAudio;
    private TextView tabXt;
    private TextView tabXy;
    private TextView tabFft;
    private TextView btnAction;
    private TextView btnHold;
    private TextView btnSettings;
    private TextView btnExport;
    private TextView btnFileToggle;
    private SeekBar seekFile;
    private TextView tvAnalysis;
    private View waterfallControls;
    private SeekBar seekWaterfallSpeed;
    private SeekBar seekWaterfallBlack;
    private SeekBar seekWaterfallWhite;
    private TextView tvWaterfallSpeed;
    private TextView tvWaterfallBlack;
    private TextView tvWaterfallWhite;
    private TextView btnWaterfallView;
    private boolean filePlayingAtPause;
    private long filePositionAtPause;
    private boolean updatingWaterfallControls;
    private TextView tvRate;
    private TextView tvChannels;
    private TextView tvPeak;
    private TextView tvScale;
    private TextView tvFileName;
    private TextView tvTime;
    private View filePanel;

    private FileAudioPlayer filePlayer;
    private MediaPlayer imageAudioPlayer;
    private Uri lastSavedMediaUri;
    private MicCapture micCapture;
    private FileSpectrogramAnalyzer spectrumAnalyzer;
    private int spectrumToken;
    private WaveformData waveformData;
    private PcmFileSource pcmSource;

    private volatile int source = SRC_NONE;
    private boolean filePreparing;
    private boolean filePlaying;
    private boolean filePaused;
    private boolean fileCompleted;
    private String fileError;
    private Uri currentFileUri;
    private String currentFileName = "";
    private long fileDurationUs;
    private long filePositionUs;
    private long filePositionWallAt;

    private boolean micStarting;
    private boolean micStarted;

    private boolean mediaStarting;
    private boolean mediaStarted;

    private boolean hold;
    private boolean backgrounded;
    private boolean tickerStarted;
    private boolean pickerOpen;
    private boolean exporting;
    private boolean fileSeekTracking;
    private boolean pendingPauseAfterReady;
    private boolean currentFileIsVideo;

    private float lastPeakDb = -120.0f;
    private boolean lastHadSignal;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!tickerStarted) {
                return;
            }
            if (source == SRC_FILE && filePlaying) {
                long now = android.os.SystemClock.uptimeMillis();
                if (filePositionWallAt <= 0L) filePositionWallAt = now;
                long p = filePositionUs + (now - filePositionWallAt) * 1000L;
                if (fileDurationUs > 0L && p > fileDurationUs) p = fileDurationUs;
                scope.setPlaybackPositionUs(p);
            }
            if (isScopeLive() && !scope.isFrozen()) {
                if (config == null || config.mode != AppConfig.MODE_XY
                        || scope.shouldDrawXyFrame()) {
                    scope.postInvalidateOnAnimation();
                }
            }
            int target = config == null ? 60 : config.targetFps;
            long delay = target <= 0
                    ? (config != null && config.mode == AppConfig.MODE_FFT ? 33L : 16L)
                    : Math.max(1L, 1000L / target);
            ui.postDelayed(this, delay);
        }
    };

    private final Runnable saveConfig = () -> {
        if (config != null) {
            config.save(MainActivity.this);
        }
    };

    private final FileAudioPlayer.Listener fileListener = new FileAudioPlayer.Listener() {
        @Override
        public void onPreparing() {
            if (source != SRC_FILE) {
                return;
            }
            filePreparing = true;
            fileError = null;
            updateUi();
        }

        @Override
        public void onReady(int sampleRate, int channels, long durationUs) {
            if (source != SRC_FILE) {
                return;
            }
            fileDurationUs = durationUs > 0 ? durationUs : fileDurationUs;
            updateFilePanel();
            updateMetrics();
            scope.invalidate();
        }

        @Override
        public void onPlayingChanged(boolean playing) {
            if (source != SRC_FILE) {
                return;
            }
            if (playing) {
                if (pendingPauseAfterReady) {
                    pendingPauseAfterReady = false;
                    filePlayer.pause();
                    fileLeadingPaused();
                    updateUi();
                    return;
                }
                if (backgrounded) {
                    filePlayer.pause();
                    fileLeadingPaused();
                    updateUi();
                    return;
                }
                filePreparing = false;
                filePlaying = true;
                filePaused = false;
                filePositionWallAt = android.os.SystemClock.uptimeMillis();
                fileCompleted = false;
                fileError = null;
                scope.setHint(currentFileIsVideo ? "正在播放视频音轨" : "正在播放",
                        currentFileIsVideo
                                ? currentFileName + " · 视频画面已忽略"
                                : currentFileName + " · 原生采样率直接分析");
            } else {
                filePlaying = false;
                if (!filePreparing && !fileCompleted && fileError == null) {
                    fileLeadingPaused();
                }
            }
            updateUi();
            scope.invalidate();
        }

        @Override
        public void onProgress(long positionUs, long durationUs) {
            if (source != SRC_FILE) {
                return;
            }
            if (durationUs > 0) {
                fileDurationUs = durationUs;
            }
            filePositionUs = positionUs;
            filePositionWallAt = android.os.SystemClock.uptimeMillis();
            updateFilePanel();
        }

        @Override
        public void onCompleted() {
            if (source != SRC_FILE) {
                return;
            }
            filePreparing = false;
            filePlaying = false;
            filePaused = false;
            fileCompleted = true;
            filePositionUs = fileDurationUs;
            if (scope != null) {
                scope.setPlaybackPositionUs(fileDurationUs);
            }
            updateUi();
            scope.invalidate();
        }

        @Override
        public void onError(String message) {
            if (source != SRC_FILE) {
                return;
            }
            filePreparing = false;
            filePlaying = false;
            filePaused = false;
            fileCompleted = false;
            fileError = message;
            scope.setHint("无法播放音频", message);
            toast(message);
            updateUi();
        }
    };

    private final MicCapture.Listener micListener = new MicCapture.Listener() {
        @Override
        public void onStarted(int sampleRate, int channels) {
            if (source != SRC_MIC) {
                return;
            }
            micStarting = false;
            micStarted = true;
            source = SRC_MIC;
            scope.setHint("正在监听麦克风",
                    "原生 PCM · " + rateLabel(sampleRate) + " · "
                            + (channels >= 2 ? "立体声" : "单声道") + " · 不做降采样");
            updateUi();
            scope.invalidate();
        }

        @Override
        public void onStopped() {
            if (source != SRC_MIC) {
                return;
            }
            source = SRC_NONE;
            micStarting = false;
            micStarted = false;
            scope.setHint("监听已停止", "选择来源重新开始");
            updateUi();
        }

        @Override
        public void onError(String message) {
            if (source != SRC_MIC) {
                return;
            }
            source = SRC_NONE;
            micStarting = false;
            micStarted = false;
            scope.setHint("麦克风不可用", message);
            toast(message);
            updateUi();
        }
    };

    private final MediaCaptureService.Listener mediaListener = new MediaCaptureService.Listener() {
        @Override
        public void onCaptureStarted(int sampleRate, int channels) {
            pipeline.configure(sampleRate, channels);
            runOnUiThread(() -> {
                if (source != SRC_MEDIA) {
                    return;
                }
                mediaStarting = false;
                mediaStarted = true;
                resetAnalysisState();
                scope.setHint("正在监听媒体音频",
                        "播放媒体内容 · " + rateLabel(sampleRate) + " · 系统混音率 · 受保护内容可能无声");
                updateUi();
                scope.invalidate();
            });
        }

        @Override
        public void onSamples(short[] data, int count) {
            if (source == SRC_MEDIA) {
                pipeline.append(data, 0, count);
            }
        }

        @Override
        public void onCaptureStopped() {
            runOnUiThread(() -> {
                if (source != SRC_MEDIA) {
                    return;
                }
                source = SRC_NONE;
                mediaStarting = false;
                mediaStarted = false;
                scope.setHint("媒体音频监听已停止", "点按媒体音频可重新授权");
                updateUi();
            });
        }

        @Override
        public void onCaptureError(String message) {
            runOnUiThread(() -> {
                if (source != SRC_MEDIA) {
                    return;
                }
                source = SRC_NONE;
                mediaStarting = false;
                mediaStarted = false;
                scope.setHint("媒体音频不可用", message);
                toast(message);
                updateUi();
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(getColor(R.color.bg_deep));
        getWindow().setNavigationBarColor(getColor(R.color.bg_deep));
        setContentView(R.layout.activity_main);

        bindViews();
        scope.setPipeline(pipeline);
        config = AppConfig.load(this);
        if (savedInstanceState != null) {
            int savedMode = savedInstanceState.getInt("mode", -1);
            if (savedMode >= AppConfig.MODE_XT && savedMode <= AppConfig.MODE_FFT) {
                config.mode = savedMode;
            }
        }
        applyConfigToScope();
        selectModeTab();
        scope.setAnalysisListener((peakDb, dominantHz, sampleRate, channels, hasSignal) -> {
            if (source == SRC_NONE) {
                return;
            }
            lastPeakDb = peakDb;
            lastHadSignal = hasSignal;
            updateMetrics();
        });
        filePlayer = new FileAudioPlayer(this, pipeline, fileListener);
        micCapture = new MicCapture(pipeline, micListener);
        MediaCaptureService.setListener(mediaListener);
        wireInteractions();
        updateHoldUi();
        scope.setHint("等待音频信号", "导入音频 · 监听麦克风 · 捕获媒体音频");

        // Android 的媒体音频捕获授权不能永久保存。只在配置变更/旋转后恢复
        // 当前会话；全新启动时不自动恢复，避免每次打开应用都弹系统录屏授权。
        if (savedInstanceState != null && MediaCaptureService.isRunning()) {
            restoreMediaCapture();
        }
        updateUi();
        if (savedInstanceState != null
                && savedInstanceState.getBoolean("restore_file", false)) {
            String uriText = savedInstanceState.getString("file_uri");
            if (uriText != null) {
                Uri uri = Uri.parse(uriText);
                String name = savedInstanceState.getString("file_name", "导入的音频");
                long position = savedInstanceState.getLong("file_pos", 0L);
                boolean play = savedInstanceState.getBoolean("file_play", true);
                startFile(uri, name, position, play);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (config != null) {
            outState.putInt("mode", config.mode);
        }
        if (source == SRC_FILE && currentFileUri != null) {
            outState.putBoolean("restore_file", true);
            outState.putString("file_uri", currentFileUri.toString());
            outState.putString("file_name", currentFileName);
            long position = filePositionAtPause > 0L ? filePositionAtPause : filePositionUs;
            outState.putLong("file_pos", position);
            outState.putBoolean("file_play", filePlayingAtPause || filePlaying);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        backgrounded = false;
        MediaCaptureService.setListener(mediaListener);
        if (source == SRC_MEDIA && !MediaCaptureService.isRunning()) {
            source = SRC_NONE;
            mediaStarted = false;
            mediaStarting = false;
            scope.setHint("媒体音频监听已停止", "点按媒体音频可重新授权");
        }
        startTicker();
        updateUi();
    }

    @Override
    protected void onPause() {
        if (source == SRC_FILE) {
            filePlayingAtPause = filePlaying;
            filePositionAtPause = filePositionUs;
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        backgrounded = true;
        stopTicker();
        if (config != null) {
            config.save(this);
        }
        if (source == SRC_FILE) {
            if (isChangingConfigurations()) {
                // onPause already captured the playback state; don't block the
                // rotation by joining the decoder thread a second time.
            } else if (filePlaying) {
                filePlayer.pause();
                fileLeadingPaused();
                updateUi();
            } else if (filePreparing) {
                // Avoid starting playback in the background while the file is still opening.
                source = SRC_NONE;
                filePlayer.stop();
                clearFileState();
                scope.setHint("导入已暂停", "重新选择文件即可继续");
                updateUi();
            }
        } else if (source == SRC_MIC) {
            source = SRC_NONE;
            micStarting = false;
            micStarted = false;
            micCapture.stop();
            scope.setHint("麦克风监听已停止", "应用回到前台后可重新开始");
            updateUi();
        }
        // MediaProjection capture intentionally survives app background: the system
        // foreground service and its notification keep the session alive while the
        // user plays audio in another app.
    }

    private void playGeneratedAudio(String path, Uri contentUri) {
        if (source == SRC_FILE && filePlaying) {
            filePlayer.pause();
            fileLeadingPaused();
            updateUi();
        }
        stopGeneratedAudio();
        try {
            MediaPlayer player = new MediaPlayer();
            imageAudioPlayer = player;
            player.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            if (contentUri != null) {
                player.setDataSource(this, contentUri);
            } else {
                player.setDataSource(path);
            }
            player.setOnPreparedListener(p -> {
                if (imageAudioPlayer == p) {
                    p.start();
                }
            });
            player.setOnCompletionListener(p -> {
                if (imageAudioPlayer == p) {
                    imageAudioPlayer = null;
                    p.release();
                }
            });
            player.setOnErrorListener((p, what, extra) -> {
                if (imageAudioPlayer == p) {
                    imageAudioPlayer = null;
                }
                p.release();
                toast("WAV 播放失败");
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            stopGeneratedAudio();
            toast("WAV 播放失败：" + safeMessage(e));
        }
    }

    private void stopGeneratedAudio() {
        if (imageAudioPlayer != null) {
            try { imageAudioPlayer.stop(); } catch (Exception ignored) {}
            try { imageAudioPlayer.release(); } catch (Exception ignored) {}
            imageAudioPlayer = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopTicker();
        source = SRC_NONE;
        cancelFullSpectrumAnalysis();
        MediaCaptureService.setListener(null);
        if (micCapture != null) {
            micCapture.stop();
        }
        if (filePlayer != null) {
            filePlayer.release();
        }
        stopGeneratedAudio();
        if (pcmSource != null) {
            pcmSource.close();
            pcmSource = null;
        }
        if (!isChangingConfigurations()) {
            stopMediaCaptureServiceOnly();
        }
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void bindViews() {
        scope = findViewById(R.id.scope_view);
        tvStatus = findViewById(R.id.tv_status);
        tabFile = findViewById(R.id.tab_file);
        tabMic = findViewById(R.id.tab_mic);
        tabMedia = findViewById(R.id.tab_media);
        tabImageAudio = findViewById(R.id.tab_image_audio);
        tabXt = findViewById(R.id.tab_xt);
        tabXy = findViewById(R.id.tab_xy);
        tabFft = findViewById(R.id.tab_fft);
        btnAction = findViewById(R.id.btn_action);
        btnHold = findViewById(R.id.btn_hold);
        btnSettings = findViewById(R.id.btn_settings);
        btnExport = findViewById(R.id.btn_export);
        btnFileToggle = findViewById(R.id.btn_file_toggle);
        seekFile = findViewById(R.id.seek_file);
        tvAnalysis = findViewById(R.id.tv_analysis);
        waterfallControls = findViewById(R.id.waterfall_controls);
        seekWaterfallSpeed = findViewById(R.id.seek_waterfall_speed);
        seekWaterfallBlack = findViewById(R.id.seek_waterfall_black);
        seekWaterfallWhite = findViewById(R.id.seek_waterfall_white);
        tvWaterfallSpeed = findViewById(R.id.tv_waterfall_speed);
        tvWaterfallBlack = findViewById(R.id.tv_waterfall_black);
        tvWaterfallWhite = findViewById(R.id.tv_waterfall_white);
        btnWaterfallView = findViewById(R.id.btn_waterfall_view);
        tvRate = findViewById(R.id.tv_rate);
        tvChannels = findViewById(R.id.tv_channels);
        tvPeak = findViewById(R.id.tv_peak);
        tvScale = findViewById(R.id.tv_scale);
        tvFileName = findViewById(R.id.tv_file_name);
        tvTime = findViewById(R.id.tv_time);
        filePanel = findViewById(R.id.file_panel);
    }

    private void wireInteractions() {
        tabFile.setOnClickListener(v -> openFilePicker());
        tabMic.setOnClickListener(v -> onMicTab());
        tabMedia.setOnClickListener(v -> onMediaTab());
        tabImageAudio.setOnClickListener(v -> openImagePickerForAudio());

        tabXt.setOnClickListener(v -> setMode(AppConfig.MODE_XT));
        tabXy.setOnClickListener(v -> setMode(AppConfig.MODE_XY));
        tabFft.setOnClickListener(v -> setMode(AppConfig.MODE_FFT));

        btnAction.setOnClickListener(v -> onPrimaryAction());
        btnHold.setOnClickListener(v -> toggleHold());
        btnWaterfallView.setOnClickListener(v -> {
            if (scope == null || !scope.hasFullSpectrogram()) return;
            scope.setWaterfallFull(!scope.isWaterfallFull());
            updateWaterfallControlsVisibility();
        });
        if (scope != null) {
            scope.setOnSeekListener(us -> seekFileTo(us));
        }
        btnExport.setOnClickListener(v -> showExportDialog());
        btnFileToggle.setOnClickListener(v -> toggleFilePlayback());
        seekFile.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && fileDurationUs > 0) {
                    long preview = fileDurationUs * progress / 1000L;
                    tvTime.setText(formatTime(preview) + " / " + formatTime(fileDurationUs));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                fileSeekTracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                fileSeekTracking = false;
                if (source == SRC_FILE && currentFileUri != null && fileDurationUs > 0) {
                    long target = fileDurationUs * seekBar.getProgress() / 1000L;
                    seekFileTo(target);
                }
            }
        });
        seekWaterfallSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || updatingWaterfallControls) return;
                config.waterfallSpeed = progress / 100.0f;
                scope.setWaterfallSpeed(config.waterfallSpeed);
                updateWaterfallLabels();
                scheduleSaveConfig();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        seekWaterfallBlack.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || updatingWaterfallControls) return;
                config.waterfallBlack = progress / 100.0f;
                if (config.waterfallWhite < config.waterfallBlack + 0.05f) {
                    config.waterfallWhite = Math.min(1.0f, config.waterfallBlack + 0.05f);
                }
                scope.setWaterfallLevels(config.waterfallBlack, config.waterfallWhite);
                syncWaterfallControls();
                scheduleSaveConfig();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        seekWaterfallWhite.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || updatingWaterfallControls) return;
                config.waterfallWhite = 0.1f + progress / 100.0f;
                if (config.waterfallWhite < config.waterfallBlack + 0.05f) {
                    config.waterfallBlack = Math.max(0f, config.waterfallWhite - 0.05f);
                }
                scope.setWaterfallLevels(config.waterfallBlack, config.waterfallWhite);
                syncWaterfallControls();
                scheduleSaveConfig();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnSettings.setOnClickListener(v -> {
            if (config != null) {
                SettingsDialog.show(this, config, changed -> {
                    changed.sanitize();
                    applyConfigToScope();
                    updateMetrics();
                    scope.invalidate();
                    scheduleSaveConfig();
                });
            }
        });
    }

    private void applyConfigToScope() {
        scope.setGain(config.gain);
        scope.setTimePerDivMs(config.timePerDivMs);
        scope.setFftSize(config.fftSize);
        scope.setLogFrequency(config.logFrequency);
        scope.setTriggerLevel(config.triggerLevel);
        scope.setTriggerRising(config.triggerRising);
        scope.setAutoTrigger(config.autoTrigger);
        scope.setXyPersistenceMs(config.xyPersistenceMs);
        scope.setXySmooth(config.xySmooth);
        scope.setXyMirrorX(config.xyMirrorX);
        scope.setXyMirrorY(config.xyMirrorY);
        scope.setXySwap(config.xySwap);
        scope.setXyPixelMode(config.xyPixelMode);
        scope.setXyPixelSizeDp(config.xyPixelSizeDp);
        scope.setShowMeasurements(config.showMeasurements);
        scope.setWaterfallSpeed(config.waterfallSpeed);
        scope.setWaterfallLevels(config.waterfallBlack, config.waterfallWhite);
        if (tvWaterfallSpeed != null) {
            syncWaterfallControls();
        }
        scope.setMode(config.mode);
    }

    private void setMode(int mode) {
        if (config.mode == mode) {
            return;
        }
        config.mode = mode;
        if (hold) {
            setHold(false);
        }
        scope.setMode(mode);
        selectModeTab();
        updateWaterfallControlsVisibility();
        updateMetrics();
        scope.invalidate();
        scheduleSaveConfig();
    }

    private void selectModeTab() {
        tabXt.setSelected(config.mode == AppConfig.MODE_XT);
        tabXy.setSelected(config.mode == AppConfig.MODE_XY);
        tabFft.setSelected(config.mode == AppConfig.MODE_FFT);
    }

    private void scheduleSaveConfig() {
        ui.removeCallbacks(saveConfig);
        ui.postDelayed(saveConfig, 500);
    }

    private void toggleHold() {
        setHold(!hold);
    }

    private void setHold(boolean value) {
        hold = value;
        scope.setFrozen(value);
        if (!value) {
            scope.clearChartPicks();
        }
        updateHoldUi();
    }

    private void updateHoldUi() {
        btnHold.setSelected(hold);
        btnHold.setText(hold ? R.string.run : R.string.hold);
        btnHold.setContentDescription(hold ? "继续刷新" : "冻结画面");
        if (!hold) {
            scope.invalidate();
        }
    }

    private boolean isScopeLive() {
        if (hold) {
            return false;
        }
        if (source == SRC_FILE) {
            return filePlaying;
        }
        if (source == SRC_MIC) {
            return micStarted;
        }
        return source == SRC_MEDIA && mediaStarted;
    }

    private void startTicker() {
        if (!tickerStarted) {
            tickerStarted = true;
            ui.post(ticker);
        }
    }

    private void stopTicker() {
        tickerStarted = false;
        ui.removeCallbacks(ticker);
    }

    private void updateUi() {
        if (isFinishing()) {
            return;
        }
        updateSourceTabs();
        filePanel.setVisibility(source == SRC_FILE ? View.VISIBLE : View.GONE);
        if (source == SRC_FILE) {
            updateFilePanel();
        }
        updateStatusPill();
        updatePrimaryAction();
        updateWaterfallControlsVisibility();
        updateMetrics();
    }

    private void updateWaterfallControlsVisibility() {
        if (waterfallControls != null) {
            waterfallControls.setVisibility(
                    config != null && config.mode == AppConfig.MODE_FFT
                            ? View.VISIBLE : View.GONE);
        }
        if (btnWaterfallView != null) {
            boolean fullAvailable = source == SRC_FILE && scope != null
                    && scope.hasFullSpectrogram();
            btnWaterfallView.setVisibility(fullAvailable ? View.VISIBLE : View.GONE);
            if (fullAvailable) {
                btnWaterfallView.setText(scope.isWaterfallFull() ? "原效果" : "媒体瀑布");
            }
        }
    }

    private void updateWaterfallLabels() {
        if (tvWaterfallSpeed != null) {
            tvWaterfallSpeed.setText(config.waterfallSpeed <= 0.001f
                    ? "暂停" : String.format(Locale.US, "%.2f×", config.waterfallSpeed));
            tvWaterfallBlack.setText(Math.round(config.waterfallBlack * 100f) + "%");
            tvWaterfallWhite.setText(Math.round(config.waterfallWhite * 100f) + "%");
        }
    }

    private void syncWaterfallControls() {
        if (seekWaterfallSpeed == null) return;
        updatingWaterfallControls = true;
        seekWaterfallSpeed.setProgress(Math.round(config.waterfallSpeed * 100f));
        seekWaterfallBlack.setProgress(Math.round(config.waterfallBlack * 100f));
        seekWaterfallWhite.setProgress(Math.round((config.waterfallWhite - 0.1f) * 100f));
        updateWaterfallLabels();
        updatingWaterfallControls = false;
    }

    private void updateSourceTabs() {
        tabFile.setSelected(source == SRC_FILE);
        tabMic.setSelected(source == SRC_MIC);
        tabMedia.setSelected(source == SRC_MEDIA);
        tabImageAudio.setSelected(false);
    }

    private void updateStatusPill() {
        String label;
        int color;
        if (source == SRC_NONE) {
            label = "待机";
            color = getColor(R.color.text_secondary);
        } else if (source == SRC_FILE) {
            if (fileError != null) {
                label = "错误";
                color = getColor(R.color.danger);
            } else if (filePreparing) {
                label = "解码中";
                color = getColor(R.color.amber);
            } else if (fileCompleted) {
                label = "完成";
                color = getColor(R.color.text_secondary);
            } else if (filePaused) {
                label = "已暂停";
                color = getColor(R.color.amber);
            } else if (filePlaying) {
                label = "文件";
                color = getColor(R.color.cyan);
            } else {
                label = "文件";
                color = getColor(R.color.text_secondary);
            }
        } else if (source == SRC_MIC) {
            label = micStarting ? "连接中" : "麦克风";
            color = micStarting ? getColor(R.color.amber) : getColor(R.color.cyan);
        } else {
            label = mediaStarting ? "连接中" : "媒体";
            color = mediaStarting ? getColor(R.color.amber) : getColor(R.color.cyan);
        }
        tvStatus.setText("● " + label);
        tvStatus.setTextColor(color);
    }

    private void updatePrimaryAction() {
        String label = "导入音频";
        boolean enabled = true;

        if (source == SRC_NONE) {
            label = "导入音频";
        } else if (source == SRC_FILE) {
            if (fileError != null) {
                label = "重新选择音频";
            } else if (filePreparing) {
                label = "解码中…";
                enabled = false;
            } else if (fileCompleted) {
                label = "重新播放";
            } else if (filePlaying) {
                label = "暂停播放";
            } else if (filePaused) {
                label = "继续播放";
            } else {
                label = "选择音频";
            }
        } else if (source == SRC_MIC) {
            label = micStarting ? "停止监听" : "停止监听";
        } else if (source == SRC_MEDIA) {
            label = mediaStarting ? "停止连接" : "停止监听";
        }

        btnAction.setText(label);
        btnAction.setEnabled(enabled);
        btnAction.setAlpha(enabled ? 1.0f : 0.62f);
    }

    private void updateMetrics() {
        if (tvRate == null) {
            return;
        }
        if (source == SRC_NONE) {
            tvRate.setText("—");
            tvChannels.setText("—");
            tvPeak.setText("—");
            tvPeak.setTextColor(getColor(R.color.text));
        } else {
            int rate = pipeline.getSampleRate();
            int channels = pipeline.getChannelCount();
            tvRate.setText(rate > 0 ? String.format(Locale.US, "%.1fk", rate / 1000.0f) : "—");
            tvChannels.setText(channels >= 2 ? "2 CH" : (channels == 1 ? "MONO" : "—"));
            if (lastHadSignal && lastPeakDb > -119.5f) {
                tvPeak.setText(String.format(Locale.US, "%.1f dB", lastPeakDb));
                tvPeak.setTextColor(lastPeakDb > -1.0f ? getColor(R.color.amber) : getColor(R.color.text));
            } else {
                tvPeak.setText("—");
                tvPeak.setTextColor(getColor(R.color.text));
            }
        }

        String scale;
        if (config.mode == AppConfig.MODE_FFT) {
            scale = (config.fftSize / 1024) + "K pt";
        } else if (config.mode == AppConfig.MODE_XY) {
            scale = "全采样";
        } else {
            scale = formatTimebase(config.timePerDivMs);
        }
        tvScale.setText(scale);
    }

    private void updateFilePanel() {
        if (source != SRC_FILE) {
            return;
        }
        tvFileName.setText(currentFileName.length() == 0 ? "音频文件" : currentFileName);
        if (fileDurationUs > 0) {
            if (!fileSeekTracking) {
                tvTime.setText(formatTime(filePositionUs) + " / " + formatTime(fileDurationUs));
                int progress = (int) Math.min(1000L, filePositionUs * 1000L / fileDurationUs);
                seekFile.setProgress(progress);
            }
        } else {
            tvTime.setText(fileError != null ? "无法播放" : "解码中…");
            if (!fileSeekTracking) {
                seekFile.setProgress(0);
            }
        }
        boolean canToggle = !filePreparing && fileDurationUs > 0 && fileError == null;
        btnFileToggle.setEnabled(canToggle);
        btnFileToggle.setAlpha(canToggle ? 1.0f : 0.55f);
        if (filePreparing) {
            btnFileToggle.setText("准备");
        } else if (filePlaying) {
            btnFileToggle.setText("暂停");
        } else {
            btnFileToggle.setText("播放");
        }
        seekFile.setEnabled(source == SRC_FILE && fileDurationUs > 0 && !filePreparing);
    }

    private void toggleFilePlayback() {
        if (source != SRC_FILE) {
            return;
        }
        if (filePreparing || fileError != null) {
            return;
        }
        if (fileCompleted) {
            replayFile();
        } else if (filePlaying) {
            filePlayer.pause();
            fileLeadingPaused();
            updateUi();
        } else if (filePaused) {
            filePaused = false;
            filePlaying = true;
            filePositionWallAt = android.os.SystemClock.uptimeMillis();
            filePlayer.resume();
            updateUi();
        } else if (currentFileUri != null) {
            filePlayer.resume();
            filePlaying = true;
            filePaused = false;
            filePositionWallAt = android.os.SystemClock.uptimeMillis();
            updateUi();
        }
    }

    private void seekFileTo(long positionUs) {
        if (source != SRC_FILE || currentFileUri == null || fileDurationUs <= 0) {
            return;
        }
        long target = Math.max(0L, Math.min(fileDurationUs, positionUs));
        if (hold) {
            hold = false;
            updateHoldUi();
        }
        filePositionUs = target;
        filePositionWallAt = android.os.SystemClock.uptimeMillis();
        filePreparing = true;
        filePlaying = false;
        filePaused = false;
        fileCompleted = false;
        fileError = null;
        updateFilePanel();
        if (scope != null) {
            scope.resetAnalysis();
            scope.setPlaybackPositionUs(target);
            scope.invalidate();
        }
        filePlayer.openAt(currentFileUri, target);
        updateUi();
    }

    private void onPrimaryAction() {
        if (source == SRC_NONE) {
            openFilePicker();
            return;
        }
        if (source == SRC_FILE) {
            if (fileError != null) {
                openFilePicker();
            } else if (fileCompleted) {
                replayFile();
            } else if (filePlaying) {
                filePlayer.pause();
                fileLeadingPaused();
                updateUi();
            } else if (filePaused) {
                filePaused = false;
                filePlaying = true;
                filePlayer.resume();
                updateUi();
            } else if (!filePreparing) {
                openFilePicker();
            }
        } else if (source == SRC_MIC) {
            stopMicrophoneFromUi();
        } else if (source == SRC_MEDIA) {
            stopMediaFromUi();
        }
    }

    private void fileLeadingPaused() {
        filePlaying = false;
        filePaused = true;
    }

    private void openFilePicker() {
        if (pickerOpen) {
            return;
        }
        prepareForPicker();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"audio/*", "video/*"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        pickerOpen = true;
        try {
            startActivityForResult(intent, REQ_OPEN_AUDIO);
        } catch (ActivityNotFoundException e) {
            pickerOpen = false;
            toast("没有找到文件选择器");
        }
    }

    private void prepareForPicker() {
        if (source == SRC_MIC) {
            source = SRC_NONE;
            micStarting = false;
            micStarted = false;
            micCapture.stop();
            scope.setHint("等待音频信号", "选择一个音频或视频文件");
            updateUi();
        } else if (source == SRC_MEDIA) {
            source = SRC_NONE;
            mediaStarting = false;
            mediaStarted = false;
            stopMediaCaptureServiceOnly();
            scope.setHint("等待音频信号", "选择一个音频或视频文件");
            updateUi();
        } else if (source == SRC_FILE) {
            if (filePreparing) {
                source = SRC_NONE;
                cancelFullSpectrumAnalysis();
                if (scope != null) {
                    scope.clearFullSpectrogram();
                }
                filePlayer.stop();
                clearFileState();
                scope.setHint("等待音频信号", "选择一个音频或视频文件");
                updateUi();
            } else if (filePlaying) {
                filePlayer.pause();
                fileLeadingPaused();
                updateUi();
            }
        }
    }

    private void onMicTab() {
        if (source == SRC_MIC) {
            toast("麦克风正在监听，点按下方按钮可停止");
            return;
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_PERMISSION);
            return;
        }
        beginMicrophone();
    }

    private void beginMicrophone() {
        stopEverythingForNewSource(false);
        source = SRC_MIC;
        micStarting = true;
        micStarted = false;
        resetAnalysisState();
        pipeline.clear();
        scope.resetAnalysis();
        scope.setHint("正在打开麦克风", "直接读取设备原生 PCM，不做重采样");
        filePanel.setVisibility(View.GONE);
        setHold(false);
        updateUi();
        micCapture.start();
    }

    private void stopMicrophoneFromUi() {
        source = SRC_NONE;
        micStarting = false;
        micStarted = false;
        micCapture.stop();
        scope.setHint("监听已停止", "点按麦克风可重新开始");
        updateUi();
    }

    private void onMediaTab() {
        if (source == SRC_MEDIA) {
            toast("媒体音频正在监听，点按下方按钮可停止");
            return;
        }
        if (MediaCaptureService.isRunning()) {
            stopEverythingForNewSource(false);
            restoreMediaCapture();
            updateUi();
            toast("已继续上一次媒体音频监听");
            return;
        }
        if (Build.VERSION.SDK_INT < 29) {
            new AlertDialog.Builder(this)
                    .setTitle("需要 Android 10")
                    .setMessage("媒体音频捕获（AudioPlaybackCapture）需要 Android 10 或更高版本。\n\n你仍可使用文件导入和麦克风监听。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("监听媒体音频")
                .setMessage("接下来系统会请求“屏幕录制”授权。该授权只用于捕获其他应用播放的媒体声音，"
                        + "本应用不会截取、保存或上传屏幕画面。\n\n受 DRM 保护的内容、通话声音，"
                        + "或禁止被录制的应用可能没有声音。媒体监听会在前台服务中运行，"
                        + "切到音乐应用播放时仍会继续采集，通知栏可随时停止。")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (dialog, which) -> requestMediaCapturePermissions())
                .show();
    }

    private void requestMediaCapturePermissions() {
        List<String> needed = new ArrayList<>();
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (needed.isEmpty()) {
            launchMediaProjectionConsent();
        } else {
            requestPermissions(needed.toArray(new String[0]), REQ_MEDIA_PERMISSIONS);
        }
    }

    private void launchMediaProjectionConsent() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            showPermissionHelp("麦克风权限", "媒体音频捕获需要录音权限（Android 允许录音的应用才能捕获播放声音）。");
            return;
        }
        stopEverythingForNewSource();
        source = SRC_NONE;
        updateUi();
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            toast("系统不支持媒体音频捕获");
            return;
        }
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
        } catch (Exception e) {
            toast("无法请求屏幕录制授权：" + safeMessage(e));
        }
    }

    private void startMediaCapture(int resultCode, Intent data) {
        stopEverythingForNewSource();
        source = SRC_MEDIA;
        mediaStarting = true;
        mediaStarted = false;
        resetAnalysisState();
        pipeline.clear();
        scope.resetAnalysis();
        scope.setHint("正在连接媒体音频", "请稍候，随后播放媒体内容");
        filePanel.setVisibility(View.GONE);
        setHold(false);
        updateUi();

        Intent service = new Intent(this, MediaCaptureService.class)
                .setAction(MediaCaptureService.ACTION_START)
                .putExtra(MediaCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(MediaCaptureService.EXTRA_RESULT_DATA, data);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (Exception e) {
            source = SRC_NONE;
            mediaStarting = false;
            mediaStarted = false;
            scope.setHint("媒体音频不可用", "无法启动前台采集服务：" + safeMessage(e));
            toast("无法启动媒体音频采集：" + safeMessage(e));
            updateUi();
        }
    }

    private void stopMediaFromUi() {
        source = SRC_NONE;
        mediaStarting = false;
        mediaStarted = false;
        stopMediaCaptureServiceOnly();
        scope.setHint("监听已停止", "点按媒体音频可重新授权");
        updateUi();
    }

    private void stopMediaCaptureServiceOnly() {
        if (MediaCaptureService.isRunning()) {
            Intent stop = new Intent(this, MediaCaptureService.class)
                    .setAction(MediaCaptureService.ACTION_STOP);
            try {
                startService(stop);
            } catch (Exception ignored) {
                stopService(new Intent(this, MediaCaptureService.class));
            }
        } else {
            stopService(new Intent(this, MediaCaptureService.class));
        }
    }

    private void stopEverythingForNewSource() {
        stopEverythingForNewSource(true);
    }

    private void stopEverythingForNewSource(boolean stopMedia) {
        source = SRC_NONE;
        cancelFullSpectrumAnalysis();
        if (scope != null) {
            scope.clearFullSpectrogram();
        }
        if (filePlayer != null) {
            filePlayer.stop();
        }
        if (micCapture != null) {
            micCapture.stop();
        }
        if (stopMedia) {
            stopMediaCaptureServiceOnly();
        }
        clearFileState();
        micStarting = false;
        micStarted = false;
        mediaStarting = false;
        mediaStarted = false;
    }

    private void clearFileState() {
        filePreparing = false;
        filePlaying = false;
        filePaused = false;
        fileCompleted = false;
        fileError = null;
        pendingPauseAfterReady = false;
        currentFileUri = null;
        currentFileName = "";
        currentFileIsVideo = false;
        fileDurationUs = 0L;
        filePositionUs = 0L;
        filePositionWallAt = 0L;
        waveformData = null;
        if (pcmSource != null) {
            pcmSource.close();
            pcmSource = null;
        }
        if (scope != null) scope.clearPcmSource();
        if (filePanel != null) {
            filePanel.setVisibility(View.GONE);
        }
    }

    private void startFile(Uri uri, String displayName) {
        startFile(uri, displayName, 0L, true);
    }

    private void startFile(Uri uri, String displayName, long startUs, boolean play) {
        stopEverythingForNewSource(false);
        source = SRC_FILE;
        currentFileUri = uri;
        currentFileName = displayName == null || displayName.length() == 0
                ? "导入的音频" : displayName;
        String mime = null;
        try {
            mime = getContentResolver().getType(uri);
        } catch (Exception ignored) {
        }
        currentFileIsVideo = mime != null && mime.startsWith("video/");
        filePreparing = true;
        filePlaying = false;
        filePaused = false;
        fileCompleted = false;
        fileError = null;
        fileDurationUs = 0L;
        filePositionUs = Math.max(0L, startUs);
        filePositionWallAt = android.os.SystemClock.uptimeMillis();
        filePlayingAtPause = false;
        filePositionAtPause = 0L;
        waveformData = null;
        pendingPauseAfterReady = !play;
        resetAnalysisState();
        pipeline.clear();
        scope.resetAnalysis();
        scope.setPlaybackPositionUs(filePositionUs);
        scope.setHint(currentFileIsVideo ? "正在分离视频音轨" : "正在解码音频",
                currentFileIsVideo
                        ? currentFileName + " · 忽略视频画面，仅使用音轨"
                        : currentFileName + " · 保持文件原始采样率");
        setHold(false);
        updateUi();
        filePlayer.openAt(uri, filePositionUs);
        startFullSpectrumAnalysis(uri);
    }

    private void replayFile() {
        if (currentFileUri == null) {
            openFilePicker();
            return;
        }
        resetAnalysisState();
        pipeline.clear();
        scope.resetAnalysis();
        filePreparing = true;
        filePlaying = false;
        filePaused = false;
        fileCompleted = false;
        fileError = null;
        filePositionUs = 0L;
        scope.setHint(currentFileIsVideo ? "正在分离视频音轨" : "正在解码音频",
                currentFileIsVideo
                        ? currentFileName + " · 忽略视频画面，仅使用音轨"
                        : currentFileName + " · 保持文件原始采样率");
        scope.setPlaybackPositionUs(0L);
        setHold(false);
        updateUi();
        filePlayer.open(currentFileUri);
    }

    private void resetAnalysisState() {
        lastPeakDb = -120.0f;
        lastHadSignal = false;
    }

    private void restoreMediaCapture() {
        int rate = MediaCaptureService.getCaptureSampleRate();
        int channels = MediaCaptureService.getCaptureChannels();
        if (rate <= 0) {
            rate = 48000;
        }
        if (channels <= 0) {
            channels = 2;
        }
        pipeline.configure(rate, channels);
        source = SRC_MEDIA;
        mediaStarting = false;
        mediaStarted = true;
        resetAnalysisState();
        scope.setHint("正在监听媒体音频",
                "播放媒体内容 · " + rateLabel(rate) + " · 系统混音率 · 受保护内容可能无声");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN_AUDIO) {
            pickerOpen = false;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                startFile(uri, queryDisplayName(uri));
            }
        } else if (requestCode == REQ_OPEN_SPECTRUM_IMAGE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                showImageAudioDialog(uri);
            }
        } else if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                startMediaCapture(resultCode, data);
            } else {
                toast("已取消媒体音频授权");
                updateUi();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC_PERMISSION) {
            if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                beginMicrophone();
            } else {
                showPermissionHelp("麦克风权限", "实时示波器需要读取麦克风音频。请在系统设置中允许录音权限。");
            }
        } else if (requestCode == REQ_MEDIA_PERMISSIONS) {
            if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                launchMediaProjectionConsent();
            } else {
                showPermissionHelp("录音权限", "媒体音频捕获需要录音权限。请在系统设置中允许录音权限。");
            }
        }
    }

    private void startFullSpectrumAnalysis(Uri uri) {
        cancelFullSpectrumAnalysis();
        final int token = ++spectrumToken;
        if (tvAnalysis != null) {
            tvAnalysis.setVisibility(View.VISIBLE);
            tvAnalysis.setText("全文件频谱：解码音频…");
        }
        spectrumAnalyzer = new FileSpectrogramAnalyzer(this, uri,
                new FileSpectrogramAnalyzer.Listener() {
                    @Override
                    public void onProgress(int percent, String message) {
                        if (token != spectrumToken || source != SRC_FILE) {
                            return;
                        }
                        if (tvAnalysis != null) {
                            tvAnalysis.setVisibility(View.VISIBLE);
                            tvAnalysis.setText("全文件频谱 " + percent + "% · " + message);
                        }
                    }

                    @Override
                    public void onComplete(Bitmap bitmap, WaveformData waveform,
                                           int sampleRate, int channels,
                                           long durationUs, PcmFileSource sourcePcm) {
                        if (token != spectrumToken || source != SRC_FILE) {
                            bitmap.recycle();
                            if (sourcePcm != null) sourcePcm.close();
                            return;
                        }
                        if (fileDurationUs <= 0L) {
                            fileDurationUs = durationUs;
                        }
                        waveformData = waveform;
                        scope.setFullSpectrogram(bitmap, sampleRate, durationUs);
                        if (pcmSource != null) pcmSource.close();
                        pcmSource = sourcePcm;
                        scope.setPcmSource(sourcePcm);
                        scope.setPlaybackPositionUs(filePositionUs);
                        scope.invalidate();
                        if (tvAnalysis != null) {
                            tvAnalysis.setVisibility(View.VISIBLE);
                            tvAnalysis.setText(currentFileIsVideo
                                ? "视频音轨已分离 · 全文件频谱就绪"
                                : "全文件频谱就绪 · 白色光标随播放进度移动");
                        }
                        updateFilePanel();
                        updateWaterfallControlsVisibility();
                        spectrumAnalyzer = null;
                    }

                    @Override
                    public void onError(String message) {
                        if (token != spectrumToken || source != SRC_FILE) {
                            return;
                        }
                        if (tvAnalysis != null) {
                            tvAnalysis.setVisibility(View.VISIBLE);
                            tvAnalysis.setText("全文件频谱不可用：" + message);
                        }
                        spectrumAnalyzer = null;
                    }
                });
        spectrumAnalyzer.start();
    }

    private void cancelFullSpectrumAnalysis() {
        spectrumToken++;
        if (spectrumAnalyzer != null) {
            spectrumAnalyzer.cancel();
            spectrumAnalyzer = null;
        }
        if (tvAnalysis != null) {
            tvAnalysis.setText("");
            tvAnalysis.setVisibility(View.GONE);
        }
    }

    private void showExportDialog() {
        final boolean hasWaterfall = source == SRC_FILE && currentFileUri != null;
        final List<String> items = new ArrayList<>();
        items.add("CSV 数据");
        items.add("PNG 图表截图");
        final int waterfallIndex = hasWaterfall ? items.size() : -1;
        if (hasWaterfall) items.add("瀑布大图 PNG");
        new AlertDialog.Builder(this)
                .setTitle("导出")
                .setItems(items.toArray(new String[0]), (dialog, which) -> {
                    if (which == 0) {
                        exportCsv();
                    } else if (which == 1) {
                        exportPng();
                    } else if (which == waterfallIndex) {
                        showWaterfallTypeDialog();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void dismissKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            }
            focus.clearFocus();
        }
    }

    private void openImagePickerForAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_OPEN_SPECTRUM_IMAGE);
        } catch (Exception e) {
            toast("无法打开图片选择器");
        }
    }

    private void showImageAudioDialog(Uri uri) {
        final SpectrogramPng.Info info;
        try {
            info = SpectrogramPng.inspect(this, uri);
        } catch (Exception e) {
            toast("读取图片失败：" + safeMessage(e));
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(18 * density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, Math.round(8 * density), pad, 0);

        TextView desc = new TextView(this);
        desc.setText((info.hasMetadata ? "已识别本应用导出的频谱图\n" : "未找到本应用元数据，按默认边距裁切\n")
                + SpectrogramPng.describe(info));
        desc.setTextSize(11);
        desc.setTextColor(getColor(R.color.text_secondary));
        desc.setLineSpacing(0, 1.15f);
        root.addView(desc);

        TextView speedLabel = new TextView(this);
        speedLabel.setText("瀑布速度（像素/秒）");
        speedLabel.setTextSize(12);
        speedLabel.setTextColor(getColor(R.color.text));
        speedLabel.setPadding(0, Math.round(14 * density), 0, Math.round(4 * density));
        root.addView(speedLabel);

        final EditText speedInput = new EditText(this);
        speedInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        speedInput.setSingleLine(true);
        speedInput.setTextSize(14);
        speedInput.setTextColor(getColor(R.color.text));
        float defaultSpeed = info.speedPps > 0.1f ? info.speedPps : 100f;
        speedInput.setText(String.format(Locale.US, "%.1f", defaultSpeed));
        root.addView(speedInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setText("将从红/绿通道反变换生成 16-bit WAV。"
                + "\n速度决定每列像素对应的时间；图中顶部信息也标注了导出速度。");
        note.setTextSize(10);
        note.setTextColor(getColor(R.color.text_muted));
        note.setPadding(0, Math.round(10 * density), 0, 0);
        root.addView(note);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("频谱图 → 声音")
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("生成 WAV", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    float speed;
                    try {
                        speed = Float.parseFloat(speedInput.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        toast("请输入有效速度");
                        return;
                    }
                    if (speed <= 0.01f) {
                        toast("速度必须大于 0");
                        return;
                    }
                    double duration = info.plotWidth / (double) speed;
                    if (duration > 180.0) {
                        toast("还原时长超过 3 分钟，请提高速度");
                        return;
                    }
                    dialog.dismiss();
                    startImageAudioSynth(uri, info, speed);
                }));
        dialog.show();
    }

    private void startImageAudioSynth(Uri uri, SpectrogramPng.Info info, float speed) {
        if (exporting) {
            toast("正在导出，请稍候");
            return;
        }
        String imageBase = exportBaseFromName(queryDisplayName(uri));
        if (imageBase.length() == 0) imageBase = "频谱图";
        final String name = imageBase + "-图生声.wav";
        final boolean[] canceled = {false};
        ProgressDialog progress = new ProgressDialog(this);
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setTitle("频谱图 → 声音");
        progress.setMessage("准备中…");
        progress.setCancelable(true);
        progress.setOnCancelListener(d -> canceled[0] = true);
        dismissKeyboard();
        progress.show();
        exporting = true;
        ExportService.start(this, "频谱图生成声音");
        new Thread(() -> {
            File temp = null;
            String savedPath = null;
            Uri savedUri = null;
            String error = null;
            try {
                SpectrogramAudioSynth synth = new SpectrogramAudioSynth(this, uri, info, speed,
                        new SpectrogramAudioSynth.Listener() {
                            @Override
                            public void onProgress(int percent, String message) {
                                runOnUiThread(() -> {
                                    ExportService.progress(MainActivity.this, percent, message);
                                    if (progress.isShowing()) {
                                        progress.setProgress(percent);
                                        progress.setMessage(percent + "% · " + message);
                                    }
                                });
                            }

                            @Override
                            public boolean isCanceled() {
                                return canceled[0];
                            }
                        });
                temp = synth.synthesize();
                if (canceled[0]) throw new IllegalStateException("已取消");
                runOnUiThread(() -> {
                    if (progress.isShowing()) {
                        progress.setMessage("保存 WAV…");
                        progress.setProgress(98);
                    }
                });
                savedPath = copyFileToDownloads(name, "audio/wav", temp);
                savedUri = lastSavedMediaUri;
            } catch (Exception e) {
                error = safeMessage(e);
            } finally {
                if (temp != null) temp.delete();
            }
            final String finalPath = savedPath;
            final Uri finalUri = savedUri;
            final String finalError = error;
            final boolean success = finalPath != null;
            ExportService.finish(this, success, success ? "WAV 已生成" : "声音生成失败");
            runOnUiThread(() -> {
                exporting = false;
                if (progress.isShowing()) progress.dismiss();
                if (finalPath != null) {
                    toast("WAV 已保存，正在播放");
                    playGeneratedAudio(finalPath, finalUri);
                } else if (canceled[0]) {
                    toast("已取消");
                } else {
                    toast("生成失败：" + (finalError == null ? "未知错误" : finalError));
                }
            });
        }, "AudioVisualizer-ImageAudio").start();
    }

    private void showXyVideoDialog() {
        if (source != SRC_FILE || currentFileUri == null || fileDurationUs <= 0L) {
            toast("请先导入音频文件");
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, Math.round(6 * density), pad, 0);

        TextView info = new TextView(this);
        info.setText("XY 相图散点动画，并封装原音频（AAC）。"
                + "\n软件编码不受硬件帧率上限限制，但会非常慢，且受 CPU 性能限制。"
                + "\nH.265 需要设备编码器支持；不支持时会报具体不支持项。");
        info.setTextSize(11);
        info.setTextColor(getColor(R.color.text_secondary));
        info.setLineSpacing(0, 1.15f);
        root.addView(info);

        TextView formatLabel = new TextView(this);
        formatLabel.setText("封装格式");
        formatLabel.setTextSize(12);
        formatLabel.setTextColor(getColor(R.color.text));
        formatLabel.setPadding(0, Math.round(14 * density), 0, Math.round(5 * density));
        root.addView(formatLabel);

        final int[] selectedFormat = {XyVideoExporter.FORMAT_MP4};
        final TextView[] formatChips = {
                videoChip("MP4", selectedFormat[0] == XyVideoExporter.FORMAT_MP4),
                videoChip("MKV", selectedFormat[0] == XyVideoExporter.FORMAT_MKV)
        };
        LinearLayout formatRow = new LinearLayout(this);
        formatRow.setOrientation(LinearLayout.HORIZONTAL);
        for (TextView chip : formatChips) {
            formatRow.addView(chip, new LinearLayout.LayoutParams(0,
                    Math.round(34 * density), 1f));
        }
        formatRow.setPadding(0, 0, 0, 0);
        root.addView(formatRow);

        TextView codecLabel = new TextView(this);
        codecLabel.setText("视频编码");
        codecLabel.setTextSize(12);
        codecLabel.setTextColor(getColor(R.color.text));
        codecLabel.setPadding(0, Math.round(10 * density), 0, Math.round(5 * density));
        root.addView(codecLabel);

        final int[] selectedCodec = {XyVideoExporter.CODEC_AVC};
        final TextView[] codecChips = {
                videoChip("H.264", true),
                videoChip("H.265 / HEVC", false)
        };
        LinearLayout codecRow = new LinearLayout(this);
        codecRow.setOrientation(LinearLayout.HORIZONTAL);
        for (TextView chip : codecChips) {
            codecRow.addView(chip, new LinearLayout.LayoutParams(0,
                    Math.round(34 * density), 1f));
        }
        root.addView(codecRow);
        codecChips[0].setOnClickListener(v -> {
            selectedCodec[0] = XyVideoExporter.CODEC_AVC;
            codecChips[0].setSelected(true);
            codecChips[1].setSelected(false);
            codecChips[0].setTextColor(getColor(R.color.cyan_bright));
            codecChips[1].setTextColor(getColor(R.color.seg_text_off));
        });
        codecChips[1].setOnClickListener(v -> {
            selectedCodec[0] = XyVideoExporter.CODEC_HEVC;
            codecChips[1].setSelected(true);
            codecChips[0].setSelected(false);
            codecChips[1].setTextColor(getColor(R.color.cyan_bright));
            codecChips[0].setTextColor(getColor(R.color.seg_text_off));
        });

        TextView encoderLabel = new TextView(this);
        encoderLabel.setText("编码器");
        encoderLabel.setTextSize(12);
        encoderLabel.setTextColor(getColor(R.color.text));
        encoderLabel.setPadding(0, Math.round(10 * density), 0, Math.round(5 * density));
        root.addView(encoderLabel);

        final int[] selectedEncoder = {XyVideoExporter.ENCODER_AUTO};
        final String[] encoderNames = {"自动", "硬件", "软件"};
        final int[] encoderValues = {XyVideoExporter.ENCODER_AUTO,
                XyVideoExporter.ENCODER_HARDWARE, XyVideoExporter.ENCODER_SOFTWARE};
        final TextView[] encoderChips = new TextView[3];
        LinearLayout encoderRow = new LinearLayout(this);
        encoderRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            final int index = i;
            TextView chip = videoChip(encoderNames[i], i == 0);
            chip.setOnClickListener(v -> {
                selectedEncoder[0] = encoderValues[index];
                for (int j = 0; j < encoderChips.length; j++) {
                    boolean on = j == index;
                    encoderChips[j].setSelected(on);
                    encoderChips[j].setTextColor(getColor(on
                            ? R.color.cyan_bright : R.color.seg_text_off));
                }
            });
            encoderChips[i] = chip;
            encoderRow.addView(chip, new LinearLayout.LayoutParams(0,
                    Math.round(34 * density), 1f));
        }
        root.addView(encoderRow);
        formatChips[0].setOnClickListener(v -> {
            selectedFormat[0] = XyVideoExporter.FORMAT_MP4;
            formatChips[0].setSelected(true);
            formatChips[1].setSelected(false);
            formatChips[0].setTextColor(getColor(R.color.cyan_bright));
            formatChips[1].setTextColor(getColor(R.color.seg_text_off));
        });
        formatChips[1].setOnClickListener(v -> {
            selectedFormat[0] = XyVideoExporter.FORMAT_MKV;
            formatChips[1].setSelected(true);
            formatChips[0].setSelected(false);
            formatChips[1].setTextColor(getColor(R.color.cyan_bright));
            formatChips[0].setTextColor(getColor(R.color.seg_text_off));
        });

        TextView sizeLabel = new TextView(this);
        sizeLabel.setText("视频宽高（px，必须为偶数）");
        sizeLabel.setTextSize(12);
        sizeLabel.setTextColor(getColor(R.color.text));
        sizeLabel.setPadding(0, Math.round(12 * density), 0, Math.round(4 * density));
        root.addView(sizeLabel);

        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText widthInput = new EditText(this);
        widthInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        widthInput.setText("1080");
        widthInput.setTextSize(14);
        widthInput.setTextColor(getColor(R.color.text));
        widthInput.setSingleLine(true);
        sizeRow.addView(widthInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView xLabel = new TextView(this);
        xLabel.setText(" × ");
        xLabel.setTextColor(getColor(R.color.text_secondary));
        xLabel.setTextSize(14);
        xLabel.setGravity(Gravity.CENTER);
        sizeRow.addView(xLabel, new LinearLayout.LayoutParams(
                Math.round(28 * density), ViewGroup.LayoutParams.WRAP_CONTENT));
        final EditText heightInput = new EditText(this);
        heightInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        heightInput.setText("1080");
        heightInput.setTextSize(14);
        heightInput.setTextColor(getColor(R.color.text));
        heightInput.setSingleLine(true);
        sizeRow.addView(heightInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(sizeRow);

        TextView fpsLabel = new TextView(this);
        fpsLabel.setText("帧率（fps）");
        fpsLabel.setTextSize(12);
        fpsLabel.setTextColor(getColor(R.color.text));
        fpsLabel.setPadding(0, Math.round(10 * density), 0, Math.round(5 * density));
        root.addView(fpsLabel);

        final int[] fpsOptions = {24, 30, 40, 60, 120, 144, 240};
        final int[] selectedFps = {60};
        HorizontalScrollView fpsScroll = new HorizontalScrollView(this);
        fpsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout fpsRow = new LinearLayout(this);
        fpsRow.setOrientation(LinearLayout.HORIZONTAL);
        final List<TextView> fpsChips = new ArrayList<>();
        for (int value : fpsOptions) {
            TextView chip = videoChip(String.valueOf(value), value == selectedFps[0]);
            chip.setOnClickListener(v -> {
                selectedFps[0] = value;
                for (int i = 0; i < fpsChips.size(); i++) {
                    boolean on = fpsOptions[i] == value;
                    fpsChips.get(i).setSelected(on);
                    fpsChips.get(i).setTextColor(getColor(on
                            ? R.color.cyan_bright : R.color.seg_text_off));
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Math.round(56 * density), Math.round(34 * density));
            lp.setMarginEnd(Math.round(4 * density));
            fpsRow.addView(chip, lp);
            fpsChips.add(chip);
        }
        fpsScroll.addView(fpsRow);
        root.addView(fpsScroll);

        TextView note = new TextView(this);
        note.setText("高帧率/大尺寸会显著增加导出时间；"
                + "分辨率上限约 800 万像素。");
        note.setTextSize(10);
        note.setTextColor(getColor(R.color.text_muted));
        note.setPadding(0, Math.round(10 * density), 0, 0);
        root.addView(note);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("导出 XY 相图视频")
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始导出", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int w;
                    int h;
                    try {
                        w = Integer.parseInt(widthInput.getText().toString().trim());
                        h = Integer.parseInt(heightInput.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        toast("请输入有效宽高");
                        return;
                    }
                    if (w < 128 || h < 128 || (w & 1) != 0 || (h & 1) != 0) {
                        toast("宽高至少 128 且必须为偶数");
                        return;
                    }
                    if ((long) w * h > 8_000_000L) {
                        toast("分辨率最多约 800 万像素");
                        return;
                    }
                    long estimatedFrames = (long) Math.ceil(
                            fileDurationUs * (double) selectedFps[0] / 1_000_000.0);
                    boolean heavy = estimatedFrames > 20000L || selectedFps[0] >= 120;
                    if (heavy) {
                        new AlertDialog.Builder(this)
                                .setTitle("视频较大")
                                .setMessage("预计 " + estimatedFrames + " 帧 · "
                                        + selectedFps[0]
                                        + " fps，编码可能需要较长时间。继续？")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("继续", (dd, ww) -> {
                                    dialog.dismiss();
                                    startXyVideoExport(w, h, selectedFps[0],
                                            selectedFormat[0], selectedCodec[0],
                                            selectedEncoder[0]);
                                })
                                .show();
                    } else {
                        dialog.dismiss();
                        startXyVideoExport(w, h, selectedFps[0], selectedFormat[0],
                                selectedCodec[0], selectedEncoder[0]);
                    }
                }));
        dialog.show();
    }

    private TextView videoChip(String text, boolean selected) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(11);
        chip.setGravity(Gravity.CENTER);
        chip.setBackgroundResource(R.drawable.seg_item);
        chip.setSelected(selected);
        chip.setTextColor(getColor(selected ? R.color.cyan_bright : R.color.seg_text_off));
        chip.setClickable(true);
        chip.setFocusable(true);
        return chip;
    }

    private void startXyVideoExport(int w, int h, int fps, int format, int codec,
                                    int encoderMode) {
        if (exporting) {
            toast("正在导出，请稍候");
            return;
        }
        final Uri exportUri = currentFileUri;
        if (exportUri == null) {
            toast("请先导入音频文件");
            return;
        }
        final String extension = format == XyVideoExporter.FORMAT_MKV ? ".mkv" : ".mp4";
        final String name = currentExportBase() + "-XY相图" + extension;
        final boolean[] canceled = {false};
        ProgressDialog progress = new ProgressDialog(this);
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setTitle("生成 XY 视频");
        progress.setMessage("准备中…");
        progress.setCancelable(true);
        progress.setOnCancelListener(d -> canceled[0] = true);
        dismissKeyboard();
        progress.show();
        exporting = true;
        ExportService.start(this, "生成 XY 视频");

        new Thread(() -> {
            File temp = null;
            String savedPath = null;
            String error = null;
            try {
                XyVideoExporter exporter = new XyVideoExporter(this, exportUri,
                        w, h, fps, format, codec, encoderMode, config.gain,
                        config.xyMirrorX, config.xyMirrorY,
                        new XyVideoExporter.Listener() {
                            @Override
                            public void onProgress(int percent, String message) {
                                runOnUiThread(() -> {
                                    ExportService.progress(MainActivity.this, percent, message);
                                    if (progress.isShowing()) {
                                        progress.setProgress(percent);
                                        progress.setMessage(percent + "% · " + message);
                                    }
                                });
                            }

                            @Override
                            public boolean isCanceled() {
                                return canceled[0];
                            }
                        });
                temp = exporter.export();
                if (canceled[0]) throw new IllegalStateException("导出已取消");
                runOnUiThread(() -> {
                    if (progress.isShowing()) {
                        progress.setMessage("保存视频…");
                        progress.setProgress(98);
                    }
                });
                String mime = format == XyVideoExporter.FORMAT_MKV
                        ? "video/x-matroska" : "video/mp4";
                savedPath = copyFileToDownloads(name, mime, temp);
            } catch (Exception e) {
                error = safeMessage(e);
                writeExportDiagnostic("video", e);
            } finally {
                if (temp != null) temp.delete();
            }
            final String finalPath = savedPath;
            final String finalError = error;
            final boolean videoSuccess = finalPath != null;
            ExportService.finish(this, videoSuccess, videoSuccess
                    ? "视频已导出"
                    : (canceled[0] ? "已取消导出"
                    : "导出失败" + (finalError == null ? "" : "：" + finalError)));
            runOnUiThread(() -> {
                exporting = false;
                if (progress.isShowing()) progress.dismiss();
                if (finalPath != null) {
                    toast("视频已导出：" + finalPath);
                } else if (canceled[0]) {
                    toast("已取消导出");
                } else {
                    toast("导出失败：" + (finalError == null ? "未知错误" : finalError));
                }
            });
        }, "AudioVisualizer-XYVideo").start();
    }

    private void writeExportDiagnostic(String kind, Throwable error) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            error.printStackTrace(pw);
            pw.flush();
            String text = "kind=" + kind + "\n" + sw.toString();
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, "export-error.txt");
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/DSH-Go");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out != null) out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(uri, done, null, null);
                }
            } else {
                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "DSH-Go");
                if (dir.exists() || dir.mkdirs()) {
                    try (java.io.FileWriter fw = new java.io.FileWriter(
                            new File(dir, "export-error.txt"))) {
                        fw.write(text);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String copyFileToDownloads(String name, String mime, File source)
            throws Exception {
        lastSavedMediaUri = null;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/DSH-Go");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("无法创建视频文件");
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("无法写入视频文件");
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            } catch (Exception e) {
                getContentResolver().delete(uri, null, null);
                throw e;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
            lastSavedMediaUri = uri;
            return "Download/DSH-Go/" + name;
        }
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "DSH-Go");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建视频目录");
        File target = new File(dir, name);
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
        return target.getAbsolutePath();
    }

    private void showWaterfallTypeDialog() {
        final boolean hasSegment = source == SRC_FILE && currentFileUri != null;
        String[] items = hasSegment
                ? new String[]{"全文件瀑布", "选择片段…"}
                : new String[]{"全文件瀑布"};
        new AlertDialog.Builder(this)
                .setTitle("瀑布大图 PNG")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        showWaterfallExportDialog();
                    } else {
                        showSegmentSelector();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSegmentSelector() {
        if (source != SRC_FILE || currentFileUri == null) {
            toast("请先导入音频文件");
            return;
        }
        int rate = pipeline != null ? pipeline.getSampleRate() : 0;
        if (rate <= 0 && scope != null) rate = scope.getFullSpectrogramSampleRate();
        if (rate <= 0) rate = 44100;
        int channels = pipeline != null ? pipeline.getChannelCount() : 0;
        if (channels <= 0) channels = 2;
        long totalUs = fileDurationUs;
        if (totalUs <= 0L && scope != null) totalUs = scope.getFullSpectrogramDurationUs();
        if (totalUs <= 0L) {
            toast("音频正在准备，请稍候再选择片段");
            return;
        }
        WaveformData data = waveformData;
        final boolean waitingWaveform = data == null;
        if (data == null) {
            int buckets = 1024;
            data = new WaveformData(new float[buckets], new float[buckets],
                    new float[buckets], new float[buckets], buckets,
                    rate, channels, totalUs, Math.max(2L,
                    Math.round(totalUs * rate / 1_000_000.0)));
        }
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(false);

        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(pad, Math.round(8 * density), pad, pad);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView close = new TextView(this);
        close.setText("×");
        close.setTextSize(26);
        close.setTextColor(Color.WHITE);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> dialog.dismiss());
        top.addView(close, new LinearLayout.LayoutParams(
                Math.round(42 * density), Math.round(48 * density)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("选择片段");
        title.setTextSize(17);
        title.setTextColor(Color.WHITE);
        titles.addView(title);
        TextView sub = new TextView(this);
        sub.setText(currentFileName);
        sub.setTextSize(9);
        sub.setTextColor(getColor(R.color.text_muted));
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titles.addView(sub);
        top.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView selectAll = new TextView(this);
        selectAll.setText("全选");
        selectAll.setTextSize(11);
        selectAll.setTextColor(getColor(R.color.cyan));
        selectAll.setGravity(Gravity.CENTER);
        top.addView(selectAll, new LinearLayout.LayoutParams(
                Math.round(52 * density), Math.round(48 * density)));
        root.addView(top);

        TextView hint = new TextView(this);
        hint.setText(waitingWaveform
                ? "波形仍在分析，可先按时间拖动选择范围"
                : "拖动两侧白色手柄选择导出范围");
        hint.setTextSize(10);
        hint.setTextColor(getColor(R.color.text_muted));
        hint.setPadding(0, Math.round(2 * density), 0, Math.round(4 * density));
        root.addView(hint);

        final WaveformSelectionView waveform = new WaveformSelectionView(this);
        waveform.setMinimumHeight(Math.round(220 * density));
        root.addView(waveform, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final TextView bigTime = new TextView(this);
        bigTime.setTextSize(31);
        bigTime.setTextColor(Color.WHITE);
        bigTime.setTypeface(Typeface.MONOSPACE);
        bigTime.setGravity(Gravity.CENTER);
        bigTime.setPadding(0, Math.round(12 * density), 0, 0);
        root.addView(bigTime);

        final TextView rangeInfo = new TextView(this);
        rangeInfo.setTextSize(10);
        rangeInfo.setTextColor(getColor(R.color.text_secondary));
        rangeInfo.setGravity(Gravity.CENTER);
        rangeInfo.setPadding(0, Math.round(2 * density), 0, Math.round(12 * density));
        root.addView(rangeInfo);

        TextView next = new TextView(this);
        next.setText("下一步：设置导出尺寸");
        next.setTextSize(14);
        next.setGravity(Gravity.CENTER);
        next.setTextColor(getColor(R.color.action_text));
        next.setBackgroundResource(R.drawable.btn_action);
        next.setClickable(true);
        next.setFocusable(true);
        next.setOnClickListener(v -> {
            long start = waveform.getStartUs();
            long end = waveform.getEndUs();
            if (end - start < 20_000L) {
                toast("片段至少需要 0.02 秒");
                return;
            }
            dialog.dismiss();
            showWaterfallExportDialog(start, end);
        });
        root.addView(next, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(50 * density)));

        final WaveformData displayData = data;
        waveform.setData(displayData);
        waveform.setSelection(0L, displayData.durationUs);
        waveform.setSelectionListener((startUs, endUs) -> {
            long length = Math.max(0L, endUs - startUs);
            bigTime.setText(formatPreciseTime(length));
            rangeInfo.setText("起点 " + formatPreciseTime(startUs)
                    + "  ·  终点 " + formatPreciseTime(endUs)
                    + "  ·  全长 " + formatPreciseTime(displayData.durationUs));
        });
        selectAll.setOnClickListener(v -> waveform.selectAll());
        long length = displayData.durationUs;
        bigTime.setText(formatPreciseTime(length));
        rangeInfo.setText("起点 00:00.00  ·  终点 " + formatPreciseTime(length)
                + "  ·  全长 " + formatPreciseTime(length));

        dialog.setContentView(root);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            window.setStatusBarColor(Color.BLACK);
            window.setNavigationBarColor(Color.BLACK);
        }
    }

    private String formatPreciseTime(long us) {
        long totalCentis = Math.max(0L, us / 10_000L);
        long hours = totalCentis / 360000L;
        long minutes = (totalCentis % 360000L) / 6000L;
        long seconds = (totalCentis % 6000L) / 100L;
        long centis = totalCentis % 100L;
        if (hours > 0L) {
            return String.format(Locale.US, "%d:%02d:%02d.%02d",
                    hours, minutes, seconds, centis);
        }
        return String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, centis);
    }

    private void showWaterfallExportDialog() {
        showWaterfallExportDialog(0L, 0L);
    }

    private void showWaterfallExportDialog(long rangeStartUs, long rangeEndUs) {
        if (source != SRC_FILE || currentFileUri == null) {
            toast("请先导入音频文件");
            return;
        }
        int rate = scope != null ? scope.getFullSpectrogramSampleRate() : 0;
        if (rate <= 0 && pipeline != null) {
            rate = pipeline.getSampleRate();
        }
        long duration = scope != null ? scope.getFullSpectrogramDurationUs() : 0L;
        if (duration <= 0L) {
            duration = fileDurationUs;
        }
        if (rate <= 0 || duration <= 0L) {
            toast("音频正在准备，请稍候再导出");
            return;
        }
        final int sampleRate = rate;
        final long fileDurationUs = duration;
        long startUs = Math.max(0L, Math.min(rangeStartUs, fileDurationUs));
        long endUs = rangeEndUs > startUs
                ? Math.min(rangeEndUs, fileDurationUs) : fileDurationUs;
        if (endUs - startUs < 20_000L) {
            toast("选中片段太短");
            return;
        }
        final boolean segment = startUs > 0L || endUs < fileDurationUs - 1_000L;
        final long rangeDurationUs = endUs - startUs;
        final long totalFrames = Math.max(1L, Math.round(
                rangeDurationUs * sampleRate / 1_000_000.0));
        final int maxWidth = (int) Math.min(Integer.MAX_VALUE, totalFrames);
        final int maxHeight = 4096;
        final long exportStartUs = startUs;
        final long exportEndUs = endUs;

        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, Math.round(8 * density), pad, 0);

        TextView sourceInfo = new TextView(this);
        sourceInfo.setText((segment
                ? "片段 " + formatPreciseTime(startUs) + " – " + formatPreciseTime(endUs)
                + "（" + formatPreciseTime(rangeDurationUs) + "）\n"
                : "全文件 ")
                + "导出时会重新解码并按宽度逐列计算 FFT，不是放大屏幕预览。"
                + "\n理论最大宽度 " + totalFrames + " px（采样率 × 片段时长）"
                + "\n实际分析列上限 131072；更宽时仅时间插值"
                + "\n高度上限 " + maxHeight + " px · 单次最多 3 亿像素");
        sourceInfo.setTextSize(11);
        sourceInfo.setTextColor(getColor(R.color.text_secondary));
        sourceInfo.setLineSpacing(0, 1.15f);
        root.addView(sourceInfo);

        TextView widthLabel = new TextView(this);
        widthLabel.setText("宽度（px）");
        widthLabel.setTextSize(12);
        widthLabel.setTextColor(getColor(R.color.text));
        widthLabel.setPadding(0, Math.round(14 * density), 0, Math.round(4 * density));
        root.addView(widthLabel);

        final EditText widthInput = new EditText(this);
        widthInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        widthInput.setText(String.valueOf(Math.min(maxWidth, 16384)));
        widthInput.setTextSize(14);
        widthInput.setTextColor(getColor(R.color.text));
        widthInput.setSingleLine(true);
        root.addView(widthInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView heightLabel = new TextView(this);
        heightLabel.setText("高度（px）");
        heightLabel.setTextSize(12);
        heightLabel.setTextColor(getColor(R.color.text));
        heightLabel.setPadding(0, Math.round(10 * density), 0, Math.round(4 * density));
        root.addView(heightLabel);

        final EditText heightInput = new EditText(this);
        heightInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        heightInput.setText(String.valueOf(Math.min(maxHeight, 1024)));
        heightInput.setTextSize(14);
        heightInput.setTextColor(getColor(R.color.text));
        heightInput.setSingleLine(true);
        root.addView(heightInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setText("宽 ≤ 131072 时每列都会实际计算 FFT；高分辨率大图需要较长时间。");
        note.setTextSize(10);
        note.setTextColor(getColor(R.color.text_muted));
        note.setPadding(0, Math.round(10 * density), 0, 0);
        root.addView(note);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(segment ? "导出片段瀑布" : "导出瀑布大图")
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("导出", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int width;
                    int height;
                    try {
                        width = Integer.parseInt(widthInput.getText().toString().trim());
                        height = Integer.parseInt(heightInput.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        toast("请输入有效尺寸");
                        return;
                    }
                    if (width < 64 || height < 64) {
                        toast("宽度和高度至少 64 px");
                        return;
                    }
                    if (width > maxWidth) {
                        widthInput.setError("最大 " + maxWidth + " px");
                        return;
                    }
                    if (height > maxHeight) {
                        heightInput.setError("最大 " + maxHeight + " px");
                        return;
                    }
                    long pixels = (long) width * height;
                    if (pixels > 300000000L) {
                        toast("单次最多 3 亿像素，请减小尺寸");
                        return;
                    }
                    final int outW = width;
                    final int outH = height;
                    dialog.dismiss();
                    if (pixels > 80000000L) {
                        new AlertDialog.Builder(this)
                                .setTitle("尺寸较大")
                                .setMessage("约 " + (pixels / 1_000_000L)
                                        + " 百万像素，导出可能耗时较长且文件很大。继续？")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("继续", (dd, ww) ->
                                        startWaterfallExport(outW, outH,
                                                exportStartUs, exportEndUs))
                                .show();
                    } else {
                        startWaterfallExport(outW, outH, exportStartUs, exportEndUs);
                    }
                }));
        dialog.show();
    }

    private void startWaterfallExport(int outW, int outH, long startUs, long endUs) {
        if (exporting) {
            toast("正在导出，请稍候");
            return;
        }
        if (source != SRC_FILE || currentFileUri == null) {
            toast("请先导入音频文件");
            return;
        }
        final Uri exportUri = currentFileUri;
        long totalDuration = fileDurationUs;
        if (totalDuration <= 0L && scope != null) {
            totalDuration = scope.getFullSpectrogramDurationUs();
        }
        boolean segmentExport = startUs > 0L
                || (endUs > 0L && totalDuration > 0L
                && endUs < totalDuration - 1_000L);
        final String name = currentExportBase()
                + (segmentExport ? "-频谱图-片段.png" : "-频谱图.png");
        final boolean[] canceled = {false};
        ProgressDialog progress = new ProgressDialog(this);
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setTitle("生成瀑布大图");
        progress.setMessage("准备中…");
        progress.setCancelable(true);
        progress.setOnCancelListener(d -> canceled[0] = true);
        dismissKeyboard();
        progress.show();
        exporting = true;
        ExportService.start(this, "生成瀑布大图");

        new Thread(() -> {
            Uri uri = null;
            OutputStream stream = null;
            String savedPath = null;
            String error = null;
            boolean success = false;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/DSH-Go");
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    uri = getContentResolver().insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) {
                        throw new IllegalStateException("无法创建导出文件");
                    }
                    stream = getContentResolver().openOutputStream(uri);
                    if (stream == null) {
                        throw new IllegalStateException("无法写入导出文件");
                    }
                } else {
                    File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "DSH-Go");
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw new IllegalStateException("无法创建导出目录");
                    }
                    File file = new File(dir, name);
                    stream = new FileOutputStream(file);
                    savedPath = file.getAbsolutePath();
                }

                HighResWaterfallExporter exporter = new HighResWaterfallExporter(
                        this, exportUri, outW, outH, startUs, endUs, currentFileName,
                        new HighResWaterfallExporter.Listener() {
                            @Override
                            public void onProgress(int percent, String message) {
                                runOnUiThread(() -> {
                                    if (progress.isShowing()) {
                                        progress.setProgress(percent);
                                        progress.setMessage(percent + "% · " + message);
                                    }
                                });
                            }

                            @Override
                            public boolean isCanceled() {
                                return canceled[0];
                            }
                        });
                exporter.export(stream);
                success = true;
            } catch (Exception e) {
                error = safeMessage(e);
            } finally {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (Exception ignored) {
                    }
                }
                if (uri != null) {
                    if (success && !canceled[0]) {
                        ContentValues done = new ContentValues();
                        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        getContentResolver().update(uri, done, null, null);
                        savedPath = "Download/DSH-Go/" + name;
                    } else {
                        getContentResolver().delete(uri, null, null);
                    }
                }
            }
            final String finalPath = savedPath;
            final String finalError = error;
            final boolean finalSuccess = success && !canceled[0];
            ExportService.finish(this, finalSuccess, finalSuccess
                    ? "瀑布大图已导出" : (canceled[0] ? "已取消导出" : "导出失败"));
            runOnUiThread(() -> {
                exporting = false;
                if (progress.isShowing()) {
                    progress.dismiss();
                }
                if (finalSuccess) {
                    toast("瀑布大图已导出：" + finalPath);
                } else if (canceled[0]) {
                    toast("已取消导出");
                } else {
                    toast("导出失败：" + (finalError == null ? "未知错误" : finalError));
                }
            });
        }, "WaveScope-WaterfallExport").start();
    }

    private String currentExportBase() {
        return exportBaseFromName(currentFileName);
    }

    private String exportBaseFromName(String name) {
        if (name == null) name = "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return safeBaseName(name);
    }

    private String safeBaseName(String name) {
        String base = name == null ? "" : name.trim();
        if (base.isEmpty()) base = "音频";
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        base = base.replaceAll("\\s+", " ").trim();
        if (base.length() > 80) base = base.substring(0, 80).trim();
        if (base.isEmpty()) base = "音频";
        return base;
    }

    private String modeSlug() {
        if (config.mode == AppConfig.MODE_XY) return "XY";
        if (config.mode == AppConfig.MODE_FFT) return "FFT";
        return "XT";
    }

    private void exportCsv() {
        if (exporting) {
            toast("正在导出，请稍候");
            return;
        }
        final String csv = scope.buildExportCsv();
        if (csv == null) {
            toast("当前没有可导出的数据");
            return;
        }
        final String name = currentExportBase() + "-" + modeSlug() + ".csv";
        exporting = true;
        toast("正在导出 CSV…");
        new Thread(() -> {
            String result = null;
            String error = null;
            try {
                result = saveBytes(name, "text/csv", csv.getBytes("UTF-8"));
            } catch (Exception e) {
                error = safeMessage(e);
            }
            final String savedPath = result;
            final String err = error;
            runOnUiThread(() -> {
                exporting = false;
                if (savedPath != null) {
                    toast("已导出：" + savedPath);
                } else {
                    toast("导出失败：" + (err == null ? "未知错误" : err));
                }
            });
        }, "WaveScope-CSV").start();
    }

    private void exportPng() {
        if (exporting) {
            toast("正在导出，请稍候");
            return;
        }
        int width = scope.getWidth();
        int height = scope.getHeight();
        if (width <= 0 || height <= 0) {
            toast("图表尚未准备好");
            return;
        }
        final Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(getColor(R.color.scope_bg));
            scope.draw(canvas);
        } catch (Exception e) {
            toast("截图生成失败：" + safeMessage(e));
            return;
        }
        final String name = currentExportBase() + "-" + modeSlug() + ".png";
        exporting = true;
        toast("正在保存截图…");
        new Thread(() -> {
            String result = null;
            String error = null;
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
                result = saveBytes(name, "image/png", bytes.toByteArray());
            } catch (Exception e) {
                error = safeMessage(e);
            } finally {
                bitmap.recycle();
            }
            final String savedPath = result;
            final String err = error;
            runOnUiThread(() -> {
                exporting = false;
                if (savedPath != null) {
                    toast("已保存：" + savedPath);
                } else {
                    toast("保存失败：" + (err == null ? "未知错误" : err));
                }
            });
        }, "WaveScope-PNG").start();
    }

    private String saveBytes(String name, String mime, byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/DSH-Go");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IllegalStateException("无法创建导出文件");
            }
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    throw new IllegalStateException("无法写入导出文件");
                }
                out.write(data);
                out.flush();
            } catch (Exception e) {
                getContentResolver().delete(uri, null, null);
                throw e;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
            lastSavedMediaUri = uri;
            return "Download/DSH-Go/" + name;
        }
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "DSH-Go");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建应用下载目录");
        }
        File file = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
        return file.getAbsolutePath();
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void showPermissionHelp(String title, String message) {
        if (isFinishing()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("去设置", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    try {
                        startActivity(intent);
                    } catch (Exception e) {
                        toast("无法打开系统设置");
                    }
                })
                .show();
    }

    private String queryDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && name.length() > 0) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String path = uri.getLastPathSegment();
        return path == null ? "导入的音频" : path;
    }

    private String rateLabel(int rate) {
        return String.format(Locale.US, "%.1f kHz", rate / 1000.0f);
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

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String formatTime(long us) {
        if (us < 0) {
            us = 0;
        }
        long totalSeconds = us / 1_000_000L;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.length() == 0 ? e.getClass().getSimpleName() : m;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
