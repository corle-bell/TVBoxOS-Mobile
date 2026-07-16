package com.github.tvbox.osc.sync.webdav;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import java.util.concurrent.TimeUnit;

public final class WebDavSyncScheduler {
    private WebDavSyncScheduler() {
    }

    public static void applySettings() {
        WorkManager workManager = WorkManager.getInstance(App.getInstance());
        if (!Hawk.get(HawkConfig.WEBDAV_SYNC_ENABLED, false)) {
            workManager.cancelUniqueWork(WebDavSyncWorker.WORK_NAME_PERIODIC);
            return;
        }
        long intervalMinutes = intervalMinutes(Hawk.get(HawkConfig.WEBDAV_SYNC_INTERVAL, 0));
        if (intervalMinutes <= 0) {
            workManager.cancelUniqueWork(WebDavSyncWorker.WORK_NAME_PERIODIC);
            return;
        }
        Constraints constraints = networkConstraints();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                WebDavSyncWorker.class, intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();
        workManager.enqueueUniquePeriodicWork(WebDavSyncWorker.WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.REPLACE, request);
    }

    public static void syncNow() {
        if (!Hawk.get(HawkConfig.WEBDAV_SYNC_ENABLED, false)) return;
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(WebDavSyncWorker.class)
                .setConstraints(networkConstraints())
                .build();
        WorkManager.getInstance(App.getInstance()).enqueueUniqueWork(
                WebDavSyncWorker.WORK_NAME_ONE_SHOT, ExistingWorkPolicy.REPLACE, request);
    }

    /** Debounced sync after local history/progress writes (e.g. leaving playback). */
    public static void requestDebouncedSync() {
        if (!Hawk.get(HawkConfig.WEBDAV_SYNC_ENABLED, false)) return;
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(WebDavSyncWorker.class)
                .setConstraints(networkConstraints())
                .setInitialDelay(30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(App.getInstance()).enqueueUniqueWork(
                WebDavSyncWorker.WORK_NAME_DEBOUNCED, ExistingWorkPolicy.REPLACE, request);
    }

    public static void onAppStart() {
        applySettings();
        if (Hawk.get(HawkConfig.WEBDAV_SYNC_ENABLED, false)) {
            syncNow();
        }
    }

    public static String[] intervalLabels() {
        return new String[]{"无定时", "每 15 分钟", "每小时", "每 6 小时", "每天"};
    }

    public static long intervalMinutes(int index) {
        switch (index) {
            case 1:
                return 15;
            case 2:
                return 60;
            case 3:
                return 6 * 60;
            case 4:
                return 24 * 60;
            default:
                return 0;
        }
    }

    private static Constraints networkConstraints() {
        boolean wifiOnly = Hawk.get(HawkConfig.WEBDAV_WIFI_ONLY, true);
        return new Constraints.Builder()
                .setRequiredNetworkType(wifiOnly ? NetworkType.UNMETERED : NetworkType.CONNECTED)
                .build();
    }
}
