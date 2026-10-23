package com.laorendouyin.app.feed;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.laorendouyin.app.R;

import xyz.doikki.videoplayer.controller.BaseVideoController;

/**
 * shilaoVD 极简控制层 —— 一个**空壳**。
 *
 * 原基座（腕上音符）在这里挂了双击 ±10s 手势和全屏开关；本项目全部移除：
 *   - 双击 seek：删（对不识字的使用者没有意义，且易误触）
 *   - 全屏开关：删（本来就是全屏）
 *   - 进度驱动：删（没有进度条要更新）
 *
 * 为什么还留一个 controller 而不是 setVideoController(null)：
 * DKVideoPlayer 的 VideoView 内部在多个状态回调里会与 controller 交互，
 * 保留一个不带任何控件、不消费触摸的空壳最省心（布局 layout_tiktok_control_bar 本身就是空的）。
 *
 * 触摸处理：显式**不消费**事件，让事件继续上抛给 VerticalViewPager —— 保证上下滑永远可用。
 */
public class TikTokController extends BaseVideoController {

    public TikTokController(@NonNull Context context) {
        super(context);
    }

    public TikTokController(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public TikTokController(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.layout_tiktok_control_bar;
    }

    /** 不弹"非 WiFi 播放"提示：对不识字的用户是无法理解的打扰 */
    @Override
    public boolean showNetWarning() {
        return false;
    }

    /**
     * 关键：不消费任何触摸。
     * 返回 false 让事件继续传递 → VerticalViewPager 的上下滑始终生效，
     * 同时天然做到"点画面什么都不发生"（没有暂停、没有面板）。
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }
}
