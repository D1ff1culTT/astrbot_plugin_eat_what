package com.eatwhat.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 区块详情：本级聚合评分、子区块排名（逐级下钻）、子树内餐厅排名。
 */
public class AreaActivity extends Activity {

    private long id;

    public static void start(Activity a, long areaId) {
        android.content.Intent i = new android.content.Intent(a, AreaActivity.class);
        i.putExtra("id", areaId);
        a.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_area);
        id = getIntent().getLongExtra("id", 0);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_add_child).setOnClickListener(v -> promptCreate());
        load();
    }

    private void load() {
        Api.io(() -> {
            try {
                final JSONObject v = Api.areas(this, id, -1);
                Api.ui(() -> render(v));
            } catch (final Exception e) {
                Api.ui(() -> {
                    Ui.toast(this, e.getMessage());
                    finish();
                });
            }
        });
    }

    private void render(JSONObject v) {
        JSONArray path = v.optJSONArray("path");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            if (i > 0) sb.append(" / ");
            sb.append(path.optJSONObject(i).optString("name"));
        }
        ((TextView) findViewById(R.id.tv_path)).setText(sb.length() > 0 ? sb.toString() : "区块");

        JSONObject area = v.optJSONObject("area");
        TextView tvScore = findViewById(R.id.tv_score);
        TextView tvMeta = findViewById(R.id.tv_meta);
        if (area != null) {
            double avg = area.optDouble("avg_rating", 0);
            tvScore.setText(avg > 0 ? Ui.stars(avg) + "  " + avg : "暂无评分");
            tvMeta.setText("餐厅 " + area.optInt("restaurant_count", 0)
                    + " · 整体评价 " + area.optInt("overall_reviews", 0)
                    + " · 菜品评价 " + area.optInt("dish_review_count", 0));
        }

        LinearLayout llChildren = findViewById(R.id.ll_children);
        JSONArray children = v.optJSONArray("children");
        if (children == null || children.length() == 0) {
            llChildren.addView(noteView("无子区块"));
        } else {
            for (int i = 0; i < children.length(); i++) {
                llChildren.addView(childRow(children.optJSONObject(i), i + 1));
            }
        }

        LinearLayout llRests = findViewById(R.id.ll_rests);
        JSONArray rests = v.optJSONArray("restaurants");
        if (rests == null || rests.length() == 0) {
            llRests.addView(noteView("该区块下还没有餐厅记录"));
        } else {
            for (int i = 0; i < rests.length(); i++) {
                llRests.addView(restRow(rests.optJSONObject(i), i + 1));
            }
        }
    }

    private void promptCreate() {
        final EditText et = new EditText(this);
        et.setHint("子区块名（如 某大学 / 某饭堂 / 某窗口）");
        new AlertDialog.Builder(this)
                .setTitle("新增子区块")
                .setView(et)
                .setPositiveButton("创建", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) return;
                    Api.io(() -> {
                        try {
                            Api.createArea(this, name, id);
                            Api.ui(this::load);
                        } catch (final Exception e) {
                            Api.ui(() -> Ui.toast(this, e.getMessage()));
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private TextView noteView(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(Color.parseColor("#B9ACA2"));
        tv.setTextSize(13);
        tv.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        return tv;
    }

    private View baseCard() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.card_bg);
        int p = Ui.dp(this, 12);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 8);
        row.setLayoutParams(lp);
        return row;
    }

    /** 子区块行：名次 + 名称 + 聚合分 + 餐厅数，点击下钻。 */
    private View childRow(final JSONObject child, int no) {
        LinearLayout row = (LinearLayout) baseCard();

        TextView tvNo = new TextView(this);
        tvNo.setText(String.valueOf(no));
        tvNo.setTextColor(Color.parseColor("#FF7043"));
        tvNo.setTypeface(null, Typeface.BOLD);
        tvNo.setTextSize(15);
        tvNo.setMinWidth(Ui.dp(this, 26));
        tvNo.setGravity(Gravity.CENTER);
        row.addView(tvNo);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        midLp.leftMargin = Ui.dp(this, 10);
        row.addView(mid, midLp);

        TextView tvName = new TextView(this);
        tvName.setText(child.optString("name"));
        tvName.setTextColor(Color.parseColor("#2B2320"));
        tvName.setTextSize(15);
        tvName.setTypeface(null, Typeface.BOLD);
        mid.addView(tvName);

        TextView tvSub = new TextView(this);
        double avg = child.optDouble("avg_rating", 0);
        tvSub.setText((avg > 0 ? "★ " + avg + " · " : "暂无评分 · ")
                + "餐厅 " + child.optInt("restaurant_count", 0));
        tvSub.setTextColor(Color.parseColor("#9A8F87"));
        tvSub.setTextSize(13);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Ui.dp(this, 2);
        mid.addView(tvSub, subLp);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(Color.parseColor("#B9ACA2"));
        arrow.setTextSize(20);
        arrow.setPadding(Ui.dp(this, 8), 0, 0, 0);
        row.addView(arrow);

        row.setOnClickListener(v -> start(this, child.optLong("id")));
        return row;
    }

    /** 区块内餐厅行：名次 + 店名 + 评分 + 所在区块 + 缩略图，点击进店详情。 */
    private View restRow(final JSONObject rest, int no) {
        LinearLayout row = (LinearLayout) baseCard();

        TextView tvNo = new TextView(this);
        tvNo.setText(String.valueOf(no));
        tvNo.setTextColor(Color.parseColor("#FF7043"));
        tvNo.setTypeface(null, Typeface.BOLD);
        tvNo.setTextSize(15);
        tvNo.setMinWidth(Ui.dp(this, 26));
        tvNo.setGravity(Gravity.CENTER);
        row.addView(tvNo);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        midLp.leftMargin = Ui.dp(this, 10);
        row.addView(mid, midLp);

        TextView tvName = new TextView(this);
        tvName.setText(rest.optString("name"));
        tvName.setTextColor(Color.parseColor("#2B2320"));
        tvName.setTextSize(15);
        tvName.setTypeface(null, Typeface.BOLD);
        tvName.setMaxLines(1);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(tvName);

        TextView tvSub = new TextView(this);
        double avg = rest.optDouble("avg_rating", 0);
        String sub = avg > 0 ? "★ " + avg + " · 整体 " + rest.optInt("overall_reviews", 0)
                + " · 菜品 " + rest.optInt("dish_review_count", 0) : "暂无评分";
        String areaName = rest.optString("area_name", "");
        if (!areaName.isEmpty()) sub += " · " + areaName;
        String top = rest.optString("top_dish", "");
        if (!top.isEmpty()) sub += "\n招牌：" + top;
        tvSub.setText(sub);
        tvSub.setTextColor(Color.parseColor("#9A8F87"));
        tvSub.setTextSize(13);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Ui.dp(this, 2);
        mid.addView(tvSub, subLp);

        JSONArray imgs = rest.optJSONArray("images_sample");
        if (imgs != null && imgs.length() > 0) {
            ImageView iv = new ImageView(this);
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
            ivLp.leftMargin = Ui.dp(this, 10);
            iv.setLayoutParams(ivLp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundResource(R.drawable.bg_input);
            ImageLoader.load(iv, Api.abs(this, imgs.optString(0)));
            row.addView(iv);
        }

        row.setOnClickListener(v -> {
            long rid = rest.optLong("id");
            if (rid > 0) RestaurantDetailActivity.start(this, rid);
        });
        return row;
    }
}
