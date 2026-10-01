package com.miku.wugan;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * 内置下载器界面：文件名 + 进度条 + 状态行（百分比/速度/剩余时间），
 * 支持暂停/继续/取消；完成后可打开或删除。
 * 后台每 500ms 刷新一次（读 Task 的 volatile 字段）。
 */
public class DownloadActivity extends Activity {

    private static final int REQ_STORAGE = 9001;

    private DownloadAdapter adapter;
    private final Handler handler = new Handler();
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (adapter != null) {
                adapter.notifyDataSetChanged();
                updateEmpty();
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download);

        ListView listView = findViewById(R.id.download_list);
        adapter = new DownloadAdapter();
        listView.setAdapter(adapter);

        // API 26-28 写公共 Download 目录需要运行时权限
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_STORAGE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode == REQ_STORAGE
                && (grantResults.length == 0
                || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, getString(R.string.dl_need_perm),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void updateEmpty() {
        boolean empty = Downloader.get(this).tasks().isEmpty();
        findViewById(R.id.download_empty).setVisibility(
                empty ? View.VISIBLE : View.GONE);
        findViewById(R.id.download_list).setVisibility(
                empty ? View.GONE : View.VISIBLE);
    }

    private class DownloadAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return Downloader.get(DownloadActivity.this).tasks().size();
        }

        @Override
        public Object getItem(int position) {
            return Downloader.get(DownloadActivity.this).tasks().get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder h;
            if (convertView == null) {
                convertView = LayoutInflater.from(DownloadActivity.this)
                        .inflate(R.layout.item_download, parent, false);
                h = new ViewHolder();
                h.name = convertView.findViewById(R.id.dl_name);
                h.progress = convertView.findViewById(R.id.dl_progress);
                h.status = convertView.findViewById(R.id.dl_status);
                h.toggle = convertView.findViewById(R.id.dl_btn_toggle);
                h.cancel = convertView.findViewById(R.id.dl_btn_cancel);
                convertView.setTag(h);
            } else {
                h = (ViewHolder) convertView.getTag();
            }
            final Downloader.Task t =
                    Downloader.get(DownloadActivity.this).tasks().get(position);
            final Downloader dl = Downloader.get(DownloadActivity.this);

            h.name.setText(t.fileName);

            int pct = progressPct(t);
            h.progress.setIndeterminate(pct < 0);
            h.progress.setProgress(Math.max(0, pct));
            h.status.setText(statusText(t));

            // 按钮按状态切换
            switch (t.state) {
                case DOWNLOADING:
                case QUEUED:
                    h.toggle.setText(R.string.dl_pause);
                    h.toggle.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { dl.pause(t); }
                    });
                    h.cancel.setText(R.string.dl_cancel);
                    h.cancel.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { dl.cancel(t); }
                    });
                    h.cancel.setVisibility(View.VISIBLE);
                    break;
                case PAUSED:
                    h.toggle.setText(R.string.dl_resume);
                    h.toggle.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { dl.resume(t); }
                    });
                    h.cancel.setText(R.string.dl_cancel);
                    h.cancel.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { dl.cancel(t); }
                    });
                    h.cancel.setVisibility(View.VISIBLE);
                    break;
                case FAILED:
                    h.toggle.setText(R.string.dl_retry);
                    h.toggle.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { dl.resume(t); }
                    });
                    h.cancel.setText(R.string.dl_cancel);
                    h.cancel.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { dl.cancel(t); }
                    });
                    h.cancel.setVisibility(View.VISIBLE);
                    break;
                case DONE:
                    h.toggle.setText(R.string.dl_open);
                    h.toggle.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { openFile(t); }
                    });
                    h.cancel.setText(R.string.dl_delete);
                    h.cancel.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { dl.cancel(t); }
                    });
                    h.cancel.setVisibility(View.VISIBLE);
                    break;
                default:
                    h.cancel.setVisibility(View.GONE);
                    break;
            }
            return convertView;
        }

        private int progressPct(Downloader.Task t) {
            if (t.hls) {
                if (t.totalUnits <= 0) {
                    return -1;
                }
                return (int) (t.doneSeg * 100 / t.totalUnits);
            }
            if (t.totalUnits <= 0) {
                return -1;
            }
            return (int) (t.doneBytes * 100 / t.totalUnits);
        }

        private String statusText(Downloader.Task t) {
            switch (t.state) {
                case QUEUED:
                    return getString(R.string.dl_waiting);
                case PAUSED:
                    return getString(R.string.dl_paused);
                case DONE:
                    return getString(R.string.dl_done);
                case CANCELED:
                    return getString(R.string.dl_canceled);
                case FAILED:
                    return getString(R.string.dl_failed,
                            t.error != null ? t.error : "");
                default: {
                    String speed = Downloader.fmtSpeed(t.speedBps);
                    if (t.hls) {
                        String eta = Downloader.fmtEta(t);
                        if (eta != null) {
                            return getString(R.string.dl_status_hls,
                                    t.doneSeg, t.totalUnits, speed, eta);
                        }
                        return getString(R.string.dl_status_hls_noeta,
                                t.doneSeg, t.totalUnits, speed);
                    }
                    if (t.totalUnits > 0) {
                        int pct = (int) (t.doneBytes * 100 / t.totalUnits);
                        String eta = Downloader.fmtEta(t);
                        if (eta != null) {
                            return getString(R.string.dl_status_file,
                                    pct, speed, eta);
                        }
                        return getString(R.string.dl_status_file_noeta,
                                pct, speed);
                    }
                    return getString(R.string.dl_status_unknown,
                            Downloader.fmtSize(t.doneBytes), speed);
                }
            }
        }

        class ViewHolder {
            TextView name;
            ProgressBar progress;
            TextView status;
            Button toggle;
            Button cancel;
        }
    }

    private void openFile(Downloader.Task t) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            if (Build.VERSION.SDK_INT >= 29) {
                i.setDataAndType(Uri.parse(t.target), t.mime);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                i.setDataAndType(Uri.fromFile(new File(t.target)), t.mime);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.dl_no_app),
                    Toast.LENGTH_SHORT).show();
        }
    }
}
