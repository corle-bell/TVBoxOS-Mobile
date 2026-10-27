package com.github.tvbox.osc.download;

import java.util.HashMap;

public class DownloadTask {
    public static final int WAITING = 0;
    public static final int RUNNING = 1;
    public static final int PAUSED = 2;
    public static final int DONE = 3;
    public static final int FAILED = 4;

    public static final int TYPE_FILE = 0;
    public static final int TYPE_HLS = 1;

    public String id;
    public String title;
    public String url;
    public HashMap<String, String> headers;
    public int type = TYPE_FILE;
    public int status = WAITING;
    public int progress;
    public long totalBytes;
    public long downloadedBytes;
    public int segmentTotal;
    public int segmentDone;
    public String filePath;
    public String workDir;
    public String error;
    public long createdAt;
    public String seriesKey;
    public String seriesName;
    public String episodeName;
    public int episodeIndex = -1;
    public String sourceKey;
    public String playFlag;
    public String episodeUrl;
    public String seriesPic;

    public DownloadTask() {
    }

    public String groupKey() {
        return seriesKey == null || seriesKey.isEmpty() ? "task:" + id : seriesKey;
    }

    public String groupName() {
        return seriesName == null || seriesName.isEmpty() ? title : seriesName;
    }

    public String episodeLabel() {
        return episodeName == null || episodeName.isEmpty() ? title : episodeName;
    }

    public DownloadTask copy() {
        DownloadTask copy = new DownloadTask();
        copy.id = id;
        copy.title = title;
        copy.url = url;
        copy.headers = headers == null ? null : new HashMap<>(headers);
        copy.type = type;
        copy.status = status;
        copy.progress = progress;
        copy.totalBytes = totalBytes;
        copy.downloadedBytes = downloadedBytes;
        copy.segmentTotal = segmentTotal;
        copy.segmentDone = segmentDone;
        copy.filePath = filePath;
        copy.workDir = workDir;
        copy.error = error;
        copy.createdAt = createdAt;
        copy.seriesKey = seriesKey;
        copy.seriesName = seriesName;
        copy.episodeName = episodeName;
        copy.episodeIndex = episodeIndex;
        copy.sourceKey = sourceKey;
        copy.playFlag = playFlag;
        copy.episodeUrl = episodeUrl;
        copy.seriesPic = seriesPic;
        return copy;
    }
}
