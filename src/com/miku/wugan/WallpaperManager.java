package com.miku.wugan;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 壁纸库：全部本地、离线可用。
 * 目录 getFilesDir()/wallpapers/；默认壁纸随 APK 发布，首次启动拷贝入库。
 * 轮换：每次打开主页按文件名顺序取下一张（序号持久化、循环）。
 */
public class WallpaperManager {

    public static final String PREFS = "wugan_prefs";
    private static final String KEY_CURRENT = "wallpaper_current";
    private static final String KEY_ROTATE = "wallpaper_rotate";
    private static final String KEY_INDEX = "wallpaper_index";
    private static final String KEY_SEEDED = "wallpaper_seeded";
    private static final String DIR = "wallpapers";
    private static final String DEFAULT_ASSET = "default_wallpaper.jpg";

    private WallpaperManager() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    /** 首次启动：把 assets 默认壁纸拷入库并设为当前 */
    public static void ensureSeeded(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean(KEY_SEEDED, false)) {
            return;
        }
        File dst = new File(dir(c), DEFAULT_ASSET);
        InputStream in = null;
        OutputStream out = null;
        try {
            in = c.getAssets().open(DEFAULT_ASSET);
            out = new FileOutputStream(dst);
            copy(in, out);
            p.edit().putBoolean(KEY_SEEDED, true)
                    .putString(KEY_CURRENT, dst.getName()).apply();
        } catch (IOException e) {
            // 失败就留空库，主页用深色渐变兜底，不崩
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    /** 库内壁纸（按文件名排序，保证轮换顺序稳定） */
    public static File[] list(Context c) {
        File[] fs = dir(c).listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                String n = name.toLowerCase();
                return n.endsWith(".jpg") || n.endsWith(".jpeg")
                        || n.endsWith(".png") || n.endsWith(".webp")
                        || n.endsWith(".gif");
            }
        });
        if (fs == null) {
            return new File[0];
        }
        Arrays.sort(fs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        return fs;
    }

    public static boolean isRotate(Context c) {
        return prefs(c).getBoolean(KEY_ROTATE, false);
    }

    public static void setRotate(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_ROTATE, on).apply();
    }

    /**
     * 当前壁纸：rotate 开 → 每次调用取下一张（序号持久化、循环）；
     * 关 → 固定标记的那张；标记失效回落第一张；库空返回 null。
     */
    public static File current(Context c) {
        File[] all = list(c);
        if (all.length == 0) {
            return null;
        }
        SharedPreferences p = prefs(c);
        if (isRotate(c)) {
            int idx = p.getInt(KEY_INDEX, 0) % all.length;
            File f = all[idx];
            p.edit().putInt(KEY_INDEX, (idx + 1) % all.length)
                    .putString(KEY_CURRENT, f.getName()).apply();
            return f;
        }
        String name = p.getString(KEY_CURRENT, null);
        if (name != null) {
            File f = new File(dir(c), name);
            if (f.exists()) {
                return f;
            }
        }
        return all[0];
    }

    public static void setCurrent(Context c, File f) {
        if (f != null) {
            prefs(c).edit().putString(KEY_CURRENT, f.getName()).apply();
        }
    }

    public static String currentName(Context c) {
        return prefs(c).getString(KEY_CURRENT, null);
    }

    /** 相册选图入库并设为当前；失败返回 null */
    public static File addFromUri(Context c, Uri uri) {
        File dst = new File(dir(c), "wp_" + System.currentTimeMillis() + ".jpg");
        InputStream in = null;
        OutputStream out = null;
        try {
            in = c.getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            out = new FileOutputStream(dst);
            copy(in, out);
            if (dst.length() < 1024) {
                dst.delete();
                return null;
            }
            setCurrent(c, dst);
            return dst;
        } catch (IOException e) {
            dst.delete();
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    /**
     * 网页图片下载入库并设为当前。只处理 http/https。
     *
     * @return null=成功，否则为失败原因文案
     */
    public static String downloadFromUrl(Context c, String url) {
        if (url == null) {
            return c.getString(R.string.err_bad_url);
        }
        String u = url.toLowerCase();
        if (u.startsWith("data:")) {
            return c.getString(R.string.err_data_url);
        }
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            return c.getString(R.string.err_scheme);
        }
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        File dst = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                            + "Chrome/120.0.0.0 Safari/537.36");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                return c.getString(R.string.err_http, code);
            }
            String ct = conn.getContentType();
            if (ct == null || !ct.toLowerCase().startsWith("image/")) {
                return c.getString(R.string.err_not_image);
            }
            String lc = ct.toLowerCase();
            String ext = ".jpg";
            if (lc.contains("png")) {
                ext = ".png";
            } else if (lc.contains("webp")) {
                ext = ".webp";
            } else if (lc.contains("gif")) {
                ext = ".gif";
            }
            dst = new File(dir(c), "wp_" + System.currentTimeMillis() + ext);
            in = conn.getInputStream();
            out = new FileOutputStream(dst);
            copy(in, out);
            if (dst.length() < 1024) {
                dst.delete();
                return c.getString(R.string.err_too_small);
            }
            setCurrent(c, dst);
            return null;
        } catch (IOException e) {
            if (dst != null) {
                dst.delete();
            }
            return c.getString(R.string.err_download, e.getMessage());
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 删除壁纸；删的是当前壁纸则清空标记（下次回落第一张） */
    public static boolean delete(Context c, File f) {
        if (f == null || !f.exists()) {
            return false;
        }
        boolean ok = f.delete();
        SharedPreferences p = prefs(c);
        if (f.getName().equals(p.getString(KEY_CURRENT, ""))) {
            p.edit().remove(KEY_CURRENT).apply();
        }
        return ok;
    }

    private static void copy(InputStream in, OutputStream out)
            throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
