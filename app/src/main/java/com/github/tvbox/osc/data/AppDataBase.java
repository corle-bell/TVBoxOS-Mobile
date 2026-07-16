package com.github.tvbox.osc.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import com.github.tvbox.osc.cache.Cache;
import com.github.tvbox.osc.cache.CacheDao;
import com.github.tvbox.osc.cache.VodCollect;
import com.github.tvbox.osc.cache.VodCollectDao;
import com.github.tvbox.osc.cache.VodRecord;
import com.github.tvbox.osc.cache.VodRecordDao;
import com.github.tvbox.osc.sync.webdav.EpisodeProgressEntity;
import com.github.tvbox.osc.sync.webdav.HistorySyncEntity;
import com.github.tvbox.osc.sync.webdav.WebDavSyncDao;


/**
 * 类描述:
 *
 * @author pj567
 * @since 2020/5/15
 */
@Database(entities = {
        Cache.class,
        VodRecord.class,
        VodCollect.class,
        HistorySyncEntity.class,
        EpisodeProgressEntity.class
}, version = 2)
public abstract class AppDataBase extends RoomDatabase {
    public abstract CacheDao getCacheDao();

    public abstract VodRecordDao getVodRecordDao();

    public abstract VodCollectDao getVodCollectDao();

    public abstract WebDavSyncDao getWebDavSyncDao();
}
