package com.github.tvbox.osc.download;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * 区分真实视频数据和解析页、JSON、脚本。
 */
final class DownloadMedia {
    static final String NOT_VIDEO = "该资源不是视频";

    private static final Charset LATIN = Charset.forName("ISO-8859-1");
    private static final int HEAD = 512;

    private DownloadMedia() {
    }

    static boolean isRejectedType(String contentType) {
        if (contentType == null) return false;
        String type = contentType.toLowerCase(Locale.US);
        return type.contains("text/html") || type.contains("xhtml")
                || type.contains("application/json") || type.contains("text/json")
                || type.contains("javascript")
                || type.contains("text/xml") || type.contains("application/xml")
                || type.contains("image/");
    }

    static boolean isPlaylist(byte[] data, int length) {
        return findAscii(data, length, "#EXTM3U", length) >= 0;
    }

    static boolean isMpegUrl(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.US).contains("mpegurl");
    }

    static boolean isText(byte[] data, int length) {
        int offset = skipPrefix(data, length);
        if (offset >= length) return false;
        int b = data[offset] & 0xFF;
        if (b == '{' || b == '[') return true;
        String head = new String(data, offset, Math.min(length - offset, 24), LATIN).toLowerCase(Locale.US);
        return head.startsWith("<html") || head.startsWith("<!doctype") || head.startsWith("<?xml")
                || head.startsWith("var ") || head.startsWith("function");
    }

    static boolean isContainer(byte[] data, int length) {
        if (length >= 8 && findAscii(data, length, "ftyp", 32) >= 0) return true;
        if (length >= 3 && data[0] == 'F' && data[1] == 'L' && data[2] == 'V') return true;
        if (length >= 4 && (data[0] & 0xFF) == 0x1A && (data[1] & 0xFF) == 0x45
                && (data[2] & 0xFF) == 0xDF && (data[3] & 0xFF) == 0xA3) return true;
        if (length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F') return true;
        if (length >= 8 && (findAscii(data, length, "moof", 32) >= 0
                || findAscii(data, length, "mdat", 64) >= 0
                || findAscii(data, length, "styp", 32) >= 0)) return true;
        if (isTransportStream(data, length)) return true;
        if (length >= 3 && data[0] == 'I' && data[1] == 'D' && data[2] == '3') return true;
        return length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xE0) == 0xE0;
    }

    /** 地址探测：必须是播放列表或容器头，不能只是 HTTP 成功 */
    static boolean isMediaProbe(byte[] data, int length, String contentType) {
        if (length <= 0 || isRejectedType(contentType) || isText(data, length)) return false;
        if (isPlaylist(data, length) || isContainer(data, length)) return true;
        if (contentType == null) return false;
        String type = contentType.toLowerCase(Locale.US);
        return type.startsWith("video/") || type.contains("mp2t") || type.contains("mp4");
    }

    static boolean isTextFile(File file) {
        try {
            byte[] head = readHead(file);
            return isText(head, head.length);
        } catch (IOException e) {
            return false;
        }
    }

    /** 没有容器头的几 KB 响应，是拦截页或错误信息，不是视频 */
    static boolean isTinyJunk(File file) {
        if (file == null || !file.isFile() || file.length() <= 0 || file.length() >= 8 * 1024) return false;
        try {
            byte[] head = readHead(file);
            return !isContainer(head, head.length);
        } catch (IOException e) {
            return true;
        }
    }

    static boolean isFinalFile(File file) {
        if (file == null || !file.isFile() || file.length() <= 0) return false;
        try {
            byte[] head = readHead(file);
            return !isText(head, head.length);
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readHead(File file) throws IOException {
        byte[] buf = new byte[HEAD];
        try (InputStream in = new FileInputStream(file)) {
            int read = in.read(buf);
            if (read <= 0) return new byte[0];
            if (read == buf.length) return buf;
            byte[] exact = new byte[read];
            System.arraycopy(buf, 0, exact, 0, read);
            return exact;
        }
    }

    private static int skipPrefix(byte[] data, int length) {
        int i = 0;
        if (length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        while (i < length) {
            int b = data[i] & 0xFF;
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n') break;
            i++;
        }
        return i;
    }

    private static boolean matches(byte[] data, int length, int offset, String text) {
        if (offset < 0 || offset + text.length() > length) return false;
        for (int i = 0; i < text.length(); i++) {
            if (data[offset + i] != text.charAt(i)) return false;
        }
        return true;
    }

    private static int findAscii(byte[] data, int length, String text, int limit) {
        int end = Math.min(length, limit);
        for (int i = 0; i + text.length() <= end; i++) {
            if (matches(data, length, i, text)) return i;
        }
        return -1;
    }

    private static boolean isTransportStream(byte[] data, int length) {
        if (length < 188 || (data[0] & 0xFF) != 0x47) return false;
        if (length > 188 && (data[188] & 0xFF) != 0x47) return false;
        return length <= 376 || (data[376] & 0xFF) == 0x47;
    }
}
