package com.github.tvbox.osc.download;

import android.net.Uri;
import android.os.Environment;

import com.github.tvbox.osc.base.App;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

final class DownloadPaths {
    private DownloadPaths() {
    }

    static File movieDir() {
        File base = App.getInstance().getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (base == null) {
            base = App.getInstance().getFilesDir();
        }
        File dir = new File(base, "MBox");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    static File partFile(DownloadTask task) {
        return new File(movieDir(), task.id + ".part");
    }

    static File workDir(DownloadTask task) {
        return new File(movieDir(), "hls_" + task.id);
    }

    static boolean looksLikeM3u8(String url) {
        if (url == null) return false;
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        int hash = path.indexOf('#');
        if (hash >= 0) path = path.substring(0, hash);
        return path.toLowerCase(Locale.US).contains(".m3u8");
    }

    static String extensionFromUrl(String url) {
        try {
            String path = Uri.parse(url).getPath();
            if (path != null) {
                int dot = path.lastIndexOf('.');
                if (dot >= 0 && dot < path.length() - 1) {
                    String ext = path.substring(dot).toLowerCase(Locale.US);
                    if (ext.matches("\\.[a-z0-9]{1,4}") && !".m3u8".equals(ext)) {
                        return ext;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return ".mp4";
    }

    static String extensionFromContentType(String contentType, String fallback) {
        if (contentType == null) return fallback;
        String type = contentType.toLowerCase(Locale.US);
        if (type.contains("matroska") || type.contains("mkv")) return ".mkv";
        if (type.contains("webm")) return ".webm";
        if (type.contains("mp4")) return ".mp4";
        if (type.contains("quicktime")) return ".mov";
        if (type.contains("flv")) return ".flv";
        if (type.contains("mpeg") || type.contains("mp2t")) return ".ts";
        if (type.contains("avi")) return ".avi";
        return fallback;
    }

    static String sanitize(String name) {
        if (name == null) return "video";
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", "_").trim();
        if (cleaned.length() > 80) cleaned = cleaned.substring(0, 80).trim();
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) return "video";
        return cleaned;
    }

    static synchronized File reserveFile(File dir, String title, String ext) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建下载目录");
        }
        String suffix = ext == null || ext.isEmpty() ? ".mp4" : ext;
        File file = new File(dir, sanitize(title) + suffix);
        int index = 2;
        while (!file.createNewFile()) {
            file = new File(dir, sanitize(title) + "_" + index + suffix);
            index++;
            if (index > 999) {
                throw new IOException("无法创建文件");
            }
        }
        return file;
    }

    static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
}
