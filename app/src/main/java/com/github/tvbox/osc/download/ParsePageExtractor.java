package com.github.tvbox.osc.download;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.VideoParseRuler;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 直接请求解析页，从源码里提取视频地址。提取不到时返回 null，交给嗅探。
 */
final class ParsePageExtractor {
    private static final long BUDGET_MS = 8000;
    private static final int PAGE_LIMIT = 1024 * 1024;
    private static final int PROBE_LIMIT = 512;
    private static final int MAX_PROBES = 5;
    private static final int MAX_IFRAMES = 2;
    private static final Charset LATIN = Charset.forName("ISO-8859-1");
    private static final Pattern LINK = Pattern.compile("https?://[^\\s\"'<>\\\\]{8,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern IFRAME = Pattern.compile("(?i)<iframe[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"]");

    private final VideoDownloadManager manager;
    private final String taskId;
    private final String webUrl;
    private final HashMap<String, String> headers;
    private final String userAgent;
    private final SourceBean source;
    private final long deadline;
    private int probes;

    private ParsePageExtractor(VideoDownloadManager manager, String taskId, String webUrl,
                               HashMap<String, String> headers, String userAgent, SourceBean source) {
        this.manager = manager;
        this.taskId = taskId;
        this.webUrl = webUrl;
        this.headers = headers == null ? new HashMap<String, String>() : headers;
        this.userAgent = userAgent;
        this.source = source;
        this.deadline = System.currentTimeMillis() + BUDGET_MS;
    }

    static EpisodeUrlResolver.Result tryExtract(VideoDownloadManager manager, String taskId, String pageUrl, String webUrl,
                                                 HashMap<String, String> headers, String userAgent, SourceBean source) throws Exception {
        return new ParsePageExtractor(manager, taskId, webUrl, headers, userAgent, source).extract(pageUrl);
    }

    private EpisodeUrlResolver.Result extract(String pageUrl) throws Exception {
        String html = fetch(pageUrl, PAGE_LIMIT);
        if (html == null) return null;
        List<String> videos = new ArrayList<>();
        List<String> iframes = new ArrayList<>();
        collect(pageUrl, html, videos, iframes);
        EpisodeUrlResolver.Result found = probe(pageUrl, videos);
        if (found != null) return found;
        int followed = 0;
        for (String iframe : iframes) {
            if (followed >= MAX_IFRAMES || probes >= MAX_PROBES || !timeLeft()) break;
            followed++;
            String frameHtml = fetch(iframe, PAGE_LIMIT);
            if (frameHtml == null) continue;
            List<String> nested = new ArrayList<>();
            collect(iframe, frameHtml, nested, null);
            found = probe(iframe, nested);
            if (found != null) return found;
        }
        return null;
    }

    private void collect(String pageUrl, String html, List<String> videos, List<String> iframes) {
        String text = html.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&");
        Set<String> seen = new LinkedHashSet<>();
        Matcher links = LINK.matcher(text);
        while (links.find()) {
            String url = trim(links.group());
            if (!seen.add(url) || !isVideo(url)) continue;
            videos.add(url);
        }
        if (iframes == null) return;
        Matcher frames = IFRAME.matcher(text);
        HttpUrl base = HttpUrl.parse(pageUrl);
        while (frames.find()) {
            String src = frames.group(1).trim();
            if (src.isEmpty() || src.startsWith("javascript:") || src.startsWith("data:")) continue;
            String absolute = src;
            if (base != null && !src.startsWith("http://") && !src.startsWith("https://")) {
                HttpUrl resolved = base.resolve(src);
                if (resolved == null) continue;
                absolute = resolved.toString();
            }
            if (absolute.startsWith("http://") || absolute.startsWith("https://")) iframes.add(absolute);
        }
    }

    private EpisodeUrlResolver.Result probe(String pageUrl, List<String> videos) throws Exception {
        for (String url : videos) {
            if (probes >= MAX_PROBES || !timeLeft()) return null;
            probes++;
            if (!confirm(url, pageUrl)) continue;
            HashMap<String, String> resultHeaders = new HashMap<>();
            if (userAgent != null && !userAgent.isEmpty()) resultHeaders.put("User-Agent", userAgent);
            if (!hasHeader(headers, "Referer")) resultHeaders.put("Referer", pageUrl);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                resultHeaders.put(entry.getKey(), entry.getValue());
            }
            return new EpisodeUrlResolver.Result(url, resultHeaders);
        }
        return null;
    }

