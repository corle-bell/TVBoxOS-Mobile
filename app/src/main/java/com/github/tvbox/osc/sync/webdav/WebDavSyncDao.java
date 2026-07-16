package com.github.tvbox.osc.sync.webdav;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface WebDavSyncDao {
    @Query("SELECT * FROM historySync")
    List<HistorySyncEntity> getAllHistories();

    @Query("SELECT * FROM historySync WHERE sourceKey=:sourceKey AND vodId=:vodId")
    HistorySyncEntity getHistory(String sourceKey, String vodId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putHistory(HistorySyncEntity entity);

    @Query("SELECT * FROM episodeProgressSync")
    List<EpisodeProgressEntity> getAllEpisodeProgress();

    @Query("SELECT * FROM episodeProgressSync WHERE originalKey=:originalKey")
    EpisodeProgressEntity getEpisodeProgress(String originalKey);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putEpisodeProgress(EpisodeProgressEntity entity);
}
