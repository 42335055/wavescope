package com.dsh.wavescope;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Foreground mediaProjection service required for AudioPlaybackCapture.
 * It captures whatever media playback the user allows, at the system mix rate.
 */
public final class MediaCaptureService extends Service {

    public static final String ACTION_START = "com.dsh.wavescope.action.START_MEDIA_CAPTURE";
    public static final String ACTION_STOP = "com.dsh.wavescope.action.STOP_MEDIA_CAPTURE";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    public interface Listener {
        void onCaptureStarted(int sampleRate, int channels);

        void onSamples(short[] data, int count);

        void onCaptureStopped();

        void onCaptureError(String message);
    }

    private static final String CHANNEL_ID = "media_capture";
    private static final int NOTIFICATION_ID = 3201;
    private static volatile Listener listener;
    private static volatile boolean running;
    private static volatile MediaCaptureService runningInstance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private volatile boolean captureWanted;
    private AudioRecord audioRecord;
    private Thread captureThread;
    private boolean startedForeground;
    private int captureRate = 48000;
    private int captureChannels = 2;
    private boolean stopNotified;

    public static void setListener(Listener l) {
        listener = l;
    }

    public static boolean isRunning() {
        return running;
    }

    public static int getCaptureSampleRate() {
        return runningInstance == null ? 48000 : runningInstance.captureRate;
    }

