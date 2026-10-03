package com.eatwhat.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 餐厅详情：概览（评分/地址/地图）、招牌菜排名、最近评价（含照片）。
 */
public class RestaurantDetailActivity extends Activity {

    private long id;

    public static void start(Activity a, long restId) {
        Intent i = new Intent(a, RestaurantDetailActivity.class);
        i.putExtra("id", restId);
        a.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        id = getIntent().getLongExtra("id", 0);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        load();
    }

    private void load() {
        Api.io(() -> {
            try {
                final boolean[] cached = new boolean[1];
                final JSONObject d = Api.detail(this, id, cached);
                Api.ui(() -> {
                    findViewById(R.id.banner).setVisibility(cached[0] ? View.VISIBLE : View.GONE);
                    render(d);
                });
            } catch (final Exception e) {
                Api.ui(() -> {
                    Ui.toast(this, e.getMessage());
                    finish();
                });
            }
        });
    }

    private void render(JSONObject d) {
        ((TextView) findViewById(R.id.tv_name)).setText(d.optString("name"));

        double avg = d.optDouble("avg_rating", 0);
        ((TextView) findViewById(R.id.tv_stars)).setText(
                avg > 0 ? Ui.stars(avg) + "  " + avg : "暂无评分");
        ((TextView) findViewById(R.id.tv_count)).setText(
                "整体 " + d.optInt("overall_reviews", 0)
                        + " · 菜品 " + d.optInt("dish_review_count", 0));

        String addr = d.optString("address", "");
        ((TextView) findViewById(R.id.tv_address)).setText(addr.isEmpty() ? "未记录地址" : addr);

        // 区块路径（点击进入区块）
        TextView tvArea = findViewById(R.id.tv_area);
        final long areaId = d.optLong("area_id", 0);
        JSONArray areaPath = d.optJSONArray("area_path");
        if (areaId > 0 && areaPath != null && areaPath.length() > 0) {
            StringBuilder sb = new StringBuilder("📍 ");
            for (int i = 0; i < areaPath.length(); i++) {
                if (i > 0) sb.append(" / ");
                sb.append(areaPath.optString(i));
            }
            tvArea.setText(sb.toString());
            tvArea.setOnClickListener(v -> AreaActivity.start(this, areaId));
            tvArea.setVisibility(View.VISIBLE);
        } else {
            tvArea.setVisibility(View.GONE);
        }

        // 堂食 / 外卖 分轨评分
        TextView tvModes = findViewById(R.id.tv_modes);
        tvModes.setText(modeLine("堂食", d.optJSONObject("dine_in")) + "\n"
                + modeLine("外卖", d.optJSONObject("delivery")));
        tvModes.setVisibility(View.VISIBLE);

        final String amapUri = d.optString("amap_uri", "");
        final String amapWeb = d.optString("amap_web", "");
        TextView btnMap = findViewById(R.id.btn_map);
        if (amapUri.isEmpty() && amapWeb.isEmpty()) {
            btnMap.setVisibility(View.GONE);
        } else {
            btnMap.setOnClickListener(v -> openMap(amapUri, amapWeb));
        }

        LinearLayout llDishes = findViewById(R.id.ll_dishes);
        JSONArray dishes = d.optJSONArray("dishes");
        if (dishes == null || dishes.length() == 0) {
            llDishes.addView(noteView("还没有菜品记录"));
        } else {
            for (int i = 0; i < dishes.length(); i++) {
                llDishes.addView(dishRow(dishes.optJSONObject(i), i + 1));
            }
        }

        LinearLayout llReviews = findViewById(R.id.ll_reviews);
        JSONArray reviews = d.optJSONArray("reviews");
        if (reviews == null || reviews.length() == 0) {
            llReviews.addView(noteView("还没有评价"));
        } else {
            for (int i = 0; i < reviews.length(); i++) {
                llReviews.addView(reviewCard(reviews.optJSONObject(i)));
            }
        }
    }

    private String modeLine(String label, JSONObject s) {
        if (s == null) return label + " 暂无";
        double avg = s.optDouble("avg", 0);
        int count = s.optInt("count", 0);
        return avg > 0 ? label + " ★ " + avg + " · " + count + " 次" : label + " 暂无";
    }

