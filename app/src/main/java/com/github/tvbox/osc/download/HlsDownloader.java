package com.github.tvbox.osc.download;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class HlsDownloader {
    private static final int WORKERS = 4;
    private final VideoDownloadManager manager;

    HlsDownloader(VideoDownloadManager manager) {
        this.manager = manager;
    }

    void download(DownloadTask task) throws Exception {
        if (manager.shouldStop(task.id)) throw new DownloadStoppedException();
        final HlsPlaylist playlist = resolvePlaylist(task);

        File dir = DownloadPaths.workDir(task);
        File segDir = new File(dir, "seg");
        if (!segDir.exists() && !segDir.mkdirs()) throw new IOException("无法创建下载目录");
        task.workDir = dir.getAbsolutePath();
        task.type = DownloadTask.TYPE_HLS;
        task.segmentTotal = playlist.segments.size();
        manager.save();

        ConcurrentHashMap<String, byte[]> keys = new ConcurrentHashMap<>();
        if (playlist.mapUrl != null) {
            File init = new File(dir, "init");
            if (!init.exists() || init.length() == 0) {
                retry(task, new Attempt() {
                    @Override
                    public void run() throws Exception {
                        downloadMaybeEncrypted(task, playlist.mapUrl, playlist.mapKeyMethod, playlist.mapKeyUrl, playlist.mapIv,
                                playlist.mapByteOffset, playlist.mapByteLength, init, keys);
                    }
                });
            }
        }

        int already = 0;
        for (HlsPlaylist.Segment segment : playlist.segments) {
            File file = segmentFile(segDir, segment);
            if (file.exists() && file.length() > 0) already++;
        }
        final AtomicInteger done = new AtomicInteger(already);
        manager.updateSegmentProgress(task, already, playlist.segments.size());
        final AtomicReference<Exception> failure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        for (final HlsPlaylist.Segment segment : playlist.segments) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    if (failure.get() != null || manager.shouldStop(task.id)) return;
                    File out = segmentFile(segDir, segment);
                    if (out.exists() && out.length() > 0) return;
                    try {
                        retry(task, new Attempt() {
                            @Override
                            public void run() throws Exception {
                                File tmp = new File(segDir, String.format(Locale.US, "%05d.part", segment.index + 1));
                                if (tmp.exists()) tmp.delete();
                                downloadMaybeEncrypted(task, segment.url, segment.keyMethod, segment.keyUrl, segment.iv,
                                        segment.byteRangeOffset, segment.byteRangeLength, tmp, keys);
                                if (out.exists()) out.delete();
                                if (!tmp.renameTo(out)) throw new IOException("保存分片失败");
                            }
                        });
                        manager.updateSegmentProgress(task, done.incrementAndGet(), playlist.segments.size());
                    } catch (Exception e) {
                        failure.compareAndSet(null, e);
                    }
                }
            });
        }
        pool.shutdown();
        try {
            while (!pool.awaitTermination(400, TimeUnit.MILLISECONDS)) {
                if (manager.shouldStop(task.id) || failure.get() != null) {
                    DownloadHttp.cancel(task.id);
                    pool.shutdownNow();
                }
            }
        } catch (InterruptedException e) {
            DownloadHttp.cancel(task.id);
            pool.shutdownNow();
            Thread.currentThread().interrupt();
            throw new DownloadStoppedException();
        }
        if (manager.shouldStop(task.id)) throw new DownloadStoppedException();
        Exception error = failure.get();
        if (error instanceof DownloadStoppedException) throw (DownloadStoppedException) error;
        if (error != null) {
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException(error.getMessage() == null ? "分片下载失败" : error.getMessage());
        }
        for (HlsPlaylist.Segment segment : playlist.segments) {
            File file = segmentFile(segDir, segment);
            if (!file.exists() || file.length() == 0) throw new IOException("分片缺失");
        }
        if (manager.shouldStop(task.id)) throw new DownloadStoppedException();

        String ext = playlist.mapUrl != null ? ".mp4" : ".ts";
        File merged = new File(dir, "merged" + ext);
        if (merged.exists()) merged.delete();
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(merged))) {
            if (playlist.mapUrl != null) {
                File init = new File(dir, "init");
                if (!init.exists() || init.length() == 0) throw new IOException("初始化分片缺失");
                copy(init, output);
            }
            for (HlsPlaylist.Segment segment : playlist.segments) {
                if (manager.shouldStop(task.id)) throw new DownloadStoppedException();
                copy(segmentFile(segDir, segment), output);
            }
        } catch (Exception e) {
            if (merged.exists()) merged.delete();
            throw e;
        }
        File out = DownloadPaths.reserveFile(DownloadPaths.movieDir(), task.title, ext);
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(out))) {
            copy(merged, output);
        } catch (Exception e) {
            if (out.exists()) out.delete();
            throw e;
        }
        merged.delete();
        DownloadPaths.deleteRecursive(dir);
        if (DownloadMedia.isTextFile(out) || DownloadMedia.isTinyJunk(out)) {
            out.delete();
            throw new IOException(DownloadMedia.NOT_VIDEO);
        }
        task.workDir = null;
        task.filePath = out.getAbsolutePath();
        task.downloadedBytes = out.length();
        task.totalBytes = out.length();
        task.segmentDone = playlist.segments.size();
        task.progress = 100;
        manager.save();
    }

    private HlsPlaylist loadPlaylist(DownloadTask task, String url) throws Exception {
        String text = getText(task, url);
        return HlsPlaylist.parse(text, url);
    }

    private HlsPlaylist resolvePlaylist(DownloadTask task) throws Exception {
        HlsPlaylist playlist = loadPlaylist(task, task.url);
        if (playlist.master) {
            if (playlist.variants.isEmpty()) throw new IOException("播放列表没有可用清晰度");
            playlist = loadPlaylist(task, playlist.bestVariant().url);
            if (playlist.master || playlist.segments.isEmpty()) throw new IOException("无法解析播放列表");
        }
        if (playlist.segments.isEmpty()) throw new IOException("播放列表没有分片");
        ensureSupported(playlist);
        return playlist;
    }

    private void ensureSupported(HlsPlaylist playlist) throws IOException {
        if (isUnsupported(playlist.mapKeyMethod)) {
            throw new IOException("不支持的加密方式: " + playlist.mapKeyMethod);
        }
        for (HlsPlaylist.Segment segment : playlist.segments) {
            if (isUnsupported(segment.keyMethod)) {
                throw new IOException("不支持的加密方式: " + segment.keyMethod);
            }
            if (segment.needsAes() && (segment.keyUrl == null || segment.iv == null)) {
                throw new IOException("缺少解密信息");
            }
        }
    }

    private static boolean isUnsupported(String method) {
        return method != null && !"AES-128".equalsIgnoreCase(method) && !"NONE".equalsIgnoreCase(method);
    }

    private void downloadMaybeEncrypted(DownloadTask task, String url, String method, String keyUrl, byte[] iv,
                                        long rangeOffset, long rangeLength, File dest,
                                        ConcurrentHashMap<String, byte[]> keys) throws Exception {
        if (!"AES-128".equalsIgnoreCase(method)) {
            downloadToFile(task, url, dest, rangeOffset, rangeLength);
            return;
        }
        File encrypted = new File(dest.getAbsolutePath() + ".enc");
        if (encrypted.exists()) encrypted.delete();
        try {
            downloadToFile(task, url, encrypted, rangeOffset, rangeLength);
            byte[] key = keyFor(task, keyUrl, keys);
            decrypt(encrypted, dest, key, iv);
        } finally {
            if (encrypted.exists()) encrypted.delete();
        }
    }

    private byte[] keyFor(DownloadTask task, String url, ConcurrentHashMap<String, byte[]> keys) throws Exception {
        byte[] cached = keys.get(url);
        if (cached != null) return cached;
        synchronized (keys) {
            cached = keys.get(url);
            if (cached != null) return cached;
            byte[] key = downloadKey(task, url);
            keys.put(url, key);
            return key;
        }
    }

    private byte[] downloadKey(DownloadTask task, String url) throws Exception {
        checkHttp(url);
        Call call = DownloadHttp.enqueueCall(task.id, DownloadHttp.request(url, task.headers).build());
        try (Response response = call.execute()) {
            if (manager.shouldStop(task.id)) throw new DownloadStoppedException();
            if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
            byte[] data = readLimited(response.body().byteStream(), task.id, 256);
            if (data.length != 16) throw new IOException("密钥长度不正确");
            return data;
        } catch (DownloadStoppedException e) {
            throw e;
        } catch (IOException e) {
            throw DownloadHttp.asStoppedOrIo(manager, task.id, e);
        } finally {
            DownloadHttp.release(task.id, call);
        }
    }

    private void downloadToFile(DownloadTask task, String url, File dest, long rangeOffset, long rangeLength) throws Exception {
        checkHttp(url);
        okhttp3.Request.Builder builder = DownloadHttp.request(url, task.headers);
        if (rangeLength > 0) {
            long end = rangeOffset + rangeLength - 1;
            builder.header("Range", "bytes=" + rangeOffset + "-" + end);
        }
        Call call = DownloadHttp.enqueueCall(task.id, builder.build());
        try (Response response = call.execute()) {
            if (manager.shouldStop(task.id)) throw new DownloadStoppedException();
            if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (InputStream input = response.body().byteStream();
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(dest))) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (manager.shouldStop(task.id)) throw new DownloadStoppedException();
                    output.write(buffer, 0, read);
                }
            }
            if (dest.length() <= 0) throw new IOException("分片内容为空");
            if (DownloadMedia.isTextFile(dest)) {
                dest.delete();
                throw new IOException(DownloadMedia.NOT_VIDEO);
            }
        } catch (DownloadStoppedException e) {
            if (dest.exists()) dest.delete();
            throw e;
        } catch (IOException e) {
            if (dest.exists()) dest.delete();
            throw DownloadHttp.asStoppedOrIo(manager, task.id, e);
        } finally {
            DownloadHttp.release(task.id, call);
        }
    }

    private String getText(DownloadTask task, String url) throws Exception {
        checkHttp(url);
        Call call = DownloadHttp.enqueueCall(task.id, DownloadHttp.request(url, task.headers).build());
        try (Response response = call.execute()) {
            if (manager.shouldStop(task.id)) throw new DownloadStoppedException();
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) throw new IOException("HTTP " + response.code());
            byte[] data = readLimited(body.byteStream(), task.id, 20 * 1024 * 1024);
            String text = new String(data, "UTF-8");
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            if (!text.contains("#EXTM3U")) throw new IOException("不是有效的播放列表");
            return text;
        } catch (DownloadStoppedException e) {
            throw e;
        } catch (IOException e) {
            throw DownloadHttp.asStoppedOrIo(manager, task.id, e);
        } finally {
            DownloadHttp.release(task.id, call);
        }
    }

    private byte[] readLimited(InputStream input, String taskId, int max) throws IOException, DownloadStoppedException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (manager.shouldStop(taskId)) throw new DownloadStoppedException();
            total += read;
            if (total > max) throw new IOException("内容过大");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void decrypt(File source, File dest, byte[] key, byte[] iv) throws Exception {
        try {
            decryptWith(source, dest, key, iv, "AES/CBC/PKCS5Padding");
        } catch (GeneralSecurityException e) {
            if (dest.exists()) dest.delete();
            decryptWith(source, dest, key, iv, "AES/CBC/NoPadding");
        }
    }

    private static void decryptWith(File source, File dest, byte[] key, byte[] iv, String transformation) throws Exception {
        Cipher cipher = Cipher.getInstance(transformation);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             OutputStream output = new BufferedOutputStream(new FileOutputStream(dest))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                byte[] updated = cipher.update(buffer, 0, read);
                if (updated != null && updated.length > 0) output.write(updated);
            }
            byte[] finished = cipher.doFinal();
            if (finished != null && finished.length > 0) output.write(finished);
        }
    }

    private static File segmentFile(File segDir, HlsPlaylist.Segment segment) {
        return new File(segDir, String.format(Locale.US, "%05d.ts", segment.index + 1));
    }

    private static void checkHttp(String url) throws IOException {
        if (url == null) throw new IOException("地址无效");
        String lower = url.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IOException("该资源不支持下载");
        }
    }

    private void retry(DownloadTask task, Attempt attempt) throws Exception {
        Exception last = null;
        for (int i = 0; i < 3; i++) {
            if (manager.shouldStop(task.id)) throw new DownloadStoppedException();
            try {
                attempt.run();
                return;
            } catch (DownloadStoppedException e) {
                throw e;
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) throw last;
        throw new IOException("下载失败");
    }

    private interface Attempt {
        void run() throws Exception;
    }

    private static void copy(File source, OutputStream output) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(source))) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static void copy(File source, File dest) throws IOException {
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(dest))) {
            copy(source, output);
        }
    }
}
