package com.miku.wugan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.TrafficStats;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import mobile.Mobile;
import mobile.TunnelController;

/**
 * v13.0 系统级 VPN（VpnService + TUN）。
 *
 * v12.x 是"应用内 SOCKS5"：只有浏览器自己的 WebView/下载器流量走隧道，
 * WebRTC、DNS、其他 App 的流量全部直连——ipleak 实锤泄漏。
 * v13 改走验证过的路子（抄 exxojay/usque-android 的 UsqueVpnService）：
 * 建 TUN 接口，DNS 也走 1.1.1.1。
 *
 * v13.1 收紧：白名单写死，只接管浏览器自身流量——其他 App 不进隧道。
 *
 * 引擎还是同一份 Go 代码（gomobile JNI），只是从 startSocks 换成 startVpn。
 * 连接策略：先 QUIC/H3，15 秒连不上或报 error 就 stop 后换 HTTP2 重试一次。
 *
 * 速率显示的是 TrafficStats 统计的本 App uid 吞吐（即隧道加密后的实际流量），
 * 文案如实写"上行/下行"，不编造。
 */
public class VpnTunnelService extends VpnService {

    public static final String ACTION_STOP = "com.miku.wugan.VPN_STOP";

    private static final String CH_ID = "vpn";
    private static final int NOTIF_ID = 1002;
    private static final int MTU = 1280;
    /** 传给引擎的隧道内 DNS（逗号分隔；上游 App 的默认值） */
    private static final String DNS_ADDRS = "1.1.1.1,1.0.0.1";
    private static final long CONNECT_TIMEOUT_MS = 15000;

    private static volatile boolean running = false;
    private static volatile String proto = "";
    private static volatile String state = "idle";

    public static boolean isRunning() {
        return running;
    }

