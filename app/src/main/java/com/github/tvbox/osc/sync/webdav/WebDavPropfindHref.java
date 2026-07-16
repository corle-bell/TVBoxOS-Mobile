package com.github.tvbox.osc.sync.webdav;

import okhttp3.HttpUrl;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** Resolves WebDAV PROPFIND href values to canonical device snapshot URLs. */
final class WebDavPropfindHref {
    private static final Pattern SNAPSHOT_FILE =
            Pattern.compile("^[0-9a-fA-F-]{36}\\.json$");
    private static final String SYNC_SEGMENT = "tvbox-sync/v1/spaces/";

    private WebDavPropfindHref() {
    }

    static HttpUrl resolveSnapshot(String href, HttpUrl root, HttpUrl devicesRoot, String syncId) {
        if (href == null || href.isEmpty()) return null;
        String text = decodeHref(href.trim());
        HttpUrl candidate = parseHref(text, root, devicesRoot);
        if (candidate == null || !sameOrigin(candidate, root)) return null;

        String fileName = snapshotFileName(candidate, syncId);
        if (fileName == null) return null;
        return devicesRoot.newBuilder().addPathSegment(fileName).build();
    }

    private static String decodeHref(String href) {
        try {
            return URLDecoder.decode(href, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return href;
        }
    }

    private static HttpUrl parseHref(String href, HttpUrl root, HttpUrl devicesRoot) {
        HttpUrl absolute = HttpUrl.parse(href);
        if (absolute != null && absolute.scheme() != null) {
            return absolute;
        }
        if (href.startsWith("/")) {
            HttpUrl fromRoot = root.resolve(href);
            if (fromRoot != null) return fromRoot;
        }
        if (!href.contains("/")) {
            return devicesRoot.newBuilder().addPathSegment(href).build();
        }
        return root.newBuilder().encodedPath(normalizeLeadingSlash(href)).build();
    }

    private static String snapshotFileName(HttpUrl url, String syncId) {
        String path = normalizePath(url.encodedPath());
        if (!path.endsWith(".json") || path.contains(".tmp-")) return null;

        String fileName = lastSegment(path);
        if (!isSnapshotFileName(fileName)) return null;

        String marker = (SYNC_SEGMENT + syncId + "/devices/").toLowerCase(Locale.US);
        int markerIndex = path.indexOf(marker);
        if (markerIndex >= 0) {
            String suffix = path.substring(markerIndex + marker.length());
            if (isSnapshotFileName(suffix)) return suffix;
            return null;
        }

        if (path.endsWith("/devices/" + fileName) || path.endsWith("/devices/" + fileName + "/")) {
            return fileName;
        }
        if (!path.contains("/")) {
            return fileName;
        }
        return null;
    }

    static boolean isSnapshotFileName(String name) {
        return name != null && SNAPSHOT_FILE.matcher(name).matches();
    }

    private static String lastSegment(String path) {
        if (path == null || path.isEmpty()) return null;
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') end--;
        if (end == 0) return null;
        int start = path.lastIndexOf('/', end - 1);
        return path.substring(start + 1, end);
    }

    private static String normalizePath(String path) {
        if (path == null) return "";
        String value = path;
        while (value.endsWith("/") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        return value.toLowerCase(Locale.US);
    }

    private static String normalizeLeadingSlash(String href) {
        return href.startsWith("/") ? href : "/" + href;
    }

    private static boolean sameOrigin(HttpUrl url, HttpUrl root) {
        return root.scheme().equals(url.scheme())
                && root.host().equals(url.host())
                && root.port() == url.port();
    }
}
