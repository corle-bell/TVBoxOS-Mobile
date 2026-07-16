package com.github.tvbox.osc.sync.webdav;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;

@Entity(tableName = "episodeProgressSync",
        primaryKeys = {"originalKey"},
        indices = {@Index("cacheKey")})
public class EpisodeProgressEntity {
    @NonNull
    public String originalKey = "";
    @NonNull
    public String cacheKey = "";
    public long positionMs;
    public long updatedAt;
    @NonNull
    public String deviceId = "";
    public boolean deleted;

    public EpisodeProgressEntity() {
    }

    public EpisodeProgressEntity(EpisodeProgressDto dto) {
        originalKey = dto.originalKey;
        cacheKey = dto.cacheKey;
        positionMs = dto.positionMs;
        updatedAt = dto.updatedAt;
        deviceId = dto.deviceId;
        deleted = dto.deleted;
    }

    public EpisodeProgressDto toDto() {
        return new EpisodeProgressDto(originalKey, cacheKey, positionMs, updatedAt, deviceId, deleted);
    }
}