    private TunnelController controller;
    private volatile boolean stopRequested = false;
    private Thread ticker;
    private NotificationManager nm;

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            requestStop();
            return START_NOT_STICKY;
        }
        if (running) {
            return START_NOT_STICKY;
        }
        ensureChannel();
        startForeground(NOTIF_ID, buildNotification("无感 VPN", "连接中…"));
        new Thread(new Runnable() {
            @Override public void run() {
                connectFlow();
            }
        }).start();
        return START_NOT_STICKY;
    }

    @Override
    public void onRevoke() {
        // 系统收回 VPN 授权：停隧道，wait 线程的 finally 会收尾
        requestStop();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        requestStop();
        super.onDestroy();
    }

    /** 主流程：读配置 → 建 TUN → QUIC 试连 → 失败换 H2 重试 → 跑起来 */
    private void connectFlow() {
        state = "connecting";
        String cfg = VpnManager.readCfg(this);
        if (cfg == null) {
            fail("no config");
            return;
        }
        String ipv4, ipv6, epV4, epH2V4;
        try {
            JSONObject o = new JSONObject(cfg);
            ipv4 = o.optString("ipv4", "");
            ipv6 = o.optString("ipv6", "");
            epV4 = bareIp(o.optString("endpoint_v4", ""));
            epH2V4 = bareIp(o.optString("endpoint_h2_v4", ""));
        } catch (Exception e) {
            fail("bad config");
            return;
        }
        if (ipv4.isEmpty()) {
            fail("bad config");
            return;
        }

        // 第一次：QUIC/H3
        int fd = establishTun(ipv4, ipv6, epV4, epH2V4);
        if (fd < 0) {
            fail("establish failed");
            return;
        }
        TunnelController c = newController();
        if (c == null) {
            fail("engine");
            return;
        }
        controller = c;
        String err = tryStart(c, cfg, fd, false);
        if (err == null) {
            proto = "H3/QUIC";
            onConnected(c);
            return;
        }
        // 失败：停掉，换 HTTP2 重试一次（TUN fd 换新的，旧的 stop 时已关）
        stopQuiet(c);
        if (stopRequested) {
            cleanup();
            stopSelf();
            return;
        }
        int fd2 = establishTun(ipv4, ipv6, epV4, epH2V4);
        if (fd2 < 0) {
            fail("establish failed");
            return;
        }
        TunnelController c2 = newController();
        if (c2 == null) {
            fail("engine");
            return;
        }
        controller = c2;
        err = tryStart(c2, cfg, fd2, true);
        if (err == null) {
            proto = "H2";
            onConnected(c2);
        } else {
            fail(err);
        }
    }

    private void onConnected(final TunnelController c) {
        running = true;
        state = "connected";
        nm.notify(NOTIF_ID, buildNotification("无感 VPN · 已连接",
                "↓ -- · ↑ -- · " + proto));
        startTicker();
        // startVpn 非阻塞：另起线程守着，隧道死掉就收尾
        Thread waiter = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    c.waitUntilStopped();
                } catch (Throwable ignored) {
                }
                onTunnelDied();
            }
        });
        waiter.setDaemon(true);
        waiter.start();
    }

    private void onTunnelDied() {
        if (!stopRequested) {
            state = "error:tunnel died";
        }
        cleanup();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void fail(String err) {
        state = "error:" + err;
        nm.notify(NOTIF_ID, buildNotification("无感 VPN", "连接失败：" + err));
        cleanup();
        stopSelf();
    }

    private void cleanup() {
        running = false;
        proto = "";
        stopRequested = true;
    }

    /** 优雅停服：ACTION_STOP / onRevoke / onDestroy 都走这里 */
    private void requestStop() {
        stopRequested = true;
        final TunnelController c = controller;
        controller = null;
        // stop() 最多阻塞几秒等 goroutine，丢后台线程防 ANR
        new Thread(new Runnable() {
            @Override public void run() {
                stopQuiet(c);
            }
        }).start();
    }

    private static void stopQuiet(TunnelController c) {
        if (c == null) {
            return;
        }
        try {
            c.stop();
        } catch (Throwable ignored) {
        }
    }

    private TunnelController newController() {
        try {
            return Mobile.newTunnelController();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 起隧道并轮询状态。返回 null=连上，否则为错误信息。
     * startVpn 非阻塞，真正的状态靠 getStatus 轮询。
     */
    private String tryStart(TunnelController c, String cfg, int fd, boolean http2) {
        try {
            c.startVpn(cfg, (long) fd, DNS_ADDRS, "", (long) MTU,
                    false, http2);
        } catch (Throwable t) {
            return shortErr(t);
        }
        long deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline && !stopRequested) {
            String s;
            try {
                s = c.getStatus();
            } catch (Throwable t) {
                return shortErr(t);
            }
            if ("connected".equals(s)) {
                return null;
            }
            if (s != null && s.startsWith("error:")) {
                return s.substring(6);
            }
            SystemClock.sleep(500);
        }
        return stopRequested ? "stopped" : "timeout";
    }

    /**
     * 建 TUN 接口。v13.1 起白名单模式写死：只接管本 App 自身流量，
     * 系统油管等其他 App 不进隧道（产品定位，不是 bug）。
     * 路由仍走 0.0.0.0/0 但抠掉 endpoint 的 /32（防环路：
     * 引擎直连 endpoint 的包不能再进 TUN，否则死循环）。
     * QUIC 和 H2 的 endpoint 都抠掉——重试换协议时 endpoint 会变。
     */
    private int establishTun(String ipv4, String ipv6,
                             String epV4, String epH2V4) {
        try {
            Builder b = new Builder();
            // v13.1 写死：白名单只放自己——TUN 只接管浏览器自身流量。
            // 其他 App（系统油管等）的包根本不会进 TUN。
            b.addAllowedApplication(getPackageName());
            b.addAddress(stripPrefix(ipv4), 32);
            if (ipv6 != null && !ipv6.isEmpty()) {
                b.addAddress(stripPrefix(ipv6), 128);
            }
            boolean routed = false;
            String last = null;
            String[] eps = new String[]{epV4, epH2V4};
            for (String ep : eps) {
                if (ep == null || ep.isEmpty() || ep.equals(last)
                        || !isV4(ep)) {
                    continue;
                }
                last = ep;
                for (String[] r : splitRouteExcluding(ep)) {
                    b.addRoute(r[0], Integer.parseInt(r[1]));
                    routed = true;
                }
            }
            if (!routed) {
                b.addRoute("0.0.0.0", 0);
            }
            b.addRoute("::", 0);
            b.addDnsServer("1.1.1.1");
            b.addDnsServer("2606:4700:4700::1111");
            b.setMtu(MTU);
            b.setSession("无感VPN");
            ParcelFileDescriptor pfd = b.establish();
            if (pfd == null) {
                return -1;
            }
            return pfd.detachFd();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 把 0.0.0.0/0 拆成 32 条路由，盖住除 excludeIp/32 之外的全部地址。
     * （上游 Kotlin 版的位运算有 bug 会返回空表，这里用逐位钻取的正确实现。）
     */
    static List<String[]> splitRouteExcluding(String excludeIp) {
        List<String[]> routes = new ArrayList<>();
        int excl = ipToInt(excludeIp);
        int base = 0;
        for (int bits = 0; bits < 32; bits++) {
            int half = 1 << (31 - bits);
            if ((excl & half) == 0) {
                // 排除 IP 在低半区 → 高半区加入路由
                routes.add(new String[]{intToIp(base | half),
                        String.valueOf(bits + 1)});
            } else {
                // 排除 IP 在高半区 → 低半区加入路由，继续往高半区钻
                routes.add(new String[]{intToIp(base),
                        String.valueOf(bits + 1)});
                base |= half;
            }
        }
        return routes;
    }

    private static int ipToInt(String ip) {
        String[] p = ip.split("\\.");
        return (Integer.parseInt(p[0]) << 24) | (Integer.parseInt(p[1]) << 16)
                | (Integer.parseInt(p[2]) << 8) | Integer.parseInt(p[3]);
    }

    private static String intToIp(int i) {
        return ((i >>> 24) & 0xFF) + "." + ((i >>> 16) & 0xFF) + "."
                + ((i >>> 8) & 0xFF) + "." + (i & 0xFF);
    }

    private static boolean isV4(String ip) {
        if (ip == null) {
            return false;
        }
        String[] p = ip.split("\\.");
        if (p.length != 4) {
            return false;
        }
        try {
            for (String s : p) {
                int v = Integer.parseInt(s);
                if (v < 0 || v > 255) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** "1.2.3.4:443" → "1.2.3.4"；顺手去前缀 "/32" */
    private static String bareIp(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        int slash = s.indexOf('/');
        if (slash > 0) {
            s = s.substring(0, slash);
        }
        int colon = s.indexOf(':');
        if (colon > 0) {
            s = s.substring(0, colon);
        }
        return s;
    }

    private static String stripPrefix(String s) {
        if (s == null) {
            return "";
        }
        int slash = s.indexOf('/');
        return slash > 0 ? s.substring(0, slash).trim() : s.trim();
    }

    private static String shortErr(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) {
            m = t.getClass().getSimpleName();
        }
        return m.length() > 120 ? m.substring(0, 120) : m;
    }

    // ---------------- 前台通知：状态 + 实时速率 + 协议 ----------------

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "VPN", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent stop = new Intent(this, VpnTunnelService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, SettingsActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CH_ID);
        } else {
            b = new Notification.Builder(this);
        }
        // 铁律：smallIcon 必须用编译期确定的资源，android.R.drawable 保不为 0
        return b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        "断开", stopPi)
                .build();
    }

    /** 每 2 秒刷新一次速率 */
    private void startTicker() {
        final int uid = getApplicationInfo().uid;
        ticker = new Thread(new Runnable() {
            @Override public void run() {
                long lastRx = rxBytes(uid), lastTx = txBytes(uid);
                long lastT = SystemClock.elapsedRealtime();
                while (!stopRequested && running) {
                    SystemClock.sleep(2000);
                    long now = SystemClock.elapsedRealtime();
                    long rx = rxBytes(uid), tx = txBytes(uid);
                    long dt = now - lastT;
                    if (dt > 0 && rx >= 0 && tx >= 0
                            && lastRx >= 0 && lastTx >= 0) {
                        float down = (rx - lastRx) * 1000f / dt;
                        float up = (tx - lastTx) * 1000f / dt;
                        nm.notify(NOTIF_ID, buildNotification(
                                "无感 VPN · 已连接",
                                "↓ " + fmtSpeed(down) + " · ↑ "
                                        + fmtSpeed(up) + " · " + proto));
                    }
                    lastRx = rx;
                    lastTx = tx;
                    lastT = now;
                }
            }
        });
        ticker.setDaemon(true);
        ticker.start();
    }

    private static long rxBytes(int uid) {
        long v = TrafficStats.getUidRxBytes(uid);
        return v >= 0 ? v : TrafficStats.getTotalRxBytes();
    }

    private static long txBytes(int uid) {
        long v = TrafficStats.getUidTxBytes(uid);
        return v >= 0 ? v : TrafficStats.getTotalTxBytes();
    }

    private static String fmtSpeed(float bps) {
        if (bps < 1024) {
            return String.format("%.0f B/s", bps);
        }
        if (bps < 1024 * 1024) {
            return String.format("%.1f KB/s", bps / 1024);
        }
        return String.format("%.2f MB/s", bps / 1024 / 1024);
    }
}
