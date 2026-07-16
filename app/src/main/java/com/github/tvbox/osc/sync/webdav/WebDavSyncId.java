package com.github.tvbox.osc.sync.webdav;

import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import java.security.SecureRandom;
import java.util.Locale;

public final class WebDavSyncId {
    private static final String PREFIX = "tvbox-";
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebDavSyncId() {
    }

    public static synchronized String get() {
        String value = Hawk.get(HawkConfig.WEBDAV_SYNC_ID, "");
        if (value == null || value.isEmpty()) {
            value = generate();
            Hawk.put(HawkConfig.WEBDAV_SYNC_ID, value);
        }
        return value;
    }

    public static String generate() {
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public static String normalize(String syncId) {
        if (syncId == null) return "";
        return syncId.trim();
    }

    public static boolean isValid(String syncId) {
        String value = normalize(syncId);
        if (value.length() < 4 || value.length() > 32) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') continue;
            return false;
        }
        return true;
    }

    public static String requireValid(String syncId) {
        String value = normalize(syncId);
        if (!isValid(value)) {
            throw new IllegalArgumentException("Invalid sync space ID");
        }
        return value;
    }

    public static String shortLabel(String syncId) {
        String value = normalize(syncId);
        if (value.length() <= 12) return value;
        return value.substring(0, 12) + "…";
    }
}
