package com.github.tvbox.osc.sync.webdav;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure Java last-writer-wins merge logic. */
public final class SyncMergeEngine {
    private SyncMergeEngine() {
    }

    public static WebDavSnapshotDto merge(String localDeviceId, List<WebDavSnapshotDto> snapshots,
                                           long generatedAt) {
        Map<String, HistorySyncDto> histories = new LinkedHashMap<>();
        Map<String, EpisodeProgressDto> progress = new LinkedHashMap<>();
        if (snapshots != null) {
            for (WebDavSnapshotDto snapshot : snapshots) {
                if (snapshot == null || snapshot.version != WebDavSnapshotDto.CURRENT_VERSION) {
                    continue;
                }
                if (snapshot.histories != null) {
                    for (HistorySyncDto item : snapshot.histories) {
                        if (!valid(item)) continue;
                        HistorySyncDto current = histories.get(item.stableKey());
                        if (isNewer(item.updatedAt, item.deviceId,
                                current == null ? Long.MIN_VALUE : current.updatedAt,
                                current == null ? null : current.deviceId)) {
                            histories.put(item.stableKey(), item);
                        }
                    }
                }
                if (snapshot.episodeProgress != null) {
                    for (EpisodeProgressDto item : snapshot.episodeProgress) {
                        if (!valid(item)) continue;
                        EpisodeProgressDto current = progress.get(item.stableKey());
                        if (isNewer(item.updatedAt, item.deviceId,
                                current == null ? Long.MIN_VALUE : current.updatedAt,
                                current == null ? null : current.deviceId)) {
                            progress.put(item.stableKey(), item);
                        }
                    }
                }
            }
        }
        WebDavSnapshotDto result = new WebDavSnapshotDto(localDeviceId, generatedAt);
        result.histories = new ArrayList<>(histories.values());
        result.episodeProgress = new ArrayList<>(progress.values());
        return result;
    }

    public static boolean isNewer(long incomingTime, String incomingDevice,
                                  long currentTime, String currentDevice) {
        if (incomingTime != currentTime) return incomingTime > currentTime;
        return safe(incomingDevice).compareTo(safe(currentDevice)) > 0;
    }

    private static boolean valid(HistorySyncDto item) {
        return item != null && notEmpty(item.sourceKey) && notEmpty(item.vodId)
                && notEmpty(item.deviceId);
    }

    private static boolean valid(EpisodeProgressDto item) {
        return item != null && notEmpty(item.originalKey) && notEmpty(item.cacheKey)
                && notEmpty(item.deviceId);
    }

    private static boolean notEmpty(String value) {
        return value != null && !value.isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
