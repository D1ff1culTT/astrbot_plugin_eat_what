package com.eatwhat.app;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 五星打分控件（整数 0~5 星），点几颗就是几分。 */
public class StarInput extends LinearLayout {

    private int rating = 0;
    private final TextView[] stars = new TextView[5];

    public StarInput(Context c) {
        this(c, null);
    }

    public StarInput(Context c, AttributeSet a) {
        super(c, a);
        setOrientation(HORIZONTAL);
        int pad = Ui.dp(c, 3);
        for (int i = 0; i < 5; i++) {
            TextView t = new TextView(c);
            t.setText("★");
            t.setTextSize(26);
            t.setPadding(pad, pad, pad, pad);
            final int v = i + 1;
            t.setOnClickListener(o -> setRating(v));
            stars[i] = t;
            addView(t);
        }
        setRating(0);
    }

    public void setRating(int r) {
        rating = Math.max(0, Math.min(5, r));
        for (int i = 0; i < 5; i++) {
            stars[i].setTextColor(i < rating ? 0xFFFFB300 : 0xFFDCD2C8);
        }
    }

    public int getRating() {
        return rating;
    }
}
