package com.github.tvbox.osc.download;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.thunder.Jianpian;
import com.github.tvbox.osc.util.thunder.Thunder;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class EpisodeUrlResolver {
    static final String NEED_SNIFF = "该集需嗅探，请播放该集后再下载";
    private static final int JSON_FALLBACK_LIMIT = 3;

    static final class Result {
        final String url;
        final HashMap<String, String> headers;

        Result(String url, HashMap<String, String> headers) {
            this.url = url;
            this.headers = headers == null ? new HashMap<String, String>() : headers;
        }
    }

    private final VideoDownloadManager manager;
    private final String taskId;

    EpisodeUrlResolver(VideoDownloadManager manager, String taskId) {
        this.manager = manager;
        this.taskId = taskId;
    }

    Result resolve(DownloadTask task) throws Exception {
        String raw = task.episodeUrl == null ? "" : task.episodeUrl.trim();
        if (raw.isEmpty()) throw new IOException("缺少分集地址");
        if (Jianpian.isJpUrl(raw)) {
            String url = raw.startsWith("tvbox-xg:") ? Jianpian.JPUrlDec(raw.substring(9)) : Jianpian.JPUrlDec(raw);
            return finish(url, null);
        }
        if (Thunder.isSupportUrl(raw)) throw new IOException("该资源不支持下载");

        SourceBean source = task.sourceKey == null ? null : ApiConfig.get().getSource(task.sourceKey);
        if (source == null) throw new IOException("找不到片源，请重新进入详情页下载");
        JSONObject info = playInfo(source, task.playFlag, raw);
        checkStop();

        boolean parse = info.optString("parse", "1").equals("1");
        boolean jx = info.optString("jx", "0").equals("1");
        String playUrl = info.optString("playUrl", "");
        String flag = info.optString("flag", task.playFlag);
        String url = info.optString("url", "");
        HashMap<String, String> headers = headersOf(info.opt("header"));
        if (!parse && !jx) {
            return finish(playUrl + url, headers);
        }
        boolean useDefault = (playUrl.isEmpty() && ApiConfig.get().getVipParseFlags().contains(flag)) || jx;
        return parse(parseBeanOf(useDefault, playUrl), flag, url, source);
    }

    private JSONObject playInfo(SourceBean source, String flag, String url) throws Exception {
        int type = source.getType();
        if (type == 3) {
            Spider sp = ApiConfig.get().getCSP(source);
            String json = sp.playerContent(flag, url, ApiConfig.get().getVipParseFlags());
            JSONObject result = new JSONObject(json);
            if (!result.has("flag")) result.put("flag", flag);
            return result;
        }
        if (type == 0 || type == 1) {
            JSONObject result = new JSONObject();
            String playUrl = source.getPlayerUrl() == null ? "" : source.getPlayerUrl().trim();
            result.put("parse", DefaultConfig.isVideoFormat(url) && playUrl.isEmpty() ? 0 : 1);
            result.put("url", url);
            result.put("playUrl", playUrl);
            result.put("flag", flag);
            return result;
        }
        if (type == 4) {
            HttpUrl base = HttpUrl.parse(source.getApi());
            if (base == null) throw new IOException("片源地址无效");
            HttpUrl api = base.newBuilder()
                    .addQueryParameter("play", url)
                    .addQueryParameter("flag", flag)
                    .build();
            JSONObject result = new JSONObject(get(api.toString(), null));
            if (!result.has("flag")) result.put("flag", flag);
            return result;
        }
        throw new IOException("该片源不支持下载");
    }

    private ParseBean parseBeanOf(boolean useDefault, String playUrl) {
        ParseBean parseBean = null;
        if (useDefault) {
            parseBean = ApiConfig.get().getDefaultParse();
        } else if (playUrl.startsWith("json:")) {
            parseBean = new ParseBean();
            parseBean.setType(1);
            parseBean.setUrl(playUrl.substring(5));
        } else if (playUrl.startsWith("parse:")) {
            String name = playUrl.substring(6);
            for (ParseBean pb : ApiConfig.get().getParseBeanList()) {
                if (pb.getName().equals(name)) {
                    parseBean = pb;
                    break;
                }
            }
        }
        if (parseBean == null) {
            parseBean = new ParseBean();
            parseBean.setType(0);
            parseBean.setUrl(playUrl);
        }
        return parseBean;
    }

    private static final class Outcome {
        Result result;
        boolean needsSniff;
        String pageUrl;
        String userAgent;
        HashMap<String, String> pageHeaders = new HashMap<>();
    }

    /**
     * 指定解析器仍要嗅探时，再按订阅顺序最多试 3 个 JSON 解析器，然后抓页面、最后才嗅探
     */
    private Result parse(ParseBean primary, String flag, String webUrl, SourceBean source) throws Exception {
        checkStop();
        Outcome first = runParser(primary, flag, webUrl);
        if (first.result != null) return first.result;
        if (!first.needsSniff) throw new IOException(NEED_SNIFF);

        int extra = 0;
        List<ParseBean> parsers = new ArrayList<>(ApiConfig.get().getParseBeanList());
        for (ParseBean candidate : parsers) {
            if (extra >= JSON_FALLBACK_LIMIT) break;
            int type = candidate.getType();
            if (type < 1 || type > 3 || sameParser(candidate, primary)) continue;
            extra++;
            checkStop();
            try {
                Outcome outcome = runParser(candidate, flag, webUrl);
                if (outcome.result != null) return outcome.result;
            } catch (DownloadStoppedException stopped) {
                throw stopped;
            } catch (Exception ignored) {
            }
        }
        return resolveSniffPage(first, webUrl, source);
    }

    private Result resolveSniffPage(Outcome target, String webUrl, SourceBean source) throws Exception {
        String page = target.pageUrl == null ? "" : target.pageUrl.trim();
        String lower = page.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IOException(NEED_SNIFF);
        }
        checkStop();
        Result extracted = ParsePageExtractor.tryExtract(manager, taskId, page, webUrl, target.pageHeaders, target.userAgent, source);
        if (extracted != null) return extracted;
        checkStop();
        String click = source == null || source.getClickSelector() == null ? "" : source.getClickSelector();
        return DownloadSniffer.sniff(manager, taskId, page, webUrl, target.pageHeaders, target.userAgent, click, source);
    }

    private Outcome runParser(ParseBean pb, String flag, String webUrl) throws Exception {
        Outcome outcome = new Outcome();
        if (pb.getType() == 1) {
            outcome.result = parseJson(pb, webUrl);
            return outcome;
        }
        if (pb.getType() == 2 || pb.getType() == 3) {
            JSONObject rs = pb.getType() == 2 ? jsonExt(pb, webUrl) : jsonExtMix(pb, flag, webUrl);
            if (rs == null || rs.optString("url", "").isEmpty()) throw new IOException("解析错误");
            if (rs.optInt("parse", 0) == 1) {
                outcome.needsSniff = true;
                outcome.pageUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""));
                outcome.userAgent = rs.optString("ua", "").trim();
                outcome.pageHeaders = headersOf(rs.opt("header"));
                return outcome;
            }
            outcome.result = finish(rs.optString("url", ""), headersOf(rs.opt("header")));
            return outcome;
        }
        outcome.needsSniff = true;
        outcome.pageUrl = (pb.getUrl() == null ? "" : pb.getUrl()) + (webUrl == null ? "" : webUrl);
        fillExtRequest(pb, outcome);
        return outcome;
    }

    private Result parseJson(ParseBean pb, String webUrl) throws Exception {
        HashMap<String, String> reqHeaders = new HashMap<>();
        try {
            JSONObject ext = new JSONObject(pb.getExt());
            reqHeaders.putAll(headersOf(ext.opt("header")));
        } catch (Throwable ignored) {
        }
        String json = get(pb.getUrl() + URLEncoder.encode(webUrl, "UTF-8"), reqHeaders);
        JSONObject data = new JSONObject(json);
        String url = data.has("data") ? data.getJSONObject("data").getString("url") : data.getString("url");
        if (url.startsWith("//")) url = "http:" + url;
        HashMap<String, String> headers = new HashMap<>();
        String ua = data.optString("user-agent", "").trim();
        if (!ua.isEmpty()) headers.put("User-Agent", ua);
        String referer = data.optString("referer", "").trim();
        if (!referer.isEmpty()) headers.put("Referer", referer);
        return finish(url, headers);
    }

    private JSONObject jsonExt(ParseBean pb, String webUrl) {
        LinkedHashMap<String, String> jxs = new LinkedHashMap<>();
        for (ParseBean p : ApiConfig.get().getParseBeanList()) {
            if (p.getType() == 1) jxs.put(p.getName(), p.mixUrl());
        }
        return ApiConfig.get().jsonExt(pb.getUrl(), jxs, webUrl);
    }

    private JSONObject jsonExtMix(ParseBean pb, String flag, String webUrl) {
        LinkedHashMap<String, HashMap<String, String>> jxs = new LinkedHashMap<>();
        String extendName = "";
        for (ParseBean p : ApiConfig.get().getParseBeanList()) {
            HashMap<String, String> data = new HashMap<>();
            data.put("url", p.getUrl());
            if (p.getUrl().equals(pb.getUrl())) extendName = p.getName();
            data.put("type", p.getType() + "");
            data.put("ext", p.getExt());
            jxs.put(p.getName(), data);
        }
        return ApiConfig.get().jsonExtMix(flag + "111", pb.getUrl(), extendName, jxs, webUrl);
    }

    private static void fillExtRequest(ParseBean pb, Outcome outcome) {
        if (pb.getExt() == null || pb.getExt().isEmpty()) return;
        try {
            JSONObject header = new JSONObject(pb.getExt()).optJSONObject("header");
            if (header == null) return;
            Iterator<String> keys = header.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = header.optString(key, "").trim();
                if (value.isEmpty()) continue;
                if (key.equalsIgnoreCase("user-agent")) outcome.userAgent = value;
                else outcome.pageHeaders.put(key, value);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean sameParser(ParseBean a, ParseBean b) {
        if (a == b) return true;
        String left = a.getUrl() == null ? "" : a.getUrl();
        String right = b.getUrl() == null ? "" : b.getUrl();
        return a.getType() == b.getType() && left.equals(right);
    }

    private Result finish(String url, HashMap<String, String> headers) throws IOException {
        url = url == null ? "" : url.trim();
        String lower = url.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IOException("该集地址不支持下载");
        }
        return new Result(url, headers);
    }

    private String get(String url, HashMap<String, String> headers) throws Exception {
        Request request = DownloadHttp.request(url, headers).build();
        Call call = DownloadHttp.enqueueCall(taskId, request);
        try (Response response = call.execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) throw new IOException("解析请求失败: " + response.code());
            return body.string();
        } catch (IOException e) {
            throw DownloadHttp.asStoppedOrIo(manager, taskId, e);
        } finally {
            DownloadHttp.release(taskId, call);
        }
    }

    private void checkStop() throws DownloadStoppedException {
        if (manager.shouldStop(taskId)) throw new DownloadStoppedException();
    }

    private static HashMap<String, String> headersOf(Object value) {
        HashMap<String, String> headers = new HashMap<>();
        JSONObject json = null;
        try {
            if (value instanceof JSONObject) {
                json = (JSONObject) value;
            } else if (value instanceof String && !((String) value).trim().isEmpty()) {
                json = new JSONObject((String) value);
            }
        } catch (Throwable ignored) {
        }
        if (json == null) return headers;
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            headers.put(key, json.optString(key, "").trim());
        }
        return headers;
    }
}
