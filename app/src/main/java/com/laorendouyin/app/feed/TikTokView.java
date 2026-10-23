package com.laorendouyin.app.feed;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import xyz.doikki.videoplayer.controller.ControlWrapper;
import xyz.doikki.videoplayer.controller.IControlComponent;
import xyz.doikki.videoplayer.player.VideoView;

/**
 * shilaoVD 极简视频层 —— **只有画面与封面，没有任何按钮、文字或手势**。
 *
 * 设计约束（本项目的存在理由）：使用者是不识字的人，目标是"开机就能看、只会上下滑"。
 * 因此这里刻意**不**包含原基座（腕上音符）里的这些东西：
 *   - 暂停键 / 播放态图标（点屏幕不暂停）
 *   - 进度条 / 拖动 seek（没有进度概念）
 *   - 双击 ±10s（误触率高，且对老人无意义）
 *   - 标题 / 作者名 / 点赞评论收藏计数（不识字，看了也无用，还挡画面）
 *   - 全屏键（本来就是全屏）
 *   - 作者头像入口（点了会跳出信息流，属于"多余交互"）
 *
 * 唯一职责：加载封面 → 首帧渲染出来就隐藏，避免播放前的黑屏。
 * 唯一手势：没有。上下滑由外层 VerticalViewPager 负责。
 */
public class TikTokView extends FrameLayout implements IControlComponent {

    /** 封面图（播放前显示，首帧后隐藏）。由 FeedAdapter 加载。 */
    private final ImageView mThumb;

    private ControlWrapper mControlWrapper;

    public TikTokView(@NonNull Context context) {
        this(context, null);
    }

    public TikTokView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TikTokView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        // 纯代码构造，不再 inflate 带控件的布局：少一张 XML，也杜绝"将来有人往布局里加按钮"。
        mThumb = new ImageView(context);
        mThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mThumb.setBackgroundColor(0xFF000000);
        mThumb.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(mThumb, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    /** 封面 ImageView（适配器往里加载封面） */
    public ImageView thumb() {
        return mThumb;
    }

    // ---- IControlComponent ----

    @Override
    public void attach(@NonNull ControlWrapper controlWrapper) {
        mControlWrapper = controlWrapper;
    }

    @Override
    public View getView() {
        return this;
    }

    @Override
    public void onVisibilityChanged(boolean isVisible, Animation anim) {
        // 无面板控件 → 什么都不用同步。保留实现仅为满足接口。
    }

    @Override
    public void onPlayStateChanged(int playState) {
        // 只做一件事：首帧渲染出来就把封面撤掉；回到 IDLE 时把封面放回来。
        if (playState == VideoView.STATE_PLAYING) {
            mThumb.setVisibility(GONE);
        } else if (playState == VideoView.STATE_IDLE) {
            mThumb.setVisibility(VISIBLE);
        }
    }

    @Override
    public void onPlayerStateChanged(int playerState) {
    }

    @Override
    public void setProgress(int duration, int position) {
    }

    @Override
    public void onLockStateChanged(boolean isLocked) {
    }
}
