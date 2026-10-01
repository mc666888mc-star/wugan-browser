package com.miku.wugan;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * v10.8：下载通知。进度（1s/3% 节流）/ 完成（点开文件）/ 失败 / 暂停。
 * 框架 Notification.Builder 直写（minSdk 26，自带 channel，无需 compat 库）。
 * 通知只是"顺手提醒"：没给权限就静默不发，下载本身不受影响。
 */
public class DownloadNotifier {

    private static final String CH_ID = "wugan_downloads";

    private final Context ctx;
    private final NotificationManager nm;
    /** 节流：每个任务上次刷新时间 / 百分比 */
    private final Map<String, Long> lastUpd = new HashMap<>();
    private final Map<String, Integer> lastPct = new HashMap<>();

    public DownloadNotifier(Context appCtx) {
        ctx = appCtx.getApplicationContext();
        nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CH_ID,
                ctx.getString(R.string.notif_ch_downloads),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(ctx.getString(R.string.notif_ch_downloads_desc));
        nm.createNotificationChannel(ch);
    }

    /**
     * 下载入口处调一下：Android 13+ 没给通知权限就地申请一次，
     * 用户拒绝也不纠缠（下载照常，只是没通知）。
     */
    public static void ensurePermission(Activity a) {
        if (Build.VERSION.SDK_INT >= 33
                && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    private boolean canPost() {
        if (nm == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT < 33) {
            return true;
        }
        return ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private int nid(Downloader.Task t) {
        return 0xD1000 + Math.abs(t.id.hashCode() % 90000);
    }

    private PendingIntent openDownloads() {
        Intent i = new Intent(ctx, DownloadActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(ctx, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 进行中 / 已暂停：节流后刷新。 */
    public void onProgress(Downloader.Task t) {
        if (!canPost()) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        int pct = t.totalUnits > 0
                ? (int) (t.doneBytes * 100 / t.totalUnits) : -1;
        Long lu = lastUpd.get(t.id);
        Integer lp = lastPct.get(t.id);
        boolean paused = t.state == Downloader.Task.State.PAUSED;
        if (!paused && lu != null && now - lu < 1000
                && (lp == null || pct < 0 || Math.abs(pct - lp) < 3)) {
            return;
        }
        lastUpd.put(t.id, now);
        lastPct.put(t.id, pct);
        String name = t.fileName != null ? t.fileName
                : ctx.getString(R.string.notif_downloading);
        String sub;
        if (paused) {
            sub = ctx.getString(R.string.notif_paused);
        } else if (t.totalUnits > 0) {
            sub = pct + "% · " + Downloader.fmtSpeed(t.speedBps);
        } else {
            sub = Downloader.fmtSpeed(t.speedBps);
        }
        Notification.Builder b = new Notification.Builder(ctx, CH_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(name)
                .setContentText(sub)
                .setContentIntent(openDownloads())
                .setOngoing(!paused)
                .setOnlyAlertOnce(true);
        if (t.totalUnits > 0 && pct >= 0) {
            b.setProgress(100, Math.min(100, Math.max(0, pct)), false);
        } else {
            b.setProgress(0, 0, true);
        }
        try {
            nm.notify(nid(t), b.build());
        } catch (Exception e) {
            // 通知栏异常不影响下载
        }
    }

    /** 完成：点通知直接打开文件（打不开就进下载页）。 */
    public void onDone(Downloader.Task t) {
        if (!canPost()) {
            return;
        }
        String name = t.fileName != null ? t.fileName
                : ctx.getString(R.string.notif_done);
        PendingIntent pi = openFileIntent(t);
        Notification.Builder b = new Notification.Builder(ctx, CH_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(name)
                .setContentText(ctx.getString(R.string.notif_done_hint))
                .setContentIntent(pi != null ? pi : openDownloads())
                .setAutoCancel(true)
                .setOnlyAlertOnce(false);
        try {
            nm.notify(nid(t), b.build());
        } catch (Exception e) {
            // ignore
        }
        lastUpd.remove(t.id);
        lastPct.remove(t.id);
    }

    /** 失败：点通知进下载页看原因/重试。 */
    public void onFailed(Downloader.Task t) {
        if (!canPost()) {
            return;
        }
        String name = t.fileName != null ? t.fileName
                : ctx.getString(R.string.notif_downloading);
        Notification.Builder b = new Notification.Builder(ctx, CH_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(name)
                .setContentText(ctx.getString(R.string.notif_failed_hint))
                .setContentIntent(openDownloads())
                .setAutoCancel(true);
        try {
            nm.notify(nid(t), b.build());
        } catch (Exception e) {
            // ignore
        }
        lastUpd.remove(t.id);
        lastPct.remove(t.id);
    }

    /** 任务消失（取消/删除）：撤掉通知。 */
    public void dismiss(Downloader.Task t) {
        if (nm != null) {
            try {
                nm.cancel(nid(t));
            } catch (Exception e) {
                // ignore
            }
        }
        lastUpd.remove(t.id);
        lastPct.remove(t.id);
    }

    /**
     * 打开文件的 PendingIntent。逻辑与 DownloadActivity.openFile 一致：
     * 精确 MIME 打不开就退到通配类型，实在不行返回 null（调用方进下载页）。
     */
    private PendingIntent openFileIntent(Downloader.Task t) {
        try {
            if (t.target == null) {
                return null;
            }
            Uri uri;
            if (Build.VERSION.SDK_INT >= 29) {
                uri = Uri.parse(t.target);
            } else {
                uri = SimpleFileProvider.uriForFile(ctx, new File(t.target));
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, t.mime != null ? t.mime : "*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            // 先确认有应用能接，没就用 */* 再试
            if (i.resolveActivity(ctx.getPackageManager()) == null) {
                i.setDataAndType(uri, "*/*");
                if (i.resolveActivity(ctx.getPackageManager()) == null) {
                    return null;
                }
            }
            return PendingIntent.getActivity(ctx, nid(t), i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (ActivityNotFoundException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
