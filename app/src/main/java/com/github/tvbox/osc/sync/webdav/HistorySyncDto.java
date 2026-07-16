package com.github.tvbox.osc.sync.webdav;

import java.util.Objects;

public class HistorySyncDto {
    public String sourceKey;
    public String vodId;
    public String dataJson;
    public long updatedAt;
    public String deviceId;
    public boolean deleted;

    public HistorySyncDto() {
    }

    public HistorySyncDto(String sourceKey, String vodId, String dataJson, long updatedAt,
                          String deviceId, boolean deleted) {
        this.sourceKey = sourceKey;
        this.vodId = vodId;
        this.dataJson = dataJson;
        this.updatedAt = updatedAt;
        this.deviceId = deviceId;
        this.deleted = deleted;
    }

    public String stableKey() {
        return Objects.toString(sourceKey, "") + "\u0000" + Objects.toString(vodId, "");
    }
}
