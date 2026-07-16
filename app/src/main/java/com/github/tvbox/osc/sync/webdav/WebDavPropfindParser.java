package com.github.tvbox.osc.sync.webdav;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses WebDAV PROPFIND multistatus bodies. */
final class WebDavPropfindParser {
    private static final Pattern HREF_PATTERN = Pattern.compile(
            "<(?:[A-Za-z0-9_\\-]+:)?href\\b[^>]*>([^<]+)</(?:[A-Za-z0-9_\\-]+:)?href>",
            Pattern.CASE_INSENSITIVE);

    private WebDavPropfindParser() {
    }

    static List<String> extractHrefs(String body) {
        List<String> hrefs = new ArrayList<>();
        if (body == null || body.isEmpty()) return hrefs;
        Matcher matcher = HREF_PATTERN.matcher(body);
        while (matcher.find()) {
            String href = matcher.group(1);
            if (href == null) continue;
            href = href.trim();
            if (!href.isEmpty()) hrefs.add(href);
        }
        return hrefs;
    }
}
