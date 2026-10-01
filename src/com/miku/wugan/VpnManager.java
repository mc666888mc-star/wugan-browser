package com.miku.wugan;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import mobile.Mobile;

/**
 * v13.0 内置 VPN 唯一入口。
 *
 * v12.x 是"应用内 SOCKS5"（只有浏览器流量走隧道，WebRTC/DNS 照样泄漏），
 * v13 起走系统级 VPN：VpnService + TUN，手机所有流量都进 WARP 隧道。
 * 注册逻辑不变（Mobile.registerAccount/enrollDevice），连接/断开改走
 * VpnTunnelService。旧的 startSocks 应用内代理路径已删。
 *
 * 对外接口：是否已注册 / 一键注册 / 起服务（可能需系统授权）/ 断开 / 状态。
 * （引擎来源与许可：见 NOTICES.md）
 */
public class VpnManager {

    private static final String CFG_NAME = "tunnel.json";

    /** startService 的返回值：需要走系统 VPN 授权流程 */
    public static final String NEED_AUTH = "NEED_AUTH";

    public static boolean isOn() {
        return VpnTunnelService.isRunning();
    }

    public static boolean isRegistered(Context c) {
        File f = cfgFile(c);
        return f.exists() && f.length() > 100;
    }

    private static File cfgFile(Context c) {
        return new File(c.getFilesDir(), CFG_NAME);
    }

    /** 包内可见：VpnTunnelService 读配置用 */
    static String readCfg(Context c) {
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
     * 起系统 VPN 服务（主线程调即可，内部无阻塞网络操作）。
     * 返回 null=服务已起；NEED_AUTH=需系统授权，调用方拿 authIntent()
     * 去 startActivityForResult；否则为错误信息。
     */
    public static String startService(Context c) {
        try {
            if (VpnService.prepare(c) != null) {
                return NEED_AUTH;
            }
            Intent i = new Intent(c, VpnTunnelService.class);
            c.startForegroundService(i);
            return null;
        } catch (Throwable t) {
            return shortErr(t);
        }
    }

    /** 系统 VPN 授权弹框的 Intent（startService 返回 NEED_AUTH 时用） */
    public static Intent authIntent(Context c) {
        try {
            return VpnService.prepare(c);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 断开：给服务发 ACTION_STOP，优雅停隧道 */
    public static void disconnect(Context c) {
        try {
            Intent i = new Intent(c, VpnTunnelService.class);
            i.setAction(VpnTunnelService.ACTION_STOP);
            c.startService(i);
        } catch (Throwable ignored) {
        }
    }
}
