package com.github.tvbox.osc.share;

import com.github.tvbox.osc.bean.VodInfo;
import com.google.gson.Gson;

public class PlaySharePayload {
    public static final String TYPE = "tvbox-share-play";
    public static final String MODE_VOD = "vod";
    public static final int VERSION = 1;

    public int v = VERSION;
    public String type = TYPE;
    public String mode = MODE_VOD;

    public String sourceKey;
    public String vodId;
    public String name;
    public String playFlag;
    public int playIndex;
    public String playNote;
    public long positionMs;
    public String playerCfg;

    private static final Gson GSON = new Gson();

    public PlaySharePayload() {
    }

    public static PlaySharePayload fromVod(VodInfo vod, long positionMs) {
        PlaySharePayload payload = new PlaySharePayload();
        if (vod == null) {
            return payload;
        }
        payload.sourceKey = vod.sourceKey;
        payload.vodId = vod.id;
        payload.name = vod.name;
        payload.playFlag = vod.playFlag;
        payload.playIndex = vod.playIndex;
        payload.playerCfg = vod.playerCfg;
        payload.positionMs = Math.max(0L, positionMs);
        try {
            payload.playNote = vod.seriesMap.get(vod.playFlag).get(vod.playIndex).name;
        } catch (Throwable ignored) {
            payload.playNote = vod.playNote;
        }
        return payload;
    }

    public static String encode(PlaySharePayload payload) {
        if (payload == null) {
            return "";
        }
        if (isEmpty(payload.type)) {
            payload.type = TYPE;
        }
        if (payload.v <= 0) {
            payload.v = VERSION;
        }
        if (isEmpty(payload.mode)) {
            payload.mode = MODE_VOD;
        }
        return GSON.toJson(payload);
    }

    public static PlaySharePayload parse(String raw) {
        if (isEmpty(raw)) {
            return null;
        }
        String text = raw.trim();
        try {
            PlaySharePayload payload = GSON.fromJson(text, PlaySharePayload.class);
            if (payload != null && TYPE.equals(payload.type) && payload.isValid()) {
                return payload;
            }
        } catch (Exception ignored) {
            // Not a share payload.
        }
        return null;
    }

    public boolean isValid() {
        return MODE_VOD.equals(mode)
                && !isEmpty(sourceKey)
                && !isEmpty(vodId)
                && playIndex >= 0;
    }

    public String summary() {
        if (!isEmpty(name) && !isEmpty(playNote)) {
            return name + " · " + playNote;
        }
        if (!isEmpty(name)) {
            return name;
        }
        return vodId;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
