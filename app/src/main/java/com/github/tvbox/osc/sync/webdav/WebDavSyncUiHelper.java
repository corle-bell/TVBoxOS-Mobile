package com.github.tvbox.osc.sync.webdav;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;

import com.blankj.utilcode.util.ToastUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WebDavSyncUiHelper {
    private static final long DEVICE_COUNT_TTL_MS = 5 * 60 * 1000L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "tvbox-webdav-ui");
        thread.setDaemon(true);
        return thread;
    });

    public interface SyncCallback {
        void onStart();

        void onSuccess(WebDavSyncCoordinator.SyncResult result);

        void onError(String message);
    }

    public interface CountCallback {
        void onResult(int count);
    }

    private WebDavSyncUiHelper() {
    }

    public static String validationError() {
        if (!Hawk.get(HawkConfig.WEBDAV_SYNC_ENABLED, false)) {
            return "请先启用 WebDAV 同步";
        }
        String url = Hawk.get(HawkConfig.WEBDAV_SYNC_URL, "");
        if (TextUtils.isEmpty(url)) return "请填写 WebDAV 地址";
        String syncId = Hawk.get(HawkConfig.WEBDAV_SYNC_ID, "");
        if (!WebDavSyncId.isValid(syncId)) return "请设置有效的同步空间 ID";
        try {
            if (new WebDavCredentialStore().load() == null) return "请填写 WebDAV 凭据";
        } catch (Exception e) {
            return "凭据读取失败";
        }
        return null;
    }

    public static void syncNow(Context context, SyncCallback callback) {
        String error = validationError();
        if (error != null) {
            ToastUtils.showShort(error);
            if (callback != null) callback.onError(error);
            return;
        }
        if (callback != null) callback.onStart();
        IO.execute(() -> {
            try {
                WebDavSyncCoordinator.SyncResult result =
                        new WebDavSyncCoordinator().sync().get();
                if (callback != null) {
                    callback.onSuccess(result);
                }
            } catch (Exception e) {
                String message = shortError(e);
                if (callback != null) callback.onError(message);
            }
        });
    }

    public static void refreshDeviceCount(boolean force, CountCallback callback) {
        IO.execute(() -> {
            int cached = Hawk.get(HawkConfig.WEBDAV_SPACE_DEVICE_COUNT, 0);
            long cachedAt = Hawk.get(HawkConfig.WEBDAV_SPACE_DEVICE_COUNT_AT, 0L);
            if (!force && cachedAt > 0
                    && System.currentTimeMillis() - cachedAt < DEVICE_COUNT_TTL_MS) {
                if (callback != null) callback.onResult(cached);
                return;
            }
            try {
                String url = Hawk.get(HawkConfig.WEBDAV_SYNC_URL, "");
                String syncId = WebDavSyncId.get();
                WebDavCredentialStore.Credentials credentials = new WebDavCredentialStore().load();
                if (TextUtils.isEmpty(url) || credentials == null) {
                    if (callback != null) callback.onResult(cached);
                    return;
                }
                WebDavClient client = new WebDavClient(url.trim(),
                        credentials.username, credentials.password);
                int count = client.countDeviceSnapshots(syncId);
                Hawk.put(HawkConfig.WEBDAV_SPACE_DEVICE_COUNT, count);
                Hawk.put(HawkConfig.WEBDAV_SPACE_DEVICE_COUNT_AT, System.currentTimeMillis());
                if (callback != null) callback.onResult(count);
            } catch (Exception e) {
                if (callback != null) callback.onResult(cached);
            }
        });
    }

    public static String formatStatusSummary() {
        if (!Hawk.get(HawkConfig.WEBDAV_SYNC_ENABLED, false)) return "未启用";
        String error = Hawk.get(HawkConfig.WEBDAV_LAST_SYNC_ERROR, "");
        if (!TextUtils.isEmpty(error)) return "失败";
        String syncId = Hawk.get(HawkConfig.WEBDAV_SYNC_ID, "");
        int devices = Hawk.get(HawkConfig.WEBDAV_SPACE_DEVICE_COUNT, 0);
        long last = Hawk.get(HawkConfig.WEBDAV_LAST_SYNC_AT, 0L);
        StringBuilder sb = new StringBuilder("已启用");
        if (WebDavSyncId.isValid(syncId)) {
            sb.append(" · ").append(WebDavSyncId.shortLabel(syncId));
        }
        if (devices > 0) sb.append(" · ").append(devices).append(" 台设备");
        if (last > 0) {
            sb.append(" · ").append(new SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(new Date(last)));
        } else {
            sb.append(" · 待同步");
        }
        return sb.toString();
    }

    public static void copyInvite(Context context, String url, String syncId) {
        String payload = WebDavSpaceInvite.encode(url, syncId);
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("tvbox-sync-invite", payload));
            ToastUtils.showShort("邀请信息已复制");
        }
    }

    public static String shortError(Exception e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        String message = cause.getMessage();
        if (TextUtils.isEmpty(message)) message = cause.getClass().getSimpleName();
        if (message.length() > 80) message = message.substring(0, 80) + "…";
        return message;
    }
}
