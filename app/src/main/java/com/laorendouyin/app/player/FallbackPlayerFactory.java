package com.laorendouyin.app.player;

import android.content.Context;

import com.laorendouyin.app.util.AppLog;

import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.AndroidMediaPlayer;
import xyz.doikki.videoplayer.player.PlayerFactory;

/**
 * 播放内核工厂：**IJK 优先，系统 MediaPlayer 兜底**。
 *
 * 这是一个两级兜底：
 *   ① IJK（FFmpeg 内核）—— 老设备/怪格式的兼容性最好，默认用它；
 *   ② 系统 MediaPlayer —— IJK 的 so 装不上（架构不匹配）或创建失败时自动退到它。
 *
 * 再加一层运行时的：页面发现"用 IJK 播不出来"，可以调 {@link #forceSystemKernel()} 强制切到
 * 系统内核并重建播放器（见 FeedActivity 的 fallbackToSystemKernel）。
 *
 * 三层叠起来的目标只有一个：**任何一台机器上，至少有一个内核能把视频放出来。**
 */
public class FallbackPlayerFactory extends PlayerFactory<AbstractPlayer> {

    /**
     * 是否已被强制切到系统内核（进程级）。
     *
     * 为什么是"一旦切了就回不去"：能触发这个开关说明 IJK 在这台机器上确实有问题
     * （解码失败、花屏、卡死…），再切回去只会让用户再看一次同样的故障。
     * 下次冷启动会重新按 IJK 优先来。
     */
    private static volatile boolean sForceSystemKernel = false;

    /** 供页面在"IJK 播不出来"时调用 */
    public static void forceSystemKernel(String why) {
        if (!sForceSystemKernel) {
            sForceSystemKernel = true;
            AppLog.i("player", "已强制切到系统内核（原因：" + why + "）");
        }
    }

    public static boolean isSystemForced() {
        return sForceSystemKernel;
    }

    @Override
    public AbstractPlayer createPlayer(Context context) {
        // ① 一级：IJK
        if (!sForceSystemKernel && IjkPlayer.isAvailable()) {
            try {
                AbstractPlayer p = IjkPlayer.create(context);
                AppLog.i("player", "本机使用 IJK 内核");
                return p;
            } catch (Throwable t) {
                // IjkMediaPlayer 的静态块加载 so 失败时抛的是 Error —— 这里必须兜住
                AppLog.i("player", "IJK 创建失败，退回系统内核：" + t);
            }
        }
        // ② 二级：系统 MediaPlayer
        AppLog.i("player", "本机使用系统 MediaPlayer 内核");
        return new AndroidMediaPlayer(context);
    }
}
