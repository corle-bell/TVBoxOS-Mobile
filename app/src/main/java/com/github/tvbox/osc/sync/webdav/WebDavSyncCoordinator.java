package com.github.tvbox.osc.sync.webdav;

import android.util.Log;

import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.util.HawkConfig;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import okhttp3.HttpUrl;

public final class WebDavSyncCoordinator {
    private static final String TAG = "WebDavSync";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "tvbox-webdav-sync");
        thread.setDaemon(true);
        return thread;
    });

    private final Gson gson = new Gson();
    private final WebDavCredentialStore credentialStore;

    public WebDavSyncCoordinator() {
        this(new WebDavCredentialStore());
    }

    WebDavSyncCoordinator(WebDavCredentialStore credentialStore) {
        this.credentialStore = credentialStore;
    }

    public Future<SyncResult> sync() {
        return EXECUTOR.submit(this::syncOnWorker);
    }

    public Future<Void> testConnection() {
        return EXECUTOR.submit(() -> {
            testConnectionOnWorker();
            return null;
        });
    }

    private void testConnectionOnWorker() throws IOException, GeneralSecurityException {
        String endpoint = requireEndpoint();
        String syncId = requireSyncId();
        WebDavCredentialStore.Credentials credentials = requireCredentials();
        WebDavClient client = new WebDavClient(endpoint, credentials.username, credentials.password);
        client.testConnection(syncId);
        cacheDeviceCount(client, syncId);
    }

    private SyncResult syncOnWorker() throws IOException, GeneralSecurityException {
        if (!Hawk.get(HawkConfig.WEBDAV_SYNC_ENABLED, false)) {
            throw new IllegalStateException("WebDAV sync is disabled");
        }
        String endpoint = requireEndpoint();
        String syncId = requireSyncId();
        WebDavCredentialStore.Credentials credentials = requireCredentials();

        try {
            String deviceId = WebDavDeviceId.get();
            WebDavClient client = new WebDavClient(endpoint, credentials.username, credentials.password);
            client.ensureCollections(syncId);

            long now = System.currentTimeMillis();
            List<WebDavSnapshotDto> snapshots = new ArrayList<>();
            WebDavSnapshotDto local = RoomDataManger.exportSyncSnapshot(deviceId, now);
            local.syncId = syncId;
            snapshots.add(local);

            List<HttpUrl> remoteUrls = client.listDeviceSnapshots(syncId);
            Log.d(TAG, "sync remoteUrls=" + remoteUrls.size() + " localHistories="
                    + local.histories.size());
            int remoteCount = 0;
            for (HttpUrl url : remoteUrls) {
                String json = client.get(url);
                try {
                    WebDavSnapshotDto snapshot = gson.fromJson(json, WebDavSnapshotDto.class);
                    if (snapshot != null) {
                        snapshots.add(snapshot);
                        remoteCount++;
                        Log.d(TAG, "remote snapshot deviceId=" + snapshot.deviceId
                                + " histories=" + (snapshot.histories == null
                                ? 0 : snapshot.histories.size()));
                    }
                } catch (JsonParseException e) {
                    throw new IOException("Invalid WebDAV device snapshot", e);
                }
            }

            WebDavSnapshotDto merged = SyncMergeEngine.merge(deviceId, snapshots, now);
            merged.syncId = syncId;
            Log.d(TAG, "merged histories=" + merged.histories.size()
                    + " episodeProgress=" + merged.episodeProgress.size());
            RoomDataManger.importSyncSnapshot(merged);
            client.putSnapshot(syncId, deviceId, gson.toJson(merged));

            int deviceCount = client.countDeviceSnapshots(syncId);
            Hawk.put(HawkConfig.WEBDAV_SPACE_DEVICE_COUNT, deviceCount);
            Hawk.put(HawkConfig.WEBDAV_SPACE_DEVICE_COUNT_AT, System.currentTimeMillis());
            Hawk.put(HawkConfig.WEBDAV_LAST_SYNC_AT, now);
            Hawk.put(HawkConfig.WEBDAV_LAST_SYNC_ERROR, "");
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH));
            return new SyncResult(remoteCount, deviceCount, merged.histories.size(),
                    merged.episodeProgress.size(), now);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Hawk.put(HawkConfig.WEBDAV_LAST_SYNC_ERROR, message);
            if (e instanceof IOException) throw (IOException) e;
            if (e instanceof GeneralSecurityException) throw (GeneralSecurityException) e;
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new IOException(message, e);
        }
    }

    private void cacheDeviceCount(WebDavClient client, String syncId) throws IOException {
        int deviceCount = client.countDeviceSnapshots(syncId);
        Hawk.put(HawkConfig.WEBDAV_SPACE_DEVICE_COUNT, deviceCount);
        Hawk.put(HawkConfig.WEBDAV_SPACE_DEVICE_COUNT_AT, System.currentTimeMillis());
    }

    private String requireEndpoint() {
        String endpoint = Hawk.get(HawkConfig.WEBDAV_SYNC_URL, "");
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new IllegalStateException("WebDAV URL is not configured");
        }
        return endpoint.trim();
    }

    private String requireSyncId() {
        String syncId = Hawk.get(HawkConfig.WEBDAV_SYNC_ID, "");
        if (!WebDavSyncId.isValid(syncId)) {
            syncId = WebDavSyncId.get();
        }
        return WebDavSyncId.requireValid(syncId);
    }

    private WebDavCredentialStore.Credentials requireCredentials() throws GeneralSecurityException {
        WebDavCredentialStore.Credentials credentials = credentialStore.load();
        if (credentials == null) {
            throw new IllegalStateException("WebDAV credentials are not configured");
        }
        return credentials;
    }

    public static final class SyncResult {
        public final int remoteDeviceCount;
        public final int deviceCount;
        public final int historyCount;
        public final int episodeProgressCount;
        public final long syncedAt;

        SyncResult(int remoteDeviceCount, int deviceCount, int historyCount,
                   int episodeProgressCount, long syncedAt) {
            this.remoteDeviceCount = remoteDeviceCount;
            this.deviceCount = deviceCount;
            this.historyCount = historyCount;
            this.episodeProgressCount = episodeProgressCount;
            this.syncedAt = syncedAt;
        }
    }
}
