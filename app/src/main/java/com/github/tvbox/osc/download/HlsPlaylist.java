package com.github.tvbox.osc.download;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HlsPlaylist {
    boolean master;
    String mapUrl;
    String mapKeyMethod;
    String mapKeyUrl;
    byte[] mapIv;
    long mapByteLength = -1;
    long mapByteOffset = -1;
    final List<Variant> variants = new ArrayList<>();
    final List<Segment> segments = new ArrayList<>();

    static class Variant {
        String url;
        long bandwidth;
    }

    static class Segment {
        int index;
        String url;
        long sequence;
        String keyMethod;
        String keyUrl;
        byte[] iv;
        long byteRangeLength = -1;
        long byteRangeOffset = -1;

        boolean needsAes() {
            return keyMethod != null && "AES-128".equalsIgnoreCase(keyMethod);
        }
    }

    Variant bestVariant() {
        Variant best = variants.get(0);
        for (Variant variant : variants) {
            if (variant.bandwidth > best.bandwidth) best = variant;
        }
        return best;
    }

    static HlsPlaylist parse(String content, String baseUrl) {
        if (content.startsWith("\uFEFF")) content = content.substring(1);
        HlsPlaylist playlist = new HlsPlaylist();
        String[] lines = content.split("\\r?\\n");
        boolean nextVariant = false;
        boolean nextSegment = false;
        long bandwidth = 0;
        String keyMethod = null;
        String keyUrl = null;
        byte[] keyIv = null;
        boolean ivExplicit = false;
        long sequence = 0;
        boolean sequenceSet = false;
        long nextByteLength = -1;
        Long nextByteOffset = null;
        long lastByteEnd = 0;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    playlist.master = true;
                    nextVariant = true;
                    bandwidth = parseBandwidth(line);
                } else if (line.startsWith("#EXT-X-I-FRAME-STREAM-INF")) {
                    continue;
                } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                    sequence = parseLongAfterColon(line);
                    sequenceSet = true;
                } else if (line.startsWith("#EXT-X-DISCONTINUITY") && !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")) {
                    lastByteEnd = 0;
                } else if (line.startsWith("#EXT-X-KEY")) {
                    Map<String, String> attrs = parseAttributes(valueAfterColon(line));
                    String method = attrs.get("METHOD");
                    if (method == null || "NONE".equalsIgnoreCase(method)) {
                        keyMethod = null;
                        keyUrl = null;
                        keyIv = null;
                        ivExplicit = false;
                    } else {
                        keyMethod = method;
                        String uri = attrs.get("URI");
                        keyUrl = uri == null ? null : resolve(baseUrl, uri);
                        String iv = attrs.get("IV");
                        if (iv != null && !iv.isEmpty()) {
                            keyIv = parseHexIv(iv);
                            ivExplicit = keyIv != null;
                        } else {
                            keyIv = null;
                            ivExplicit = false;
                        }
                    }
                } else if (line.startsWith("#EXT-X-MAP")) {
                    Map<String, String> attrs = parseAttributes(valueAfterColon(line));
                    String uri = attrs.get("URI");
                    if (uri != null) playlist.mapUrl = resolve(baseUrl, uri);
                    playlist.mapKeyMethod = keyMethod;
                    playlist.mapKeyUrl = keyUrl;
                    playlist.mapIv = keyIv;
                    String byteRange = attrs.get("BYTERANGE");
                    if (byteRange != null) {
                        long[] range = parseByteRange(byteRange, 0);
                        playlist.mapByteLength = range[0];
                        playlist.mapByteOffset = range[1];
                    }
                } else if (line.startsWith("#EXT-X-BYTERANGE")) {
                    long[] range = parseByteRange(valueAfterColon(line), lastByteEnd);
                    nextByteLength = range[0];
                    nextByteOffset = range[1];
                } else if (line.startsWith("#EXTINF")) {
                    nextSegment = true;
                }
                continue;
            }
            if (nextVariant) {
                Variant variant = new Variant();
                variant.url = resolve(baseUrl, line);
                variant.bandwidth = bandwidth;
                playlist.variants.add(variant);
                nextVariant = false;
                continue;
            }
            if (nextSegment || !playlist.master) {
                Segment segment = new Segment();
                segment.index = playlist.segments.size();
                segment.url = resolve(baseUrl, line);
                segment.sequence = sequenceSet ? sequence : playlist.segments.size();
                sequence++;
                segment.keyMethod = keyMethod;
                segment.keyUrl = keyUrl;
                if (segment.needsAes()) {
                    segment.iv = ivExplicit && keyIv != null ? keyIv : ivFromSequence(segment.sequence);
                }
                if (nextByteLength > 0) {
                    long offset = nextByteOffset == null ? lastByteEnd : nextByteOffset;
                    segment.byteRangeLength = nextByteLength;
                    segment.byteRangeOffset = offset;
                    lastByteEnd = offset + nextByteLength;
                    nextByteLength = -1;
                    nextByteOffset = null;
                }
                playlist.segments.add(segment);
                nextSegment = false;
            }
        }
        return playlist;
    }

    private static String valueAfterColon(String line) {
        int index = line.indexOf(':');
        if (index < 0 || index == line.length() - 1) return "";
        return line.substring(index + 1).trim();
    }

    private static long parseLongAfterColon(String line) {
        try {
            return Long.parseLong(valueAfterColon(line));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseBandwidth(String line) {
        Matcher matcher = Pattern.compile("BANDWIDTH=(\\d+)").matcher(line);
        if (matcher.find()) {
            try {
                return Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static long[] parseByteRange(String value, long fallbackOffset) {
        String[] parts = value.trim().split("@");
        long length = -1;
        long offset = fallbackOffset;
        try {
            length = Long.parseLong(parts[0].trim());
            if (parts.length > 1) offset = Long.parseLong(parts[1].trim());
        } catch (NumberFormatException ignored) {
        }
        return new long[]{length, offset};
    }

    static Map<String, String> parseAttributes(String text) {
        Map<String, String> attributes = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("([A-Za-z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))").matcher(text);
        while (matcher.find()) {
            String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            attributes.put(matcher.group(1).toUpperCase(Locale.US), value == null ? "" : value.trim());
        }
        return attributes;
    }

    static byte[] parseHexIv(String iv) {
        String hex = iv.trim();
        if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
        if (hex.length() != 32) return null;
        byte[] out = new byte[16];
        try {
            for (int i = 0; i < 16; i++) {
                out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    static byte[] ivFromSequence(long sequence) {
        byte[] iv = new byte[16];
        for (int i = 15; i >= 0; i--) {
            iv[i] = (byte) (sequence & 0xFF);
            sequence >>= 8;
        }
        return iv;
    }

    static String resolve(String base, String ref) {
        if (ref == null || ref.isEmpty()) return base;
        try {
            return new URL(new URL(base), ref).toString();
        } catch (MalformedURLException e) {
            try {
                return new URL(new URL(base), ref.replace(" ", "%20")).toString();
            } catch (MalformedURLException ignored) {
                return ref;
            }
        }
    }
}
