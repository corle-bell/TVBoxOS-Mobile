package com.github.tvbox.osc.download;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import android.view.ContextThemeWrapper;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.AdBlocker;
import com.github.tvbox.osc.util.VideoParseRuler;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 同一时间只用一个 WebView 嗅探。下载线程排队等待当前这一集的结果。
 */
final class DownloadSniffer {
    private static final long SNIFF_TIMEOUT_MS = 20_000;
    private static final long IDLE_DESTROY_MS = 30_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final ArrayDeque<Job> QUEUE = new ArrayDeque<>();

    private static Job current;
    private static WebView webView;
    private static String defaultUserAgent = "";
    private static int generation;
    private static final Runnable IDLE = new Runnable() {
        @Override
        public void run() {
            destroyIdle();
        }
    };

    private DownloadSniffer() {
    }

    static EpisodeUrlResolver.Result sniff(VideoDownloadManager manager, String taskId, String pageUrl, String webUrl,
                                            HashMap<String, String> headers, String userAgent, String click,
                                            SourceBean source) throws Exception {
        Job job = new Job(taskId, pageUrl, webUrl, headers, userAgent, click, source);
        synchronized (LOCK) {
            QUEUE.add(job);
        }
        if (manager.shouldStop(taskId)) {
            cancel(taskId);
            throw new DownloadStoppedException();
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                pump();
            }
        });
        while (!job.latch.await(300, TimeUnit.MILLISECONDS)) {
            if (manager.shouldStop(taskId)) {
                cancel(taskId);
                throw new DownloadStoppedException();
            }
        }
        if (job.error instanceof DownloadStoppedException) throw (DownloadStoppedException) job.error;
        if (manager.shouldStop(taskId)) throw new DownloadStoppedException();
        if (job.result == null) throw new java.io.IOException(EpisodeUrlResolver.NEED_SNIFF);
        return job.result;
    }

    static void cancel(final String taskId) {
        if (taskId == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                synchronized (LOCK) {
                    ArrayDeque<Job> kept = new ArrayDeque<>();
                    for (Job job : QUEUE) {
                        if (taskId.equals(job.taskId)) job.finish(null, new DownloadStoppedException());
                        else kept.add(job);
                    }
                    QUEUE.clear();
                    QUEUE.addAll(kept);
                }
                if (current != null && taskId.equals(current.taskId)) {
                    current.finish(null, new DownloadStoppedException());
                    current = null;
                    blank();
                    pump();
                }
            }
        });
    }

    private static void pump() {
        if (current != null) return;
        Job job;
        synchronized (LOCK) {
            job = QUEUE.poll();
        }
        if (job == null) {
            MAIN.removeCallbacks(IDLE);
            MAIN.postDelayed(IDLE, IDLE_DESTROY_MS);
            return;
        }
        MAIN.removeCallbacks(IDLE);
        current = job;
        final int gen = ++generation;
        job.generation = gen;
        try {
            ensureWebView();
            String agent = job.userAgent == null || job.userAgent.isEmpty() ? defaultUserAgent : job.userAgent;
            webView.getSettings().setUserAgentString(agent);
            webView.stopLoading();
            HashMap<String, String> extra = loadHeaders(job.headers);
            if (extra.isEmpty()) webView.loadUrl(job.pageUrl);
            else webView.loadUrl(job.pageUrl, extra);
        } catch (Throwable error) {
            error.printStackTrace();
            current = null;
            job.finish(null, null);
            pump();
            return;
        }
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                timeout(gen);
            }
        }, SNIFF_TIMEOUT_MS);
    }

    private static void timeout(int gen) {
        if (current == null || current.generation != gen) return;
        Job job = current;
        current = null;
        job.finish(null, null);
        blank();
        pump();
    }

    private static void found(int gen, String url, HashMap<String, String> headers) {
        if (current == null || current.generation != gen) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return;
        String cookie = CookieManager.getInstance().getCookie(url);
        if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie.trim());
        Job job = current;
        if (!job.finish(new EpisodeUrlResolver.Result(url, headers), null)) return;
        current = null;
        blank();
        pump();
    }

    private static HashMap<String, String> loadHeaders(HashMap<String, String> headers) {
        HashMap<String, String> extra = new HashMap<>();
        if (headers == null) return extra;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            if (entry.getKey().equalsIgnoreCase("user-agent")) continue;
            extra.put(entry.getKey(), entry.getValue());
        }
        return extra;
    }

    private static void blank() {
        if (webView == null) return;
        webView.stopLoading();
        webView.loadUrl("about:blank");
    }

    private static void destroyIdle() {
        if (current != null) return;
        synchronized (LOCK) {
            if (!QUEUE.isEmpty()) return;
        }
        if (webView == null) return;
        webView.stopLoading();
        webView.removeAllViews();
        webView.destroy();
        webView = null;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void ensureWebView() {
        if (webView != null) return;
        Context context = new ContextThemeWrapper(App.getInstance(), android.R.style.Theme_DeviceDefault);
        CookieManager.getInstance();
        webView = new WebView(context);
        WebSettings settings = webView.getSettings();
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setDatabaseEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBlockNetworkImage(true);
        settings.setUseWideViewPort(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setDefaultTextEncodingName("utf-8");
        defaultUserAgent = settings.getUserAgentString();
        CookieManager manager = CookieManager.getInstance();
        manager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            manager.setAcceptThirdPartyCookies(webView, true);
        }
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                result.cancel();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                result.cancel();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                result.cancel();
                return true;
            }
        });
        webView.setWebViewClient(new SniffClient());
    }

    private static boolean isVideo(Job job, String url) {
        try {
            if (url.contains("url=http") || url.contains(".html")) return false;
            if (VideoParseRuler.isFilter(job.webUrl, url)) return false;
            if (job.source != null && job.source.getType() == 3) {
                Spider spider = ApiConfig.get().getCSP(job.source);
                if (spider != null && spider.manualVideoCheck()) return spider.isVideoFormat(url);
            }
            return VideoParseRuler.checkIsVideoForParse(job.webUrl, url);
        } catch (Exception e) {
            return false;
        }
    }

    private static final class Job {
        final String taskId;
        final String pageUrl;
        final String webUrl;
        final HashMap<String, String> headers;
        final String userAgent;
        final String click;
        final SourceBean source;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean done = new AtomicBoolean(false);
        volatile int generation;
        volatile EpisodeUrlResolver.Result result;
        volatile Exception error;

        Job(String taskId, String pageUrl, String webUrl, HashMap<String, String> headers,
            String userAgent, String click, SourceBean source) {
            this.taskId = taskId;
            this.pageUrl = pageUrl;
            this.webUrl = webUrl;
            this.headers = headers;
            this.userAgent = userAgent;
            this.click = click == null ? "" : click;
            this.source = source;
        }

        boolean finish(EpisodeUrlResolver.Result result, Exception error) {
            if (!done.compareAndSet(false, true)) return false;
            this.result = result;
            this.error = error;
            latch.countDown();
            return true;
        }
    }

    private static final class SniffClient extends WebViewClient {
        @SuppressLint("WebViewClientOnReceivedSslError")
        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.proceed();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            Job job = current;
            if (job == null || url == null || !url.startsWith("http") || clickJs(job, url) == null) return;
            view.loadUrl("javascript:" + clickJs(job, url));
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Job job = current;
            if (job == null) return null;
            String url = request.getUrl().toString();
            if (url.endsWith("/favicon.ico")) return null;
            if (VideoParseRuler.isFilter(job.webUrl, url)) return null;
            if (AdBlocker.isAd(url)) return AdBlocker.createEmptyResource();
            if (!isVideo(job, url)) return null;
            HashMap<String, String> headers = new HashMap<>();
            Map<String, String> incoming = request.getRequestHeaders();
            if (incoming != null) {
                for (Map.Entry<String, String> entry : incoming.entrySet()) {
                    String key = entry.getKey();
                    if (key == null) continue;
                    if (key.equalsIgnoreCase("user-agent") || key.equalsIgnoreCase("referer") || key.equalsIgnoreCase("origin")) {
                        headers.put(key, entry.getValue() == null ? "" : entry.getValue().trim());
                    }
                }
            }
            final int gen = job.generation;
            final String foundUrl = url;
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    found(gen, foundUrl, headers);
                }
            });
            return AdBlocker.createEmptyResource();
        }

        private static String clickJs(Job job, String url) {
            String click = job.click;
            if (click.isEmpty()) return null;
            String selector;
            if (click.contains(";")) {
                String[] parts = click.split(";", 2);
                if (!url.contains(parts[0])) return null;
                selector = parts[1];
            } else {
                selector = click.trim();
            }
            if (selector.isEmpty()) return null;
            return "$(\"" + selector + "\").click();";
        }
    }
}
