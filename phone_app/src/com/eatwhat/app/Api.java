package com.eatwhat.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 服务器 HTTP 层：baseUrl/token 存 SharedPreferences。
 * 所有网络方法必须在后台线程调用（配合 {@link #io}）；返回 org.json 结构。
 *
 * 读取类接口支持离线缓存回退（带 boolean[] fromCache 出参的重载）：
 * 网络失败时返回上次缓存的响应，fromCache[0]=true 供 UI 提示。
 */
public final class Api {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 服务器明确拒绝（HTTP >= 400）——重试无意义；其余 IOException 视为网络不可用。 */
    public static class HttpError extends IOException {
        public final int code;

        HttpError(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    /** 后台任务，异常由任务内部自行处理（toast）。 */
    public interface Task {
        void run() throws Exception;
    }

    public static void io(Task task) {
        POOL.execute(() -> {
            try {
                task.run();
            } catch (Throwable ignored) {
            }
        });
    }

    public static void ui(Task task) {
        MAIN.post(() -> {
            try {
                task.run();
            } catch (Exception ignored) {
            }
        });
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("api", Context.MODE_PRIVATE);
    }

    public static String baseUrl(Context c) {
        String v = prefs(c).getString("base", "");
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    public static String token(Context c) {
        return prefs(c).getString("token", "");
    }

    public static boolean configured(Context c) {
        return !baseUrl(c).isEmpty();
    }

    public static void setServer(Context c, String base, String token) {
        String b = base.trim();
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        prefs(c).edit().putString("base", b).putString("token", token.trim()).apply();
    }

    /** 相对路径（如 /images/xx.jpg）转绝对 URL。 */
    public static String abs(Context c, String path) {
        if (path == null || path.isEmpty() || path.startsWith("http")) return path;
        return baseUrl(c) + path;
    }

    private static HttpURLConnection open(Context c, String path) throws IOException {
        if (!configured(c)) throw new IOException("请先在右上角 ⚙ 配置服务器地址");
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl(c) + path).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(30000);
        String tk = token(c);
        if (!tk.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + tk);
        return conn;
    }

    private static String readBody(HttpURLConnection conn) throws IOException {
        InputStream is = conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        r.close();
        return sb.toString();
    }

    private static String errorFor(int code, String body) {
        String msg = "";
        try {
            msg = new JSONObject(body).optString("detail");
        } catch (Exception ignored) {
        }
        if (code == 401) return "令牌错误（⚙ 里检查 Token）";
        return msg.isEmpty() ? "服务器错误 " + code : msg;
    }

    private static String fetch(Context c, String path) throws Exception {
        HttpURLConnection conn = open(c, path);
        int code = conn.getResponseCode();
        String body = readBody(conn);
        conn.disconnect();
        if (code >= 400) throw new HttpError(code, errorFor(code, body));
        return body;
    }

    /** 网络失败时回退到上次缓存（请求路径即缓存键）；fromCache 可为 null。 */
    private static String fetchCached(Context c, String path, boolean[] fromCache) throws Exception {
        try {
            String body = fetch(c, path);
            LocalStore.get(c).cachePut(path, body);
            if (fromCache != null) fromCache[0] = false;
            return body;
        } catch (HttpError e) {
            throw e;  // 服务器明确报错，不使用缓存
        } catch (IOException e) {
            String cached = LocalStore.get(c).cacheGet(path);
            if (cached == null) throw e;
            if (fromCache != null) fromCache[0] = true;
            return cached;
        }
    }

    public static JSONObject get(Context c, String path) throws Exception {
        return new JSONObject(fetch(c, path));
    }

    public static JSONArray getArr(Context c, String path) throws Exception {
        return new JSONArray(fetch(c, path));
    }

    public static JSONObject getCached(Context c, String path, boolean[] fromCache) throws Exception {
        return new JSONObject(fetchCached(c, path, fromCache));
    }

    public static JSONArray getArrCached(Context c, String path, boolean[] fromCache) throws Exception {
        return new JSONArray(fetchCached(c, path, fromCache));
    }

    public static JSONObject post(Context c, String path, JSONObject payload) throws Exception {
        return sendWithBody(c, "POST", path, payload);
    }

    public static JSONObject put(Context c, String path, JSONObject payload) throws Exception {
        return sendWithBody(c, "PUT", path, payload);
    }

    private static JSONObject sendWithBody(Context c, String method, String path,
                                           JSONObject payload) throws Exception {
        HttpURLConnection conn = open(c, path);
        conn.setRequestMethod(method);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        OutputStream os = conn.getOutputStream();
        os.write(payload.toString().getBytes("UTF-8"));
        os.close();
        int code = conn.getResponseCode();
        String body = readBody(conn);
        conn.disconnect();
        if (code >= 400) throw new HttpError(code, errorFor(code, body));
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            throw new IOException("响应不是有效 JSON");
        }
    }

    public static JSONObject delete(Context c, String path) throws Exception {
        HttpURLConnection conn = open(c, path);
        conn.setRequestMethod("DELETE");
        int code = conn.getResponseCode();
        String body = readBody(conn);
        conn.disconnect();
        if (code >= 400) throw new HttpError(code, errorFor(code, body));
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            throw new IOException("响应不是有效 JSON");
        }
    }

    /** 上传图片，返回服务器相对 URL。 */
    public static String upload(Context c, File f) throws Exception {
        HttpURLConnection conn = open(c, "/api/upload");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        String boundary = "----eatwhat" + System.currentTimeMillis();
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        OutputStream os = new BufferedOutputStream(conn.getOutputStream());
        os.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"img.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n").getBytes("UTF-8"));
        InputStream in = new BufferedInputStream(new FileInputStream(f));
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        in.close();
        os.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
        os.close();
        int code = conn.getResponseCode();
        String body = readBody(conn);
        conn.disconnect();
        if (code >= 400) throw new HttpError(code, errorFor(code, body));
        return new JSONObject(body).getString("url");
    }

    // ---------------- 业务接口 ----------------
    // mode 约定：-1=全部，0=堂食，1=外卖；带 fromCache 参数的版本离线时回退缓存

    public static JSONObject health(Context c) throws Exception {
        return get(c, "/api/health");
    }

    public static JSONArray restaurants(Context c, String keyword, int limit) throws Exception {
        return restaurants(c, keyword, limit, -1, null);
    }

    public static JSONArray restaurants(Context c, String keyword, int limit, int mode) throws Exception {
        return restaurants(c, keyword, limit, mode, null);
    }

    public static JSONArray restaurants(Context c, String keyword, int limit, int mode,
                                        boolean[] fromCache) throws Exception {
        return getArrCached(c, "/api/restaurants?limit=" + limit
                + "&keyword=" + URLEncoder.encode(keyword == null ? "" : keyword, "UTF-8")
                + "&mode=" + mode, fromCache);
    }

    public static JSONObject detail(Context c, long id) throws Exception {
        return detail(c, id, null);
    }

    public static JSONObject detail(Context c, long id, boolean[] fromCache) throws Exception {
        return getCached(c, "/api/restaurants/" + id, fromCache);
    }

    public static JSONArray rankDishes(Context c, int limit, int mode) throws Exception {
        return rankDishes(c, limit, mode, null);
    }

    public static JSONArray rankDishes(Context c, int limit, int mode, boolean[] fromCache) throws Exception {
        return getArrCached(c, "/api/rank/dishes?limit=" + limit + "&mode=" + mode, fromCache);
    }

    public static JSONArray signature(Context c, int limit, int mode) throws Exception {
        return signature(c, limit, mode, null);
    }

    public static JSONArray signature(Context c, int limit, int mode, boolean[] fromCache) throws Exception {
        return getArrCached(c, "/api/rank/restaurant_dishes?limit=" + limit + "&mode=" + mode, fromCache);
    }

    public static JSONObject randomPick(Context c, int mode) throws Exception {
        return get(c, "/api/random?mode=" + mode);
    }

    /** 区块视图（路径/子区块/子树餐厅）。parentId 传 0 表示根。 */
    public static JSONObject areas(Context c, long parentId, int mode) throws Exception {
        return areas(c, parentId, mode, null);
    }

    public static JSONObject areas(Context c, long parentId, int mode, boolean[] fromCache) throws Exception {
        String q = "/api/areas?mode=" + mode;
        if (parentId > 0) q += "&parent_id=" + parentId;
        return getCached(c, q, fromCache);
    }

    public static JSONObject createArea(Context c, String name, long parentId) throws Exception {
        JSONObject p = new JSONObject();
        p.put("name", name);
        if (parentId > 0) p.put("parent_id", parentId);
        return post(c, "/api/areas", p);
    }

    public static JSONObject geocode(Context c, double lat, double lng) throws Exception {
        return get(c, "/api/geocode?lat=" + lat + "&lng=" + lng);
    }

    public static JSONObject visit(Context c, JSONObject payload) throws Exception {
        return post(c, "/api/visits", payload);
    }

    /** 修改评价：评分 / 文字 / 堂食外卖。 */
    public static JSONObject updateReview(Context c, long id, int rating,
                                          String comment, int mode) throws Exception {
        JSONObject p = new JSONObject();
        p.put("rating", rating);
        p.put("comment", comment == null ? "" : comment);
        p.put("mode", mode);
        return put(c, "/api/reviews/" + id, p);
    }

    public static JSONObject deleteReview(Context c, long id) throws Exception {
        return delete(c, "/api/reviews/" + id);
    }

    private Api() {
    }
}
