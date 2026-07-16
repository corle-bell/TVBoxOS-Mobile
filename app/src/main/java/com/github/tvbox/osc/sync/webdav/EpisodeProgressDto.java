package com.github.tvbox.osc.sync.webdav;

public class EpisodeProgressDto {
    /** Stable key before hashing or URL resolution. */
    public String originalKey;
    /** Existing local cache key, normally derived from originalKey. */
    public String cacheKey;
    public long positionMs;
    public long updatedAt;
    public String deviceId;
    public boolean deleted;

    public EpisodeProgressDto() {
    }

    public EpisodeProgressDto(String originalKey, String cacheKey, long positionMs, long updatedAt,
                              String deviceId, boolean deleted) {
        this.originalKey = originalKey;
        this.cacheKey = cacheKey;
        this.positionMs = positionMs;
        this.updatedAt = updatedAt;
        this.deviceId = deviceId;
        this.deleted = deleted;
    }

    public String stableKey() {
        return originalKey == null ? "" : originalKey;
    }
}
