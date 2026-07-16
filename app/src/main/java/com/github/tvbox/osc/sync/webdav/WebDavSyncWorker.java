package com.github.tvbox.osc.sync.webdav;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

public class WebDavSyncWorker extends Worker {
    public static final String WORK_NAME_PERIODIC = "webdav_sync_periodic";
    public static final String WORK_NAME_ONE_SHOT = "webdav_sync_oneshot";
    public static final String WORK_NAME_DEBOUNCED = "webdav_sync_debounced";

    public WebDavSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (!Hawk.get(HawkConfig.WEBDAV_SYNC_ENABLED, false)) {
            return Result.success();
        }
        try {
            new WebDavSyncCoordinator().sync().get();
            return Result.success();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Hawk.put(HawkConfig.WEBDAV_LAST_SYNC_ERROR, message);
            return Result.retry();
        }
    }
}
