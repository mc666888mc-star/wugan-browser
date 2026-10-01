package com.miku.wugan;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.Locale;

/**
 * 极简 FileProvider（无第三方依赖，不想引入 androidx 就手写一个）。
 * 只给 API 26-28 的下载文件对外分享用（29+ 走 MediaStore 自带 content URI）。
 * URI 形如 content://com.miku.wugan.fileprovider/download/&lt;Download 下的相对路径&gt;。
 */
public class SimpleFileProvider extends ContentProvider {

    /** 把 Download/ 下的文件包成 content URI（带 FLAG_GRANT_READ_URI_PERMISSION 分享）。 */
    public static Uri uriForFile(Context ctx, File file) throws Exception {
        File base = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        String basePath = base.getCanonicalPath();
        String path = file.getCanonicalPath();
        if (!path.startsWith(basePath + File.separator)) {
            throw new SecurityException("not under Downloads");
        }
        String rel = path.substring(basePath.length() + 1);
        Uri.Builder b = new Uri.Builder()
                .scheme("content")
                .authority(ctx.getPackageName() + ".fileprovider")
                .appendPath("download");
        for (String seg : rel.split("/")) {
            b.appendPath(seg);
        }
        return b.build();
    }

    private File fileForUri(Uri uri) throws FileNotFoundException {
        List<String> segs = uri.getPathSegments();
        if (segs.size() < 2 || !"download".equals(segs.get(0))) {
            throw new FileNotFoundException("bad uri");
        }
        try {
            File base = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            File f = new File(base, segs.get(1));
            for (int i = 2; i < segs.size(); i++) {
                f = new File(f, segs.get(i));
            }
            // canonical 校验：防止 ../ 跳出 Download 目录
            String basePath = base.getCanonicalPath();
            String path = f.getCanonicalPath();
            if (!path.startsWith(basePath + File.separator) || !f.isFile()) {
                throw new FileNotFoundException("outside Downloads");
            }
            return f;
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException("bad uri");
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileForUri(uri),
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        try {
            String name = fileForUri(uri).getName()
                    .toLowerCase(Locale.ROOT);
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                String mt = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(name.substring(dot + 1));
                if (mt != null) {
                    return mt;
                }
            }
        } catch (Exception ignored) {
        }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            File f = fileForUri(uri);
            MatrixCursor c = new MatrixCursor(new String[]{
                    OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
