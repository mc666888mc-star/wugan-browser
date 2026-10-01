package com.miku.wugan;

import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v12.0：VPN 开启时，WebView 的 http/https GET 请求经本机 SOCKS5 代理出去。
 *
 * 只接管 GET——WebResourceRequest 拿不到 POST 的 body，接管了也发不出去，
 * 所以 POST 返回 null 让 WebView 自己直连（登录类请求暂走直连）。
 * 任何异常都返回 null，绝不把页面搞崩。
 *
 * Cookie 双向同步：请求带上 CookieManager 里的，响应的 Set-Cookie 写回去，
 * 否则登录态会丢。
 */
public class VpnFetch {

    public static WebResourceResponse fetch(WebResourceRequest req) {
        if (!VpnManager.isOn()) {
            return null;
        }
        if (!"GET".equalsIgnoreCase(req.getMethod())) {
            return null;
        }
        String urlStr = req.getUrl().toString();
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            return null;
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection(VpnManager.proxy());
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            // 请求头原样带过去；Accept-Encoding 跳过，让 HttpURLConnection 自己决定
            //（否则 gzip 回来我们还得手动解）
            Map<String, String> rh = req.getRequestHeaders();
            if (rh != null) {
                for (Map.Entry<String, String> e : rh.entrySet()) {
                    if ("Accept-Encoding".equalsIgnoreCase(e.getKey())) {
                        continue;
                    }
                    try {
                        c.setRequestProperty(e.getKey(), e.getValue());
                    } catch (Exception ignored) {
                    }
                }
            }
            // Cookie 带上
            try {
                String cookie = CookieManager.getInstance().getCookie(urlStr);
                if (cookie != null && !cookie.isEmpty()) {
                    c.setRequestProperty("Cookie", cookie);
                }
            } catch (Exception ignored) {
            }

            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) {
                return null;
            }

            // Set-Cookie 写回 CookieManager
            try {
                CookieManager cm = CookieManager.getInstance();
                for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                    if (e.getKey() != null
                            && "set-cookie".equalsIgnoreCase(e.getKey())
                            && e.getValue() != null) {
                        for (String sc : e.getValue()) {
                            try {
                                cm.setCookie(urlStr, sc);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            String mime = "text/plain";
            String enc = null;
            String ct = c.getContentType();
            if (ct != null) {
                String[] parts = ct.split(";");
                if (parts.length > 0) {
                    mime = parts[0].trim();
                }
                for (int i = 1; i < parts.length; i++) {
                    String p = parts[i].trim();
                    if (p.regionMatches(true, 0, "charset=", 0, 8)) {
                        enc = p.substring(8).trim();
                    }
                }
            }
            Map<String, String> respHeaders = new HashMap<>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null && e.getValue() != null
                        && !e.getValue().isEmpty()) {
                    respHeaders.put(e.getKey(), e.getValue().get(0));
                }
            }
            return new WebResourceResponse(mime, enc, code, "", respHeaders, in);
        } catch (Exception e) {
            return null;
        }
    }
}
