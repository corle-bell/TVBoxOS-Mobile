package com.github.tvbox.osc.util;

import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class StartupTrace {
    private static final String TAG = "StartupTrace";
    private static final Map<String, Long> STARTS = new ConcurrentHashMap<>();

    private StartupTrace() {
    }

    public static void begin(String stage) {
        STARTS.put(stage, SystemClock.elapsedRealtime());
    }

    public static void end(String stage) {
        Long start = STARTS.remove(stage);
        if (start == null) return;
        Log.i(TAG, stage + " " + (SystemClock.elapsedRealtime() - start) + "ms, since process start " + sinceProcessStart() + "ms");
    }

    public static void mark(String event) {
        Log.i(TAG, event + " at " + sinceProcessStart() + "ms since process start");
    }

    private static long sinceProcessStart() {
        return SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime();
    }
}
