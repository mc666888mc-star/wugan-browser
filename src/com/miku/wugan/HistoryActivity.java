package com.miku.wugan;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SimpleAdapter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 浏览历史列表：时间 + 标题 + 网址，点击回到浏览器打开，支持一键清空。 */
public class HistoryActivity extends Activity {

    private HistoryDbHelper db;
    private SimpleAdapter adapter;
    private final List<Map<String, String>> data = new ArrayList<Map<String, String>>();
    private final List<String> urls = new ArrayList<String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        db = new HistoryDbHelper(this);

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
                Intent i = new Intent(HistoryActivity.this, MainActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                i.putExtra("url", url);
                startActivity(i);
                finish();
            }
        });

        Button clearBtn = findViewById(R.id.clear_history_button);
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
                m.put("line2", url);
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
