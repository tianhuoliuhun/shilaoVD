package com.laorendouyin.app.player;

import android.content.Context;

import com.laorendouyin.app.util.AppLog;

import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.AndroidMediaPlayer;
import xyz.doikki.videoplayer.player.PlayerFactory;

/**
 * 播放内核工厂：**IJK →（仅老系统）ExoPlayer → 系统 MediaPlayer**，依次兜底。
 *
 * 🔴 **内核顺序按系统版本分开**（2026-10-14 真机闪退后定稿）：
 *    · **API 21+（新系统）**：**只用系统 MediaPlayer**（IJK 禁用）—— IJK 的
 *      `av_application_on_http_event` 在真机上必崩（tombstone 取证，见 sIjkBroken）；
 *    · **API < 21（老系统）**：**IJK 优先** —— 那边的系统解码器解不了 High Profile，
 *      IJK 是唯一活路。
 *
 * ⚠️ 2026-10-14 起播优化：老系统上 IJK 提到第一位。
 *    原来老系统是 ExoPlayer 优先，但它在 4.4 上**首条要白等 11.2 秒**才失败
 *    （4.4 的系统解码器连 H.264 High Profile 都不支持，ExoPlayer 走 MediaCodec 必挂），
 *    而 IJK 走本地代理后是验证过能播的、后续条起播只要 60~130ms。
 *    ExoPlayer 仍留作**老系统的第二级兜底**（真机若有硬件解码器，它是唯一能走硬解的路）。
 *
 * 这条顺序是有依据的：
 *   ① **ExoPlayer** —— 新系统上最稳（软硬解切换、容器兼容性都好）；更关键的是它取流走
 *      **Java 网络栈**，在 Android 4.x 上能吃到我们装的 Conscrypt（现代 TLS），
 *      是唯一能让老机器真正播出来的内核；
 *   ② **IJK** —— FFmpeg 内核，编解码兼容性广（ExoPlayer 建不起来时用）；
 *   ③ **系统 MediaPlayer** —— 最后的保底，什么都不行时至少试一次。
 *
 * 两级降级都做了：
 *   · **启动时**：前面那个内核创建失败（so 装不上等）→ 自动退到下一个；
 *   · **运行时**：当前内核播不出来 → {@link #fallbackToNext(String)} 降一级 + 重建播放器重放。
 *
 * 一旦降级就不再回头（进程内）：能触发降级说明这个内核对当前设备/内容确实不行，
 * 切回去只会让用户再看一次同样的故障。下次冷启动会重新按 ExoPlayer 优先来。
 */
public class FallbackPlayerFactory extends PlayerFactory<AbstractPlayer> {

    public static final int KERNEL_EXO = 0;
    public static final int KERNEL_IJK = 1;
    public static final int KERNEL_SYSTEM = 2;

    /**
     * 当前内核档位（进程级）。
     *
     * ⚠️ 初值**按系统版本定**，不能一律给 KERNEL_EXO ——
     * 光把 sExoBroken 置 true 只是"跳过 ExoPlayer"，档位记录仍停在 Exo，
     * 结果实际用的是 IJK、日志却报"ExoPlayer → IJK 降级"，还会白白多降一级
     * （踩过：新系统上明明直接用了 IJK，却先报一次假的 ExoPlayer 降级）。
     */
    private static volatile int sLevel =
            (android.os.Build.VERSION.SDK_INT >= 21) ? KERNEL_SYSTEM : KERNEL_IJK;
    /** 某内核被判定不可用后不再尝试 */
    private static volatile boolean sExoBroken =
            // ⚠️ 关键取舍（2026-10-14）：
            //   · **API 21+（新系统）上暂时不用 ExoPlayer** —— 它在 MuMu 上实测能缓冲
            //     （BUFFERING → PREPARED）但**始终不出画面**，集成还有问题待查；
            //     而 IJK 在新系统上是验证过能播的，所以新系统直接走 IJK，别拿它冒险。
            //   · **API < 21（老系统）上 ExoPlayer 退居兜底** —— 它取流走 Java 网络栈、
            //     能吃到我装的 Conscrypt；但它在 4.4 上首条必挂（软解器不支持 High Profile，
            //     白等 11.2s），所以只在 IJK 也失败之后才轮得到它。
            // 等 ExoPlayer 的不出画面问题查清了，这里就可以放开。
            android.os.Build.VERSION.SDK_INT >= 21;
    private static volatile boolean sIjkBroken =
            // 🔴 新系统（API 21+）**禁用 IJK**（2026-10-14 真机闪退取证）：
            //
            // 真机（Redmi / HyperOS / Android 15 / arm64）上连崩数次，tombstone 指向同一处：
            // ```
            //   tid 3418, name: ff_read                       ← IJK 的读线程
            //   signal 7 (SIGBUS) / 11 (SIGSEGV)，PC 跳到垃圾地址
            //   #01  libijkffmpeg.so (av_application_on_http_event+24)
            // ```
            // 即 IJK 的 `ijkavapplication` 模块在处理 **HTTP 事件回调**时访问了失效指针。
            //
            // 而我们的用法必然触发它：**IJK 的 FFmpeg 没有编译 https**，所以所有播放都要
            // 走本地代理（`http://127.0.0.1:port/https%3A%2F%2F...`），每次 HTTP 事件都会
            // 回调进这个函数。
            //
            // 新系统上系统 MediaPlayer 完全够用（抖音的流是标准 MP4 / H.264-HEVC，
            // 真机还有完整硬解），没有理由为 IJK 的容器兼容性去冒 native 崩溃的风险。
            //
            // ⚠️ 老系统（API<21）**不能**跟着禁：那边的系统解码器解不了 H.264 High Profile，
            //    IJK 是唯一的活路（见本文件其它注释）。
            android.os.Build.VERSION.SDK_INT >= 21;

