package com.miku.wugan;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * 内置下载器界面：文件名 + 进度条 + 状态行（百分比/速度/剩余时间）。
 * 每行右侧 ⋮ 菜单：下载中=暂停/取消，暂停=继续/取消，失败=重试/删除，
 * 完成=打开/在文件管理器中打开/重命名/删除。
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
                h.more = convertView.findViewById(R.id.dl_btn_more);
                convertView.setTag(h);
            } else {
                h = (ViewHolder) convertView.getTag();
            }
            final Downloader.Task t =
                    Downloader.get(DownloadActivity.this).tasks().get(position);

            h.name.setText(t.fileName);

            int pct = progressPct(t);
            h.progress.setIndeterminate(pct < 0);
            h.progress.setProgress(Math.max(0, pct));
            h.status.setText(statusText(t));

            h.more.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showMenu(v, t);
                }
            });
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
            ImageButton more;
        }
    }

    // ---------------- ⋮ 菜单 ----------------

    private void showMenu(View anchor, final Downloader.Task t) {
        final Downloader dl = Downloader.get(this);
        PopupMenu menu = new PopupMenu(this, anchor);
        Menu m = menu.getMenu();
        switch (t.state) {
            case DOWNLOADING:
            case QUEUED:
                m.add(0, 1, 0, R.string.dl_pause);
                m.add(0, 2, 0, R.string.dl_cancel);
                break;
            case PAUSED:
                m.add(0, 3, 0, R.string.dl_resume);
                m.add(0, 2, 0, R.string.dl_cancel);
                break;
            case FAILED:
                m.add(0, 4, 0, R.string.dl_retry);
                m.add(0, 5, 0, R.string.dl_delete);
                break;
            case DONE:
                m.add(0, 6, 0, R.string.dl_open);
                m.add(0, 7, 0, R.string.dl_open_folder);
                m.add(0, 8, 0, R.string.dl_rename);
                m.add(0, 5, 0, R.string.dl_delete);
                break;
            default:
                return;
        }
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        dl.pause(t);
                        return true;
                    case 2: // 取消（下载中/暂停中）：停掉并移除
                    case 5: // 删除（失败/完成）：删文件并移除
                        dl.cancel(t);
                        return true;
                    case 3:
                    case 4:
                        dl.resume(t);
                        return true;
                    case 6:
                        openFile(t);
                        return true;
                    case 7:
                        openFolder();
                        return true;
                    case 8:
                        renameDialog(t);
                        return true;
                    default:
                        return false;
                }
            }
        });
        menu.show();
    }

    // ---------------- 打开 / 文件夹 / 重命名 ----------------

    private void openFile(Downloader.Task t) {
        try {
            Uri uri;
            if (Build.VERSION.SDK_INT >= 29) {
                uri = Uri.parse(t.target);
            } else {
                // file:// 会被系统直接拦（FileUriExposedException），走自己的 provider
                uri = SimpleFileProvider.uriForFile(this, new File(t.target));
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, t.mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
            } catch (ActivityNotFoundException e) {
                // 精确 MIME 没应用接（比如之前下错的 octet-stream），退到 */* 让用户选
                i.setDataAndType(uri, "*/*");
                startActivity(i);
            }
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.dl_no_app),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** 在系统文件管理器里打开 Download/无感浏览器 文件夹。 */
    private void openFolder() {
        try {
            Uri uri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Download/" + Downloader.DIR_NAME);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.dl_no_folder),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void renameDialog(final Downloader.Task t) {
        final EditText input = new EditText(this);
        input.setText(t.fileName);
        input.selectAll();
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle(R.string.dl_rename)
                .setView(input)
                .setPositiveButton(android.R.string.ok,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                boolean ok = Downloader.get(DownloadActivity.this)
                                        .rename(t,
                                                input.getText().toString());
                                Toast.makeText(DownloadActivity.this,
                                        ok ? R.string.dl_rename_ok
                                                : R.string.dl_rename_failed,
                                        Toast.LENGTH_SHORT).show();
                                if (adapter != null) {
                                    adapter.notifyDataSetChanged();
                                }
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
