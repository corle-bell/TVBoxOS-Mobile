package com.github.tvbox.osc.download;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.util.FileUtils;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public final class VideoDownloadManager {
    public static final int ADDED = 1;
    public static final int DUPLICATE = 2;
    public static final int RESUMED = 3;

    private static final String TAG = "VideoDownload";
    private static final String HAWK_KEY = "download_tasks";
    private static final String STORE_FILE = "downloads.json";
    private static final long PROGRESS_SAVE_INTERVAL_MS = 3000;
    private static final int MAX_CONCURRENT = 2;

    private static volatile VideoDownloadManager instance;

    private final List<DownloadTask> tasks = new ArrayList<>();
    private final java.util.Set<String> runningIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService saver = Executors.newSingleThreadExecutor(r -> new Thread(r, "download-store"));
    private final AtomicReference<String> pendingJson = new AtomicReference<>();
    private final Gson gson = new Gson();
    private long lastNotifyAt;
    private long lastSavedAt;

    private VideoDownloadManager() {
        load();
    }

    public static VideoDownloadManager get() {
        if (instance == null) {
            synchronized (VideoDownloadManager.class) {
                if (instance == null) {
                    instance = new VideoDownloadManager();
                }
            }
        }
        return instance;
    }

    public void resetInterrupted() {
        synchronized (tasks) {
            boolean changed = false;
            for (DownloadTask task : tasks) {
                if (task.status == DownloadTask.RUNNING) {
                    task.status = DownloadTask.PAUSED;
                    changed = true;
                }
            }
            if (changed) saveLocked();
        }
    }

    public static final class EpisodeRequest {
        public String title;
        public String url;
        public Map<String, String> headers;
        public String seriesKey;
        public String seriesName;
        public String seriesPic;
        public String episodeName;
        public String episodeUrl;
        public int episodeIndex = -1;
        public String sourceKey;
        public String playFlag;
    }

    public static final class EnqueueResult {
        public int added;
        public int resumed;
        public int skipped;
    }

    public int enqueue(String title, String url, Map<String, String> headers) {
        EpisodeRequest request = new EpisodeRequest();
        request.title = title;
        request.url = url;
        request.headers = headers;
        EnqueueResult result = enqueueEpisodes(java.util.Collections.singletonList(request));
        if (result.added > 0) return ADDED;
        return result.resumed > 0 ? RESUMED : DUPLICATE;
    }

    public EnqueueResult enqueueEpisodes(List<EpisodeRequest> requests) {
        EnqueueResult result = new EnqueueResult();
        if (requests == null || requests.isEmpty()) return result;
        synchronized (tasks) {
            for (EpisodeRequest request : requests) {
                int r = enqueueLocked(request);
                if (r == ADDED) {
                    result.added++;
                } else if (r == RESUMED) {
                    result.resumed++;
                } else {
                    result.skipped++;
                }
            }
            saveLocked();
        }
        for (EpisodeRequest request : requests) {
            PosterStore.ensure(request.seriesKey, request.seriesPic);
        }
        notifyImmediate();
        if (result.added > 0 || result.resumed > 0) startService();
        return result;
    }

    public File posterFile(String seriesKey) {
        return PosterStore.existing(seriesKey);
    }

    public void ensurePoster(String seriesKey, String pic) {
        PosterStore.ensure(seriesKey, pic);
    }

    private int enqueueLocked(EpisodeRequest request) {
        String url = request.url == null ? "" : request.url.trim();
        String episodeUrl = request.episodeUrl == null ? null : request.episodeUrl.trim();
        DownloadTask existing = null;
        for (DownloadTask task : tasks) {
            if (episodeUrl != null && !episodeUrl.isEmpty() && request.seriesKey != null
                    && request.seriesKey.equals(task.seriesKey) && episodeUrl.equals(task.episodeUrl)) {
                existing = task;
                break;
            }
            if (!url.isEmpty() && url.equals(task.url)) {
                existing = task;
                break;
            }
        }
        if (existing != null) {
            fillMissing(existing, request, episodeUrl);
            if ((existing.url == null || existing.url.isEmpty()) && !url.isEmpty()) {
                existing.url = url;
                existing.headers = request.headers == null ? new HashMap<String, String>() : new HashMap<>(request.headers);
                existing.type = DownloadPaths.looksLikeM3u8(url) ? DownloadTask.TYPE_HLS : DownloadTask.TYPE_FILE;
            }
            if (existing.status == DownloadTask.FAILED || existing.status == DownloadTask.PAUSED) {
                existing.status = DownloadTask.WAITING;
                existing.error = null;
                return RESUMED;
            }
            return DUPLICATE;
        }
        DownloadTask task = new DownloadTask();
        task.id = UUID.randomUUID().toString().replace("-", "");
        String title = request.title;
        task.title = title == null || title.trim().isEmpty() ? "video" : title.trim();
        task.url = url;
        task.headers = request.headers == null ? new HashMap<String, String>() : new HashMap<>(request.headers);
        task.type = DownloadPaths.looksLikeM3u8(url) ? DownloadTask.TYPE_HLS : DownloadTask.TYPE_FILE;
        task.status = DownloadTask.WAITING;
        task.createdAt = System.currentTimeMillis();
        task.seriesKey = request.seriesKey;
        task.seriesName = request.seriesName;
        task.seriesPic = request.seriesPic;
        task.episodeName = request.episodeName;
        task.episodeIndex = request.episodeIndex;
        task.episodeUrl = episodeUrl;
        task.sourceKey = request.sourceKey;
        task.playFlag = request.playFlag;
        tasks.add(0, task);
        return ADDED;
    }

    private static void fillMissing(DownloadTask task, EpisodeRequest request, String episodeUrl) {
        if ((task.seriesKey == null || task.seriesKey.isEmpty()) && request.seriesKey != null) {
            task.seriesKey = request.seriesKey;
            task.seriesName = request.seriesName;
            task.episodeName = request.episodeName;
            task.episodeIndex = request.episodeIndex;
        }
        if (task.episodeUrl == null && episodeUrl != null) task.episodeUrl = episodeUrl;
        if (task.seriesPic == null && request.seriesPic != null) task.seriesPic = request.seriesPic;
        if (task.sourceKey == null && request.sourceKey != null) task.sourceKey = request.sourceKey;
        if (task.playFlag == null && request.playFlag != null) task.playFlag = request.playFlag;
    }

    public Map<String, DownloadTask> episodeStates(String seriesKey) {
        Map<String, DownloadTask> states = new HashMap<>();
        if (seriesKey == null) return states;
        for (DownloadTask task : snapshot()) {
            if (!seriesKey.equals(task.seriesKey)) continue;
            if (task.episodeUrl != null && !task.episodeUrl.isEmpty()) states.put(urlStateKey(task.episodeUrl), task);
            if (task.episodeName != null && !task.episodeName.isEmpty()) {
                String key = nameStateKey(task.episodeName);
                DownloadTask old = states.get(key);
                if (old == null || task.status == DownloadTask.DONE) states.put(key, task);
            }
        }
        return states;
    }

    public static String urlStateKey(String episodeUrl) {
        return "url:" + episodeUrl.trim();
    }

    public static String nameStateKey(String episodeName) {
        return "name:" + episodeName;
    }

    public void pause(String id) {
        synchronized (tasks) {
            DownloadTask task = findLocked(id);
            if (task == null) return;
            if (task.status == DownloadTask.RUNNING || task.status == DownloadTask.WAITING) {
                task.status = DownloadTask.PAUSED;
                saveLocked();
            }
        }
        cancelWork(id);
        notifyImmediate();
    }

    public void resume(String id) {
        synchronized (tasks) {
            DownloadTask task = findLocked(id);
            if (task == null) return;
            if (task.status == DownloadTask.PAUSED || task.status == DownloadTask.FAILED) {
                task.status = DownloadTask.WAITING;
                task.error = null;
                saveLocked();
            }
        }
        notifyImmediate();
        startService();
    }

    public void delete(String id) {
        DownloadTask removed;
        boolean lastOfGroup = true;
        synchronized (tasks) {
            removed = findLocked(id);
            if (removed == null) return;
            tasks.remove(removed);
            String groupKey = removed.groupKey();
            for (DownloadTask task : tasks) {
                if (task.groupKey().equals(groupKey)) {
                    lastOfGroup = false;
                    break;
                }
            }
            saveLocked();
        }
        cancelWork(id);
        deleteFiles(removed);
        if (lastOfGroup) PosterStore.delete(removed.seriesKey);
        notifyImmediate();
        if (!hasActive()) DownloadService.stopIfIdle();
    }

    public void deleteGroup(String groupKey) {
        List<DownloadTask> removed = new ArrayList<>();
        synchronized (tasks) {
            for (DownloadTask task : tasks) {
                if (task.groupKey().equals(groupKey)) removed.add(task);
            }
            if (removed.isEmpty()) return;
            tasks.removeAll(removed);
            saveLocked();
        }
        for (DownloadTask task : removed) {
            cancelWork(task.id);
            deleteFiles(task);
        }
        PosterStore.delete(groupKey);
        notifyImmediate();
        if (!hasActive()) DownloadService.stopIfIdle();
    }

    public List<DownloadTask> groupSnapshot(String groupKey) {
        List<DownloadTask> result = new ArrayList<>();
        for (DownloadTask task : snapshot()) {
            if (task.groupKey().equals(groupKey)) result.add(task);
        }
        java.util.Collections.sort(result, new java.util.Comparator<DownloadTask>() {
            @Override
            public int compare(DownloadTask a, DownloadTask b) {
                if (a.episodeIndex != b.episodeIndex) return a.episodeIndex < b.episodeIndex ? -1 : 1;
                return a.createdAt < b.createdAt ? -1 : (a.createdAt == b.createdAt ? 0 : 1);
            }
        });
        return result;
    }

    public void clearAll() {
        List<DownloadTask> removed;
        synchronized (tasks) {
            removed = new ArrayList<>(tasks);
            tasks.clear();
            saveLocked();
        }
        for (DownloadTask task : removed) {
            cancelWork(task.id);
            deleteFiles(task);
        }
        PosterStore.deleteAll();
        notifyImmediate();
        DownloadService.stopIfIdle();
    }

    public List<DownloadTask> snapshot() {
        synchronized (tasks) {
            List<DownloadTask> copy = new ArrayList<>();
            for (DownloadTask task : tasks) {
                copy.add(task.copy());
            }
            return copy;
        }
    }

    public boolean hasActive() {
        synchronized (tasks) {
            for (DownloadTask task : tasks) {
                if (task.status == DownloadTask.WAITING || task.status == DownloadTask.RUNNING) {
                    return true;
                }
            }
            return false;
        }
    }

    public boolean shouldStop(String id) {
        synchronized (tasks) {
            DownloadTask task = findLocked(id);
            return task == null || task.status != DownloadTask.RUNNING;
        }
    }

    void pump() {
        List<String> startIds = new ArrayList<>();
        synchronized (tasks) {
            int active = 0;
            for (DownloadTask task : tasks) {
                if (task.status == DownloadTask.RUNNING || runningIds.contains(task.id)) active++;
            }
            for (DownloadTask task : tasks) {
                if (active >= MAX_CONCURRENT) break;
                if (task.status == DownloadTask.WAITING && runningIds.add(task.id)) {
                    task.status = DownloadTask.RUNNING;
                    active++;
                    startIds.add(task.id);
                }
            }
            if (!startIds.isEmpty()) saveLocked();
        }
        for (String id : startIds) {
            final String taskId = id;
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    runTask(taskId);
                }
            });
        }
        if (startIds.isEmpty() && !hasActive()) {
            DownloadService.stopIfIdle();
        } else if (!startIds.isEmpty()) {
            notifyImmediate();
        }
    }

    void updateFileProgress(DownloadTask task, long downloaded, long total) {
        task.downloadedBytes = downloaded;
        task.totalBytes = total;
        if (total > 0) {
            task.progress = (int) Math.min(99, downloaded * 100 / total);
        }
        notifyThrottled();
    }

    void updateSegmentProgress(DownloadTask task, int done, int total) {
        task.segmentDone = done;
        task.segmentTotal = total;
        task.downloadedBytes = done;
        task.totalBytes = total;
        if (total > 0) {
            task.progress = (int) Math.min(99, done * 100L / total);
        }
        notifyThrottled();
    }

    void save() {
        synchronized (tasks) {
            saveLocked();
        }
    }

    private void runTask(String id) {
        DownloadTask task = null;
        try {
            synchronized (tasks) {
                task = findLocked(id);
            }
            if (task == null || task.status != DownloadTask.RUNNING) return;
            if (task.url == null || task.url.isEmpty()) {
                EpisodeUrlResolver.Result resolved = new EpisodeUrlResolver(this, id).resolve(task);
                synchronized (tasks) {
                    if (findLocked(id) == null || task.status != DownloadTask.RUNNING) return;
                    task.url = resolved.url;
                    task.headers = resolved.headers;
                    task.type = DownloadPaths.looksLikeM3u8(resolved.url) ? DownloadTask.TYPE_HLS : DownloadTask.TYPE_FILE;
                    saveLocked();
                }
                notifyImmediate();
            }
            if (task.type == DownloadTask.TYPE_HLS || DownloadPaths.looksLikeM3u8(task.url)) {
                task.type = DownloadTask.TYPE_HLS;
                new HlsDownloader(this).download(task);
            } else {
                new FileDownloader(this).download(task);
            }
            boolean finished = false;
            synchronized (tasks) {
                DownloadTask current = findLocked(id);
                if (current != null && current.status == DownloadTask.RUNNING) {
                    File file = current.filePath == null ? null : new File(current.filePath);
                    if (file == null || !file.exists() || file.length() == 0) {
                        current.status = DownloadTask.FAILED;
                        current.error = "下载内容为空";
                    } else if (DownloadMedia.isTextFile(file) || DownloadMedia.isTinyJunk(file)) {
                        file.delete();
                        current.status = DownloadTask.FAILED;
                        current.error = DownloadMedia.NOT_VIDEO;
                        current.downloadedBytes = 0;
                    } else {
                        current.status = DownloadTask.DONE;
                        current.progress = 100;
                        current.error = null;
                        current.downloadedBytes = file.length();
                        current.totalBytes = file.length();
                        finished = true;
                    }
                    saveLocked();
                }
            }
            if (finished) {
                final String title = task.title;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        com.blankj.utilcode.util.ToastUtils.showShort("下载完成: " + title);
                    }
                });
            }
        } catch (DownloadStoppedException ignored) {
        } catch (Exception e) {
            Log.e(TAG, "download failed", e);
            final String message = messageOf(e);
            synchronized (tasks) {
                DownloadTask current = findLocked(id);
                if (current != null && current.status == DownloadTask.RUNNING) {
                    current.status = DownloadTask.FAILED;
                    current.error = message;
                    saveLocked();
                }
            }
            if (!shouldStop(id)) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        com.blankj.utilcode.util.ToastUtils.showShort("下载失败: " + message);
                    }
                });
            }
        } finally {
            runningIds.remove(id);
            synchronized (tasks) {
                if (findLocked(id) == null && task != null) {
                    deleteFiles(task);
                }
            }
            notifyImmediate();
            pump();
        }
    }

    private static void cancelWork(String id) {
        DownloadHttp.cancel(id);
        DownloadSniffer.cancel(id);
    }

    private void startService() {
        DownloadService.start(App.getInstance());
    }

    private DownloadTask findLocked(String id) {
        for (DownloadTask task : tasks) {
            if (task.id != null && task.id.equals(id)) return task;
        }
        return null;
    }

    private void deleteFiles(DownloadTask task) {
        if (task.filePath != null) DownloadPaths.deleteRecursive(new File(task.filePath));
        if (task.workDir != null) DownloadPaths.deleteRecursive(new File(task.workDir));
        DownloadPaths.deleteRecursive(DownloadPaths.partFile(task));
    }

    private static File storeFile() {
        return new File(App.getInstance().getFilesDir(), STORE_FILE);
    }

    private void load() {
        File store = storeFile();
        boolean migrate = !store.exists();
        try {
            String json = migrate ? Hawk.get(HAWK_KEY, "") : FileUtils.readText(store);
            if (json != null && !json.isEmpty()) {
                Type type = new TypeToken<ArrayList<DownloadTask>>() {
                }.getType();
                List<DownloadTask> saved = gson.fromJson(json, type);
                if (saved != null) tasks.addAll(saved);
            }
        } catch (Exception e) {
            Log.e(TAG, "load downloads failed", e);
            return;
        }
        if (migrate && Hawk.contains(HAWK_KEY)) {
            synchronized (tasks) {
                saveLocked();
            }
            saver.execute(() -> {
                if (storeFile().exists()) Hawk.delete(HAWK_KEY);
            });
        }
    }

    /**
     * 序列化在锁内完成，写盘交给单线程；连续多次保存只写最后一份
     */
    private void saveLocked() {
        lastSavedAt = System.currentTimeMillis();
        String json;
        try {
            json = gson.toJson(tasks);
        } catch (Exception e) {
            Log.e(TAG, "save downloads failed", e);
            return;
        }
        if (pendingJson.getAndSet(json) == null) {
            saver.execute(this::flushPending);
        }
    }

    private void flushPending() {
        String json = pendingJson.getAndSet(null);
        if (json == null) return;
        try {
            if (!FileUtils.writeAtomic(storeFile(), json.getBytes("UTF-8"))) {
                Log.e(TAG, "save downloads failed");
            }
        } catch (Exception e) {
            Log.e(TAG, "save downloads failed", e);
        }
    }

    private void notifyThrottled() {
        long now = System.currentTimeMillis();
        synchronized (tasks) {
            if (now - lastNotifyAt < 1000) return;
            lastNotifyAt = now;
            if (now - lastSavedAt >= PROGRESS_SAVE_INTERVAL_MS) saveLocked();
        }
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_DOWNLOAD_UPDATE));
        DownloadService.refresh();
    }

    private void notifyImmediate() {
        lastNotifyAt = System.currentTimeMillis();
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_DOWNLOAD_UPDATE));
        DownloadService.refresh();
    }

    private static String messageOf(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) message = "下载失败";
        if (message.length() > 80) message = message.substring(0, 80);
        return message;
    }
}
