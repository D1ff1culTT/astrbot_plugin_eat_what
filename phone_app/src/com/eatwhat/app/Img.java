package com.eatwhat.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;

/** 上传前的图片压缩：EXIF 摆正 + 采样缩放 + 长边 ≤1600 的 JPEG。 */
public final class Img {

    public static File compress(Context c, File src) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return src;

        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1600) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(src.getAbsolutePath(), o);
        if (b == null) return src;
        b = rotateByExif(src, b);

        int max = Math.max(b.getWidth(), b.getHeight());
        if (max > 1600) {
            float scale = 1600f / max;
            b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale),
                    Math.round(b.getHeight() * scale), true);
        }
        File out = new File(new File(c.getCacheDir(), "up"),
                "up_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 100000) + ".jpg");
        out.getParentFile().mkdirs();
        FileOutputStream fo = new FileOutputStream(out);
        b.compress(Bitmap.CompressFormat.JPEG, 85, fo);
        fo.close();
        return out;
    }

    /** 列表缩略图：按目标像素采样解码。 */
    public static Bitmap thumb(File src, int px) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getAbsolutePath(), o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= px) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(src.getAbsolutePath(), o);
        if (b == null) return null;
        Bitmap fixed = rotateByExif(src, b);
        if (fixed != b) {
            int m = Math.max(fixed.getWidth(), fixed.getHeight());
            if (m > px * 2) {
                float scale = px * 2f / m;
                fixed = Bitmap.createScaledBitmap(fixed,
                        Math.round(fixed.getWidth() * scale), Math.round(fixed.getHeight() * scale), true);
            }
        }
        return fixed;
    }

    private static Bitmap rotateByExif(File src, Bitmap b) {
        try {
            ExifInterface ex = new ExifInterface(src.getAbsolutePath());
            int deg;
            switch (ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    deg = 90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    deg = 180;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    deg = 270;
                    break;
                default:
                    return b;
            }
            Matrix m = new Matrix();
            m.postRotate(deg);
            return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        } catch (Exception e) {
            return b;
        }
    }

    private Img() {
    }
}
