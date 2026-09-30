package com.miku.wugan;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** 收藏夹：SQLite 存 url/title/time，同 url 去重（新记录顶到最前面）。 */
public class BookmarkDbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "bookmarks.db";
    private static final int DB_VER = 1;

    public BookmarkDbHelper(Context ctx) {
        super(ctx, DB_NAME, null, DB_VER);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE bookmarks("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "url TEXT,title TEXT,time INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
    }

    public void add(String url, String title) {
        if (url == null) {
            return;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.delete("bookmarks", "url=?", new String[]{url});
        ContentValues cv = new ContentValues();
        cv.put("url", url);
        cv.put("title", title == null ? url : title);
        cv.put("time", System.currentTimeMillis());
        db.insert("bookmarks", null, cv);
    }

    public void delete(String url) {
        getWritableDatabase().delete("bookmarks", "url=?", new String[]{url});
    }

    public Cursor list() {
        return getReadableDatabase().query("bookmarks",
                new String[]{"_id", "url", "title", "time"},
                null, null, null, null, "time DESC", "500");
    }

    public void clear() {
        getWritableDatabase().delete("bookmarks", null, null);
    }
}
