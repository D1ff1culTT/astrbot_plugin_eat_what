package com.eatwhat.app;

import android.content.Context;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

/** 小工具：星星文本、dp、toast、横幅。 */
public final class Ui {

    public static int dp(Context c, int v) {
        return Math.round(c.getResources().getDisplayMetrics().density * v);
    }

    public static void toast(Context c, String s) {
        Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }

    /** 页面顶部提示横幅：error=红色（可重试），否则黄色（离线缓存）。 */
    public static void banner(TextView tv, boolean error, String msg) {
        tv.setVisibility(View.VISIBLE);
        tv.setBackgroundColor(error ? 0xFFFDECEA : 0xFFFFF3E0);
        tv.setTextColor(error ? 0xFFC62828 : 0xFFB26A00);
        tv.setText(msg);
    }

    /** 列表为空时的提示语：若有待同步记录，说明数据在本机。 */
    public static String emptyHint(Context c, String defaultText) {
        int pend = LocalStore.get(c).pendingCount();
        return pend > 0 ? "本机有 " + pend + " 条记录待同步，联网后自动上传" : defaultText;
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
