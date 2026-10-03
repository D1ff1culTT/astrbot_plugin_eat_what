package com.eatwhat.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 简易异步图片加载：内存缓存 + 采样解码，用于列表缩略图与详情大图。 */
public final class ImageLoader {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    public static void load(ImageView iv, String url) {
        if (url == null || url.isEmpty()) {
            iv.setTag(null);
            iv.setImageBitmap(null);
            return;
        }
        Bitmap hit = CACHE.get(url);
        if (hit != null) {
            iv.setTag(url);
            iv.setImageBitmap(hit);
            return;
        }
        iv.setTag(url);
        iv.setImageBitmap(null);
        Api.io(() -> {
            try {
                Bitmap b = fetch(url);
                if (b == null) return;
                CACHE.put(url, b);
                Api.ui(() -> {
                    if (url.equals(iv.getTag())) iv.setImageBitmap(b);
                });
            } catch (Exception ignored) {
            }
        });
    }

    private static Bitmap fetch(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(20000);
        InputStream is = conn.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        conn.disconnect();
        byte[] data = bo.toByteArray();

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= 720 || o.outHeight / (sample * 2) >= 720) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    private ImageLoader() {
    }
}
