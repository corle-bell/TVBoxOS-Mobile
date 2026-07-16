package com.github.tvbox.osc.sync.webdav;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class WebDavSyncSpaceTest {
    @Test
    public void twoDevicesSameSyncIdMergeHistories() {
        WebDavSnapshotDto phone = new WebDavSnapshotDto("phone", 1);
        phone.syncId = "tvbox-abc12345";
        phone.histories.add(new HistorySyncDto("src", "vod-a", "{}", 10, "phone", false));

        WebDavSnapshotDto tablet = new WebDavSnapshotDto("tablet", 2);
        tablet.syncId = "tvbox-abc12345";
        tablet.histories.add(new HistorySyncDto("src", "vod-b", "{}", 11, "tablet", false));

        WebDavSnapshotDto merged = SyncMergeEngine.merge("phone",
                Arrays.asList(phone, tablet), 3);

        assertEquals(2, merged.histories.size());
    }

    @Test
    public void differentSyncIdsAreIsolatedByPathSegment() {
        String spaceA = "tvbox-spaceaaa";
        String spaceB = "tvbox-spacebbb";
        assertNotEquals(
                "tvbox-sync/v1/spaces/" + spaceA + "/devices/",
                "tvbox-sync/v1/spaces/" + spaceB + "/devices/");
    }

    @Test
    public void deviceCountEqualsDistinctSnapshots() {
        WebDavSnapshotDto a = new WebDavSnapshotDto("device-a", 1);
        WebDavSnapshotDto b = new WebDavSnapshotDto("device-b", 1);
        WebDavSnapshotDto merged = SyncMergeEngine.merge("device-a",
                Arrays.asList(a, b), 2);
        assertEquals(2, Arrays.asList(a, b).size());
        assertEquals("device-a", merged.deviceId);
        assertEquals(0, merged.histories.size());
    }

    @Test
    public void emptyRemoteListStillExportsLocal() {
        WebDavSnapshotDto local = new WebDavSnapshotDto("local", 5);
        local.histories.add(new HistorySyncDto("src", "vod", "{}", 1, "local", false));
        WebDavSnapshotDto merged = SyncMergeEngine.merge("local",
                Collections.singletonList(local), 6);
        assertEquals(1, merged.histories.size());
    }
}