    /** 运行时降级：当前内核播不出来时调用 */
    public static void fallbackToNext(String why) {
        int old = sLevel;
        if (sLevel == KERNEL_IJK) {
            sIjkBroken = true;
            // 老系统给 ExoPlayer 留一次机会（真机硬解）；新系统的 ExoPlayer 暂时雪藏
            sLevel = (android.os.Build.VERSION.SDK_INT < 21) ? KERNEL_EXO : KERNEL_SYSTEM;
        } else if (sLevel == KERNEL_EXO) {
            sExoBroken = true;
            sLevel = KERNEL_SYSTEM;
        } else {
            return;   // 已经是最后一个内核，没得退了
        }
        AppLog.i("player", "内核降级：" + name(old) + " → " + name(sLevel) + "（原因：" + why + "）");
    }

    /** 是否已经退到最后一个内核（系统 MediaPlayer）—— 再失败就不是内核的问题了 */
    public static boolean isLastKernel() {
        return sLevel >= KERNEL_SYSTEM;
    }

    public static String currentKernelName() {
        return name(sLevel);
    }

    /**
     * 当前档位是不是 IJK —— 缓存层据此决定"必须走本地代理"。
     *
     * 为什么必须问这个：**IJK 0.8.8 的 FFmpeg 根本没有编译 https 协议**，
     * 直连 https 一定报 Error(-10000)（实测 native 日志：
     * `https protocol not found, recompile FFmpeg with openssl...`），
     * 只有本地代理的 http://127.0.0.1 它才打得开。详见 PreloadManager.getPlayUrl。
     */
    public static boolean isIjkKernel() {
        return sLevel == KERNEL_IJK;
    }

    private static String name(int level) {
        if (level == KERNEL_EXO) return "ExoPlayer";
        if (level == KERNEL_IJK) return "IJK";
        return "系统MediaPlayer";
    }

    @Override
    public AbstractPlayer createPlayer(Context context) {
        // ① ExoPlayer
        if (sLevel <= KERNEL_EXO && !sExoBroken) {
            try {
                // ⚠️ **绝对不要在这里调 initPlayer()** —— DKPlayer 的 BaseVideoView 拿到实例后
                //    自己会调一次。这里再调就会创建**两个**内部播放器：第二个把第一个覆盖掉，
                //    而 Surface 还绑在第一个身上 → 表现是"缓冲好了（STATE_READY）却永远不出画面"。
                //    IJK 能容忍重复初始化，ExoPlayer 不能（实测踩到：日志里连着两条 Init）。
                AbstractPlayer p = new ExoPlayerKernel(context);
                AppLog.i("player", "本机使用 ExoPlayer 内核");
                return p;
            } catch (Throwable t) {
                // ExoPlayer 2.19 起要 API 21；我们用 2.18 并 overrideLibrary 放行了，
                // 万一在 14/15 上真起不来，这里兜住即可
                sExoBroken = true;
                AppLog.i("player", "ExoPlayer 创建失败，退到 IJK：" + t);
            }
        }
        // ② IJK
        if (sLevel <= KERNEL_IJK && !sIjkBroken && IjkPlayer.isAvailable()) {
            try {
                // 同上：不要调 initPlayer()
                AbstractPlayer p = new IjkPlayer(context);
                AppLog.i("player", "本机使用 IJK 内核");
                return p;
            } catch (Throwable t) {
                // IjkMediaPlayer 的静态块加载 so 失败时抛的是 Error —— 这里必须兜住
                sIjkBroken = true;
                AppLog.i("player", "IJK 创建失败，退到系统内核：" + t);
            }
        }
        // ③ 系统 MediaPlayer
        AppLog.i("player", "本机使用系统 MediaPlayer 内核");
        return new AndroidMediaPlayer(context);
    }
}
