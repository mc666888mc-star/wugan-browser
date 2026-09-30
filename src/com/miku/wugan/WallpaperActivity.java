package com.miku.wugan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * 壁纸管理：缩略图网格。点一下设为当前壁纸，长按删除。
 */
public class WallpaperActivity extends Activity {

    private GridView grid;
    private TextView emptyView;
    private File[] wallpapers = new File[0];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wallpaper);

        grid = findViewById(R.id.wallpaper_grid);
        emptyView = findViewById(R.id.wallpaper_empty);

        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view,
                                    int position, long id) {
                File f = wallpapers[position];
                WallpaperManager.setCurrent(WallpaperActivity.this, f);
                Toast.makeText(WallpaperActivity.this, getString(R.string.wallpaper_set_current),
                        Toast.LENGTH_SHORT).show();
                refresh();
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view,
                                           final int position, long id) {
                new AlertDialog.Builder(WallpaperActivity.this)
                        .setTitle(R.string.delete_wallpaper)
                        .setMessage(R.string.delete_wallpaper_confirm)
                        .setPositiveButton(R.string.delete,
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d,
                                                        int which) {
                                        File f = wallpapers[position];
                                        if (WallpaperManager.delete(
                                                WallpaperActivity.this, f)) {
                                            Toast.makeText(
                                                    WallpaperActivity.this,
                                                    getString(R.string.deleted), Toast.LENGTH_SHORT)
                                                    .show();
                                        } else {
                                            Toast.makeText(
                                                    WallpaperActivity.this,
                                                    getString(R.string.delete_failed), Toast.LENGTH_SHORT)
                                                    .show();
                                        }
                                        refresh();
                                    }
                                })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
                return true;
            }
        });

        refresh();
    }

    private void refresh() {
        wallpapers = WallpaperManager.list(this);
        grid.setAdapter(new ThumbAdapter());
        emptyView.setVisibility(
                wallpapers.length == 0 ? View.VISIBLE : View.GONE);
        grid.setVisibility(
                wallpapers.length == 0 ? View.GONE : View.VISIBLE);
    }

    private class ThumbAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return wallpapers.length;
        }

        @Override
        public Object getItem(int position) {
            return wallpapers[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView,
                            ViewGroup parent) {
            ImageView iv;
            if (convertView instanceof ImageView) {
                iv = (ImageView) convertView;
            } else {
                iv = new ImageView(WallpaperActivity.this);
                int col = (grid.getWidth() - grid.getPaddingLeft()
                        - grid.getPaddingRight()) / 2;
                if (col <= 0) {
                    col = 480;
                }
                iv.setLayoutParams(new GridView.LayoutParams(col, col * 3 / 4));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(0xFF2A2A2A);
            }
            Bitmap bm = thumb(wallpapers[position]);
            if (bm != null) {
                iv.setImageBitmap(bm);
            } else {
                iv.setImageDrawable(null);
            }
            // 当前壁纸加高亮边框提示
            String cur = WallpaperManager.currentName(WallpaperActivity.this);
            if (cur != null && cur.equals(wallpapers[position].getName())) {
                iv.setPadding(4, 4, 4, 4);
                iv.setBackgroundColor(0xFF4CAF50);
            } else {
                iv.setPadding(0, 0, 0, 0);
                iv.setBackgroundColor(0xFF2A2A2A);
            }
            return iv;
        }
    }

    /** 按需缩放解码，避免大图 OOM */
    private static Bitmap thumb(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int scale = 1;
            while ((o.outWidth / scale > 360 || o.outHeight / scale > 360)
                    && scale < 16) {
                scale *= 2;
            }
            o.inJustDecodeBounds = false;
            o.inSampleSize = scale;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Exception e) {
            return null;
        }
    }
}
