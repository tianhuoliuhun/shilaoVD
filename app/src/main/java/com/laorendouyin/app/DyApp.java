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
     * 安装播放内核工厂：**IJK 优先，系统 MediaPlayer 兜底**。
     *
     * 必须在任何 VideoView 创建之前执行（VideoView 在构造时就从工厂取播放器）。
     * 两级兜底怎么分工，见 {@link com.laorendouyin.app.player.FallbackPlayerFactory}。
     *
     * 为什么这件事对"让安卓 4 跑起来"很关键：老机器上系统 MediaPlayer 对容器和编码挑得厉害，
     * 同一批视频新手机能放、它就不行。IJK 底层是 FFmpeg，软硬解通吃，是老设备上更稳的那张牌。
     */
    private void installPlayerKernel() {
        try {
            xyz.doikki.videoplayer.player.VideoViewManager.setConfig(
                    new xyz.doikki.videoplayer.player.VideoViewConfig.Builder()
                            .setPlayerFactory(new com.laorendouyin.app.player.FallbackPlayerFactory())
                            .build());
            AppLog.i("app", "播放内核工厂已安装（新系统用系统内核 / 老系统 IJK 优先）");
        } catch (Throwable t) {
            AppLog.i("app", "安装播放内核工厂失败，将使用默认内核: " + t);
        }
    }

    /**
     * 给 Android 4.4 及以下**补上 TLS 1.2**。
     *
     * 为什么必须有：API 20 之前的系统默认只启用 TLS 1.0/1.1，而抖音的接口和视频 CDN
     * 都在 HTTPS 上、早已要求 TLS 1.2 —— 不补这一手，老设备上的表现就是
     * "一直转圈、一条视频都拉不到"（而且不会有任何报错，最难查的那种）。
     *
     * 用的是官方推荐的做法：显式建一个 TLSv1.2 的 SSLContext 并设为 HttpsURLConnection 的默认
     * SocketFactory。API 21+ 本来就有，直接跳过。
     * 失败也不抛（有些 ROM 确实不带该 Provider），至少不影响其他功能。
     */
    private void enableTls12OnOldDevices() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) return;
        try {
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLSv1.2");
            sc.init(null, null, null);
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            AppLog.i("app", "老系统（API " + android.os.Build.VERSION.SDK_INT + "）已启用 TLS 1.2");
        } catch (Throwable e) {
            AppLog.i("app", "启用 TLS 1.2 失败（系统可能不支持）: " + e);
        }
    }

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
        // ⚠️ 日志必须**最先**初始化：下面这些初始化步骤都会打日志，
        //    而 AppLog 在 init 之前的调用是看不到的 —— 排查老设备问题时全靠这些日志，
        //    少一行就可能把"SSL 握手失败"这种事查半天。
        AppLog.init(this);
        CrashShield.install(this);
        // 老系统的两件事一起做（都在 TlsCompat 里）：
        //   ① 抬到 TLS 1.2 —— 系统默认只给 TLS 1.0/1.1，服务器早就不收了
        //   ② 自带一份新 CA —— 系统内置根证书是出厂那年的，认不出抖音现在的证书链
        // ⚠️ 这两件事必须放在**同一个 SSLContext** 里，否则后设的会把先设的覆盖掉
        //    （踩过：先设 TLS1.2 再 setDefaultSSLSocketFactory("TLS") → 又退回 1.0）
        com.laorendouyin.app.util.TlsCompat.install(this);
        installPlayerKernel();
        AppLog.i("app", "应用启动（myDVQ 极简版）");
    }
}
