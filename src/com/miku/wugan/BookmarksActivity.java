package com.miku.wugan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 收藏夹列表：复用 activity_history 布局。点击打开，长按删除，一键清空。 */
public class BookmarksActivity extends Activity {

    private BookmarkDbHelper db;
    private SimpleAdapter adapter;
    private final List<Map<String, String>> data = new ArrayList<Map<String, String>>();
    private final List<String> urls = new ArrayList<String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        db = new BookmarkDbHelper(this);

        ListView listView = findViewById(R.id.history_list);
        adapter = new SimpleAdapter(this, data,
                android.R.layout.simple_list_item_2,
                new String[]{"line1", "line2"},
                new int[]{android.R.id.text1, android.R.id.text2});
        listView.setAdapter(adapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String url = urls.get(position);
                Intent i = new Intent(BookmarksActivity.this, MainActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                i.putExtra("url", url);
                startActivity(i);
                finish();
            }
        });

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view,
                                            final int position, long id) {
                final String url = urls.get(position);
                new AlertDialog.Builder(BookmarksActivity.this)
                        .setMessage(getString(R.string.delete_bookmark_confirm, url))
                        .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                db.delete(url);
                                refresh();
                                Toast.makeText(BookmarksActivity.this,
                                        getString(R.string.deleted), Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
                return true;
            }
        });

        Button clearBtn = findViewById(R.id.clear_history_button);
        clearBtn.setText(R.string.clear_bookmarks);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                db.clear();
                refresh();
            }
        });

        refresh();
    }

    private void refresh() {
        data.clear();
        urls.clear();
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        Cursor c = db.list();
        try {
            int urlI = c.getColumnIndex("url");
            int titleI = c.getColumnIndex("title");
            int timeI = c.getColumnIndex("time");
            while (c.moveToNext()) {
                String url = c.getString(urlI);
                String title = c.getString(titleI);
                long t = c.getLong(timeI);
                Map<String, String> m = new HashMap<String, String>();
                m.put("line1", fmt.format(new Date(t)) + "  " + title);
                m.put("line2", UrlFmt.display(url));
                data.add(m);
                urls.add(url);
            }
        } finally {
            c.close();
        }
        adapter.notifyDataSetChanged();
    }

    @Override
    protected void onDestroy() {
        if (db != null) {
            db.close();
        }
        super.onDestroy();
    }
}
