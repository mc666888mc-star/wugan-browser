package com.miku.wugan;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.URLUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内置下载器：单例，后台 3 并发，任务只在进程内有效。
 * - 普通文件：断点续传（Range）。API 29+ 走 MediaStore（Download/无感浏览器/），
 *   26-28 走公共 Download 目录（需 WRITE_EXTERNAL_STORAGE）。
 * - m3u8：拉媒体 playlist，按顺序下载分片后直接拼成 .ts（TS 分片字节拼接可播）；
 *   加密流（EXT-X-KEY）直接报失败，不硬拼。
 * - 下载带上 WebView 的 Cookie（如 cf_clearance），过验证站点的视频也能下。
 * - 速度：2 秒滑动窗口；ETA = 剩余量 / 速度。
 */
public class Downloader {

    private static final String TAG = "WuganDL";
    static final String DIR_NAME = "无感浏览器";
    private static final int CONN_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;
    // 桌面 Chrome Mobile UA：部分 CDN 会拦 Java 默认 UA
    private static final String DL_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static Downloader instance;

    public static synchronized Downloader get(Context ctx) {
        if (instance == null) {
            instance = new Downloader(ctx.getApplicationContext());
        }
        return instance;
    }

    /** 仅测试/无 Context 时用（功能受限）。 */
    public static synchronized Downloader get() {
        return instance;
    }

    public static class Task {
        public enum State { QUEUED, DOWNLOADING, PAUSED, DONE, FAILED, CANCELED }

        public final String id = UUID.randomUUID().toString();
        public String url;
        public String fileName;
        public String mime = "application/octet-stream";
        public boolean hls;
        public volatile State state = State.QUEUED;
        /** 文件模式=字节总数；HLS 模式=分片总数；-1=未知 */
        public volatile long totalUnits = -1;
        /** 文件模式=已下字节；HLS 模式=已下字节（进度另看 doneSeg） */
        public volatile long doneBytes = 0;
        /** HLS 模式：已完成分片数 */
        public volatile int doneSeg = 0;
        public volatile long speedBps = 0;
        public volatile String error;
        /** API 29+: MediaStore content uri 字符串；26-28：绝对路径 */
        public volatile String target;
        /** 本次下载开始时间（uptimeMillis），用于 HLS 的 ETA 估算 */
        public volatile long startMs = 0;
        volatile boolean pauseReq;
        volatile boolean cancelReq;
    }

    private final Context appCtx;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final CopyOnWriteArrayList<Task> tasks = new CopyOnWriteArrayList<Task>();
    /** v10.8：下载通知（进度/完成/失败），没权限就静默不发 */
    private final DownloadNotifier notifier;

    private Downloader(Context appCtx) {
        this.appCtx = appCtx;
        this.notifier = new DownloadNotifier(appCtx);
    }

    public List<Task> tasks() {
        return tasks;
    }

    /** 入队一个下载，返回 Task（已在线程池排队）。 */
    public Task enqueue(String url) {
        return enqueue(url, null, null);
    }

    /**
     * v10.3：contentDisposition / mimetype 来自 WebView 的 DownloadListener
     *（比如下载站的 attachment; filename="app.apk"），有就传，没有传 null。
     * 之前直接扔掉它们、只拿 URL 猜名字，没文件名的 URL 就猜出 .bin —— 这就是
     * "APK 变 bin、别的 App 打不开"的根因。
     */
    public Task enqueue(String url, String contentDisposition, String mimetype) {
        Task t = new Task();
        t.url = url;
        String lower = url.toLowerCase(Locale.ROOT).split("[?#]")[0];
        t.hls = lower.endsWith(".m3u8");
        String guess = URLUtil.guessFileName(url, contentDisposition, mimetype);
        if (guess == null || guess.isEmpty()) {
            guess = "download_" + System.currentTimeMillis();
        }
        guess = guess.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (t.hls) {
            int dot = guess.lastIndexOf('.');
            guess = (dot > 0 ? guess.substring(0, dot) : guess) + ".ts";
            t.mime = "video/mp2t";
        } else {
            t.mime = mimeFor(guess);
            // 后缀猜不出类型时，信 WebView 给的 mimetype
            if ("application/octet-stream".equals(t.mime)
                    && mimetype != null && mimetype.contains("/")) {
                t.mime = mimetype.split(";")[0].trim();
            }
        }
        t.fileName = guess;
        tasks.add(0, t);
        pool.execute(new Worker(t));
        return t;
    }

