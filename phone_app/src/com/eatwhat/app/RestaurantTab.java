package com.eatwhat.app;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 餐厅页：搜索（餐厅/菜品/地址均可匹配），点进详情。 */
public class RestaurantTab implements Tab {

    private final MainActivity act;
    private View root;

    private ListView lv;
    private View empty;
    private View banner;
    private final List<JSONObject> data = new ArrayList<>();
    private RestAdapter adapter;
    private boolean loading;
    private final android.os.Handler handler = new android.os.Handler();
    private Runnable searchFetch;

    RestaurantTab(MainActivity act) {
        this.act = act;
    }

    @Override
    public View view() {
        if (root != null) return root;
        root = LayoutInflater.from(act).inflate(R.layout.tab_rest, null);
        lv = root.findViewById(R.id.lv_rest);
        empty = root.findViewById(R.id.empty);
        banner = root.findViewById(R.id.banner);
        EditText etSearch = root.findViewById(R.id.et_search);

        adapter = new RestAdapter();
        lv.setAdapter(adapter);
        lv.setEmptyView(empty);
        lv.setOnItemClickListener((p, v, pos, id) -> {
            long restId = data.get(pos).optLong("id");
            if (restId > 0) RestaurantDetailActivity.start(act, restId);
        });
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                handler.removeCallbacks(searchFetch);
                String kw = s.toString().trim();
                searchFetch = () -> refresh(kw);
                handler.postDelayed(searchFetch, 300);
            }
        });
        return root;
    }

    @Override
    public void onShow() {
        refresh(currentKeyword());
    }

    private String currentKeyword() {
        EditText et = root.findViewById(R.id.et_search);
        return et == null ? "" : et.getText().toString().trim();
    }

    private void refresh(String kw) {
        if (loading) return;
        loading = true;
        Api.io(() -> {
            try {
                final boolean[] cached = new boolean[1];
                JSONArray arr = Api.restaurants(act, kw, 50, -1, cached);
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

    private class RestAdapter extends BaseAdapter {
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
            wrap.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
            wrap.addView(row, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout mid = new LinearLayout(c);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(mid);

            TextView tvTitle = new TextView(c);
            tvTitle.setTextColor(0xFF2B2320);
            tvTitle.setTextSize(16);
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            tvTitle.setMaxLines(1);
            tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tvTitle.setText(item.optString("name"));
            mid.addView(tvTitle);

            TextView tvSub = new TextView(c);
            tvSub.setTextColor(0xFF9A8F87);
            tvSub.setTextSize(13);
            tvSub.setMaxLines(2);
            double avg = item.optDouble("avg_rating", 0);
            String sub = Ui.score(avg, item.optInt("overall_reviews", 0)
                    + item.optInt("dish_review_count", 0));
            String addr = item.optString("address", "");
            if (!addr.isEmpty()) sub += "  " + addr;
            String top = item.optString("top_dish", "");
            if (!top.isEmpty()) sub += "\n招牌：" + top;
            tvSub.setText(sub);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = Ui.dp(c, 2);
            mid.addView(tvSub, subLp);

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
