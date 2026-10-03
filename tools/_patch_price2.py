# -*- coding: utf-8 -*-
"""一次性补丁：RankTab 性价比 chip + Api + Detail 价格展示。用后即删。"""
import pathlib

BASE = pathlib.Path(__file__).resolve().parent.parent


def rep(path, old, new, what):
    p = BASE / path
    src = p.read_text(encoding='utf-8')
    assert old in src, what
    p.write_text(src.replace(old, new), encoding='utf-8')
    print('OK', what)


# ================= RankTab：性价比 chip + 行渲染 =================
p = 'phone_app/src/com/eatwhat/app/RankTab.java'

rep(p,
    '''    private TextView chipRest, chipDish, chipSign;
    private TextView chipModeAll, chipModeIn, chipModeOut;''',
    '''    private TextView chipRest, chipDish, chipSign, chipValue;
    private TextView chipModeAll, chipModeIn, chipModeOut;''', 'RankTab:field')

rep(p,
    '''        chipRest = root.findViewById(R.id.chip_rest);
        chipDish = root.findViewById(R.id.chip_dish);
        chipSign = root.findViewById(R.id.chip_sign);''',
    '''        chipRest = root.findViewById(R.id.chip_rest);
        chipDish = root.findViewById(R.id.chip_dish);
        chipSign = root.findViewById(R.id.chip_sign);
        chipValue = root.findViewById(R.id.chip_value);''', 'RankTab:bind')

rep(p,
    '''        chipRest.setOnClickListener(v -> switchMode(0));
        chipDish.setOnClickListener(v -> switchMode(1));
        chipSign.setOnClickListener(v -> switchMode(2));''',
    '''        chipRest.setOnClickListener(v -> switchMode(0));
        chipDish.setOnClickListener(v -> switchMode(1));
        chipSign.setOnClickListener(v -> switchMode(2));
        chipValue.setOnClickListener(v -> switchMode(3));''', 'RankTab:click')

rep(p,
    '''            try {
                final boolean[] cached = new boolean[1];
                JSONArray arr = mode == 0 ? Api.restaurants(act, "", 50, modeFilter, cached, tagFilter)
                        : mode == 1 ? Api.rankDishes(act, 50, modeFilter, cached, tagFilter)
                        : Api.signature(act, 50, modeFilter, cached, tagFilter);''',
    '''            try {
                final boolean[] cached = new boolean[1];
                JSONArray arr = mode == 0 ? Api.restaurants(act, "", 50, modeFilter, cached, tagFilter)
                        : mode == 1 ? Api.rankDishes(act, 50, modeFilter, cached, tagFilter)
                        : mode == 2 ? Api.signature(act, 50, modeFilter, cached, tagFilter)
                        : Api.valueDishes(act, 50, modeFilter, cached, tagFilter);''', 'RankTab:fetch')

rep(p,
    '''        TextView[] chips = {chipRest, chipDish, chipSign};
        for (int i = 0; i < chips.length; i++) {
            boolean on = i == mode;''',
    '''        TextView[] chips = {chipRest, chipDish, chipSign, chipValue};
        for (int i = 0; i < chips.length; i++) {
            boolean on = i == mode;''', 'RankTab:stylechips')

rep(p,
    '''            if (mode == 0) {
                title = item.optString("name");
                sub = Ui.score(avg, item.optInt("overall_reviews", 0) + item.optInt("dish_review_count", 0));''',
    '''            if (mode == 3) {
                title = item.optString("dish_name");
                sub = Ui.score(avg, count) + " · ¥" + Ui.num(item.optDouble("price", 0))
                        + " · 性价比 " + Ui.num(item.optDouble("value", 0))
                        + " · " + item.optString("restaurant_name", "");
            } else if (mode == 0) {
                title = item.optString("name");
                sub = Ui.score(avg, item.optInt("overall_reviews", 0) + item.optInt("dish_review_count", 0));''',
    'RankTab:valuerow')

rep(p,
    '''            JSONObject item = data.get(pos);
            long restId = mode == 0 ? item.optLong("id") : item.optLong("restaurant_id");
            if (restId > 0) RestaurantDetailActivity.start(act, restId);''',
    '''            JSONObject item = data.get(pos);
            long restId = mode == 0 ? item.optLong("id") : item.optLong("restaurant_id");
            if (restId > 0) RestaurantDetailActivity.start(act, restId);''', 'RankTab:click-noop')

# ================= Api：valueDishes + updateReview 带 price =================
p = 'phone_app/src/com/eatwhat/app/Api.java'

