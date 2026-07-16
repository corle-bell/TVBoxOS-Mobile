package com.github.tvbox.osc.cache;

import android.text.TextUtils;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.sync.webdav.EpisodeProgressDto;
import com.github.tvbox.osc.sync.webdav.EpisodeProgressEntity;
import com.github.tvbox.osc.sync.webdav.HistorySyncDto;
import com.github.tvbox.osc.sync.webdav.HistorySyncEntity;
import com.github.tvbox.osc.sync.webdav.SyncMergeEngine;
import com.github.tvbox.osc.sync.webdav.WebDavDeviceId;
import com.github.tvbox.osc.sync.webdav.WebDavSnapshotDto;
import com.github.tvbox.osc.sync.webdav.WebDavSyncDao;
import com.github.tvbox.osc.sync.webdav.WebDavSyncScheduler;
import com.google.gson.ExclusionStrategy;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import com.orhanobut.hawk.Hawk;
import java.util.ArrayList;
import java.util.List;

/**
 * @author pj567
 * @date :2021/1/7
 * @description:
 */
public class RoomDataManger {
    static ExclusionStrategy vodInfoStrategy = new ExclusionStrategy() {
        @Override
        public boolean shouldSkipField(FieldAttributes field) {
            if (field.getDeclaringClass() == VodInfo.class && field.getName().equals("seriesFlags")) {
                return true;
            }
            if (field.getDeclaringClass() == VodInfo.class && field.getName().equals("seriesMap")) {
                return true;
            }
            return false;
        }

        @Override
        public boolean shouldSkipClass(Class<?> clazz) {
            return false;
        }
    };

    private static Gson getVodInfoGson() {
        return new GsonBuilder().addSerializationExclusionStrategy(vodInfoStrategy).create();
    }

    public static void insertVodRecord(String sourceKey, VodInfo vodInfo) {
        final String dataJson = getVodInfoGson().toJson(vodInfo);
        final String deviceId = WebDavDeviceId.get();
        AppDataManager.get().runInTransaction(() -> {
            HistorySyncEntity old = AppDataManager.get().getWebDavSyncDao()
                    .getHistory(sourceKey, vodInfo.id);
            putHistory(sourceKey, vodInfo.id, dataJson,
                    nextTimestamp(old == null ? 0 : old.updatedAt), deviceId, false);
        });
        WebDavSyncScheduler.requestDebouncedSync();
    }

