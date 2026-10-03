package com.eatwhat.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 主界面：头部（随机推荐 / 服务器设置）+ 记录 / 排行 / 餐厅 三个标签页。
 */
public class MainActivity extends Activity {

    private FrameLayout container;
    private TextView[] tabViews;
    private Tab[] tabs;
    private int current = -1;

    private RecordTab recordTab;
    private RankTab rankTab;
    private RestaurantTab restTab;
    private AreaTab areaTab;

    private BroadcastReceiver netReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recordTab = new RecordTab(this);
        rankTab = new RankTab(this);
        restTab = new RestaurantTab(this);
        areaTab = new AreaTab(this);
        tabs = new Tab[]{recordTab, rankTab, restTab, areaTab};

        container = findViewById(R.id.container);
        tabViews = new TextView[]{
                findViewById(R.id.tab_record),
                findViewById(R.id.tab_rank),
                findViewById(R.id.tab_rest),
                findViewById(R.id.tab_area)};

        for (int i = 0; i < tabViews.length; i++) {
            final int idx = i;
            tabViews[i].setOnClickListener(v -> switchTab(idx));
        }
        findViewById(R.id.btn_settings).setOnClickListener(v -> settingsDialog());
        findViewById(R.id.btn_random).setOnClickListener(v -> randomDialog());

        switchTab(0);

