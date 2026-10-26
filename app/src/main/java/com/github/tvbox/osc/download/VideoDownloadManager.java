package com.github.tvbox.osc.download;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.event.RefreshEvent;
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

public final class VideoDownloadManager {
    public static final int ADDED = 1;
    public static final int DUPLICATE = 2;
    public static final int RESUMED = 3;

    private static final String TAG = "VideoDownload";
    private static final String HAWK_KEY = "download_tasks";
    private static final int MAX_CONCURRENT = 2;

    private static volatile VideoDownloadManager instance;

    private final List<DownloadTask> tasks = new ArrayList<>();
    private final java.util.Set<String> runningIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();
    private long lastNotifyAt;

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

    public int enqueue(String title, String url, Map<String, String> headers) {
        return enqueue(title, url, headers, null, null, null, -1);
    }

    public int enqueue(String title, String url, Map<String, String> headers,
                       String seriesKey, String seriesName, String episodeName, int episodeIndex) {
        if (url != null) url = url.trim();
        synchronized (tasks) {
            for (DownloadTask task : tasks) {
                if (task.url != null && task.url.equals(url)) {
                    if ((task.seriesKey == null || task.seriesKey.isEmpty()) && seriesKey != null) {
                        task.seriesKey = seriesKey;
                        task.seriesName = seriesName;
                        task.episodeName = episodeName;
                        task.episodeIndex = episodeIndex;
                        saveLocked();
                    }
                    if (task.status == DownloadTask.FAILED || task.status == DownloadTask.PAUSED) {
                        task.status = DownloadTask.WAITING;
                        task.error = null;
                        saveLocked();
                        startService();
                        return RESUMED;
                    }
                    return DUPLICATE;
                }
            }
            DownloadTask task = new DownloadTask();
            task.id = UUID.randomUUID().toString().replace("-", "");
            task.title = title == null || title.trim().isEmpty() ? "video" : title.trim();
            task.url = url;
            task.headers = headers == null ? new HashMap<String, String>() : new HashMap<>(headers);
            task.type = DownloadPaths.looksLikeM3u8(url) ? DownloadTask.TYPE_HLS : DownloadTask.TYPE_FILE;
            task.status = DownloadTask.WAITING;
            task.createdAt = System.currentTimeMillis();
            task.seriesKey = seriesKey;
            task.seriesName = seriesName;
            task.episodeName = episodeName;
            task.episodeIndex = episodeIndex;
            tasks.add(0, task);
            saveLocked();
        }
        notifyImmediate();
        startService();
        return ADDED;
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
        DownloadHttp.cancel(id);
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
        synchronized (tasks) {
            removed = findLocked(id);
            if (removed == null) return;
            tasks.remove(removed);
            saveLocked();
        }
        DownloadHttp.cancel(id);
        deleteFiles(removed);
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
            DownloadHttp.cancel(task.id);
            deleteFiles(task);
        }
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
            DownloadHttp.cancel(task.id);
            deleteFiles(task);
        }
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

    private void load() {
        try {
            String json = Hawk.get(HAWK_KEY, "");
            if (json == null || json.isEmpty()) return;
            Type type = new TypeToken<ArrayList<DownloadTask>>() {
            }.getType();
            List<DownloadTask> saved = gson.fromJson(json, type);
            if (saved != null) tasks.addAll(saved);
        } catch (Exception e) {
            Log.e(TAG, "load downloads failed", e);
        }
    }

    private void saveLocked() {
        try {
            Hawk.put(HAWK_KEY, gson.toJson(tasks));
        } catch (Exception e) {
            Log.e(TAG, "save downloads failed", e);
        }
    }

    private void notifyThrottled() {
        long now = System.currentTimeMillis();
        synchronized (tasks) {
            if (now - lastNotifyAt < 1000) return;
            lastNotifyAt = now;
            saveLocked();
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
