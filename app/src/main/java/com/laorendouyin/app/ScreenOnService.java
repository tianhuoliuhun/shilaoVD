package com.laorendouyin.app;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;

import com.laorendouyin.app.feed.FeedActivity;
import com.laorendouyin.app.util.AppLog;

/**
 * 亮屏即拉起视频界面 —— 用来"越过"系统锁屏。
 *
 * 为什么需要它：视频界面自己加了 FLAG_DISMISS_KEYGUARD，但**只有 Activity 被启动时**那个 flag
 * 才会去解除锁屏。老人按电源键亮屏后，桌面/锁屏是系统自己拉起来的，我们的 Activity 没被启动，
 * 于是 flag 也没机会生效 —— 所以必须在 SCREEN_ON 这一刻主动 startActivity。
 *
 * ⚠️ 为什么必须是"服务 + 动态注册"：
 *    `ACTION_SCREEN_ON` 是**只能在代码里注册**的广播，写进 manifest 的 `<receiver>` 收不到。
 *    要长期监听就只能常驻一个 Service，在里面 registerReceiver。
 *
 * ⚠️ 为什么只在 API < 26 启用（见 DyApp）：
 *    Android 8.0 起后台服务受限、Android 10 起禁止后台启动 Activity。这招专治老设备
 *    （Android 4.x~7.x 那批锁屏绕不过去的机器），新系统上本来也不需要，所以直接不启动。
 */
public class ScreenOnService extends Service {

    private BroadcastReceiver mScreenOn;

    /**
     * 必须存成字段：这个对象一旦被 GC，系统会自动把锁屏恢复回来
     * （AOSP 里 KeyguardLock 的 finalize 会调 reenableKeyguard）。
     */
    @SuppressWarnings("deprecation")
    private android.app.KeyguardManager.KeyguardLock mKeyguardLock;

    @Override
    @SuppressWarnings("deprecation")
    public void onCreate() {
        super.onCreate();

        // ---------- 从根上禁用锁屏 ----------
        // KeyguardManager.disableKeyguard() 是 API 25 及以下「禁用锁屏」的正规做法
        // （API 26 起才 deprecated），需要 DISABLE_KEYGUARD 权限（normal，安装即授予）。
        //
        // 为什么不用 Activity 的 FLAG_DISMISS_KEYGUARD / FLAG_SHOW_WHEN_LOCKED：
        //   锤子 SM901（Android 6.0.1）实测会打
        //     ActivityThread: checkShowWhenLockedPermission and finish ComponentInfo{...}
        //   把 Activity 当场 finish 掉（两个 flag 单独用都会触发，已用日志时间戳确认）。
        //
        // disableKeyguard() 是让**系统不再显示锁屏**，从源头解决；不是让界面去跟锁屏抢。
        try {
            android.app.KeyguardManager km =
                    (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null) {
                mKeyguardLock = km.newKeyguardLock("shilao");
                mKeyguardLock.disableKeyguard();
                AppLog.i("lock", "已禁用锁屏（disableKeyguard），当前 isKeyguardLocked="
                        + km.isKeyguardLocked());
            } else {
                AppLog.i("lock", "拿不到 KeyguardManager，锁屏禁用跳过");
            }
        } catch (Throwable t) {
            AppLog.i("lock", "禁用锁屏失败: " + t);
        }

        mScreenOn = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_SCREEN_ON.equals(intent.getAction())) return;
                try {
                    Intent go = new Intent(context, FeedActivity.class);
                    // 从 Service 启动 Activity 必须带 NEW_TASK；
                    // SINGLE_TOP 避免已经在界面时重建（FeedActivity 本身是 singleTask）
                    go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    context.startActivity(go);
                    AppLog.i("lock", "亮屏 → 拉起视频界面");
                } catch (Throwable t) {
                    AppLog.i("lock", "亮屏拉起失败: " + t);
                }
            }
        };
        try {
            registerReceiver(mScreenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
            AppLog.i("lock", "亮屏监听已注册");
        } catch (Throwable t) {
            AppLog.i("lock", "亮屏监听注册失败: " + t);
        }
    }

    /**
     * START_STICKY：这个服务被系统杀掉后会自动重建，否则"被杀一次就永久失效"，
     * 而用户完全感知不到 —— 只会觉得"怎么又要解锁了"。
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // 进程退出/服务销毁时把锁屏还回去 —— 不然用户卸载 App 后锁屏仍处于禁用状态，
        // 只能重启才恢复，会被当成手机坏了。
        if (mKeyguardLock != null) {
            try {
                mKeyguardLock.reenableKeyguard();
            } catch (Throwable ignored) {
            }
            mKeyguardLock = null;
        }
        if (mScreenOn != null) {
            try {
                unregisterReceiver(mScreenOn);
            } catch (Throwable ignored) {
            }
            mScreenOn = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
