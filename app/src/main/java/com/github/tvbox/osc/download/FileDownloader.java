package com.github.tvbox.osc.download;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class FileDownloader {
    private final VideoDownloadManager manager;

    FileDownloader(VideoDownloadManager manager) {
        this.manager = manager;
    }

    void download(DownloadTask task) throws Exception {
        if (manager.shouldStop(task.id)) throw new DownloadStoppedException();
        File part = DownloadPaths.partFile(task);
        if (task.filePath == null || task.filePath.isEmpty()) {
            File dest = DownloadPaths.reserveFile(DownloadPaths.movieDir(), task.title, DownloadPaths.extensionFromUrl(task.url));
            task.filePath = dest.getAbsolutePath();
            task.type = DownloadTask.TYPE_FILE;
            manager.save();
        }
        File dest = new File(task.filePath);
        if (dest.exists() && dest.length() > 0 && !(part.exists() && part.length() > 0)) {
            if (DownloadMedia.isFinalFile(dest) && !DownloadMedia.isTinyJunk(dest)) {
                task.downloadedBytes = dest.length();
                task.totalBytes = dest.length();
                task.progress = 100;
                return;
            }
            dest.delete();
        }
        try {
            downloadOnce(task, dest, part, true);
        } catch (RangeRejectedException ignored) {
            if (part.exists()) part.delete();
            downloadOnce(task, dest, part, false);
        }
    }

    private void downloadOnce(DownloadTask task, File dest, File part, boolean allowRange) throws Exception {
        long existing = part.exists() ? part.length() : 0;
        okhttp3.Request.Builder builder = DownloadHttp.request(task.url, task.headers);
        if (allowRange && existing > 0) {
            builder.header("Range", "bytes=" + existing + "-");
        }
        Call call = DownloadHttp.enqueueCall(task.id, builder.build());
        try (Response response = call.execute()) {
            if (manager.shouldStop(task.id)) throw new DownloadStoppedException();
            int code = response.code();
            if (code == 416 && allowRange && existing > 0) {
                throw new RangeRejectedException();
            }
            if (code != 200 && code != 206) {
                throw new IOException("HTTP " + code);
            }
            ResponseBody body = response.body();
            if (body == null) throw new IOException("下载内容为空");

            boolean append = code == 206 && existing > 0;
            long downloaded = append ? existing : 0;
            if (!append && part.exists()) part.delete();

            BufferedInputStream input = new BufferedInputStream(body.byteStream());
            if (!append) {
                byte[] peek = peek(input);
                String type = response.header("Content-Type");
                if (DownloadMedia.isPlaylist(peek, peek.length) || DownloadMedia.isMpegUrl(type)) {
                    input.close();
                    response.close();
                    switchToHls(task, dest, part);
                    return;
                }
                if (DownloadMedia.isRejectedType(type) || DownloadMedia.isText(peek, peek.length)) {
                    throw new IOException(DownloadMedia.NOT_VIDEO);
                }
                retargetExtension(task, dest, type);
                dest = new File(task.filePath);
            }

            long total = totalBytes(response, body, downloaded);
            File parent = part.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (OutputStream output = new BufferedOutputStream(new FileOutputStream(part, append))) {
                byte[] buffer = new byte[8192];
                long reported = downloaded;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (manager.shouldStop(task.id)) {
                        output.flush();
                        throw new DownloadStoppedException();
                    }
                    output.write(buffer, 0, read);
                    downloaded += read;
                    if (downloaded - reported >= 256 * 1024) {
                        manager.updateFileProgress(task, downloaded, total);
                        reported = downloaded;
                    }
                }
                output.flush();
            }
            if (manager.shouldStop(task.id)) throw new DownloadStoppedException();
            if (downloaded <= 0) {
                if (part.exists()) part.delete();
                throw new IOException("下载内容为空");
            }
            if (DownloadMedia.isTextFile(part) || DownloadMedia.isTinyJunk(part)) {
                if (part.exists()) part.delete();
                throw new IOException(DownloadMedia.NOT_VIDEO);
            }
            if (total > 0 && downloaded < total) {
                manager.updateFileProgress(task, downloaded, total);
                throw new IOException("下载未完成");
            }
            if (dest.exists() && dest.length() == 0 && !dest.delete()) {
                throw new IOException("无法保存文件");
            }
            if (!part.renameTo(dest)) {
                copyFile(part, dest);
                part.delete();
            }
            task.filePath = dest.getAbsolutePath();
            task.downloadedBytes = dest.length();
            task.totalBytes = dest.length();
            task.progress = 100;
            manager.save();
        } catch (DownloadStoppedException e) {
            throw e;
        } catch (RangeRejectedException e) {
            throw e;
        } catch (IOException e) {
            throw DownloadHttp.asStoppedOrIo(manager, task.id, e);
        } finally {
            DownloadHttp.release(task.id, call);
        }
    }

    private void retargetExtension(DownloadTask task, File dest, String contentType) throws IOException {
        if (dest.length() > 0) return;
        String current = extensionOf(dest.getName());
        String wanted = DownloadPaths.extensionFromContentType(contentType, current);
        if (wanted.equalsIgnoreCase(current)) return;
        File updated = DownloadPaths.reserveFile(dest.getParentFile(), stripExtension(task.title), wanted);
        if (dest.exists() && dest.length() == 0) dest.delete();
        task.filePath = updated.getAbsolutePath();
        manager.save();
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "";
        return name.substring(dot);
    }

    private static String stripExtension(String title) {
        return title == null ? "video" : title;
    }

    private static byte[] peek(BufferedInputStream input) throws IOException {
        input.mark(1024);
        byte[] buf = new byte[512];
        int count = input.read(buf);
        input.reset();
        if (count <= 0) return new byte[0];
        if (count == buf.length) return buf;
        byte[] exact = new byte[count];
        System.arraycopy(buf, 0, exact, 0, count);
        return exact;
    }

    private void switchToHls(DownloadTask task, File dest, File part) throws Exception {
        if (dest.exists() && dest.length() == 0) dest.delete();
        if (part.exists()) part.delete();
        task.filePath = null;
        task.type = DownloadTask.TYPE_HLS;
        manager.save();
        new HlsDownloader(manager).download(task);
    }

    private static long totalBytes(Response response, ResponseBody body, long downloaded) {
        String range = response.header("Content-Range");
        if (range != null) {
            int slash = range.lastIndexOf('/');
            if (slash >= 0) {
                String totalText = range.substring(slash + 1).trim();
                if (!"*".equals(totalText)) {
                    try {
                        return Long.parseLong(totalText);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        long length = body.contentLength();
        if (length >= 0) return downloaded + length;
        return -1;
    }

    private static void copyFile(File from, File to) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(from));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(to))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static class RangeRejectedException extends Exception {
    }
}
