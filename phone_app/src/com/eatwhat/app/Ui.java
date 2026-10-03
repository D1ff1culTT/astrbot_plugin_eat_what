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

    /** 百分制：80 分显示 ★★★★ 80 分（5 星仅为视觉换算）。 */
    public static String stars(double avg) {
        int r = (int) Math.round(avg / 20.0);
        r = Math.max(0, Math.min(5, r));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(i < r ? '★' : '☆');
        sb.append(' ').append(num(avg)).append(" 分");
        return sb.toString();
    }

    /** 列表行评分摘要："83 分 · 12 次"。 */
    public static String score(double avg, int count) {
        return num(avg) + " 分 · " + count + " 次";
    }

    /** 整数不带小数点，小数保留原样。 */
    public static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private Ui() {
    }
}
