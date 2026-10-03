package com.eatwhat.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 相机拍照回写的极简 FileProvider 替代（免 androidx）：
 * 把 cache/capture/ 下的文件以 content:// 暴露给相机 app 写入。
 */
public class PhotoProvider extends ContentProvider {

    public static final String AUTH = "com.eatwhat.app.photos";

    private static File dir(Context c) {
        return new File(c.getCacheDir(), "capture");
    }

    /** 生成一次拍照的目标 URI。 */
    public static Uri newCaptureUri(Context c) {
        File d = dir(c);
        d.mkdirs();
        File f = new File(d, "cap_" + System.currentTimeMillis() + ".jpg");
        return Uri.parse("content://" + AUTH + "/capture/" + f.getName());
    }

    private static final String SAFE_NAME = "[A-Za-z0-9._-]+";

    /** 由拍照返回的 URI 找回本地文件。 */
    public static File fileFor(Context c, Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches(SAFE_NAME)) {
            throw new IllegalArgumentException("bad capture uri");
        }
        return new File(dir(c), name);
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches(SAFE_NAME)) {
            throw new FileNotFoundException(uri.toString());
        }
        File f = new File(dir(getContext()), name);
        if (!f.getParentFile().equals(dir(getContext()))) {
            throw new FileNotFoundException(uri.toString());
        }
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int m = ParcelFileDescriptor.MODE_READ_WRITE
                | ParcelFileDescriptor.MODE_CREATE
                | ParcelFileDescriptor.MODE_TRUNCATE;
        return ParcelFileDescriptor.open(fileFor(uri), m);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null && projection.length > 0
                ? projection : new String[]{"_display_name", "_size"};
        MatrixCursor mc = new MatrixCursor(cols);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if ("_display_name".equals(cols[i])) row[i] = f.getName();
            else if ("_size".equals(cols[i])) row[i] = f.length();
            else row[i] = null;
        }
        mc.addRow(row);
        return mc;
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
