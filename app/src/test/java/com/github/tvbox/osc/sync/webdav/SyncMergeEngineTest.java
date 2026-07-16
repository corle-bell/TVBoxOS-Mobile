package com.github.tvbox.osc.sync.webdav;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SyncMergeEngineTest {
    @Test
    public void newerHistoryTombstoneWins() {
        WebDavSnapshotDto oldSnapshot = new WebDavSnapshotDto("a", 1);
        oldSnapshot.histories.add(new HistorySyncDto("source", "vod", "{}", 10, "a", false));
        WebDavSnapshotDto newSnapshot = new WebDavSnapshotDto("b", 2);
        newSnapshot.histories.add(new HistorySyncDto("source", "vod", null, 11, "b", true));

        WebDavSnapshotDto result = SyncMergeEngine.merge(
                "local", Arrays.asList(oldSnapshot, newSnapshot), 3);

        assertEquals(1, result.histories.size());
        assertTrue(result.histories.get(0).deleted);
    }

    @Test
    public void deviceIdBreaksTimestampTieDeterministically() {
        WebDavSnapshotDto a = new WebDavSnapshotDto("a", 1);
        a.episodeProgress.add(new EpisodeProgressDto("raw", "cache", 100, 10, "a", false));
        WebDavSnapshotDto b = new WebDavSnapshotDto("b", 1);
        b.episodeProgress.add(new EpisodeProgressDto("raw", "cache", 200, 10, "b", false));

        WebDavSnapshotDto result = SyncMergeEngine.merge(
                "local", Arrays.asList(b, a), 3);

        assertEquals(200, result.episodeProgress.get(0).positionMs);
        assertEquals("b", result.episodeProgress.get(0).deviceId);
    }

    @Test
    public void rewatchZeroBeatsOlderHighPosition() {
        WebDavSnapshotDto oldHigh = new WebDavSnapshotDto("phone", 1);
        oldHigh.episodeProgress.add(new EpisodeProgressDto(
                "src1vod1flag01", "md5", 3_600_000L, 100, "phone", false));
        WebDavSnapshotDto rewatch = new WebDavSnapshotDto("tv", 2);
        rewatch.episodeProgress.add(new EpisodeProgressDto(
                "src1vod1flag01", "md5", 0L, 200, "tv", false));

        WebDavSnapshotDto result = SyncMergeEngine.merge(
                "local", Arrays.asList(oldHigh, rewatch), 3);

        assertEquals(0L, result.episodeProgress.get(0).positionMs);
        assertFalse(result.episodeProgress.get(0).deleted);
    }

    @Test
    public void olderTombstoneDoesNotWinOverNewerWrite() {
        WebDavSnapshotDto deleted = new WebDavSnapshotDto("a", 1);
        deleted.histories.add(new HistorySyncDto("source", "vod", null, 10, "a", true));
        WebDavSnapshotDto restored = new WebDavSnapshotDto("b", 2);
        restored.histories.add(new HistorySyncDto("source", "vod", "{}", 20, "b", false));

        WebDavSnapshotDto result = SyncMergeEngine.merge(
                "local", Arrays.asList(deleted, restored), 3);

        assertEquals(1, result.histories.size());
        assertFalse(result.histories.get(0).deleted);
        assertEquals("{}", result.histories.get(0).dataJson);
    }

    @Test
    public void ignoresUnsupportedSchemaVersion() {
        WebDavSnapshotDto v2 = new WebDavSnapshotDto("a", 1);
        v2.version = 2;
        v2.histories.add(new HistorySyncDto("source", "vod", "{}", 10, "a", false));
        WebDavSnapshotDto v1 = new WebDavSnapshotDto("b", 2);
        v1.histories.add(new HistorySyncDto("source", "other", "{}", 5, "b", false));

        WebDavSnapshotDto result = SyncMergeEngine.merge(
                "local", Arrays.asList(v2, v1), 3);

        assertEquals(1, result.histories.size());
        assertEquals("other", result.histories.get(0).vodId);
    }

    @Test
    public void keepsHistoryFromUnavailableSourceKey() {
        WebDavSnapshotDto remote = new WebDavSnapshotDto("remote", 1);
        remote.histories.add(new HistorySyncDto("gone-source", "vod9", "{\"name\":\"x\"}", 50,
                "remote", false));

        WebDavSnapshotDto result = SyncMergeEngine.merge(
                "local", Collections.singletonList(remote), 3);

        assertEquals(1, result.histories.size());
        assertEquals("gone-source", result.histories.get(0).sourceKey);
    }

    @Test
    public void rejectsItemsMissingDeviceId() {
        WebDavSnapshotDto bad = new WebDavSnapshotDto("a", 1);
        bad.histories.add(new HistorySyncDto("source", "vod", "{}", 10, "", false));
        bad.episodeProgress.add(new EpisodeProgressDto("k", "c", 1, 10, null, false));

        WebDavSnapshotDto result = SyncMergeEngine.merge(
                "local", Collections.singletonList(bad), 3);

        assertTrue(result.histories.isEmpty());
        assertTrue(result.episodeProgress.isEmpty());
    }

    @Test
    public void isNewerUsesLexicographicDeviceIdOnEqualTime() {
        assertTrue(SyncMergeEngine.isNewer(10, "b", 10, "a"));
        assertFalse(SyncMergeEngine.isNewer(10, "a", 10, "b"));
        assertTrue(SyncMergeEngine.isNewer(11, "a", 10, "z"));
    }
}
