package com.miku.wugan;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 域名级广告拦截：只拦域名，不碰页面内容，不做任何 JS/CSS 注入。
 *
 * 匹配规则：精确匹配，或子域名后缀匹配（host 以 "." + 拦截域名结尾）。
 * 请求路径上只做 HashSet 查询，无 I/O，保证 shouldInterceptRequest 不卡。
 */
public class AdBlocker {

    private static final String TAG = "WuganAdblock";
    private static final String HOSTS_FILE = "adblock_hosts.txt";
    /** 完整规则至少上万条，低于此数说明下载内容不对，直接弃用 */
    private static final int MIN_SANE_RULES = 1000;

    private volatile Set<String> blocked = new HashSet<String>();
    private volatile boolean enabled = true;

    public void setEnabled(boolean e) {
        enabled = e;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 该请求是否命中拦截规则（已关闭时一律放行） */
    public boolean shouldBlock(Uri uri) {
        if (!enabled || uri == null) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        Set<String> set = blocked;
        String h = host;
        while (true) {
            if (set.contains(h)) {
                return true;
            }
            int dot = h.indexOf('.');
            if (dot < 0) {
                break;
            }
            h = h.substring(dot + 1);
        }
        return false;
    }

    /**
     * 初始化：首次运行把 assets 里的规则拷到 filesDir，之后一直用 filesDir 的版本
     * （更新规则会覆盖它）。返回加载的规则条数，失败返回 -1。
     */
    public int init(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), HOSTS_FILE);
            if (!f.exists()) {
                InputStream in = ctx.getAssets().open(HOSTS_FILE);
                try {
                    FileOutputStream out = new FileOutputStream(f);
                    try {
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = in.read(buf)) > 0) {
                            out.write(buf, 0, r);
                        }
                    } finally {
                        out.close();
                    }
                } finally {
                    in.close();
                }
            }
            FileInputStream fin = new FileInputStream(f);
            try {
                Set<String> fresh = new HashSet<String>();
                int n = parseInto(fin, fresh);
                blocked = fresh;
                Log.i(TAG, "adblock loaded: " + n + " domains");
                return n;
            } finally {
                fin.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "adblock init failed", e);
            return -1;
        }
    }

    /**
     * 从网络更新规则：先试主源，失败再试备用源。
     * 成功返回新规则条数并持久化；都失败返回 -1。
     */
    public int updateFromNetwork(Context ctx, String primaryUrl, String fallbackUrl) {
        String[] urls = new String[]{primaryUrl, fallbackUrl};
        for (String u : urls) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(u).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(45000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36");
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    continue;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                InputStream in = conn.getInputStream();
                try {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        bos.write(buf, 0, r);
                    }
                } finally {
                    in.close();
                }
                byte[] data = bos.toByteArray();
                Set<String> fresh = new HashSet<String>();
                int n = parseInto(new ByteArrayInputStream(data), fresh);
                if (n < MIN_SANE_RULES) {
                    Log.w(TAG, "adblock update from " + u + " too small (" + n + "), skip");
                    continue;
                }
                File dir = ctx.getFilesDir();
                File tmp = new File(dir, HOSTS_FILE + ".tmp");
                File dst = new File(dir, HOSTS_FILE);
                FileOutputStream out = new FileOutputStream(tmp);
                try {
                    out.write(data);
                } finally {
                    out.close();
                }
                if (!tmp.renameTo(dst)) {
                    continue;
                }
                blocked = fresh;
                Log.i(TAG, "adblock updated: " + n + " domains from " + u);
                return n;
            } catch (Exception e) {
                Log.w(TAG, "adblock update failed for " + u + ": " + e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return -1;
    }

    /**
     * 解析 hosts 格式：跳过空行和 # 注释，每行取最后一个 token 为域名，
     * 跳过 localhost 和 IP 字面量。返回去重后的域名数。
     */
    private static int parseInto(InputStream in, Set<String> out) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash).trim();
            }
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+");
            String domain = parts[parts.length - 1].toLowerCase(Locale.ROOT);
            if (domain.isEmpty()
                    || domain.equals("localhost")
                    || domain.equals("localhost.localdomain")
                    || domain.contains("/")
                    || domain.contains(":")
                    || domain.matches("^[0-9.]+$")) {
                continue;
            }
            out.add(domain);
        }
        return out.size();
    }
}
