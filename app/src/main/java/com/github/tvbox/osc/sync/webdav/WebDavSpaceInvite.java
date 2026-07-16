package com.github.tvbox.osc.sync.webdav;

import com.google.gson.Gson;
public class WebDavSpaceInvite {
    public static final String TYPE = "tvbox-sync-space";
    public static final int VERSION = 1;

    public int v = VERSION;
    public String type = TYPE;
    public String url;
    public String syncId;

    public WebDavSpaceInvite() {
    }

    public WebDavSpaceInvite(String url, String syncId) {
        this.url = url;
        this.syncId = syncId;
    }

    private static final Gson GSON = new Gson();

    public static String encode(String url, String syncId) {
        WebDavSpaceInvite invite = new WebDavSpaceInvite(url, syncId);
        return GSON.toJson(invite);
    }

    public static WebDavSpaceInvite parse(String raw) {
        if (isEmpty(raw)) return null;
        String text = raw.trim();
        try {
            WebDavSpaceInvite invite = GSON.fromJson(text, WebDavSpaceInvite.class);
            if (invite != null && !isEmpty(invite.url) && !isEmpty(invite.syncId)) {
                return invite;
            }
        } catch (Exception ignored) {
            // Fall through to line-based parsing.
        }
        String url = null;
        String syncId = null;
        for (String line : text.split("\\r?\\n")) {
            String part = line.trim();
            if (part.startsWith("url=")) url = part.substring(4).trim();
            else if (part.startsWith("syncId=")) syncId = part.substring(7).trim();
            else if (part.startsWith("http://") || part.startsWith("https://")) url = part;
        }
        if (!isEmpty(url) && !isEmpty(syncId)) {
            return new WebDavSpaceInvite(url, syncId);
        }
        return null;
    }

    public boolean isValid() {
        return !isEmpty(url) && WebDavSyncId.isValid(syncId);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