    private void openMap(String amapUri, String amapWeb) {
        try {
            if (!amapUri.isEmpty()) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(amapUri)));
                return;
            }
        } catch (Exception ignored) {
        }
        if (!amapWeb.isEmpty()) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(amapWeb)));
            } catch (Exception e) {
                Ui.toast(this, "打不开地图");
            }
        }
    }

    private TextView noteView(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(Color.parseColor("#B9ACA2"));
        tv.setTextSize(13);
        tv.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        return tv;
    }

    /** 招牌菜排名行：名次 + 菜名 + 评分 + 缩略图。 */
    private View dishRow(JSONObject dish, int no) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.card_bg);
        int p = Ui.dp(this, 12);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Ui.dp(this, 8);
        row.setLayoutParams(rowLp);

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
        tvName.setText(dish.optString("name"));
        tvName.setTextColor(Color.parseColor("#2B2320"));
        tvName.setTextSize(15);
        tvName.setTypeface(null, Typeface.BOLD);
        tvName.setMaxLines(1);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(tvName);

        TextView tvSub = new TextView(this);
        tvSub.setText(Ui.score(dish.optDouble("avg_rating", 0), dish.optInt("review_count", 0)));
        tvSub.setTextColor(Color.parseColor("#9A8F87"));
        tvSub.setTextSize(13);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Ui.dp(this, 2);
        mid.addView(tvSub, subLp);

        JSONArray imgs = dish.optJSONArray("images_sample");
        if (imgs != null && imgs.length() > 0) {
            ImageView iv = new ImageView(this);
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
            ivLp.leftMargin = Ui.dp(this, 10);
            iv.setLayoutParams(ivLp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundResource(R.drawable.bg_input);
            ImageLoader.load(iv, Api.abs(this, imgs.optString(0)));
            iv.setOnClickListener(v -> bigImage(Api.abs(this, imgs.optString(0))));
            row.addView(iv);
        }
        return row;
    }

    /** 评价卡片：整体/菜品标签、星星、正文、照片行。 */
    private View reviewCard(JSONObject rv) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        int p = Ui.dp(this, 12);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = Ui.dp(this, 8);
        card.setLayoutParams(cardLp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String dishName = rv.optString("dish_name", "");
        String prefix = rv.optInt("mode", 0) == 1 ? "外卖 · " : "";
        TextView tag = new TextView(this);
        tag.setText(prefix + (dishName.isEmpty() ? "整体评价" : "菜品 · " + dishName));
        tag.setTextColor(Color.parseColor("#FF7043"));
        tag.setTypeface(null, Typeface.BOLD);
        tag.setTextSize(13);
        tag.setMaxLines(1);
        tag.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(tag, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String time = rv.optString("created_at", "");
        TextView tvTime = new TextView(this);
        tvTime.setText(time.length() >= 16 ? time.substring(5, 16) : time);
        tvTime.setTextColor(Color.parseColor("#B9ACA2"));
        tvTime.setTextSize(12);
        head.addView(tvTime);

        TextView tvStars = new TextView(this);
        int rating = rv.optInt("rating", 0);
        tvStars.setText(Ui.stars(rating));
        tvStars.setTextColor(Color.parseColor("#FFB300"));
        tvStars.setTextSize(14);
        LinearLayout.LayoutParams starsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        starsLp.topMargin = Ui.dp(this, 4);
        card.addView(tvStars, starsLp);

        String comment = rv.optString("comment", "");
        if (!comment.isEmpty()) {
            TextView tvC = new TextView(this);
            tvC.setText(comment);
            tvC.setTextColor(Color.parseColor("#2B2320"));
            tvC.setTextSize(14);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.topMargin = Ui.dp(this, 4);
            card.addView(tvC, cLp);
        }

        JSONArray imgs = rv.optJSONArray("images");
        if (imgs != null && imgs.length() > 0) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = Ui.dp(this, 8);
            card.addView(row, rowLp);
            for (int i = 0; i < imgs.length(); i++) {
                final String url = Api.abs(this, imgs.optString(i));
                ImageView iv = new ImageView(this);
                LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(Ui.dp(this, 84), Ui.dp(this, 84));
                if (i < imgs.length() - 1) ivLp.rightMargin = Ui.dp(this, 8);
                iv.setLayoutParams(ivLp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundResource(R.drawable.bg_input);
                ImageLoader.load(iv, url);
                iv.setOnClickListener(v -> bigImage(url));
                row.addView(iv);
            }
        }
        return card;
    }

    /** 全屏看大图，点击关闭。 */
    private void bigImage(String url) {
        Dialog dlg = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        int pad = Ui.dp(this, 16);
        iv.setPadding(pad, pad * 2, pad, pad);
        ScrollView sc = new ScrollView(this);
        sc.addView(iv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dlg.setContentView(sc);
        dlg.show();
        iv.setOnClickListener(v -> dlg.dismiss());
        ImageLoader.load(iv, url);
    }
}
