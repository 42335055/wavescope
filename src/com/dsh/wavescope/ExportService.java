package com.dsh.wavescope;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Keeps long-running exports alive while the app is in the background. */
public final class ExportService extends Service {

    private static final String CHANNEL_ID = "export_progress";
    private static final int NOTIFICATION_ID = 4201;
    private static final String ACTION_START = "com.dsh.wavescope.export.START";
    private static final String ACTION_PROGRESS = "com.dsh.wavescope.export.PROGRESS";
    private static final String ACTION_FINISH = "com.dsh.wavescope.export.FINISH";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_PERCENT = "percent";
    private static final String EXTRA_MESSAGE = "message";
    private static final String EXTRA_SUCCESS = "success";

    private static volatile boolean active;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String title = "正在导出";
    private boolean foregroundStarted;
    private boolean finishing;

    public static void start(Context context, String title) {
        active = true;
        Intent intent = new Intent(context, ExportService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_TITLE, title);
        startCompat(context, intent);
    }

    public static void progress(Context context, int percent, String message) {
        if (!active) return;
        Intent intent = new Intent(context, ExportService.class);
        intent.setAction(ACTION_PROGRESS);
        intent.putExtra(EXTRA_PERCENT, percent);
        intent.putExtra(EXTRA_MESSAGE, message);
        startCompat(context, intent);
    }

    public static void finish(Context context, boolean success, String message) {
        if (!active) return;
        active = false;
        Intent intent = new Intent(context, ExportService.class);
        intent.setAction(ACTION_FINISH);
        intent.putExtra(EXTRA_SUCCESS, success);
        intent.putExtra(EXTRA_MESSAGE, message);
        startCompat(context, intent);
    }

    private static void startCompat(Context context, Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            title = intent.getStringExtra(EXTRA_TITLE);
            if (title == null || title.length() == 0) title = "正在导出";
            finishPending();
            showForeground(build(0, "准备中…", false, false), true);
        } else if (ACTION_PROGRESS.equals(action)) {
            int percent = Math.max(0, Math.min(100, intent.getIntExtra(EXTRA_PERCENT, 0)));
            String message = intent.getStringExtra(EXTRA_MESSAGE);
            if (message == null) message = "导出中…";
            Notification n = build(percent, message, false, false);
            if (foregroundStarted) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.notify(NOTIFICATION_ID, n);
            } else {
                showForeground(n, true);
            }
        } else if (ACTION_FINISH.equals(action)) {
            boolean success = intent.getBooleanExtra(EXTRA_SUCCESS, false);
            String message = intent.getStringExtra(EXTRA_MESSAGE);
            if (message == null) message = success ? "导出完成" : "导出未完成";
            Notification n = build(100, message, true, !success);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, n);
            if (foregroundStarted) {
                stopForeground(!success);
                foregroundStarted = false;
            }
            finishing = true;
            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(this::stopSelf, success ? 2500L : 0L);
        }
        return START_NOT_STICKY;
    }

    private void showForeground(Notification notification, boolean ongoing) {
        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, notification);
            foregroundStarted = true;
        } else {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, notification);
        }
    }

    private void finishPending() {
        handler.removeCallbacksAndMessages(null);
        finishing = false;
    }

    private Notification build(int percent, String message, boolean done, boolean failed) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);
        b.setSmallIcon(R.drawable.ic_stat_wave)
                .setContentTitle("音频可视化 · " + title)
                .setContentText(message)
                .setContentIntent(pi)
                .setOngoing(!done)
                .setAutoCancel(done)
                .setOnlyAlertOnce(true);
        if (!done) {
            b.setProgress(100, percent, false);
        }
        if (failed) {
            b.setContentTitle("音频可视化 · 导出未完成");
        }
        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        "后台导出", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("显示导出进度");
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (foregroundStarted) {
            stopForeground(true);
            foregroundStarted = false;
        }
        super.onDestroy();
    }
}
