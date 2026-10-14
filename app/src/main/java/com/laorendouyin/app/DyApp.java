package com.laorendouyin.app;

import android.app.Application;

import com.laorendouyin.app.util.AppLog;
import com.laorendouyin.app.util.CrashShield;

/**
 * 全局初始化：日志 + 崩溃护盾（无 adb 环境的观测底座）。
 *
 * myDVQ 相对原基座（腕上音符）删掉的两件事：
 *   - 会话 cookie 回写 WebView CookieStore（本项目没有登录，也没有 WebView）
 *   - debug 包开 WebView 远程调试（同上）
 */
public class DyApp extends Application {

    /**
     * minSdk 19 专用：Android 5.0（API 20）及以下**没有系统级 multidex 支持**，
     * 必须在这里手动把从 dex 装进来，否则一启动就是 NoClassDefFoundError。
     * （API 21+ 由系统原生支持，这一句在那些设备上是空操作，留着不影响。）
     */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        androidx.multidex.MultiDex.install(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 全站配色按深色设计（bg_base #0E1418 等），主题是 Material3.DayNight——
        // 跟随系统会在浅色模式下把默认字色换成深色，配深色背景直接看不见。固定夜间。
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
        AppLog.init(this);
        CrashShield.install(this);
        AppLog.i("app", "应用启动（myDVQ 极简版）");
    }
}
