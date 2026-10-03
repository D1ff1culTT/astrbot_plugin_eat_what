package com.eatwhat.app;

import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 区块 Tab：展示一级区块（含聚合评分排序），可新增、点进下级。 */
public class AreaTab implements Tab {

    private final MainActivity act;
    private View root;

    private ListView lv;
    private View empty;
    private TextView banner;
    private final List<JSONObject> data = new ArrayList<>();
    private AreaAdapter adapter;
    private boolean loading;

    AreaTab(MainActivity act) {
        this.act = act;
    }

    @Override
    public View view() {
        if (root != null) return root;
        root = LayoutInflater.from(act).inflate(R.layout.tab_area, null);
        lv = root.findViewById(R.id.lv_area);
        empty = root.findViewById(R.id.empty);
        banner = root.findViewById(R.id.banner);

        adapter = new AreaAdapter();
        lv.setAdapter(adapter);
        lv.setEmptyView(empty);
        lv.setOnItemClickListener((p, v, pos, id) -> {
            long areaId = data.get(pos).optLong("id");
            if (areaId > 0) AreaActivity.start(act, areaId);
        });
        root.findViewById(R.id.btn_add_root).setOnClickListener(v -> promptCreate(0));
        return root;
    }

    @Override
    public void onShow() {
        refresh();
    }

    private void refresh() {
        if (loading) return;
        loading = true;
        Api.io(() -> {
            String err = null;
            boolean fromCache = false;
            List<JSONObject> l = new ArrayList<>();
            try {
                final boolean[] cached = new boolean[1];
                JSONObject v = Api.areas(act, 0, -1, cached);
                JSONArray ch = v.optJSONArray("children");
                for (int i = 0; i < ch.length(); i++) l.add(ch.optJSONObject(i));
                fromCache = cached[0];
            } catch (Exception e) {
                err = e.getMessage() == null ? "网络错误" : e.getMessage();
            }
            final List<JSONObject> res = l;
            final String fErr = err;
            final boolean fc = fromCache;
            Api.ui(() -> {
                loading = false;
                if (fErr != null) {
                    Ui.banner(banner, true, "⚠ 加载失败：" + fErr + "（点击重试）");
                    banner.setOnClickListener(v -> refresh());
                } else if (fc) {
                    Ui.banner(banner, false, "⚠ 离线：显示上次缓存的数据");
                    banner.setOnClickListener(null);
                } else {
                    banner.setVisibility(View.GONE);
                    banner.setOnClickListener(null);
                }
                data.clear();
                data.addAll(res);
                adapter.notifyDataSetChanged();
                if (res.isEmpty() && fErr == null) {
                    ((TextView) empty).setText(Ui.emptyHint(act,
                            "还没有区块，先新增一个（如：广州市）"));
                }
            });
        });
    }

    private void promptCreate(final long parentId) {
        final EditText et = new EditText(act);
        et.setHint("一级区块名（如 广州市 / 东莞市）");
        new AlertDialog.Builder(act)
                .setTitle("新增一级区块")
                .setView(et)
                .setPositiveButton("创建", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) return;
                    Api.io(() -> {
                        try {
                            Api.createArea(act, name, parentId);
                            Api.ui(this::refresh);
                        } catch (final Exception e) {
                            Api.ui(() -> Ui.toast(act, e.getMessage()));
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private class AreaAdapter extends BaseAdapter {
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

            TextView tvName = new TextView(c);
            tvName.setTextColor(0xFF2B2320);
            tvName.setTextSize(16);
            tvName.setTypeface(null, android.graphics.Typeface.BOLD);
            tvName.setText(item.optString("name"));
            mid.addView(tvName);

            TextView tvSub = new TextView(c);
            tvSub.setTextColor(0xFF9A8F87);
            tvSub.setTextSize(13);
            double avg = item.optDouble("avg_rating", 0);
            String sub = avg > 0 ? "★ " + Ui.num(avg) + " · 整体 " + item.optInt("overall_reviews", 0)
                    + " · 菜品 " + item.optInt("dish_review_count", 0)
                    : "暂无评分";
            sub += " · 餐厅 " + item.optInt("restaurant_count", 0);
            tvSub.setText(sub);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = Ui.dp(c, 2);
            mid.addView(tvSub, subLp);

            TextView arrow = new TextView(c);
            arrow.setText("›");
            arrow.setTextColor(0xFFB9ACA2);
            arrow.setTextSize(20);
            arrow.setPadding(Ui.dp(c, 8), 0, 0, 0);
            row.addView(arrow);
            return wrap;
        }
    }
}