    public static int getCaptureChannels() {
        return runningInstance == null ? 2 : runningInstance.captureChannels;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT < 29) {
            notifyError("媒体音频捕获需要 Android 10 或更高版本");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            startInForeground();
            startCapture(resultCode, resultData);
        } catch (Exception e) {
            String msg = e.getMessage();
            notifyError("媒体音频启动失败" + (msg == null ? "" : "：" + msg));
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopCapture();
        releaseProjection();
        try {
            if (startedForeground) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            }
        } catch (Exception ignored) {
        }
        startedForeground = false;
        super.onDestroy();
    }

    private void startInForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        startedForeground = true;
    }

    private void startCapture(int resultCode, Intent resultData) {
        releaseProjection();
        if (resultData == null) {
            notifyError("未获得屏幕录制授权，无法监听媒体音频");
            stopSelf();
            return;
        }
        synchronized (lock) {
            stopCaptureLocked();
            stopNotified = false;
            captureWanted = true;
        }
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            notifyError("系统不支持媒体投影");
            stopSelf();
            return;
        }
        MediaProjection mp;
        try {
            mp = manager.getMediaProjection(resultCode, resultData);
        } catch (Exception e) {
            notifyError("无法创建媒体投影：" + safeMessage(e));
            stopSelf();
            return;
        }
        if (mp == null) {
            notifyError("媒体投影授权无效");
            stopSelf();
            return;
        }
        projection = mp;
        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                main.post(() -> {
                    stopCapture();
                    stopSelf();
                });
            }
        };
        mp.registerCallback(projectionCallback, main);

        int rate = systemMixRate();
        int channels = 2;
        AudioPlaybackCaptureConfiguration captureConfig;
        try {
            captureConfig = new AudioPlaybackCaptureConfiguration.Builder(mp)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
        } catch (Exception e) {
            notifyError("无法创建媒体音频配置：" + safeMessage(e));
            stopSelf();
            return;
        }

        AudioRecord record;
        try {
            int channelMask = AudioFormat.CHANNEL_IN_STEREO;
            int minBuffer = AudioRecord.getMinBufferSize(rate, channelMask,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) {
                minBuffer = rate / 5 * channels * 2;
            }
            int bufferSize = Math.max(minBuffer * 2, rate / 5 * channels * 2);
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(channelMask)
                    .build();
            record = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .build();
        } catch (Exception e) {
            notifyError("媒体音频采集初始化失败：" + safeMessage(e));
            stopSelf();
            return;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            try {
                record.release();
            } catch (Exception ignored) {
            }
            notifyError("系统拒绝了媒体音频采集（设备或权限不支持）");
            stopSelf();
            return;
        }

        audioRecord = record;
        captureRate = rate;
        captureChannels = record.getChannelCount() > 0 ? record.getChannelCount() : channels;
        try {
            record.startRecording();
        } catch (Exception e) {
            notifyError("无法开始媒体音频采集：" + safeMessage(e));
            releaseRecord(record);
            audioRecord = null;
            stopSelf();
            return;
        }
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            notifyError("媒体音频采集未启动，可能被系统限制");
            releaseRecord(record);
            audioRecord = null;
            stopSelf();
            return;
        }

        running = true;
        runningInstance = this;
        updateNotification("正在捕获媒体音频 · " + captureRate + " Hz");
        Listener l = listener;
        if (l != null) {
            l.onCaptureStarted(captureRate, captureChannels);
        }

        captureThread = new Thread(this::captureLoop, "WaveScope-Media");
        captureThread.start();
    }

    private void captureLoop() {
        AudioRecord record = audioRecord;
        if (record == null) {
            return;
        }
        short[] buffer = new short[2048 * Math.max(1, captureChannels)];
        try {
            while (captureWanted) {
                int count = record.read(buffer, 0, buffer.length);
                if (count > 0) {
                    Listener l = listener;
                    if (l != null) {
                        l.onSamples(buffer, count);
                    }
                } else if (count == AudioRecord.ERROR_INVALID_OPERATION
                        || count == AudioRecord.ERROR_BAD_VALUE
                        || count == AudioRecord.ERROR_DEAD_OBJECT) {
                    if (captureWanted) {
                        notifyError("媒体音频采集中断（错误 " + count + "）");
                    }
                    break;
                }
            }
        } catch (Exception e) {
            if (captureWanted) {
                notifyError("媒体音频采集失败：" + safeMessage(e));
            }
        } finally {
            running = false;
            runningInstance = null;
            releaseRecord(record);
            if (audioRecord == record) {
                audioRecord = null;
            }
            notifyStopped();
            main.post(() -> {
                stopCapture();
                stopSelf();
            });
        }
    }

    private void stopCapture() {
        synchronized (lock) {
            stopCaptureLocked();
        }
    }

    private void stopCaptureLocked() {
        captureWanted = false;
        running = false;
        runningInstance = null;
        AudioRecord record = audioRecord;
        if (record != null) {
            try {
                record.stop();
            } catch (Exception ignored) {
            }
        }
        Thread t = captureThread;
        captureThread = null;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (record != null) {
            releaseRecord(record);
            if (audioRecord == record) {
                audioRecord = null;
            }
        }
    }

    private void releaseProjection() {
        MediaProjection.Callback cb = projectionCallback;
        MediaProjection mp = projection;
        projection = null;
        projectionCallback = null;
        if (mp != null) {
            try {
                if (cb != null) {
                    mp.unregisterCallback(cb);
                }
            } catch (Exception ignored) {
            }
            try {
                mp.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private void releaseRecord(AudioRecord record) {
        if (record == null) {
            return;
        }
        try {
            record.stop();
        } catch (Exception ignored) {
        }
        try {
            record.release();
        } catch (Exception ignored) {
        }
    }

    private int systemMixRate() {
        int rate = 48000;
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                String prop = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
                if (prop != null) {
                    int parsed = Integer.parseInt(prop);
                    if (parsed >= 44100 && parsed <= 192000) {
                        rate = parsed;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return rate;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.media_channel_name), NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("捕获媒体播放声音时显示");
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);
        Intent stop = new Intent(this, MediaCaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stop, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(R.drawable.ic_stat_wave)
                .setContentTitle(getString(R.string.media_notification_title))
                .setContentText(getString(R.string.media_notification_text))
                .setContentIntent(content)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat_wave),
                        getString(R.string.media_notification_stop), stopPending).build());
        if (Build.VERSION.SDK_INT < 26) {
            builder.setPriority(Notification.PRIORITY_LOW);
        }
        return builder.build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            Intent open = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent content = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Intent stop = new Intent(this, MediaCaptureService.class).setAction(ACTION_STOP);
            PendingIntent stopPending = PendingIntent.getService(this, 1, stop,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= 26) {
                builder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
                builder.setPriority(Notification.PRIORITY_LOW);
            }
            builder.setSmallIcon(R.drawable.ic_stat_wave)
                    .setContentTitle(getString(R.string.media_notification_title))
                    .setContentText(text)
                    .setContentIntent(content)
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .addAction(new Notification.Action.Builder(
                            Icon.createWithResource(this, R.drawable.ic_stat_wave),
                            getString(R.string.media_notification_stop), stopPending).build());
            nm.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception ignored) {
        }
    }

    private void notifyStopped() {
        if (stopNotified) {
            return;
        }
        stopNotified = true;
        main.post(() -> {
            Listener l = listener;
            if (l != null) {
                l.onCaptureStopped();
            }
        });
    }

    private void notifyError(String message) {
        main.post(() -> {
            Listener l = listener;
            if (l != null) {
                l.onCaptureError(message);
            }
        });
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.length() == 0 ? e.getClass().getSimpleName() : m;
    }
}
