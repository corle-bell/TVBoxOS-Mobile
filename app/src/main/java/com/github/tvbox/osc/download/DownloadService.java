package com.github.tvbox.osc.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.ui.activity.DownloadsActivity;

import java.util.List;

public class DownloadService extends Service {
    private static final String CHANNEL_ID = "mbox_download";
    private static final int NOTIFICATION_ID = 1001;

    private static DownloadService instance;
    private int lastStartId;

    public static void start(android.content.Context context) {
        Intent intent = new Intent(context, DownloadService.class);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void refresh() {
        DownloadService service = instance;
        if (service != null) service.updateNotification();
    }

    public static void stopIfIdle() {
        DownloadService service = instance;
        if (service != null) service.stopIfIdleInternal();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "下载", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        startForeground(NOTIFICATION_ID, buildNotification());
        VideoDownloadManager.get().pump();
        if (!VideoDownloadManager.get().hasActive()) {
            shutdown(startId);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void stopIfIdleInternal() {
        if (!VideoDownloadManager.get().hasActive()) {
            shutdown(lastStartId);
        }
    }

    private void shutdown(int startId) {
        stopForeground(true);
        stopSelf(startId);
    }

    private void updateNotification() {
        if (!VideoDownloadManager.get().hasActive()) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        String text = "准备下载";
        int progress = 0;
        boolean indeterminate = true;
        int running = 0;
        DownloadTask shown = null;
        List<DownloadTask> tasks = VideoDownloadManager.get().snapshot();
        for (DownloadTask task : tasks) {
            if (task.status == DownloadTask.RUNNING) {
                running++;
                if (shown == null) shown = task;
            }
        }
        if (shown != null) {
            progress = Math.max(0, Math.min(100, shown.progress));
            indeterminate = progress <= 0;
            if (running > 1) {
                text = shown.title + " 等" + running + "个任务";
            } else {
                text = shown.title + "  " + progress + "%";
            }
        }
        Intent intent = new Intent(this, DownloadsActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在下载")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setProgress(100, progress, indeterminate)
                .build();
    }
}
