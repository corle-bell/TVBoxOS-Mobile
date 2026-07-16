package com.github.tvbox.osc.sync.webdav;

import java.util.ArrayList;
import java.util.List;

public class WebDavSnapshotDto {
    public static final int CURRENT_VERSION = 1;

    public int version = CURRENT_VERSION;
    public String deviceId;
    public String syncId;
    public long generatedAt;
    public List<HistorySyncDto> histories = new ArrayList<>();
    public List<EpisodeProgressDto> episodeProgress = new ArrayList<>();

    public WebDavSnapshotDto() {
    }

    public WebDavSnapshotDto(String deviceId, long generatedAt) {
        this.deviceId = deviceId;
        this.generatedAt = generatedAt;
    }
}