        // 离线兜底：启动时补传本地队列；网络恢复时也会触发
        syncSilently();
        netReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                syncSilently();
            }
        };
        try {
            registerReceiver(netReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (netReceiver != null) unregisterReceiver(netReceiver);
        } catch (Exception ignored) {
        }
    }

    private void syncSilently() {
        if (LocalStore.get(this).pendingCount() == 0) return;
        Syncer.sync(this, (synced, remaining, err) -> {
            if (synced > 0) Ui.toast(this, "已同步 " + synced + " 条本地记录");
            else if (err != null) Ui.toast(this, err);
        });
    }

    private void switchTab(int idx) {
        if (idx == current) return;
        current = idx;
        for (int i = 0; i < tabViews.length; i++) {
            tabViews[i].setSelected(i == idx);
            tabViews[i].setTypeface(null, i == idx ? Typeface.BOLD : Typeface.NORMAL);
        }
        container.removeAllViews();
        container.addView(tabs[idx].view(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tabs[idx].onShow();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        recordTab.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        recordTab.onPermissionResult(requestCode, permissions, grantResults);
    }

    // ---------------- 服务器设置 ----------------

    private void settingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 22);
        box.setPadding(pad, Ui.dp(this, 12), pad, 0);

        TextView lb1 = new TextView(this);
        lb1.setText("服务器地址（手机与服务器需同一局域网或可达）");
        lb1.setTextColor(Color.GRAY);
        lb1.setTextSize(12);
        box.addView(lb1);

        EditText etBase = new EditText(this);
        etBase.setSingleLine(true);
        etBase.setHint("http://192.168.1.10:8765");
        etBase.setText(Api.baseUrl(this));
        box.addView(etBase);

        TextView lb2 = new TextView(this);
        lb2.setText("访问令牌（服务器 config.json 的 token，可留空）");
        lb2.setTextColor(Color.GRAY);
        lb2.setTextSize(12);
        LinearLayout.LayoutParams lb2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lb2Lp.topMargin = Ui.dp(this, 12);
        box.addView(lb2, lb2Lp);

        EditText etToken = new EditText(this);
        etToken.setSingleLine(true);
        etToken.setText(Api.token(this));
        box.addView(etToken);

        // 离线队列状态与手动同步
        int pending = LocalStore.get(this).pendingCount();
        TextView tvPending = new TextView(this);
        tvPending.setText(pending > 0
                ? "⚠ 有 " + pending + " 条记录存在本机待同步（离线时保存的）"
                : "本机没有待同步的记录");
        tvPending.setTextColor(Color.parseColor(pending > 0 ? "#B26A00" : "#9A8F87"));
        tvPending.setTextSize(13);
        LinearLayout.LayoutParams pendLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pendLp.topMargin = Ui.dp(this, 12);
        box.addView(tvPending, pendLp);

        TextView btnSync = new TextView(this);
        btnSync.setText("立即同步");
        btnSync.setTextColor(Color.parseColor("#FF7043"));
        btnSync.setTypeface(null, Typeface.BOLD);
        btnSync.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        box.addView(btnSync);
        btnSync.setOnClickListener(v -> {
            Ui.toast(this, "同步中…");
            Syncer.sync(this, (synced, remaining, err) -> Ui.toast(MainActivity.this,
                    err != null ? err
                            : synced > 0 ? "同步完成 " + synced + " 条"
                            : remaining > 0 ? "网络不可用，剩余 " + remaining + " 条待同步"
                            : "没有待同步的记录"));
        });

        TextView btnTest = new TextView(this);
        btnTest.setText("测试连接");
        btnTest.setTextColor(Color.parseColor("#FF7043"));
        btnTest.setTypeface(null, Typeface.BOLD);
        btnTest.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 6));
        box.addView(btnTest);

        btnTest.setOnClickListener(v -> {
            String base = etBase.getText().toString().trim();
            String tk = etToken.getText().toString().trim();
            if (base.isEmpty()) {
                Ui.toast(this, "先填服务器地址");
                return;
            }
            Api.setServer(this, base, tk);
            Ui.toast(this, "连接中…");
            Api.io(() -> {
                try {
                    JSONObject h = Api.health(this);
                    String ver = h.optString("version", "未知版本");
                    Api.ui(() -> Ui.toast(this, "连接成功（服务端 " + ver + "）：餐厅 "
                            + h.optInt("restaurants") + " · 菜品 " + h.optInt("dishes")
                            + " · 评价 " + h.optInt("reviews")));
                } catch (final Exception e) {
                    Api.ui(() -> Ui.toast(this, "连接失败：" + e.getMessage()));
                }
            });
        });

        new AlertDialog.Builder(this)
                .setTitle("服务器设置")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    Api.setServer(MainActivity.this,
                            etBase.getText().toString().trim(), etToken.getText().toString().trim());
                    Ui.toast(MainActivity.this, "已保存");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 今天吃什么 ----------------

    private void randomDialog() {
        if (!Api.configured(this)) {
            Ui.toast(this, "请先在右上角 ⚙ 配置服务器地址");
            return;
        }
        Ui.toast(this, "正在挑选…");
        Api.io(() -> {
            try {
                JSONObject r = Api.randomPick(MainActivity.this, -1);
                Api.ui(() -> showRandom(r));
            } catch (final Exception e) {
                Api.ui(() -> Ui.toast(MainActivity.this, e.getMessage()));
            }
        });
    }

    private void showRandom(JSONObject r) {
        JSONObject rest = r.optJSONObject("restaurant");
        if (rest == null) return;
        JSONObject dish = r.optJSONObject("dish");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 24);
        box.setPadding(pad, Ui.dp(this, 18), pad, 0);

        TextView tvName = new TextView(this);
        tvName.setText(rest.optString("name"));
        tvName.setTextSize(21);
        tvName.setTypeface(null, Typeface.BOLD);
        tvName.setTextColor(Color.parseColor("#2B2320"));
        box.addView(tvName);

        TextView tvStars = new TextView(this);
        double avg = rest.optDouble("avg_rating", 0);
        tvStars.setText(avg > 0 ? Ui.stars(avg) + "  " + avg : "暂无评分");
        tvStars.setTextColor(Color.parseColor("#FFB300"));
        tvStars.setTextSize(16);
        LinearLayout.LayoutParams starsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        starsLp.topMargin = Ui.dp(this, 6);
        box.addView(tvStars, starsLp);

        TextView tvDish = new TextView(this);
        if (dish != null) {
            tvDish.setText("推荐菜：" + dish.optString("name")
                    + "（★ " + dish.optDouble("avg_rating", 0) + "）");
        } else {
            tvDish.setText("推荐菜：还没记录过菜品");
        }
        tvDish.setTextColor(Color.parseColor("#2B2320"));
        tvDish.setTextSize(15);
        LinearLayout.LayoutParams dishLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dishLp.topMargin = Ui.dp(this, 12);
        box.addView(tvDish, dishLp);

        TextView tvAddr = new TextView(this);
        String addr = rest.optString("address", "");
        tvAddr.setText(addr.isEmpty() ? "未记录地址" : addr);
        tvAddr.setTextColor(Color.GRAY);
        tvAddr.setTextSize(13);
        LinearLayout.LayoutParams addrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addrLp.topMargin = Ui.dp(this, 4);
        box.addView(tvAddr, addrLp);

        TextView tvLink = new TextView(this);
        tvLink.setText("查看详情 →");
        tvLink.setTextColor(Color.parseColor("#FF7043"));
        tvLink.setTypeface(null, Typeface.BOLD);
        tvLink.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 4));
        box.addView(tvLink);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("今天吃什么 🎲")
                .setView(box)
                .setPositiveButton("再来一个", (d, w) -> randomDialog())
                .setNegativeButton("关闭", null)
                .show();
        tvLink.setOnClickListener(v -> {
            dlg.dismiss();
            long id = rest.optLong("id");
            if (id > 0) RestaurantDetailActivity.start(MainActivity.this, id);
        });
    }
}
