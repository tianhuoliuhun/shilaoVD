package com.laorendouyin.app.ui;

// shilaoVD 极简 Activity 基类。
//
// 原基座（腕上音符）这里做了两件事：按用户设置应用全局字体/界面缩放（UiScale）、
// 以及圆屏边距内缩（Settings）。这两样都随"设置页"一起移除了 ——
// 本项目只有一个页面、一种形态，没有可调项，也就没有"跟着设置变"的必要。
//
// 保留下来的只有一件事：刷视频页需要屏幕常亮（不然看着看着黑屏）。

import android.os.Bundle;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

public class UiActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    /** 子类覆写：本页是否需要常亮 */
    protected boolean keepScreenOnWhileVisible() {
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (keepScreenOnWhileVisible()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }
}
