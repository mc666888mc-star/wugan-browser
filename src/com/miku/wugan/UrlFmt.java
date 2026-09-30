package com.miku.wugan;

import java.net.URLDecoder;

/** 列表里显示网址用的格式化：%XX 解码成中文，超长截断。只用于显示，不改存的值。 */
public class UrlFmt {
    public static String display(String url) {
        if (url == null) return "";
        try {
            String d = URLDecoder.decode(url, "UTF-8");
            if (d.length() > 120) d = d.substring(0, 120) + "…";
            return d;
        } catch (Exception e) {
            return url.length() > 120 ? url.substring(0, 120) + "…" : url;
        }
    }
}
