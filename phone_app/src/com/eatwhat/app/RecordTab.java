package com.eatwhat.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 记录页：餐厅（联想已有）、定位+地址反查、整体评价、菜品卡（评分/评价/照片），保存为一餐。
 */
public class RecordTab implements Tab {

    private static final int REQ_CAMERA = 2001;
    private static final int REQ_PICK = 2002;
    private static final int REQ_LOC = 2003;

    private final MainActivity act;
    private View root;

    private AutoCompleteTextView actRestaurant;
    private TextView btnLocate, tvCoords, btnSave;
    private EditText etAddress, etOverall;
    private StarInput starOverall;
    private LinearLayout llDishes;
    private final List<DishCard> cards = new ArrayList<>();
    private final SuggestAdapter suggest;

    private double lat = Double.NaN, lng = Double.NaN;
    private boolean silent, locating;
    private int mode = 0;          // 0=堂食 1=外卖
    private long areaId = 0;       // 已选区块，0=未选择
    private TextView chipModeIn, chipModeOut, tvArea;

    private DishCard pendingCard;
    private Uri pendingCapture;

    private final android.os.Handler handler = new android.os.Handler();
    private Runnable suggestFetch;

    private final LocationListener locListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (locating) finishLocate(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    /** 一张菜品卡片的状态。 */
    private static class DishCard {
        View view;
        EditText name;
        StarInput star;
        EditText comment;
        LinearLayout photos;
        final List<File> files = new ArrayList<>();
    }

    RecordTab(MainActivity act) {
        this.act = act;
        suggest = new SuggestAdapter(act);
    }

    @Override
    public View view() {
        if (root != null) return root;
        root = LayoutInflater.from(act).inflate(R.layout.tab_record, null);
        actRestaurant = root.findViewById(R.id.act_restaurant);
        btnLocate = root.findViewById(R.id.btn_locate);
        tvCoords = root.findViewById(R.id.tv_coords);
        etAddress = root.findViewById(R.id.et_address);
        etOverall = root.findViewById(R.id.et_overall);
        starOverall = root.findViewById(R.id.star_overall);
        llDishes = root.findViewById(R.id.ll_dishes);
        btnSave = root.findViewById(R.id.btn_save);

        actRestaurant.setAdapter(suggest);
        actRestaurant.setThreshold(1);
        actRestaurant.setOnItemClickListener((p, v, pos, id) -> {
            silent = true;
            actRestaurant.setText((String) p.getItemAtPosition(pos));
            actRestaurant.dismissDropDown();
            silent = false;
        });
        actRestaurant.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (silent) return;
                handler.removeCallbacks(suggestFetch);
                String kw = s.toString().trim();
                suggestFetch = () -> fetchSuggest(kw);
                handler.postDelayed(suggestFetch, 300);
            }
        });

        btnLocate.setOnClickListener(v -> locate());
        root.findViewById(R.id.btn_add_dish).setOnClickListener(v -> addDishCard());
        btnSave.setOnClickListener(v -> save());

        chipModeIn = root.findViewById(R.id.chip_mode_in);
        chipModeOut = root.findViewById(R.id.chip_mode_out);
        tvArea = root.findViewById(R.id.tv_area);
        chipModeIn.setOnClickListener(v -> setMode(0));
        chipModeOut.setOnClickListener(v -> setMode(1));
        setMode(0);
        final View areaBtn = root.findViewById(R.id.btn_area);
        areaBtn.setOnClickListener(v ->
                AreaPickerDialog.show(act, (id, text) -> {
                    areaId = id;
                    tvArea.setText(id > 0 ? "区块：" + text : "区块：未选择");
                }));
        tvArea.setOnClickListener(v -> areaBtn.performClick());

        addDishCard();
        return root;
    }

    @Override
    public void onShow() {
    }

    private void setMode(int m) {
        mode = m;
        chipModeIn.setBackgroundResource(m == 0 ? R.drawable.chip_on : R.drawable.chip_off);
        chipModeIn.setTextColor(m == 0 ? 0xFFFFFFFF : 0xFF2B2320);
        chipModeOut.setBackgroundResource(m == 1 ? R.drawable.chip_on : R.drawable.chip_off);
        chipModeOut.setTextColor(m == 1 ? 0xFFFFFFFF : 0xFF2B2320);
    }

    private void fetchSuggest(String kw) {
        if (kw.isEmpty()) {
            suggest.replace(new ArrayList<>());
            return;
        }
        Api.io(() -> {
            try {
                JSONArray arr = Api.restaurants(act, kw, 8);
                List<String> names = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) names.add(arr.getJSONObject(i).optString("name"));
                Api.ui(() -> {
                    if (silent || !kw.equals(actRestaurant.getText().toString().trim())) return;
                    suggest.replace(names);
                    if (!names.isEmpty() && actRestaurant.hasFocus()) actRestaurant.showDropDown();
                });
            } catch (Exception ignored) {
            }
        });
    }

    // ---------------- 定位 ----------------

    private void locate() {
        if (act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOC);
            return;
        }
        doLocate();
    }

    private void doLocate() {
        LocationManager lm = (LocationManager) act.getSystemService(Activity.LOCATION_SERVICE);
        boolean gps = isOn(lm, LocationManager.GPS_PROVIDER);
        boolean net = isOn(lm, LocationManager.NETWORK_PROVIDER);
        if (!gps && !net) {
            Ui.toast(act, "请先开启定位服务（GPS / 网络）");
            return;
        }
        locating = true;
        btnLocate.setEnabled(false);
        btnLocate.setText("定位中…");
        try {
            if (net) lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, locListener, null);
        } catch (Exception ignored) {
        }
        try {
            if (gps) lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, locListener, null);
        } catch (Exception ignored) {
        }
        handler.postDelayed(() -> {
            if (!locating) return;
            Location best = bestLastKnown(lm);
            if (best != null) finishLocate(best);
            else stopLocate("定位超时，可手动填写地址");
        }, 8000);
    }

    private boolean isOn(LocationManager lm, String provider) {
        try {
            return lm.isProviderEnabled(provider);
        } catch (Exception e) {
            return false;
        }
    }

    private Location bestLastKnown(LocationManager lm) {
        Location best = null;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (!isOn(lm, p)) continue;
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException ignored) {
            }
        }
        return best;
    }

    private void finishLocate(Location loc) {
        LocationManager lm = (LocationManager) act.getSystemService(Activity.LOCATION_SERVICE);
        try {
            lm.removeUpdates(locListener);
        } catch (Exception ignored) {
        }
        locating = false;
        lat = loc.getLatitude();
        lng = loc.getLongitude();
        btnLocate.setEnabled(true);
        btnLocate.setText("📍 重新定位");
        tvCoords.setText(String.format("已定位 %.5f, %.5f", lat, lng));

        Api.io(() -> {
            try {
                JSONObject g = Api.geocode(act, lat, lng);
                String addr = g.optString("address", "");
                Api.ui(() -> {
                    if (addr != null && !addr.isEmpty()
                            && etAddress.getText().toString().trim().isEmpty()) {
                        etAddress.setText(addr);
                    }
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void stopLocate(String msg) {
        LocationManager lm = (LocationManager) act.getSystemService(Activity.LOCATION_SERVICE);
        try {
            lm.removeUpdates(locListener);
        } catch (Exception ignored) {
        }
        locating = false;
        btnLocate.setEnabled(true);
        btnLocate.setText("📍 定位");
        Ui.toast(act, msg);
    }

    public void onPermissionResult(int req, String[] perms, int[] results) {
        if (req == REQ_LOC) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) doLocate();
            else Ui.toast(act, "没有定位权限，可手动填写地址");
        }
    }

    // ---------------- 菜品卡片与照片 ----------------

    private void addDishCard() {
        final DishCard card = new DishCard();
        card.view = LayoutInflater.from(act).inflate(R.layout.dish_card, llDishes, false);
        card.name = card.view.findViewById(R.id.et_dish_name);
        card.star = card.view.findViewById(R.id.star_dish);
        card.comment = card.view.findViewById(R.id.et_dish_comment);
        card.photos = card.view.findViewById(R.id.ll_photos);

        card.view.findViewById(R.id.btn_del_dish).setOnClickListener(v -> {
            llDishes.removeView(card.view);
            cards.remove(card);
            if (cards.isEmpty()) addDishCard();
        });
        card.view.findViewById(R.id.btn_photo).setOnClickListener(v -> {
            pendingCard = card;
            new android.app.AlertDialog.Builder(act)
                    .setItems(new String[]{"📷 拍照", "🖼 从相册选"}, (d, w) -> {
                        if (w == 0) openCamera();
                        else openPicker();
                    })
                    .show();
        });
        cards.add(card);
        llDishes.addView(card.view);
    }

    private void openCamera() {
        try {
            pendingCapture = PhotoProvider.newCaptureUri(act);
            Intent it = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            it.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, pendingCapture);
            it.setClipData(ClipData.newRawUri("photo", pendingCapture));
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (it.resolveActivity(act.getPackageManager()) == null) {
                Ui.toast(act, "没有可用的相机应用");
                return;
            }
            act.startActivityForResult(it, REQ_CAMERA);
        } catch (Exception e) {
            Ui.toast(act, "无法打开相机：" + e.getMessage());
        }
    }

    private void openPicker() {
        try {
            Intent it = new Intent(Intent.ACTION_GET_CONTENT);
            it.setType("image/*");
            it.addCategory(Intent.CATEGORY_OPENABLE);
            act.startActivityForResult(Intent.createChooser(it, "选择图片"), REQ_PICK);
        } catch (Exception e) {
            Ui.toast(act, "无法打开相册：" + e.getMessage());
        }
    }

    public void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_CAMERA && req != REQ_PICK) return;
        final DishCard card = pendingCard;
        pendingCard = null;
        if (card == null || res != Activity.RESULT_OK) {
            if (req == REQ_CAMERA && pendingCapture != null) {
                try {
                    PhotoProvider.fileFor(act, pendingCapture).delete();
                } catch (Exception ignored) {
                }
            }
            pendingCapture = null;
            return;
        }
        if (req == REQ_CAMERA) {
            final Uri cap = pendingCapture;
            pendingCapture = null;
            if (cap == null) return;
            Api.io(() -> {
                try {
                    File raw = PhotoProvider.fileFor(act, cap);
                    File up = Img.compress(act, raw);
                    Api.ui(() -> attachPhoto(card, up));
                } catch (Exception e) {
                    Api.ui(() -> Ui.toast(act, "照片处理失败：" + e.getMessage()));
                }
            });
        } else {
            final Uri picked = data == null ? null : data.getData();
            if (picked == null) return;
            Api.io(() -> {
                try {
                    File raw = copyPicked(picked);
                    File up = Img.compress(act, raw);
                    raw.delete();
                    Api.ui(() -> attachPhoto(card, up));
                } catch (Exception e) {
                    Api.ui(() -> Ui.toast(act, "图片处理失败：" + e.getMessage()));
                }
            });
        }
    }

    private File copyPicked(Uri uri) throws Exception {
        InputStream in = act.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("读不到所选图片");
        File f = new File(new File(act.getCacheDir(), "pick"),
                "pick_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 100000));
        f.getParentFile().mkdirs();
        FileOutputStream fo = new FileOutputStream(f);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
        in.close();
        fo.close();
        return f;
    }

    private void attachPhoto(DishCard card, File file) {
        BitmapHolder holder = new BitmapHolder(Img.thumb(file, Ui.dp(act, 72)));
        if (holder.bm == null) {
            Ui.toast(act, "缩略图生成失败");
            return;
        }
        card.files.add(file);
        int idx = card.files.indexOf(file);
        ImageView iv = new ImageView(act);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(act, 72), Ui.dp(act, 72));
        lp.rightMargin = Ui.dp(act, 8);
        iv.setLayoutParams(lp);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(holder.bm);
        iv.setOnClickListener(v -> Ui.toast(act, "长按可删除这张照片"));
        iv.setOnLongClickListener(v -> {
            int i = card.files.indexOf(file);
            if (i >= 0 && i < card.photos.getChildCount()) {
                card.files.remove(i);
                card.photos.removeViewAt(i);
            }
            return true;
        });
        card.photos.addView(iv, Math.min(idx, card.photos.getChildCount()));
    }

    /** 让 lambda 里能用非 final 的位图。 */
    private static class BitmapHolder {
        final android.graphics.Bitmap bm;

        BitmapHolder(android.graphics.Bitmap bm) {
            this.bm = bm;
        }
    }

    // ---------------- 保存 ----------------

    private void save() {
        final String restName = actRestaurant.getText().toString().trim();
        if (restName.isEmpty()) {
            Ui.toast(act, "先填写餐厅名");
            return;
        }
        if (!Api.configured(act)) {
            Ui.toast(act, "请先在右上角 ⚙ 配置服务器地址");
            return;
        }
        for (DishCard card : cards) {
            String n = card.name.getText().toString().trim();
            if (!n.isEmpty() && card.star.getRating() == 0) {
                Ui.toast(act, "请给「" + n + "」打分");
                return;
            }
        }
        final int overall = starOverall.getRating();
        boolean hasDish = false;
        for (DishCard card : cards) {
            if (!card.name.getText().toString().trim().isEmpty()) hasDish = true;
        }
        if (overall == 0 && !hasDish) {
            Ui.toast(act, "至少要给整体打分，或添加一道菜");
            return;
        }

        btnSave.setEnabled(false);
        btnSave.setAlpha(0.6f);
        btnSave.setText("保存中…");
        Api.io(() -> {
            try {
                // 压缩照片（与 dishes 下标对齐）；payload 先不带图片 URL
                JSONArray dishArr = new JSONArray();
                List<List<File>> photoFiles = new ArrayList<>();
                for (DishCard card : cards) {
                    String n = card.name.getText().toString().trim();
                    if (n.isEmpty()) continue;
                    List<File> fs = new ArrayList<>();
                    for (File f : card.files) fs.add(Img.compress(act, f));
                    photoFiles.add(fs);
                    JSONObject d = new JSONObject();
                    d.put("name", n);
                    d.put("rating", card.star.getRating());
                    d.put("comment", card.comment.getText().toString().trim());
                    d.put("images", new JSONArray());
                    dishArr.put(d);
                }
                final JSONObject payload = buildPayload(restName, dishArr, overall);

                try {
                    // 在线：逐张上传拿 URL 后提交
                    int done = 0;
                    int total = 0;
                    for (List<File> fs : photoFiles) total += fs.size();
                    for (int i = 0; i < dishArr.length(); i++) {
                        JSONObject d = dishArr.getJSONObject(i);
                        JSONArray urls = new JSONArray();
                        for (File f : photoFiles.get(i)) {
                            urls.put(Api.upload(act, f));
                            final int p = ++done, t = total;
                            Api.ui(() -> btnSave.setText("上传图片 " + p + "/" + t));
                        }
                        d.put("images", urls);
                    }
                    Api.visit(act, payload);
                    Api.ui(() -> {
                        resetForm();
                        Ui.toast(act, "已记录 ✓");
                        if (LocalStore.get(act).pendingCount() > 0) {
                            Syncer.sync(act, (synced, remaining, err) -> {
                                if (synced > 0) Ui.toast(act, "已同步 " + synced + " 条本地记录");
                            });
                        }
                    });
                } catch (Api.HttpError e) {
                    // 服务器明确拒绝（参数/token），重试无意义，不进离线队列
                    Api.ui(() -> {
                        restore();
                        Ui.toast(act, "保存失败：" + e.getMessage());
                    });
                } catch (IOException e) {
                    // 离线：整餐存本地队列（照片复制到应用内部目录），联网后自动补传
                    final int pend = LocalStore.get(act).enqueueVisit(act, payload, photoFiles);
                    Api.ui(() -> {
                        resetForm();
                        Ui.toast(act, "当前离线，已存到手机本地（待同步 " + pend
                                + " 条），联网后自动上传");
                    });
                }
            } catch (Exception e) {
                Api.ui(() -> {
                    restore();
                    Ui.toast(act, "保存失败：" + e.getMessage());
                });
            }
        });
    }

    private void restore() {
        btnSave.setEnabled(true);
        btnSave.setAlpha(1f);
        btnSave.setText("保存这一餐");
    }

    private JSONObject buildPayload(String restName, JSONArray dishArr, int overall) throws Exception {
        JSONObject payload = new JSONObject();
        JSONObject rest = new JSONObject();
        rest.put("name", restName);
        rest.put("address", etAddress.getText().toString().trim());
        if (areaId > 0) rest.put("area_id", areaId);
        if (!Double.isNaN(lat)) {
            rest.put("lat", lat);
            rest.put("lng", lng);
        }
        payload.put("restaurant", rest);
        payload.put("dishes", dishArr);
        payload.put("mode", mode);
        if (overall > 0) {
            JSONObject o = new JSONObject();
            o.put("rating", overall);
            o.put("comment", etOverall.getText().toString().trim());
            o.put("images", new JSONArray());
            payload.put("overall", o);
        }
        return payload;
    }

    private void resetForm() {
        silent = true;
        actRestaurant.setText("");
        etAddress.setText("");
        etOverall.setText("");
        starOverall.setRating(0);
        tvCoords.setText("");
        lat = lng = Double.NaN;
        areaId = 0;
        tvArea.setText("区块：未选择");
        llDishes.removeAllViews();
        cards.clear();
        addDishCard();
        btnSave.setEnabled(true);
        btnSave.setAlpha(1f);
        btnSave.setText("保存这一餐");
        silent = false;
    }
}
