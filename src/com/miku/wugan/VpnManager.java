package com.miku.wugan;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;

import mobile.Mobile;
import mobile.TunnelController;

/**
 * v12.1 内置 VPN（引擎换血）。
 *
 * v12.0 试图用 ProcessBuilder 跑外置二进制，在真机上直接 "Cannot run program" 暴毙。
 * v12.1 改走验证过的路子：gomobile 把隧道引擎编进 APK，进程内 JNI 调用，
 * 一次 exec 都没有。注册→建隧道→127.0.0.1:1080 开 SOCKS5，浏览器流量经它走。
 *
 * 对外接口不变：是否已注册 / 一键注册 / 连接 / 断开 / 状态。
 * （引擎来源与许可：见 NOTICES.md）
 */
public class VpnManager {

    private static final String CFG_NAME = "tunnel.json";
    public static final int PORT = 1080;
    public static final String HOST = "127.0.0.1";

    private static TunnelController ctl;
    private static final Object LOCK = new Object();

    /** 给 HttpURLConnection 用的 SOCKS5 代理 */
    public static Proxy proxy() {
        return new Proxy(Proxy.Type.SOCKS,
                new InetSocketAddress(HOST, PORT));
    }

    public static boolean isOn() {
        synchronized (LOCK) {
            if (ctl == null) {
                return false;
            }
            try {
                String s = ctl.getStatus();
                return "connecting".equals(s) || "connected".equals(s)
                        || "reconnecting".equals(s);
            } catch (Throwable t) {
                return false;
            }
        }
    }

    public static boolean isRegistered(Context c) {
        File f = cfgFile(c);
        return f.exists() && f.length() > 100;
    }

    private static File cfgFile(Context c) {
        return new File(c.getFilesDir(), CFG_NAME);
    }

    private static String readCfg(Context c) {
        File f = cfgFile(c);
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            String s = sb.toString();
            return s.length() > 100 ? s : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    private static boolean writeCfg(Context c, String json) {
        OutputStream out = null;
        try {
            out = new FileOutputStream(cfgFile(c));
            out.write(json.getBytes("UTF-8"));
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) {}
        }
    }

    private static String shortErr(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) {
            m = t.getClass().getSimpleName();
        }
        return m.length() > 120 ? m.substring(0, 120) : m;
    }

    /**
     * 一键注册普通账号（只需一次，config 落盘后一直有效，后台线程调）。
     * 参数与已验证的第三方实现保持一致，不引入新变量。
     * 返回 null=成功，否则为错误信息。
     */
    public static String register(Context c) {
        try {
            String accountJson = Mobile.registerAccount("PC", "en_US", "", true);
            String configJson = Mobile.enrollDevice(accountJson, "Android");
            if (configJson == null || configJson.length() < 100) {
                return "bad config";
            }
            if (!writeCfg(c, configJson)) {
                return "save failed";
            }
            return null;
        } catch (Throwable t) {
            return shortErr(t);
        }
    }

    /**
     * 连接：起隧道并做端到端 SOCKS 探针，确认真流量能走。
     * QUIC 不通自动改 HTTP2（TCP）再试一次（后台线程调）。
     * 返回 null=成功，否则为错误信息。
     */
    public static String connect(Context c) {
        synchronized (LOCK) {
            if (isOn()) {
                return null;
            }
            String cfg = readCfg(c);
            if (cfg == null) {
                return "no config";
            }
            String err = startTunnel(cfg, false);
            if (err != null) {
                err = startTunnel(cfg, true);
            }
            return err;
        }
    }

    private static String startTunnel(String configJson, boolean http2) {
        final TunnelController t;
        try {
            t = Mobile.newTunnelController();
        } catch (Throwable th) {
            return "engine: " + shortErr(th);
        }
        synchronized (LOCK) {
            ctl = t;
        }
        // startSocks 是阻塞调用，丢后台线程跑
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    t.startSocks(configJson, HOST + ":" + PORT,
                            "", "", 1280L, false, http2, null);
                } catch (Throwable ignored) {
                    // stop() 会让它返回，错误由主流程的探针判定
                } finally {
                    synchronized (LOCK) {
                        if (ctl == t) {
                            ctl = null;
                        }
                    }
                }
            }
        });
        worker.setDaemon(true);
        worker.start();

        if (!waitPort(15000)) {
            stopCtl(t);
            return "timeout";
        }
        // 端口开了不代表隧道通了：经 SOCKS5 真连一次 1.1.1.1:443，走完全程才算
        if (!socksProbe(12000)) {
            stopCtl(t);
            return http2 ? "probe failed" : "quic blocked";
        }
        return null;
    }

    private static void stopCtl(TunnelController t) {
        try {
            t.stop();
        } catch (Throwable ignored) {}
        synchronized (LOCK) {
            if (ctl == t) {
                ctl = null;
            }
        }
    }

    public static void disconnect() {
        synchronized (LOCK) {
            if (ctl != null) {
                try {
                    ctl.stop();
                } catch (Throwable ignored) {}
                ctl = null;
            }
        }
    }

    private static boolean waitPort(long ms) {
        long deadline = SystemClock.elapsedRealtime() + ms;
        while (SystemClock.elapsedRealtime() < deadline) {
            Socket s = null;
            try {
                s = new Socket();
                s.connect(new InetSocketAddress(HOST, PORT), 800);
                return true;
            } catch (Exception ignored) {
            } finally {
                if (s != null) try { s.close(); } catch (Exception ignored) {}
            }
            SystemClock.sleep(300);
        }
        return false;
    }

    /**
     * SOCKS5 握手 + CONNECT 1.1.1.1:443。
     * 1.1.1.1 是 Cloudflare 自己的 IP，无需 DNS、经隧道一定可达，
     * 连上即证明整条隧道是通的。
     */
    private static boolean socksProbe(long ms) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(HOST, PORT), 5000);
            s.setSoTimeout((int) ms);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            out.write(new byte[]{0x05, 0x01, 0x00}); // VER, 1 种方法, 无需认证
            out.flush();
            byte[] b = new byte[2];
            readFully(in, b, 2);
            if (b[0] != 0x05 || b[1] != 0x00) {
                return false;
            }
            // CONNECT 1.1.1.1:443
            out.write(new byte[]{0x05, 0x01, 0x00, 0x01,
                    0x01, 0x01, 0x01, 0x01, 0x01, (byte) 0xBB});
            out.flush();
            byte[] r = new byte[10];
            readFully(in, r, 10);
            return r[0] == 0x05 && r[1] == 0x00;
        } catch (Exception e) {
            return false;
        } finally {
            if (s != null) try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static void readFully(InputStream in, byte[] b, int len) throws Exception {
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) {
                throw new Exception("eof");
            }
            off += n;
        }
    }
}
