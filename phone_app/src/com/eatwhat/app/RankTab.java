package com.eatwhat.app;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 排行页：餐厅榜 / 菜品榜 / 招牌菜（餐厅菜品排名）三个榜单。
 * 行视图在代码中构建，轻量复用。
 */
public class RankTab implements Tab {

    private final MainActivity act;
    private View root;

    private ListView lv;
    private View empty;
    private View banner;
    private TextView chipRest, chipDish, chipSign;
    private TextView chipModeAll, chipModeIn, chipModeOut;
    private int mode = 0;
    private int modeFilter = -1;
    private boolean loading;
    private final List<JSONObject> data = new ArrayList<>();
    private RankAdapter adapter;

    RankTab(MainActivity act) {
        this.act = act;
    }

    @Override
    public View view() {
        if (root != null) return root;
        root = LayoutInflater.from(act).inflate(R.layout.tab_rank, null);
        lv = root.findViewById(R.id.lv_rank);
        empty = root.findViewById(R.id.empty);
        banner = root.findViewById(R.id.banner);
        chipRest = root.findViewById(R.id.chip_rest);
        chipDish = root.findViewById(R.id.chip_dish);
        chipSign = root.findViewById(R.id.chip_sign);
        chipModeAll = root.findViewById(R.id.chip_mode_all);
        chipModeIn = root.findViewById(R.id.chip_mode_in);
        chipModeOut = root.findViewById(R.id.chip_mode_out);

        chipRest.setOnClickListener(v -> switchMode(0));
        chipDish.setOnClickListener(v -> switchMode(1));
        chipSign.setOnClickListener(v -> switchMode(2));
        chipModeAll.setOnClickListener(v -> switchModeFilter(-1));
        chipModeIn.setOnClickListener(v -> switchModeFilter(0));
        chipModeOut.setOnClickListener(v -> switchModeFilter(1));

        adapter = new RankAdapter();
        lv.setAdapter(adapter);
        lv.setEmptyView(empty);
        lv.setOnItemClickListener((p, v, pos, id) -> {
            JSONObject item = data.get(pos);
            long restId = mode == 0 ? item.optLong("id") : item.optLong("restaurant_id");
            if (restId > 0) RestaurantDetailActivity.start(act, restId);
        });
        return root;
    }

    @Override
    public void onShow() {
        refresh();
    }

    private void switchMode(int m) {
        mode = m;
        styleChips();
        refresh();
    }

    private void switchModeFilter(int m) {
        modeFilter = m;
        styleModeChips();
        refresh();
    }

    private void styleChips() {
        TextView[] chips = {chipRest, chipDish, chipSign};
        for (int i = 0; i < chips.length; i++) {
            boolean on = i == mode;
            chips[i].setBackgroundResource(on ? R.drawable.chip_on : R.drawable.chip_off);
            chips[i].setTextColor(on ? 0xFFFFFFFF : 0xFF2B2320);
        }
    }

    private void styleModeChips() {
        TextView[] chips = {chipModeAll, chipModeIn, chipModeOut};
        for (int i = 0; i < chips.length; i++) {
            boolean on = (i - 1) == modeFilter;
            chips[i].setBackgroundResource(on ? R.drawable.chip_on : R.drawable.chip_off);
            chips[i].setTextColor(on ? 0xFFFFFFFF : 0xFF2B2320);
        }
    }

    private void refresh() {
        if (loading) return;
        loading = true;
        Api.io(() -> {
            try {
                final boolean[] cached = new boolean[1];
                JSONArray arr = mode == 0 ? Api.restaurants(act, "", 50, modeFilter, cached)
                        : mode == 1 ? Api.rankDishes(act, 50, modeFilter, cached)
                        : Api.signature(act, 50, modeFilter, cached);
                List<JSONObject> l = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) l.add(arr.getJSONObject(i));
                Api.ui(() -> {
                    banner.setVisibility(cached[0] ? View.VISIBLE : View.GONE);
                    data.clear();
                    data.addAll(l);
                    adapter.notifyDataSetChanged();
                    loading = false;
                });
            } catch (final Exception e) {
                Api.ui(() -> {
                    loading = false;
                    Ui.toast(act, "加载失败：" + e.getMessage());
                });
            }
        });
    }

    private class RankAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            JSONObject item = data.get(position);
            Context c = act;

            FrameLayout wrap = new FrameLayout(c);
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.card_bg);
            int p = Ui.dp(c, 12);
            row.setPadding(p, p, p, p);
            wrap.setPadding(0, Ui.dp(c, 5), 0, Ui.dp(c, 5));
            wrap.addView(row, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView tvRank = new TextView(c);
            int no = position + 1;
            tvRank.setText(no <= 3 ? new String[]{"🥇", "🥈", "🥉"}[no - 1] : String.valueOf(no));
            tvRank.setTextSize(17);
            tvRank.setMinWidth(Ui.dp(c, 32));
            tvRank.setGravity(Gravity.CENTER);
            row.addView(tvRank);

            LinearLayout mid = new LinearLayout(c);
            mid.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            midLp.leftMargin = Ui.dp(c, 10);
            row.addView(mid, midLp);

            TextView tvTitle = new TextView(c);
            tvTitle.setTextColor(0xFF2B2320);
            tvTitle.setTextSize(16);
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            tvTitle.setMaxLines(1);
            tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            mid.addView(tvTitle);

            TextView tvSub = new TextView(c);
            tvSub.setTextColor(0xFF9A8F87);
            tvSub.setTextSize(13);
            tvSub.setMaxLines(2);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = Ui.dp(c, 2);
            mid.addView(tvSub, subLp);

            String title, sub;
            double avg = item.optDouble("avg_rating", 0);
            int count = item.optInt("review_count", 0);
            if (mode == 0) {
                title = item.optString("name");
                sub = Ui.score(avg, item.optInt("overall_reviews", 0) + item.optInt("dish_review_count", 0));
                String addr = item.optString("address", "");
                if (!addr.isEmpty()) sub += "  " + addr;
                String top = item.optString("top_dish", "");
                if (!top.isEmpty()) sub += "\n招牌：" + top;
            } else {
                title = item.optString("dish_name");
                sub = Ui.score(avg, count) + " · " + item.optString("restaurant_name", "");
            }
            tvTitle.setText(title);
            tvSub.setText(sub);

            JSONArray imgs = item.optJSONArray("images_sample");
            if (imgs != null && imgs.length() > 0) {
                ImageView iv = new ImageView(c);
                LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(Ui.dp(c, 54), Ui.dp(c, 54));
                ivLp.leftMargin = Ui.dp(c, 10);
                iv.setLayoutParams(ivLp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundResource(R.drawable.bg_input);
                ImageLoader.load(iv, Api.abs(act, imgs.optString(0)));
                row.addView(iv);
            }
            return wrap;
        }
    }
}