    public void pause(Task t) {
        if (t.state == Task.State.DOWNLOADING || t.state == Task.State.QUEUED) {
            t.pauseReq = true;
        }
    }

    public void resume(Task t) {
        if (t.state == Task.State.PAUSED || t.state == Task.State.FAILED) {
            t.pauseReq = false;
            t.cancelReq = false;
            t.error = null;
            if (t.state == Task.State.FAILED) {
                t.doneBytes = 0;
                t.doneSeg = 0;
                deleteTarget(t);
                t.target = null;
            }
            t.state = Task.State.QUEUED;
            pool.execute(new Worker(t));
        }
    }

    public void cancel(Task t) {
        if (t.state == Task.State.DONE) {
            deleteTarget(t);
            tasks.remove(t);
            notifier.dismiss(t);
            return;
        }
        t.cancelReq = true;
        if (t.state == Task.State.QUEUED || t.state == Task.State.PAUSED
                || t.state == Task.State.FAILED) {
            deleteTarget(t);
            tasks.remove(t);
        }
        // DOWNLOADING：Worker 收尾时删文件并移除
    }

    /** v10.3：重命名已完成任务的文件，返回是否成功（仅 DONE 任务调用） */
    public boolean rename(Task t, String newName) {
        if (t.state != Task.State.DONE || t.target == null) {
            return false;
        }
        newName = newName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (newName.isEmpty()) {
            return false;
        }
        // 没写后缀就保留原后缀（省得把 .apk 改没了装不上）
        if (newName.lastIndexOf('.') < 0) {
            int dot = t.fileName.lastIndexOf('.');
            if (dot > 0) {
                newName += t.fileName.substring(dot);
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, newName);
                v.put(MediaStore.Downloads.MIME_TYPE, mimeFor(newName));
                int rows = appCtx.getContentResolver()
                        .update(Uri.parse(t.target), v, null, null);
                if (rows <= 0) {
                    return false;
                }
            } else {
                File old = new File(t.target);
                File nf = new File(old.getParent(), newName);
                if (!old.renameTo(nf)) {
                    return false;
                }
                t.target = nf.getAbsolutePath();
            }
            t.fileName = newName;
            t.mime = mimeFor(newName);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void removeTask(Task t) {
        tasks.remove(t);
    }

    // ---------------- Worker ----------------

    private static class PauseSignal extends RuntimeException {}

    private class Worker implements Runnable {
        final Task t;
        final Speedo speedo = new Speedo();

        Worker(Task t) {
            this.t = t;
        }

        @Override
        public void run() {
            // 排队时被取消
            if (t.cancelReq) {
                deleteTarget(t);
                removeTask(t);
                return;
            }
            t.state = Task.State.DOWNLOADING;
            t.startMs = SystemClock.uptimeMillis();
            notifier.onProgress(t);
            try {
                if (t.hls) {
                    downloadHls();
                } else {
                    downloadFile();
                }
                if (t.cancelReq) {
                    deleteTarget(t);
                    removeTask(t);
                    notifier.dismiss(t);
                } else {
                    t.state = Task.State.DONE;
                    t.speedBps = 0;
                    notifier.onDone(t);
                }
            } catch (PauseSignal e) {
                t.state = Task.State.PAUSED;
                t.speedBps = 0;
                notifier.onProgress(t);
            } catch (Exception e) {
                Log.w(TAG, "download failed: " + t.url, e);
                t.state = Task.State.FAILED;
                t.error = e.getMessage() != null ? e.getMessage() : e.toString();
                t.speedBps = 0;
                notifier.onFailed(t);
            }
        }

        private void checkPoint() {
            if (t.cancelReq) {
                throw new PauseSignal(); // 复用为"停工"信号，run 里再按 cancelReq 区分
            }
            if (t.pauseReq) {
                throw new PauseSignal();
            }
        }

        // ---------- 普通文件 ----------

        private void downloadFile() throws Exception {
            long start;
            boolean mediaStore = Build.VERSION.SDK_INT >= 29;
            Uri uri = null;
            File file = null;
            if (mediaStore) {
                uri = t.target == null ? insertMedia() : Uri.parse(t.target);
                t.target = uri.toString();
                start = querySize(uri);
            } else {
                file = legacyFile();
                t.target = file.getAbsolutePath();
                start = file.exists() ? file.length() : 0;
            }

            HttpURLConnection c = openConn(t.url, start);
            int code = c.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK && start > 0) {
                // 服务器无视 Range：从头来
                start = 0;
            } else if (code != HttpURLConnection.HTTP_OK
                    && code != HttpURLConnection.HTTP_PARTIAL) {
                throw new Exception("HTTP " + code);
            }
            long len = c.getContentLengthLong();
            t.totalUnits = len >= 0 ? len + start : -1;
            t.doneBytes = start;

            // v10.3：新任务用响应头纠正文件名（Content-Disposition / Content-Type）。
            // WebView 那边没拿到真名、URL 里又没文件名时，全靠这次兜底。
            if (start == 0 && !t.hls) {
                String better = deriveFileName(c, t.url);
                if (better != null && !better.equals(t.fileName)) {
                    t.fileName = better;
                    t.mime = mimeFor(better);
                    if (mediaStore) {
                        ContentValues mv = new ContentValues();
                        mv.put(MediaStore.Downloads.DISPLAY_NAME, better);
                        mv.put(MediaStore.Downloads.MIME_TYPE, t.mime);
                        appCtx.getContentResolver().update(uri, mv, null, null);
                    } else {
                        file = new File(file.getParent(), better);
                        t.target = file.getAbsolutePath();
                    }
                }
            }

            OutputStream out;
            if (mediaStore) {
                out = appCtx.getContentResolver()
                        .openOutputStream(uri, start > 0 ? "wa" : "w");
            } else {
                RandomAccessFile raf = new RandomAccessFile(file, "rw");
                if (code == HttpURLConnection.HTTP_OK) {
                    raf.setLength(0);
                }
                raf.seek(start);
                out = new RafOutputStream(raf);
            }
            if (out == null) {
                throw new Exception("cannot open output");
            }
            InputStream in = c.getInputStream();
            byte[] buf = new byte[32768];
            try {
                int n;
                while ((n = in.read(buf)) != -1) {
                    checkPoint();
                    out.write(buf, 0, n);
                    t.doneBytes += n;
                    speedo.add(n);
                    t.speedBps = speedo.bps();
                    notifier.onProgress(t);
                }
                out.flush();
            } finally {
                try { out.close(); } catch (Exception ignored) {}
                try { in.close(); } catch (Exception ignored) {}
                c.disconnect();
            }
            checkPoint();
            if (mediaStore) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.IS_PENDING, 0);
                appCtx.getContentResolver().update(uri, v, null, null);
            }
        }

        // ---------- HLS ----------

        private void downloadHls() throws Exception {
            String master = httpGetText(t.url);
            String mediaUrl = pickVariant(t.url, master);
            String media = httpGetText(mediaUrl);
            if (media.contains("#EXT-X-KEY")) {
                throw new Exception(appCtx.getString(R.string.dl_encrypted));
            }
            List<String> segs = parseSegments(mediaUrl, media);
            if (segs.isEmpty()) {
                throw new Exception("empty playlist");
            }
            t.totalUnits = segs.size();

            boolean mediaStore = Build.VERSION.SDK_INT >= 29;
            Uri uri = null;
            File file = null;
            if (mediaStore) {
                uri = t.target == null ? insertMedia() : Uri.parse(t.target);
                t.target = uri.toString();
            } else {
                file = legacyFile();
                t.target = file.getAbsolutePath();
            }

            for (int i = t.doneSeg; i < segs.size(); i++) {
                checkPoint();
                byte[] data = httpGetBytes(segs.get(i));
                checkPoint();
                // 整片原子追加：暂停/取消不会留下半片
                if (mediaStore) {
                    OutputStream out = appCtx.getContentResolver()
                            .openOutputStream(uri, "wa");
                    try {
                        out.write(data);
                    } finally {
                        try { out.close(); } catch (Exception ignored) {}
                    }
                } else {
                    RandomAccessFile raf = new RandomAccessFile(file, "rw");
                    try {
                        raf.seek(raf.length());
                        raf.write(data);
                    } finally {
                        try { raf.close(); } catch (Exception ignored) {}
                    }
                }
                t.doneSeg = i + 1;
                t.doneBytes += data.length;
                speedo.add(data.length);
                t.speedBps = speedo.bps();
                notifier.onProgress(t);
            }
            checkPoint();
            if (mediaStore) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.IS_PENDING, 0);
                appCtx.getContentResolver().update(uri, v, null, null);
            }
        }

        // ---------- 网络小工具 ----------

        private HttpURLConnection openConn(String url, long start) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(CONN_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setRequestProperty("User-Agent", DL_UA);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) {
                c.setRequestProperty("Cookie", cookie);
            }
            if (start > 0) {
                c.setRequestProperty("Range", "bytes=" + start + "-");
            }
            c.connect();
            return c;
        }

        private String httpGetText(String url) throws Exception {
            HttpURLConnection c = openConn(url, 0);
            try {
                int code = c.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new Exception("HTTP " + code);
                }
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) != -1) {
                    checkPoint();
                    bos.write(buf, 0, n);
                }
                return bos.toString("UTF-8");
            } finally {
                c.disconnect();
            }
        }

        private byte[] httpGetBytes(String url) throws Exception {
            HttpURLConnection c = openConn(url, 0);
            try {
                int code = c.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new Exception("HTTP " + code);
                }
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) != -1) {
                    checkPoint();
                    bos.write(buf, 0, n);
                    speedo.add(n);
                    t.speedBps = speedo.bps();
                }
                return bos.toByteArray();
            } finally {
                c.disconnect();
            }
        }

        private String pickVariant(String masterUrl, String master) throws Exception {
            String best = null;
            long bestBw = -1;
            String[] lines = master.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    long bw = -1;
                    int bi = line.indexOf("BANDWIDTH=");
                    if (bi >= 0) {
                        int end = bi + 10;
                        while (end < line.length()
                                && Character.isDigit(line.charAt(end))) {
                            end++;
                        }
                        try {
                            bw = Long.parseLong(line.substring(bi + 10, end));
                        } catch (Exception ignored) {}
                    }
                    if (i + 1 < lines.length) {
                        String u = lines[i + 1].trim();
                        if (!u.isEmpty() && !u.startsWith("#") && bw >= bestBw) {
                            bestBw = bw;
                            best = u;
                        }
                    }
                }
            }
            if (best == null) {
                return masterUrl; // 本来就是媒体 playlist
            }
            return new URL(new URL(masterUrl), best).toString();
        }

        private List<String> parseSegments(String mediaUrl, String media)
                throws Exception {
            List<String> segs = new ArrayList<String>();
            URL base = new URL(mediaUrl);
            for (String raw : media.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                segs.add(new URL(base, line).toString());
            }
            return segs;
        }

        // ---------- 存储 ----------

        private Uri insertMedia() throws Exception {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, t.fileName);
            v.put(MediaStore.Downloads.MIME_TYPE, t.mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME);
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = appCtx.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                throw new Exception("cannot create download entry");
            }
            return uri;
        }

        private long querySize(Uri uri) {
            Cursor cur = null;
            try {
                cur = appCtx.getContentResolver().query(uri,
                        new String[]{OpenableColumns.SIZE}, null, null, null);
                if (cur != null && cur.moveToFirst()) {
                    return cur.getLong(0);
                }
            } catch (Exception ignored) {
            } finally {
                if (cur != null) {
                    cur.close();
                }
            }
            return 0;
        }

        private File legacyFile() throws Exception {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), DIR_NAME);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new Exception("cannot create dir");
            }
            return new File(dir, t.fileName);
        }

        /**
         * v10.3：从响应头推断更准的文件名。
         * 1) Content-Disposition 的 filename；2) URL 路径最后一段；
         * 3) 没后缀就按 Content-Type 补一个。实在推断不出返回 null（保持原名）。
         */
        private String deriveFileName(HttpURLConnection c, String url) {
            String name = null;
            String cd = c.getHeaderField("Content-Disposition");
            if (cd != null) {
                name = parseDispositionName(cd);
            }
            if (name == null || name.isEmpty()) {
                try {
                    String path = new URL(url).getPath();
                    int slash = path.lastIndexOf('/');
                    String seg = slash >= 0 ? path.substring(slash + 1) : path;
                    try {
                        seg = java.net.URLDecoder.decode(seg, "UTF-8");
                    } catch (Exception ignored) {}
                    if (seg != null && !seg.isEmpty()
                            && !seg.equalsIgnoreCase("download")
                            && !seg.equalsIgnoreCase("downloadfile")) {
                        name = seg;
                    }
                } catch (Exception ignored) {}
            }
            if (name == null || name.isEmpty()) {
                return null;
            }
            name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (name.lastIndexOf('.') < 0) {
                String ext = extForMime(c.getContentType());
                if (ext != null) {
                    name += "." + ext;
                }
            }
            return name;
        }

        private static String parseDispositionName(String cd) {
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("filename\\*\\s*=\\s*([^;]+)").matcher(cd);
                if (m.find()) {
                    String v = m.group(1).trim();
                    int qi = v.indexOf("''");
                    String enc = qi > 0 ? v.substring(0, qi) : "UTF-8";
                    String val = qi > 0 ? v.substring(qi + 2) : v;
                    return java.net.URLDecoder.decode(val, enc);
                }
                m = java.util.regex.Pattern
                        .compile("filename\\s*=\\s*\"([^\"]+)\"").matcher(cd);
                if (m.find()) {
                    return m.group(1);
                }
                m = java.util.regex.Pattern
                        .compile("filename\\s*=\\s*([^;\\s]+)").matcher(cd);
                if (m.find()) {
                    return m.group(1).trim();
                }
            } catch (Exception ignored) {}
            return null;
        }

        /** v10.3：Content-Type → 后缀；未知类型返回 null（不硬猜 .bin） */
        private static String extForMime(String contentType) {
            if (contentType == null) {
                return null;
            }
            String ct = contentType.split(";")[0].trim()
                    .toLowerCase(Locale.ROOT);
            if (ct.equals("application/vnd.android.package-archive")) {
                return "apk";
            }
            if (ct.equals("video/mp4")) {
                return "mp4";
            }
            if (ct.equals("video/webm")) {
                return "webm";
            }
            if (ct.equals("video/x-matroska")) {
                return "mkv";
            }
            if (ct.equals("audio/mpeg")) {
                return "mp3";
            }
            if (ct.equals("image/jpeg")) {
                return "jpg";
            }
            if (ct.equals("image/png")) {
                return "png";
            }
            if (ct.equals("image/webp")) {
                return "webp";
            }
            if (ct.equals("application/pdf")) {
                return "pdf";
            }
            if (ct.equals("application/zip")) {
                return "zip";
            }
            if (ct.equals("text/plain")) {
                return "txt";
            }
            return null;
        }
    }

    private void deleteTarget(Task t) {
        try {
            if (t.target == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 29) {
                appCtx.getContentResolver().delete(Uri.parse(t.target), null, null);
            } else {
                new File(t.target).delete();
            }
        } catch (Exception ignored) {}
    }

    private static String mimeFor(String name) {
        String l = name.toLowerCase(Locale.ROOT);
        if (l.endsWith(".mp4")) return "video/mp4";
        if (l.endsWith(".webm")) return "video/webm";
        if (l.endsWith(".mov")) return "video/quicktime";
        if (l.endsWith(".m4v")) return "video/x-m4v";
        if (l.endsWith(".flv")) return "video/x-flv";
        if (l.endsWith(".mkv")) return "video/x-matroska";
        if (l.endsWith(".mp3")) return "audio/mpeg";
        if (l.endsWith(".m4a")) return "audio/mp4";
        if (l.endsWith(".jpg") || l.endsWith(".jpeg")) return "image/jpeg";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".gif")) return "image/gif";
        if (l.endsWith(".webp")) return "image/webp";
        if (l.endsWith(".pdf")) return "application/pdf";
        if (l.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (l.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    /** RandomAccessFile 适配成 OutputStream。 */
    private static class RafOutputStream extends OutputStream {
        final RandomAccessFile raf;
        RafOutputStream(RandomAccessFile raf) { this.raf = raf; }
        @Override public void write(int b) throws java.io.IOException { raf.write(b); }
        @Override public void write(byte[] b, int off, int len)
                throws java.io.IOException { raf.write(b, off, len); }
        @Override public void close() throws java.io.IOException { raf.close(); }
    }

    /** 2 秒滑动窗口测速。 */
    private static class Speedo {
        final LinkedList<long[]> w = new LinkedList<long[]>();
        synchronized void add(long bytes) {
            long now = SystemClock.uptimeMillis();
            w.add(new long[]{now, bytes});
            while (w.size() > 1 && now - w.get(0)[0] > 2000) {
                w.remove(0);
            }
        }
        synchronized long bps() {
            if (w.size() < 2) {
                return 0;
            }
            long dt = w.get(w.size() - 1)[0] - w.get(0)[0];
            if (dt <= 0) {
                return 0;
            }
            long sum = 0;
            for (long[] e : w) {
                sum += e[1];
            }
            return sum * 1000 / dt;
        }
    }

    // ---------- 展示用格式化 ----------

    public static String fmtSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    public static String fmtSpeed(long bps) {
        return fmtSize(bps) + "/s";
    }

    /** ETA 文本：mm:ss 或 h:mm:ss；算不出返回 null。 */
    public static String fmtEta(Task t) {
        long remaining;
        if (t.hls) {
            if (t.totalUnits <= 0 || t.doneSeg <= 0 || t.startMs <= 0) {
                return null;
            }
            // 按已用平均分片耗时估算
            long elapsed = SystemClock.uptimeMillis() - t.startMs;
            if (elapsed <= 0) {
                return null;
            }
            double perSeg = (double) elapsed / t.doneSeg;
            remaining = (long) ((t.totalUnits - t.doneSeg) * perSeg / 1000);
        } else {
            if (t.totalUnits <= 0 || t.speedBps <= 0) {
                return null;
            }
            remaining = (t.totalUnits - t.doneBytes) / t.speedBps;
        }
        if (remaining < 0) {
            remaining = 0;
        }
        long h = remaining / 3600, m = (remaining % 3600) / 60, s = remaining % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }
}
