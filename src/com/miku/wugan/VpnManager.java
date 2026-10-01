package com.miku.wugan;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * v12.0 内置 VPN。
 *
 * 在 App 私有目录跑一个用户态隧道进程，对本机 127.0.0.1:1080 开 SOCKS5，
 * 浏览器流量经它走加密隧道直达外网。免 root，不碰系统 VPN 设置。
 *
 * 对外只暴露五个动作：是否已注册 / 一键注册 / 连接 / 断开 / 状态。
 * 进程名、文件名里不出现上游项目名（见 NOTICES.md）。
 */
public class VpnManager {

    private static final String BIN_NAME = "vpnc";
    private static final String CFG_NAME = "tunnel.json";
    public static final int PORT = 1080;
    public static final String HOST = "127.0.0.1";

    private static Process proc;
    private static final Object LOCK = new Object();

    /** 给 HttpURLConnection / OkHttp 用的 SOCKS5 代理 */
    public static Proxy proxy() {
        return new Proxy(Proxy.Type.SOCKS,
                new InetSocketAddress(HOST, PORT));
    }

    public static boolean isOn() {
        synchronized (LOCK) {
            return proc != null && proc.isAlive();
        }
    }

    public static boolean isRegistered(Context c) {
        return cfgFile(c).exists();
    }

    private static File binFile(Context c) {
        return new File(c.getFilesDir(), BIN_NAME);
    }

    private static File cfgFile(Context c) {
        return new File(c.getFilesDir(), CFG_NAME);
    }

    /** 第一次把二进制从 assets 拷到私有目录并加执行权限（后台线程调） */
    public static void ensureBinary(Context c) throws Exception {
        File f = binFile(c);
        long want;
        try {
            want = c.getAssets().openFd(BIN_NAME).getLength();
        } catch (Exception e) {
            want = -1;
        }
        if (f.exists() && f.canExecute() && (want < 0 || f.length() == want)) {
            return;
        }
        File tmp = new File(c.getFilesDir(), BIN_NAME + ".tmp");
        InputStream in = null;
        OutputStream out = null;
        try {
            in = c.getAssets().open(BIN_NAME);
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (out != null) try { out.close(); } catch (Exception ignored) {}
        }
        if (!tmp.renameTo(f)) {
            throw new Exception("copy failed");
        }
        if (!f.setExecutable(true)) {
            throw new Exception("chmod failed");
        }
    }

    private static void setupEnv(Context c, ProcessBuilder pb) {
        // 子进程缺 HOME 会起不来（adb 的老坑），手动给
        Map<String, String> env = pb.environment();
        env.put("HOME", c.getFilesDir().getAbsolutePath());
        env.put("TMPDIR", c.getCacheDir().getAbsolutePath());
        pb.directory(c.getFilesDir());
        pb.redirectErrorStream(true);
    }

    /** 吃掉子进程输出，防管道写满卡死 */
    private static void drain(final InputStream in) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[4096];
                try {
                    while (in.read(buf) >= 0) { /* 丢弃 */ }
                } catch (Exception ignored) {}
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /**
     * 一键注册普通账号（只需一次，config 落盘后一直有效）。
     * 返回 null=成功，否则为错误信息。
     */
    public static String register(Context c) {
        try {
            ensureBinary(c);
            File cfg = cfgFile(c);
            if (cfg.exists()) {
                cfg.delete();
            }
            ProcessBuilder pb = new ProcessBuilder(
                    binFile(c).getAbsolutePath(),
                    "-c", cfg.getAbsolutePath(),
                    "register");
            setupEnv(c, pb);
            Process p = pb.start();
            drain(p.getInputStream());
            boolean done = p.waitFor(90, TimeUnit.SECONDS);
            p.destroy();
            if (!done) {
                return "timeout";
            }
            if (cfg.exists() && cfg.length() > 100) {
                return null;
            }
            return "no config";
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    /**
     * 连接：起 SOCKS5 进程并等端口就绪。
     * QUIC 不通时自动改走 --http2（TCP）再试一次。
     * 返回 null=成功，否则为错误信息。
     */
    public static String connect(Context c) {
        synchronized (LOCK) {
            if (isOn()) {
                return null;
            }
            String err = startSocks(c, false);
            if (err != null) {
                err = startSocks(c, true);
            }
            return err;
        }
    }

    private static String startSocks(Context c, boolean http2) {
        try {
            ensureBinary(c);
            File cfg = cfgFile(c);
            ProcessBuilder pb;
            if (http2) {
                pb = new ProcessBuilder(
                        binFile(c).getAbsolutePath(),
                        "-c", cfg.getAbsolutePath(),
                        "--http2",
                        "socks", "-b", HOST, "-p", String.valueOf(PORT));
            } else {
                pb = new ProcessBuilder(
                        binFile(c).getAbsolutePath(),
                        "-c", cfg.getAbsolutePath(),
                        "socks", "-b", HOST, "-p", String.valueOf(PORT));
            }
            setupEnv(c, pb);
            proc = pb.start();
            drain(proc.getInputStream());
            long deadline = SystemClock.elapsedRealtime() + 15000;
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!proc.isAlive()) {
                    proc = null;
                    return "process died";
                }
                if (portOpen()) {
                    return null;
                }
                Thread.sleep(300);
            }
            disconnect();
            return "timeout";
        } catch (Exception e) {
            disconnect();
            return String.valueOf(e.getMessage());
        }
    }

    private static boolean portOpen() {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(HOST, PORT), 800);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (s != null) try { s.close(); } catch (Exception ignored) {}
        }
    }

    public static void disconnect() {
        synchronized (LOCK) {
            if (proc != null) {
                proc.destroy();
                proc = null;
            }
        }
    }
}
