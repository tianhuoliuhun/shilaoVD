package com.laorendouyin.app.player;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.laorendouyin.app.util.AppLog;

import java.util.Map;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import xyz.doikki.videoplayer.player.AbstractPlayer;

/**
 * IJK 内核适配器（DKPlayer 的 {@link AbstractPlayer} 实现）。
 *
 * 为什么加它：**老设备（尤其 Android 4.x）上系统 MediaPlayer 的兼容性很差** ——
 * 它对容器/编码挑得厉害，同一个视频在新手机上好好的、在老人那台老机器上就是黑屏或报错。
 * IJK 底层是 FFmpeg，软硬解通吃，是"务必让安卓 4 跑起来"这件事上最可靠的一张牌。
 *
 * ⚠️ IJK 带 native so（libijkffmpeg / libijksdl / libijkplayer），
 *    所以本项目**不再是纯 Java**了 —— APK 里从此有 lib/ 目录，也就有了 ABI 之分。
 *    这也是为什么 so 的加载必须包在 try-catch(Throwable) 里：
 *    架构不匹配时抛的是 UnsatisfiedLinkError（Error 而非 Exception），漏了就闪退。
 *
 * 加载失败时不抛异常，而是让 {@link #isAvailable()} 返回 false，
 * 由 FallbackPlayerFactory 自动退到系统 MediaPlayer —— 这正是"系统兜底"。
 */
public class IjkPlayer extends AbstractPlayer {

    /** so 状态：-1 未检测 / 0 不可用 / 1 可用 */
    private static int sSoState = -1;

    /**
     * IJK 的 native 库能不能用。**只探一次**（探测本身有成本，且结果在进程内不会变）。
     *
     * 注意顺序：先单独 System.loadLibrary 探一遍，再让下面的 IjkMediaPlayer 静态块去加载 ——
     * 后者失败时会抛 Error，所以任何对它类的首次引用都必须包在 try-catch 里。
     */
    public static synchronized boolean isAvailable() {
        if (sSoState < 0) {
            try {
                System.loadLibrary("ijkffmpeg");
                System.loadLibrary("ijksdl");
                System.loadLibrary("ijkplayer");
                sSoState = 1;
                AppLog.i("player", "IJK native 库加载成功");
            } catch (Throwable t) {
                sSoState = 0;
                AppLog.i("player", "IJK native 库不可用（架构不匹配或缺失），改用系统内核：" + t);
            }
        }
        return sSoState == 1;
    }

    private final Context mAppContext;
    private IjkMediaPlayer mMediaPlayer;

    public IjkPlayer(Context context) {
        mAppContext = context.getApplicationContext();
    }

    /**
     * 构造 IJK 实例 —— 单独包一层，因为 {@link IjkMediaPlayer} 的静态块会加载 so，
     * 失败时抛的是 {@link UnsatisfiedLinkError}。
     */
    public static IjkPlayer create(Context context) {
        IjkPlayer p = new IjkPlayer(context);
        p.initPlayer();
        return p;
    }

