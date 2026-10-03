package com.eatwhat.app;

import android.view.View;

/** 主界面三个标签页的通用接口。 */
public interface Tab {

    /** 惰性创建的页面根视图。 */
    View view();

    /** 每次切到该页时回调（刷新数据）。 */
    void onShow();
}
