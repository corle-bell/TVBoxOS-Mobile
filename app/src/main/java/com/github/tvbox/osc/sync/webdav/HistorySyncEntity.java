package com.github.tvbox.osc.sync.webdav;

import androidx.annotation.NonNull;
import androidx.room.Entity;

@Entity(tableName = "historySync", primaryKeys = {"sourceKey", "vodId"})
public class HistorySyncEntity {
    @NonNull
    public String sourceKey = "";
    @NonNull
    public String vodId = "";
    public String dataJson;
    public long updatedAt;
    @NonNull
    public String deviceId = "";
    public boolean deleted;

    public HistorySyncEntity() {
    }

    public HistorySyncEntity(HistorySyncDto dto) {
        sourceKey = dto.sourceKey;
        vodId = dto.vodId;
        dataJson = dto.dataJson;
        updatedAt = dto.updatedAt;
        deviceId = dto.deviceId;
        deleted = dto.deleted;
    }

    public HistorySyncDto toDto() {
        return new HistorySyncDto(sourceKey, vodId, dataJson, updatedAt, deviceId, deleted);
    }
}
