package com.github.tvbox.osc.sync.webdav;

import com.google.gson.Gson;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class WebDavSnapshotDtoTest {
    private final Gson gson = new Gson();

    @Test
    public void roundTripsSchemaVersionOne() {
        WebDavSnapshotDto snapshot = new WebDavSnapshotDto("device-1", 1234L);
        snapshot.histories.add(new HistorySyncDto("src", "id", "{}", 99, "device-1", false));
        snapshot.episodeProgress.add(new EpisodeProgressDto(
                "srcidflag0ep", "deadbeef", 1500L, 99, "device-1", false));

        WebDavSnapshotDto parsed = gson.fromJson(gson.toJson(snapshot), WebDavSnapshotDto.class);

        assertEquals(WebDavSnapshotDto.CURRENT_VERSION, parsed.version);
        assertEquals("device-1", parsed.deviceId);
        assertEquals(1234L, parsed.generatedAt);
        assertEquals(1, parsed.histories.size());
        assertEquals("src", parsed.histories.get(0).sourceKey);
        assertEquals(1, parsed.episodeProgress.size());
        assertEquals(1500L, parsed.episodeProgress.get(0).positionMs);
    }

    @Test
    public void parsesMinimalLegacyFriendlyJson() {
        String json = "{\"version\":1,\"deviceId\":\"x\",\"generatedAt\":1,"
                + "\"histories\":[],\"episodeProgress\":[]}";
        WebDavSnapshotDto parsed = gson.fromJson(json, WebDavSnapshotDto.class);
        assertNotNull(parsed);
        assertEquals(1, parsed.version);
        assertNotNull(parsed.histories);
        assertNotNull(parsed.episodeProgress);
    }
}
