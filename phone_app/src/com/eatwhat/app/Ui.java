package com.eatwhat.app;

import android.content.Context;
import android.widget.Toast;

/** 小工具：星星文本、dp、toast。 */
public final class Ui {

    public static int dp(Context c, int v) {
        return Math.round(c.getResources().getDisplayMetrics().density * v);
    }

    public static void toast(Context c, String s) {
        Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }

    /** 4.6 -> "★★★★★"（四舍五入取整铺星）。 */
    public static String stars(double avg) {
        int r = (int) Math.round(avg);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(i < r ? '★' : '☆');
        return sb.toString();
    }

    /** 列表行评分摘要："★ 4.6 · 12 次"。 */
    public static String score(double avg, int count) {
        return "★ " + avg + " · " + count + " 次";
    }

    private Ui() {
    }
}
