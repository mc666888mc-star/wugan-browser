package com.miku.wugan;

import java.util.Locale;

/**
 * 纯 Java 的视频资源识别：给 WebView 被动嗅探用。
 * 零 Android 依赖，可 JVM 单测。
 *
 * v13.3：YouTube 的媒体 URL 是 googlevideo.com/videoplayback?...，
 * path 上没有 .mp4 这类后缀——旧逻辑"去 query 后按后缀判定"永远命中不了。
 */
public final class VideoSniffer {

    private static final String[] VIDEO_EXTS = {
            ".mp4", ".m3u8", ".webm", ".mov", ".flv", ".m4v"
    };

    private VideoSniffer() {
    }

    public static boolean isVideoUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        // YouTube：无后缀，靠 host + path 识别
        if (lower.contains("googlevideo.com")
                && lower.contains("/videoplayback")) {
            return true;
        }
        // 通用：去 query/fragment 后按后缀判定
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        int h = u.indexOf('#');
        if (h >= 0) {
            u = u.substring(0, h);
        }
        u = u.toLowerCase(Locale.ROOT);
        for (String ext : VIDEO_EXTS) {
            if (u.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
}
