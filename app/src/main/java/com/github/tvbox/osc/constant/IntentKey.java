package com.github.tvbox.osc.constant;

public class IntentKey {

    /**
     * 画中画/后台服务
     */
    public static final String BROADCAST_ACTION = "VOD_CONTROL";
    public static final int BROADCAST_ACTION_PREV = 0;
    public static final int BROADCAST_ACTION_PLAYPAUSE = 1;
    public static final int BROADCAST_ACTION_NEXT = 2;
    public static final int BROADCAST_ACTION_CLOSE = 3;
    public static final String CACHE_CONFIG_CHANGED = "use_cache";

    /** VOD share: line flag from shared payload. */
    public static final String SHARE_PLAY_FLAG = "share_play_flag";
    /** VOD share: episode index from shared payload. */
    public static final String SHARE_PLAY_INDEX = "share_play_index";
    /** VOD share: resume position in milliseconds. */
    public static final String SHARE_POSITION_MS = "share_position_ms";
    /** VOD share: serialized player config. */
    public static final String SHARE_PLAYER_CFG = "share_player_cfg";
    /** VOD share: auto start playback after detail loads. */
    public static final String SHARE_AUTO_PLAY = "share_auto_play";
}