package com.eatwhat.app;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** 百分制打分控件：0~100 滑杆 + 实时分数。0 视为未打分。 */
public class ScoreInput extends LinearLayout {

    private final SeekBar seek;
    private final TextView tv;

    public ScoreInput(Context c) {
        this(c, null);
    }

    public ScoreInput(Context c, AttributeSet a) {
        super(c, a);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        seek = new SeekBar(c);
        seek.setMax(100);
        addView(seek, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        tv = new TextView(c);
        tv.setTextSize(17);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(0xFFFFB300);
        tv.setMinWidth(Ui.dp(c, 64));
        tv.setGravity(Gravity.CENTER);
        LayoutParams tlp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Ui.dp(c, 8);
        addView(tv, tlp);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                tv.setText(v == 0 ? "未打分" : v + " 分");
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        setScore(0);
    }

    public void setScore(int v) {
        seek.setProgress(Math.max(0, Math.min(100, v)));
    }

    public int getScore() {
        return seek.getProgress();
    }
}