    @Override
    public void initPlayer() {
        mMediaPlayer = new IjkMediaPlayer();
        setOptions();
        // ⚠️ IJK 的日志开关叫 setLogEnabled，**没有** setDebug（照 DKPlayer 老代码写会编译不过）
        mMediaPlayer.setLogEnabled(false);
        mMediaPlayer.setScreenOnWhilePlaying(true);

        mMediaPlayer.setOnErrorListener(new IMediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(IMediaPlayer mp, int what, int extra) {
                AppLog.i("player", "IJK 播放出错 what=" + what + " extra=" + extra);
                // 返回 true 表示"已处理"，避免 IJK 自己再往上抛
                if (mPlayerEventListener != null) mPlayerEventListener.onError();
                return true;
            }
        });
        mMediaPlayer.setOnCompletionListener(new IMediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(IMediaPlayer mp) {
                if (mPlayerEventListener != null) mPlayerEventListener.onCompletion();
            }
        });
        mMediaPlayer.setOnInfoListener(new IMediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(IMediaPlayer mp, int what, int extra) {
                if (mPlayerEventListener != null) mPlayerEventListener.onInfo(what, extra);
                return false;
            }
        });
        mMediaPlayer.setOnPreparedListener(new IMediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(IMediaPlayer mp) {
                if (mPlayerEventListener != null) mPlayerEventListener.onPrepared();
            }
        });
        mMediaPlayer.setOnVideoSizeChangedListener(new IMediaPlayer.OnVideoSizeChangedListener() {
            @Override
            public void onVideoSizeChanged(IMediaPlayer mp, int width, int height, int sarNum, int sarDen) {
                if (mPlayerEventListener != null) mPlayerEventListener.onVideoSizeChanged(width, height);
            }
        });
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        try {
            // ⚠️ 关键：IJK 的 setDataSource(String) 不方便带请求头，而抖音的 CDN
            //    没有 Referer 一律 403 —— 用 format 选项把 headers 塞进去。
            if (headers != null && !headers.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                }
                mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "headers", sb.toString());
            }
            mMediaPlayer.setDataSource(path);
        } catch (Throwable t) {
            AppLog.i("player", "IJK setDataSource 失败: " + t);
            if (mPlayerEventListener != null) mPlayerEventListener.onError();
        }
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        try {
            // IJK 的 setDataSource(FileDescriptor) **不接受** offset/length
            // （MediaPlayer 那套签名它没有）；asset 里的视频 offset 本来就是 0，直接给 fd
            mMediaPlayer.setDataSource(fd.getFileDescriptor());
        } catch (Throwable t) {
            AppLog.i("player", "IJK setDataSource(asset) 失败: " + t);
            if (mPlayerEventListener != null) mPlayerEventListener.onError();
        }
    }

    @Override
    public void start() {
        try {
            mMediaPlayer.start();
        } catch (Throwable t) {
            AppLog.i("player", "IJK start 失败: " + t);
        }
    }

    @Override
    public void pause() {
        try {
            mMediaPlayer.pause();
        } catch (Throwable t) {
            AppLog.i("player", "IJK pause 失败: " + t);
        }
    }

    @Override
    public void stop() {
        try {
            mMediaPlayer.stop();
        } catch (Throwable t) {
            AppLog.i("player", "IJK stop 失败: " + t);
        }
    }

    @Override
    public void prepareAsync() {
        try {
            mMediaPlayer.prepareAsync();
        } catch (Throwable t) {
            AppLog.i("player", "IJK prepareAsync 失败: " + t);
            if (mPlayerEventListener != null) mPlayerEventListener.onError();
        }
    }

    @Override
    public void reset() {
        try {
            mMediaPlayer.reset();
            setOptions();   // reset 会清掉选项，必须重设
        } catch (Throwable t) {
            AppLog.i("player", "IJK reset 失败: " + t);
        }
    }

    @Override
    public boolean isPlaying() {
        try {
            return mMediaPlayer.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void seekTo(long time) {
        try {
            mMediaPlayer.seekTo(time);
        } catch (Throwable t) {
            AppLog.i("player", "IJK seekTo 失败: " + t);
        }
    }

    @Override
    public void release() {
        try {
            if (mMediaPlayer != null) {
                mMediaPlayer.reset();
                mMediaPlayer.release();
            }
        } catch (Throwable t) {
            AppLog.i("player", "IJK release 失败: " + t);
        }
    }

    @Override
    public long getCurrentPosition() {
        try {
            return mMediaPlayer.getCurrentPosition();
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public long getDuration() {
        try {
            return mMediaPlayer.getDuration();
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public int getBufferedPercentage() {
        try {
            // IJK 没有现成的百分比接口，用"已缓冲时长/总时长"估算
            long dur = mMediaPlayer.getDuration();
            if (dur <= 0) return 0;
            long buf = mMediaPlayer.getCurrentPosition();   // 保守取当前位置
            return (int) Math.min(100, buf * 100 / dur);
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public void setSurface(Surface surface) {
        try {
            mMediaPlayer.setSurface(surface);
        } catch (Throwable t) {
            AppLog.i("player", "IJK setSurface 失败: " + t);
        }
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        try {
            mMediaPlayer.setDisplay(holder);
        } catch (Throwable t) {
            AppLog.i("player", "IJK setDisplay 失败: " + t);
        }
    }

    @Override
    public void setVolume(float v1, float v2) {
        try {
            mMediaPlayer.setVolume(v1, v2);
        } catch (Throwable t) {
            // 忽略
        }
    }

    @Override
    public void setLooping(boolean isLooping) {
        try {
            mMediaPlayer.setLooping(isLooping);
        } catch (Throwable t) {
            // 忽略
        }
    }

    /**
     * IJK 的解码选项。这里每一条都是"老设备能不能跑起来"的关键。
     */
    @Override
    public void setOptions() {
        try {
            // ---- 解码方式按系统版本分开（实测踩出来的）----
            //
            // API < 21（Android 4.x）**强制软解**：实测 4.4 模拟器上系统只有
            //   OMX.google.h264.decoder 这一个**纯软解**的 MediaCodec，走硬解路径直接
            //   ERROR_CODE_DECODING_FAILED；而老真机的硬解也普遍残缺。
            //   FFmpeg 软解虽然吃 CPU，但至少能出画面 —— 对老设备来说"能看"比"省电"重要。
            // API 21+ 则硬解优先（更流畅也更省电）。
            int useMediaCodec = (android.os.Build.VERSION.SDK_INT >= 21) ? 1 : 0;
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", useMediaCodec);
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", useMediaCodec);
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", useMediaCodec);
            // ⚠️ 这一条**绝不能设成 1**：它表示"所有视频都走硬解"，等于把回退软解的路堵死，
            //    在老设备上直接判死刑（之前我就是这么写的，4.4 上三个内核全灭）。
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos", 0);

            // —— 起播与流畅度 ——
            // 不等缓冲攒满就开播（抖音这种短视频，等满反而更慢）
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0);
            // 音画不同步时丢帧保音频（观感上比卡顿好）
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1);

            // —— 网络：抖音 CDN 偶尔抽风，给足超时并允许重连 ——
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 15000000L);   // 微秒
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 1);
        } catch (Throwable t) {
            AppLog.i("player", "IJK 设置选项失败（不影响播放）: " + t);
        }
    }

    @Override
    public void setSpeed(float speed) {
        try {
            mMediaPlayer.setSpeed(speed);
        } catch (Throwable t) {
            // 忽略
        }
    }

    @Override
    public float getSpeed() {
        try {
            // IJK 只有 getSpeed(float)（那是"按基准速率换算"的语义），没有无参版本；
            // 本项目不做倍速播放，恒返回 1.0
            return 1f;
        } catch (Throwable t) {
            return 1f;
        }
    }

    @Override
    public long getTcpSpeed() {
        try {
            return mMediaPlayer.getTcpSpeed();
        } catch (Throwable t) {
            return 0;
        }
    }
}