    private boolean confirm(String url, String pageUrl) throws Exception {
        HashMap<String, String> requestHeaders = new HashMap<>(headers);
        if (userAgent != null && !userAgent.isEmpty()) requestHeaders.put("User-Agent", userAgent);
        if (!hasHeader(requestHeaders, "Referer")) requestHeaders.put("Referer", pageUrl);
        long remaining = deadline - System.currentTimeMillis();
        if (remaining < 500) return false;
        long timeout = Math.min(remaining, 4000);
        OkHttpClient http = DownloadHttp.client().newBuilder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .callTimeout(timeout, TimeUnit.MILLISECONDS)
                .build();
        Request request = DownloadHttp.request(url, requestHeaders).build();
        Call call = DownloadHttp.enqueueCall(taskId, http, request);
        try (Response response = call.execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) return false;
            byte[] buf = new byte[PROBE_LIMIT];
            int read = body.byteStream().read(buf);
            if (read < 0) read = 0;
            return DownloadMedia.isMediaProbe(buf, read, response.header("Content-Type"));
        } catch (IOException e) {
            if (manager.shouldStop(taskId)) throw new DownloadStoppedException();
            return false;
        } finally {
            DownloadHttp.release(taskId, call);
        }
    }

    private String fetch(String url, int limit) throws Exception {
        if (!timeLeft()) return null;
        if (manager.shouldStop(taskId)) throw new DownloadStoppedException();
        long remaining = deadline - System.currentTimeMillis();
        long timeout = Math.min(Math.max(remaining, 500), 4000);
        OkHttpClient http = DownloadHttp.client().newBuilder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .callTimeout(timeout, TimeUnit.MILLISECONDS)
                .build();
        HashMap<String, String> requestHeaders = new HashMap<>(headers);
        if (userAgent != null && !userAgent.isEmpty()) requestHeaders.put("User-Agent", userAgent);
        Request request = DownloadHttp.request(url, requestHeaders).build();
        Call call = DownloadHttp.enqueueCall(taskId, http, request);
        try (Response response = call.execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) return null;
            byte[] buf = new byte[8192];
            StringBuilder text = new StringBuilder();
            int total = 0;
            int n;
            while (total < limit && (n = body.byteStream().read(buf)) != -1) {
                int take = Math.min(n, limit - total);
                text.append(new String(buf, 0, take, LATIN));
                total += take;
            }
            return text.toString();
        } catch (IOException e) {
            if (manager.shouldStop(taskId)) throw new DownloadStoppedException();
            return null;
        } finally {
            DownloadHttp.release(taskId, call);
        }
    }

    private boolean isVideo(String url) {
        try {
            if (url.contains("url=http") || url.contains(".html")) return false;
            if (VideoParseRuler.isFilter(webUrl, url)) return false;
            if (source != null && source.getType() == 3) {
                Spider spider = ApiConfig.get().getCSP(source);
                if (spider != null && spider.manualVideoCheck()) return spider.isVideoFormat(url);
            }
            return VideoParseRuler.checkIsVideoForParse(webUrl, url);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean timeLeft() {
        return System.currentTimeMillis() < deadline;
    }

    private static boolean hasHeader(Map<String, String> headers, String name) {
        for (String key : headers.keySet()) {
            if (key != null && key.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static String trim(String raw) {
        String url = raw;
        while (!url.isEmpty()) {
            char end = url.charAt(url.length() - 1);
            if (end == ')' || end == ']' || end == '}' || end == ',' || end == ';' || end == '.' || end == '\\') {
                url = url.substring(0, url.length() - 1);
            } else {
                break;
            }
        }
        return url;
    }
}
