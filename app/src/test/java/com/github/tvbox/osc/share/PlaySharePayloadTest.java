package com.github.tvbox.osc.share;

import com.github.tvbox.osc.bean.VodInfo;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PlaySharePayloadTest {
    @Test
    public void encodeAndParseRoundTrip() {
        PlaySharePayload payload = new PlaySharePayload();
        payload.sourceKey = "demo";
        payload.vodId = "1001";
        payload.name = "测试影片";
        payload.playFlag = "线路1";
        payload.playIndex = 2;
        payload.playNote = "第03集";
        payload.positionMs = 120000L;
        payload.playerCfg = "{\"pl\":1}";

        PlaySharePayload parsed = PlaySharePayload.parse(PlaySharePayload.encode(payload));
        assertNotNull(parsed);
        assertEquals("demo", parsed.sourceKey);
        assertEquals("1001", parsed.vodId);
        assertEquals("测试影片", parsed.name);
        assertEquals("线路1", parsed.playFlag);
        assertEquals(2, parsed.playIndex);
        assertEquals("第03集", parsed.playNote);
        assertEquals(120000L, parsed.positionMs);
        assertEquals("{\"pl\":1}", parsed.playerCfg);
        assertTrue(parsed.isValid());
    }

    @Test
    public void fromVodBuildsPayload() {
        VodInfo vod = new VodInfo();
        vod.sourceKey = "demo";
        vod.id = "2002";
        vod.name = "鬼灭之刃";
        vod.playFlag = "默认";
        vod.playIndex = 1;
        vod.playerCfg = "{}";
        vod.seriesMap = new LinkedHashMap<>();
        List<VodInfo.VodSeries> series = new ArrayList<>();
        series.add(new VodInfo.VodSeries("第01集", "http://example.com/1"));
        series.add(new VodInfo.VodSeries("第02集", "http://example.com/2"));
        vod.seriesMap.put("默认", series);

        PlaySharePayload payload = PlaySharePayload.fromVod(vod, 5000L);
        assertTrue(payload.isValid());
        assertEquals("第02集", payload.playNote);
        assertEquals(5000L, payload.positionMs);
    }

    @Test
    public void invalidPayloadReturnsNull() {
        assertNull(PlaySharePayload.parse(""));
        assertNull(PlaySharePayload.parse("not-json"));
        assertNull(PlaySharePayload.parse("{\"type\":\"other\"}"));
    }
}
