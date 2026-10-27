package com.github.tvbox.osc.download;

import com.github.tvbox.osc.util.OkGoHelper;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;

final class DownloadHttp {
    private static final ConcurrentHashMap<String, Set<Call>> CALLS = new ConcurrentHashMap<>();
    private static volatile OkHttpClient client;

    private DownloadHttp() {
    }

    static OkHttpClient client() {
        if (client == null) {
            synchronized (DownloadHttp.class) {
                if (client == null) {
                    OkHttpClient base = OkGoHelper.getDefaultClient();
                    OkHttpClient.Builder builder = base != null ? base.newBuilder() : new OkHttpClient.Builder();
                    client = builder
                            .connectTimeout(20, TimeUnit.SECONDS)
                            .readTimeout(60, TimeUnit.SECONDS)
                            .writeTimeout(60, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .build();
                }
            }
        }
        return client;
    }

    static Request.Builder request(String url, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder().url(url);
        applyHeaders(builder, headers);
        return builder;
    }

    static void applyHeaders(Request.Builder builder, Map<String, String> headers) {
        if (headers == null) return;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String name = entry.getKey().trim();
            if (name.isEmpty()) continue;
            String lower = name.toLowerCase(Locale.US);
            if ("host".equals(lower) || "content-length".equals(lower) || "range".equals(lower)
                    || "connection".equals(lower) || "accept-encoding".equals(lower)) {
                continue;
            }
            try {
                String value = entry.getValue().replace("\n", "").replace("\r", "").trim();
                if (value.isEmpty()) continue;
                builder.header(name, value);
            } catch (Exception ignored) {
            }
        }
    }

    static Call enqueueCall(String taskId, Request request) {
        return enqueueCall(taskId, client(), request);
    }

    static Call enqueueCall(String taskId, OkHttpClient http, Request request) {
        Call call = http.newCall(request);
        Set<Call> set = CALLS.get(taskId);
        if (set == null) {
            set = ConcurrentHashMap.newKeySet();
            Set<Call> existing = CALLS.putIfAbsent(taskId, set);
            if (existing != null) set = existing;
        }
        set.add(call);
        return call;
    }

    static void release(String taskId, Call call) {
        Set<Call> set = CALLS.get(taskId);
        if (set != null) set.remove(call);
    }

    static void cancel(String taskId) {
        Set<Call> set = CALLS.remove(taskId);
        if (set == null) return;
        for (Call call : set) {
            call.cancel();
        }
    }

    static IOException asStoppedOrIo(VideoDownloadManager manager, String taskId, IOException error) throws DownloadStoppedException {
        if (manager.shouldStop(taskId) || (error != null && isCanceled(error))) {
            if (manager.shouldStop(taskId)) throw new DownloadStoppedException();
        }
        return error == null ? new IOException("下载失败") : error;
    }

    private static boolean isCanceled(IOException error) {
        String message = error.getMessage();
        return message != null && message.toLowerCase(Locale.US).contains("cancel");
    }
}
