package com.eatwhat.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Random;

/**
 * 离线兜底存储（手机本地）：
 * - pending_visits：连不上服务器时保存的一餐（payload + 本地照片路径），联网后由 Syncer 补传。
 * - response_cache：列表/详情接口最后一次成功响应，离线时展示缓存数据。
 */
public class LocalStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "eatwhat_offline.db";
    private static final int DB_VERSION = 1;

    private static LocalStore sInstance;

    public static synchronized LocalStore get(Context c) {
        if (sInstance == null) {
            sInstance = new LocalStore(c.getApplicationContext());
        }
        return sInstance;
    }

    private LocalStore(Context c) {
        super(c, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE pending_visits("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "created_at TEXT DEFAULT (datetime('now','localtime')), "
                + "payload TEXT NOT NULL,"
                + "photos TEXT DEFAULT '{}')");
        db.execSQL("CREATE TABLE response_cache("
                + "key TEXT PRIMARY KEY,"
                + "body TEXT NOT NULL,"
                + "saved_at INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    // ---------------- 待同步的一餐 ----------------

    /** 队列中的一条记录。 */
    public static class Pending {
        public long id;
        public JSONObject payload;   // /api/visits 请求体（images 字段为空）
        public JSONArray dishPhotos; // 每道菜的本地照片路径数组（与 payload.dishes 下标对齐）
    }

    /**
     * 保存一餐到本地队列：照片复制进 files/pending/（cache 目录会被系统清理，不能用）。
     * photoFiles 与 payload.dishes 下标对齐；返回当前队列总数。
     */
    public int enqueueVisit(Context c, JSONObject payload, List<List<File>> photoFiles)
            throws Exception {
        File dir = pendingDir(c);
        dir.mkdirs();
        JSONArray paths = new JSONArray();
        Random rnd = new Random();
        for (int i = 0; i < photoFiles.size(); i++) {
            JSONArray arr = new JSONArray();
            for (File f : photoFiles.get(i)) {
                File dst = new File(dir, "p" + System.currentTimeMillis() + "_" + i
                        + "_" + arr.length() + "_" + rnd.nextInt(100000) + ".jpg");
                copy(f, dst);
                arr.put(dst.getAbsolutePath());
            }
            paths.put(arr);
        }
        JSONObject photos = new JSONObject();
        photos.put("dishes", paths);
        photos.put("overall", new JSONArray());

        ContentValues cv = new ContentValues();
        cv.put("payload", payload.toString());
        cv.put("photos", photos.toString());
        SQLiteDatabase db = getWritableDatabase();
        try {
            db.insert("pending_visits", null, cv);
            return pendingCount();
        } finally {
            db.close();
        }
    }

    public Pending peekOldest(Context c) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cur = null;
        try {
            cur = db.rawQuery(
                    "SELECT id, payload, photos FROM pending_visits ORDER BY id LIMIT 1", null);
            if (!cur.moveToFirst()) return null;
            Pending p = new Pending();
            p.id = cur.getLong(0);
            try {
                p.payload = new JSONObject(cur.getString(1));
            } catch (Exception e) {
                return null;
            }
            p.dishPhotos = new JSONArray();
            try {
                JSONArray arr = new JSONObject(cur.getString(2)).optJSONArray("dishes");
                if (arr != null) p.dishPhotos = arr;
            } catch (Exception ignored) {
            }
            return p;
        } finally {
            if (cur != null) cur.close();
            db.close();
        }
    }

    public void deletePending(Context c, long id) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor cur = null;
        try {
            cur = db.rawQuery("SELECT photos FROM pending_visits WHERE id=?",
                    new String[]{String.valueOf(id)});
            if (cur.moveToFirst()) {
                try {
                    JSONArray dishes = new JSONObject(cur.getString(0)).optJSONArray("dishes");
                    for (int i = 0; dishes != null && i < dishes.length(); i++) {
                        JSONArray arr = dishes.optJSONArray(i);
                        for (int j = 0; arr != null && j < arr.length(); j++) {
                            new File(arr.optString(j)).delete();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            db.delete("pending_visits", "id=?", new String[]{String.valueOf(id)});
        } finally {
            if (cur != null) cur.close();
            db.close();
        }
    }

    public int pendingCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cur = null;
        try {
            cur = db.rawQuery("SELECT COUNT(*) FROM pending_visits", null);
            cur.moveToFirst();
            return cur.getInt(0);
        } finally {
            if (cur != null) cur.close();
            db.close();
        }
    }

    private static File pendingDir(Context c) {
        return new File(c.getFilesDir(), "pending");
    }

    private static void copy(File src, File dst) throws Exception {
        InputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    // ---------------- 响应缓存 ----------------

    public void cachePut(String key, String body) {
        SQLiteDatabase db = getWritableDatabase();
        try {
            ContentValues cv = new ContentValues();
            cv.put("key", key);
            cv.put("body", body);
            cv.put("saved_at", System.currentTimeMillis());
            db.insertWithOnConflict("response_cache", null, cv,
                    SQLiteDatabase.CONFLICT_REPLACE);
        } finally {
            db.close();
        }
    }

    public String cacheGet(String key) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cur = null;
        try {
            cur = db.rawQuery("SELECT body FROM response_cache WHERE key=?",
                    new String[]{key});
            return cur.moveToFirst() ? cur.getString(0) : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (cur != null) cur.close();
            db.close();
        }
    }
}
