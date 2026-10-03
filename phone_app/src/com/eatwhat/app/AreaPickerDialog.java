package com.eatwhat.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 区块选择器（下钻式）：逐级点进子区块，可随时在此级新增；
 * 「确定」选中当前所在节点，「清除已选」恢复未选择。
 */
public class AreaPickerDialog {

    public interface OnPicked {
        void onPicked(long areaId, String pathText);
    }

    public static void show(final MainActivity act, final OnPicked cb) {
        final List<Long> stackIds = new ArrayList<>();
        final List<String> stackNames = new ArrayList<>();
        final List<JSONObject> items = new ArrayList<>();

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(act, 20);
        box.setPadding(pad, Ui.dp(act, 10), pad, 0);

        final TextView tvPath = new TextView(act);
        tvPath.setTextColor(Color.parseColor("#9A8F87"));
        tvPath.setTextSize(13);
        box.addView(tvPath);

        TextView btnUp = new TextView(act);
        btnUp.setText("⬆ 返回上一级");
        btnUp.setTextColor(Color.parseColor("#FF7043"));
        btnUp.setTextSize(13);
        btnUp.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 4));
        box.addView(btnUp);

        TextView btnAdd = new TextView(act);
        btnAdd.setText("＋ 在此级新增区块");
        btnAdd.setTextColor(Color.parseColor("#FF7043"));
        btnAdd.setTypeface(null, Typeface.BOLD);
        btnAdd.setTextSize(13);
        btnAdd.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 8));
        box.addView(btnAdd);

        final ListView lv = new ListView(act);
        lv.setDivider(null);
        LinearLayout.LayoutParams lvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 300));
        lvLp.topMargin = Ui.dp(act, 4);
        box.addView(lv, lvLp);

        final BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return items.size();
            }

            @Override
            public Object getItem(int position) {
                return items.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                Context c = act;
                JSONObject it = items.get(position);
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.VERTICAL);
                int p = Ui.dp(c, 10);
                row.setPadding(p, p, p, p);

                TextView tvName = new TextView(c);
                tvName.setText(it.optString("name"));
                tvName.setTextColor(Color.parseColor("#2B2320"));
                tvName.setTextSize(15);
                row.addView(tvName);

                TextView tvSub = new TextView(c);
                double avg = it.optDouble("avg_rating", 0);
                tvSub.setText(avg > 0
                        ? "★ " + avg + " · 餐厅 " + it.optInt("restaurant_count", 0)
                        : "暂无评分 · 餐厅 " + it.optInt("restaurant_count", 0));
                tvSub.setTextColor(Color.parseColor("#9A8F87"));
                tvSub.setTextSize(12);
                row.addView(tvSub);
                return row;
            }
        };
        lv.setAdapter(adapter);

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("选择区块")
                .setView(box)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null)
                .setNeutralButton("清除已选", (d, w) -> cb.onPicked(0, ""))
                .show();

        final Runnable load = new Runnable() {
            @Override
            public void run() {
                final long pid = stackIds.isEmpty() ? 0 : stackIds.get(stackIds.size() - 1);
                tvPath.setText(stackNames.isEmpty()
                        ? "当前：顶层（如 广州市 / 东莞市）"
                        : "当前：" + joinNames(stackNames));
                btnUp.setEnabled(!stackIds.isEmpty());
                btnUp.setTextColor(stackIds.isEmpty()
                        ? Color.parseColor("#B9ACA2") : Color.parseColor("#FF7043"));
                Api.io(() -> {
                    try {
                        JSONObject v = Api.areas(act, pid, -1);
                        JSONArray ch = v.optJSONArray("children");
                        List<JSONObject> l = new ArrayList<>();
                        for (int i = 0; i < ch.length(); i++) l.add(ch.optJSONObject(i));
                        Api.ui(() -> {
                            items.clear();
                            items.addAll(l);
                            adapter.notifyDataSetChanged();
                        });
                    } catch (final Exception e) {
                        Api.ui(() -> Ui.toast(act, e.getMessage()));
                    }
                });
            }
        };

        btnUp.setOnClickListener(v -> {
            if (!stackIds.isEmpty()) {
                stackIds.remove(stackIds.size() - 1);
                stackNames.remove(stackNames.size() - 1);
                load.run();
            }
        });
        btnAdd.setOnClickListener(v -> {
            final long pid = stackIds.isEmpty() ? 0 : stackIds.get(stackIds.size() - 1);
            final EditText et = new EditText(act);
            et.setHint("区块名（如 广州市 / 某商圈 / 某饭堂）");
            new AlertDialog.Builder(act)
                    .setTitle(stackNames.isEmpty() ? "新增一级区块" : "在「"
                            + stackNames.get(stackNames.size() - 1) + "」下新增")
                    .setView(et)
                    .setPositiveButton("创建", (d, w) -> {
                        String name = et.getText().toString().trim();
                        if (name.isEmpty()) return;
                        Api.io(() -> {
                            try {
                                JSONObject node = Api.createArea(act, name, pid);
                                final long newId = node.getJSONObject("area").optLong("id");
                                Api.ui(() -> {
                                    stackIds.add(newId);
                                    stackNames.add(name);
                                    load.run();
                                });
                            } catch (final Exception e) {
                                Api.ui(() -> Ui.toast(act, e.getMessage()));
                            }
                        });
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        lv.setOnItemClickListener((p, v, pos, id) -> {
            JSONObject c = items.get(pos);
            stackIds.add(c.optLong("id"));
            stackNames.add(c.optString("name"));
            load.run();
        });

        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (stackIds.isEmpty()) {
                Ui.toast(act, "请先点进一个区块（或选「清除已选」）");
                return;
            }
            cb.onPicked(stackIds.get(stackIds.size() - 1), joinNames(stackNames));
            dlg.dismiss();
        });

        load.run();
    }

    private static String joinNames(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(" / ");
            sb.append(names.get(i));
        }
        return sb.toString();
    }
}
