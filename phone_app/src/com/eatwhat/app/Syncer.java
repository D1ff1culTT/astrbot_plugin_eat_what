package com.eatwhat.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地待同步记录的补传：先逐张上传照片拿 URL，再提交一餐。
 * 网络不可用时立即停止并保留队列，下次联网（启动/网络恢复/手动）再试；
 * 被服务器拒绝（参数/token 问题）也停止，提示用户排查。
 */
public final class Syncer {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    public interface Callback {
        /** synced=本次成功条数；remaining=剩余条数；errorMsg 非空表示被服务器拒绝。 */
        void done(int synced, int remaining, String errorMsg);
    }

    private Syncer() {
    }

    public static void sync(final Context ctx, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        final int remaining = LocalStore.get(app).pendingCount();
        if (!Api.configured(app) || !RUNNING.compareAndSet(false, true)) {
            if (cb != null) cb.done(0, remaining, null);
            return;
        }
        Api.io(() -> {
            int synced = 0;
            String error = null;
            try {
                while (true) {
                    LocalStore.Pending p = LocalStore.get(app).peekOldest(app);
                    if (p == null) break;
                    try {
                        uploadAndPost(app, p);
                        LocalStore.get(app).deletePending(app, p.id);
                        synced++;
                    } catch (Api.HttpError e) {
                        error = "有记录被服务器拒绝：" + e.getMessage() + "（可在 ⚙ 检查 Token）";
                        break;
                    } catch (IOException e) {
                        break;  // 网络不可用：静默保留，下次再试
                    }
                }
            } finally {
                RUNNING.set(false);
            }
            final int ok = synced;
            final int left = LocalStore.get(app).pendingCount();
            final String err = error;
            Api.ui(() -> {
                if (cb != null) cb.done(ok, left, err);
            });
        });
    }

    private static void uploadAndPost(Context c, LocalStore.Pending p) throws Exception {
        JSONArray dishes = p.payload.optJSONArray("dishes");
        for (int i = 0; dishes != null && i < dishes.length(); i++) {
            JSONObject dish = dishes.optJSONObject(i);
            JSONArray local = p.dishPhotos.optJSONArray(i);
            if (dish == null) continue;
            JSONArray urls = new JSONArray();
            for (int j = 0; local != null && j < local.length(); j++) {
                urls.put(Api.upload(c, new File(local.optString(j))));
            }
            dish.put("images", urls);
        }
        Api.visit(c, p.payload);
    }
}