rep(p,
    '''    /** 全部已用标签（restaurant / dish 两组），供筛选。 */
    public static JSONObject tags(Context c) throws Exception {
        return getCached(c, "/api/tags", null);
    }''',
    '''    /** 全部已用标签（restaurant / dish 两组），供筛选。 */
    public static JSONObject tags(Context c) throws Exception {
        return getCached(c, "/api/tags", null);
    }

    /** 菜品性价比排行（性价比 = 评分 ÷ 最新实付价 × 10）。 */
    public static JSONArray valueDishes(Context c, int limit, int mode,
                                        boolean[] fromCache, String tag) throws Exception {
        return getArrCached(c, "/api/rank/value_dishes?limit=" + limit + "&mode=" + mode
                + "&tag=" + URLEncoder.encode(tag == null ? "" : tag, "UTF-8"), fromCache);
    }

    /** 商家性价比排行（性价比 = 评分 ÷ 均实付价 × 10）。 */
    public static JSONArray valueRestaurants(Context c, int limit, int mode,
                                             boolean[] fromCache, String tag) throws Exception {
        return getArrCached(c, "/api/rank/value_restaurants?limit=" + limit + "&mode=" + mode
                + "&tag=" + URLEncoder.encode(tag == null ? "" : tag, "UTF-8"), fromCache);
    }''', 'Api:value')

rep(p,
    '''    /** 修改评价：评分 / 文字 / 堂食外卖。 */
    public static JSONObject updateReview(Context c, long id, int rating,
                                          String comment, int mode) throws Exception {
        JSONObject p = new JSONObject();
        p.put("rating", rating);
        p.put("comment", comment == null ? "" : comment);
        p.put("mode", mode);
        return put(c, "/api/reviews/" + id, p);
    }''',
    '''    /** 修改评价：评分 / 文字 / 堂食外卖；price 非 null 时同时更新价格。 */
    public static JSONObject updateReview(Context c, long id, int rating,
                                          String comment, int mode) throws Exception {
        return updateReview(c, id, rating, comment, mode, null);
    }

    public static JSONObject updateReview(Context c, long id, int rating,
                                          String comment, int mode, Double price) throws Exception {
        JSONObject p = new JSONObject();
        p.put("rating", rating);
        p.put("comment", comment == null ? "" : comment);
        p.put("mode", mode);
        if (price != null) p.put("price", price);
        return put(c, "/api/reviews/" + id, p);
    }''', 'Api:updateprice')

# ================= RestaurantDetailActivity：价格展示 + 编辑价格 =================
p = 'phone_app/src/com/eatwhat/app/RestaurantDetailActivity.java'

rep(p,
    '''        // 标签
        JSONArray restTags = d.optJSONArray("tags");''',
    '''        // 人均消费与性价比
        TextView tvPrice = findViewById(R.id.tv_price);
        if (d.isNull("avg_price")) {
            tvPrice.setVisibility(View.GONE);
        } else {
            double ap = d.optDouble("avg_price", 0);
            String line = "均消费 ¥" + Ui.num(ap);
            if (!d.isNull("value")) line += " · 性价比 " + Ui.num(d.optDouble("value", 0)) + "（每10元）";
            tvPrice.setText(line);
            tvPrice.setVisibility(View.VISIBLE);
        }

        // 标签
        JSONArray restTags = d.optJSONArray("tags");''', 'Detail:avgprice')

rep(p,
    '''        TextView tvSub = new TextView(this);
        String dsub = Ui.score(dish.optDouble("avg_rating", 0), dish.optInt("review_count", 0));''',
    '''        TextView tvSub = new TextView(this);
        String dsub = Ui.score(dish.optDouble("avg_rating", 0), dish.optInt("review_count", 0));
        if (!dish.isNull("price")) {
            dsub += " · ¥" + Ui.num(dish.optDouble("price", 0));
        }''', 'Detail:dishprice')

rep(p,
    '''        final EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(2);
        et.setTextColor(Color.parseColor("#2B2320"));
        et.setText(rv.optString("comment", ""));
        et.setHint("评价内容");
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etLp.topMargin = Ui.dp(this, 10);
        box.addView(et, etLp);''',
    '''        final EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(2);
        et.setTextColor(Color.parseColor("#2B2320"));
        et.setText(rv.optString("comment", ""));
        et.setHint("评价内容");
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etLp.topMargin = Ui.dp(this, 10);
        box.addView(et, etLp);

        final EditText etPrice = new EditText(this);
        etPrice.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etPrice.setHint("实付价格（元，可选）");
        etPrice.setTextColor(Color.parseColor("#2B2320"));
        if (!rv.isNull("price")) {
            etPrice.setText(Ui.num(rv.optDouble("price", 0)));
        }
        LinearLayout.LayoutParams priceLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        priceLp.topMargin = Ui.dp(this, 8);
        box.addView(etPrice, priceLp);''', 'Detail:editprice')

rep(p,
    '''                            Api.updateReview(this, rid, rating,
                                    et.getText().toString().trim(), selMode[0]);''',
    '''                            Api.updateReview(this, rid, rating,
                                    et.getText().toString().trim(), selMode[0],
                                    parsePrice(etPrice.getText().toString()));''', 'Detail:updatecall')

rep(p,
    '''    private TextView modeChip(String label) {''',
    '''    private Double parsePrice(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            double v = Double.parseDouble(s.trim());
            return v < 0 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private TextView modeChip(String label) {''', 'Detail:parseprice')

print('ALL DONE')