    public static VodInfo getVodInfo(String sourceKey, String vodId) {
        VodRecord record = AppDataManager.get().getVodRecordDao().getVodRecord(sourceKey, vodId);
        try {
            if (record != null && record.dataJson != null && !TextUtils.isEmpty(record.dataJson)) {
                VodInfo vodInfo = getVodInfoGson().fromJson(record.dataJson, new TypeToken<VodInfo>() {
                }.getType());
                if (vodInfo.name == null)
                    return null;
                return vodInfo;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void deleteVodRecord(String sourceKey, VodInfo vodInfo) {
        final String vodId = vodInfo.id;
        final String deviceId = WebDavDeviceId.get();
        AppDataManager.get().runInTransaction(() -> {
            WebDavSyncDao syncDao = AppDataManager.get().getWebDavSyncDao();
            HistorySyncEntity old = syncDao.getHistory(sourceKey, vodId);
            long updatedAt = nextTimestamp(old == null ? 0 : old.updatedAt);
            putHistory(sourceKey, vodId, null, updatedAt, deviceId, true);
        });
        WebDavSyncScheduler.requestDebouncedSync();
    }

    public static List<VodInfo> getAllVodRecord(int limit) {
        int count = AppDataManager.get().getVodRecordDao().getCount();
        Integer index = Hawk.get(HawkConfig.HISTORY_NUM, 0);
        Integer hisNum = HistoryHelper.getHisNum(index);
        if (count > hisNum) {
            List<VodRecord> records = AppDataManager.get().getVodRecordDao().exportAll();
            for (int i = hisNum; i < records.size(); i++) {
                VodRecord record = records.get(i);
                deleteVodRecord(record.sourceKey, record.vodId);
            }
        }
        List<VodRecord> recordList = AppDataManager.get().getVodRecordDao().getAll(limit);
        List<VodInfo> vodInfoList = new ArrayList<>();
        if (recordList != null) {
            for (VodRecord record : recordList) {
                VodInfo info = null;
                try {
                    if (record.dataJson != null && !TextUtils.isEmpty(record.dataJson)) {
                        info = getVodInfoGson().fromJson(record.dataJson, new TypeToken<VodInfo>() {
                        }.getType());
                        info.sourceKey = record.sourceKey;
                        SourceBean sourceBean = ApiConfig.get().getSource(info.sourceKey);
                        if (sourceBean == null || info.name == null)
                            info = null;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (info != null)
                    vodInfoList.add(info);
            }
        }
        return vodInfoList;
    }

    public static void insertVodCollect(String sourceKey, VodInfo vodInfo) {
        VodCollect record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodInfo.id);
        if (record != null) {
            return;
        }
        record = new VodCollect();
        record.sourceKey = sourceKey;
        record.vodId = vodInfo.id;
        record.updateTime = System.currentTimeMillis();
        record.name = vodInfo.name;
        record.pic = vodInfo.pic;
        AppDataManager.get().getVodCollectDao().insert(record);
    }

    public static void deleteVodCollect(int id) {
        AppDataManager.get().getVodCollectDao().delete(id);
    }

    public static void deleteVodCollect(String sourceKey, VodInfo vodInfo) {
        VodCollect record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodInfo.id);
        if (record != null) {
            AppDataManager.get().getVodCollectDao().delete(record);
        }
    }

    public static boolean isVodCollect(String sourceKey, String vodId) {
        VodCollect record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodId);
        return record != null;
    }

    public static List<VodCollect> getAllVodCollect() {
        return AppDataManager.get().getVodCollectDao().getAll();
    }

    /**
     * 删除全部收藏
     */
    public static void deleteVodCollectAll() {
        AppDataManager.get().getVodCollectDao().deleteAll();
    }

    /**
     * 删除全部历史记录
     */
    public static void deleteVodRecordAll() {
        final String deviceId = WebDavDeviceId.get();
        AppDataManager.get().runInTransaction(() -> {
            List<VodRecord> records = AppDataManager.get().getVodRecordDao().exportAll();
            WebDavSyncDao syncDao = AppDataManager.get().getWebDavSyncDao();
            for (VodRecord record : records) {
                HistorySyncEntity old = syncDao.getHistory(record.sourceKey, record.vodId);
                putHistory(record.sourceKey, record.vodId, null,
                        nextTimestamp(old == null ? 0 : old.updatedAt), deviceId, true);
            }
            AppDataManager.get().getVodRecordDao().deleteAll();
        });
        WebDavSyncScheduler.requestDebouncedSync();
    }

    private static void deleteVodRecord(String sourceKey, String vodId) {
        VodInfo value = new VodInfo();
        value.id = vodId;
        deleteVodRecord(sourceKey, value);
    }

    private static void putHistory(String sourceKey, String vodId, String dataJson, long updatedAt,
                                   String deviceId, boolean deleted) {
        HistorySyncEntity metadata = new HistorySyncEntity(
                new HistorySyncDto(sourceKey, vodId, dataJson, updatedAt, deviceId, deleted));
        AppDataManager.get().getWebDavSyncDao().putHistory(metadata);
        AppDataManager.get().getVodRecordDao().deleteByKey(sourceKey, vodId);
        if (!deleted) {
            VodRecord record = new VodRecord();
            record.sourceKey = sourceKey;
            record.vodId = vodId;
            record.dataJson = dataJson;
            record.updateTime = updatedAt;
            AppDataManager.get().getVodRecordDao().insert(record);
        }
    }

    public static WebDavSnapshotDto exportSyncSnapshot(String deviceId, long generatedAt) {
        AppDataManager.get().runInTransaction(() -> {
            WebDavSyncDao syncDao = AppDataManager.get().getWebDavSyncDao();
            for (VodRecord record : AppDataManager.get().getVodRecordDao().exportAll()) {
                HistorySyncEntity metadata = syncDao.getHistory(record.sourceKey, record.vodId);
                if (metadata == null || metadata.deviceId.isEmpty()) {
                    syncDao.putHistory(new HistorySyncEntity(new HistorySyncDto(
                            record.sourceKey, record.vodId, record.dataJson, record.updateTime,
                            deviceId, false)));
                }
            }
        });
        WebDavSnapshotDto snapshot = new WebDavSnapshotDto(deviceId, generatedAt);
        for (HistorySyncEntity entity : AppDataManager.get().getWebDavSyncDao().getAllHistories()) {
            snapshot.histories.add(entity.toDto());
        }
        for (EpisodeProgressEntity entity :
                AppDataManager.get().getWebDavSyncDao().getAllEpisodeProgress()) {
            snapshot.episodeProgress.add(entity.toDto());
        }
        return snapshot;
    }

    public static void importSyncSnapshot(WebDavSnapshotDto snapshot) {
        AppDataManager.get().runInTransaction(() -> {
            if (snapshot.histories != null) {
                for (HistorySyncDto item : snapshot.histories) importHistory(item);
            }
            if (snapshot.episodeProgress != null) {
                for (EpisodeProgressDto item : snapshot.episodeProgress) importEpisodeProgress(item);
            }
        });
    }

    public static void importHistory(HistorySyncDto incoming) {
        if (incoming == null || TextUtils.isEmpty(incoming.sourceKey)
                || TextUtils.isEmpty(incoming.vodId) || TextUtils.isEmpty(incoming.deviceId)) return;
        HistorySyncEntity current = AppDataManager.get().getWebDavSyncDao()
                .getHistory(incoming.sourceKey, incoming.vodId);
        if (current == null || SyncMergeEngine.isNewer(incoming.updatedAt, incoming.deviceId,
                current.updatedAt, current.deviceId)) {
            putHistory(incoming.sourceKey, incoming.vodId, incoming.dataJson, incoming.updatedAt,
                    incoming.deviceId, incoming.deleted);
        }
    }

    /**
     * Read episode progress, preferring the sync table and migrating legacy cache rows on demand.
     */
    public static long getEpisodeProgressMs(String originalKey, String cacheKey) {
        EpisodeProgressEntity entity = AppDataManager.get().getWebDavSyncDao()
                .getEpisodeProgress(originalKey);
        if (entity != null) {
            return entity.deleted ? 0L : entity.positionMs;
        }
        long legacy = readLegacyCacheProgress(cacheKey);
        if (legacy > 0 && !Hawk.get(HawkConfig.PRIVATE_BROWSING, false)) {
            saveEpisodeProgress(originalKey, cacheKey, legacy);
        }
        return legacy;
    }

    public static void saveEpisodeProgress(String originalKey, String cacheKey, long positionMs) {
        if (Hawk.get(HawkConfig.PRIVATE_BROWSING, false)) {
            CacheManager.save(cacheKey, positionMs);
            return;
        }
        EpisodeProgressEntity old = AppDataManager.get().getWebDavSyncDao()
                .getEpisodeProgress(originalKey);
        saveEpisodeProgress(originalKey, cacheKey, positionMs,
                nextTimestamp(old == null ? 0 : old.updatedAt), WebDavDeviceId.get(), false);
    }

    public static void deleteEpisodeProgress(String originalKey, String cacheKey) {
        if (Hawk.get(HawkConfig.PRIVATE_BROWSING, false)) {
            CacheManager.delete(cacheKey, 0L);
            return;
        }
        EpisodeProgressEntity old = AppDataManager.get().getWebDavSyncDao()
                .getEpisodeProgress(originalKey);
        saveEpisodeProgress(originalKey, cacheKey, 0,
                nextTimestamp(old == null ? 0 : old.updatedAt), WebDavDeviceId.get(), true);
    }

    public static void importEpisodeProgress(EpisodeProgressDto incoming) {
        if (incoming == null || TextUtils.isEmpty(incoming.originalKey)
                || TextUtils.isEmpty(incoming.cacheKey) || TextUtils.isEmpty(incoming.deviceId)) return;
        EpisodeProgressEntity current = AppDataManager.get().getWebDavSyncDao()
                .getEpisodeProgress(incoming.originalKey);
        if (current == null || SyncMergeEngine.isNewer(incoming.updatedAt, incoming.deviceId,
                current.updatedAt, current.deviceId)) {
            saveEpisodeProgress(incoming.originalKey, incoming.cacheKey, incoming.positionMs,
                    incoming.updatedAt, incoming.deviceId, incoming.deleted);
        }
    }

    private static void saveEpisodeProgress(String originalKey, String cacheKey, long positionMs,
                                            long updatedAt, String deviceId, boolean deleted) {
        EpisodeProgressDto dto = new EpisodeProgressDto(originalKey, cacheKey, positionMs,
                updatedAt, deviceId, deleted);
        AppDataManager.get().getWebDavSyncDao().putEpisodeProgress(new EpisodeProgressEntity(dto));
        if (deleted) CacheManager.delete(cacheKey, 0L);
        else CacheManager.save(cacheKey, positionMs);
    }

    private static long readLegacyCacheProgress(String cacheKey) {
        Object theCache = CacheManager.getCache(cacheKey);
        if (theCache instanceof Long) return (Long) theCache;
        if (theCache instanceof String) {
            try {
                return Long.parseLong((String) theCache);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static long nextTimestamp(long previous) {
        return Math.max(System.currentTimeMillis(), previous + 1);
    }

}