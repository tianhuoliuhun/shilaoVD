package com.laorendouyin.app.player;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.video.VideoSize;
import com.laorendouyin.app.util.AppLog;

import java.util.Map;

import xyz.doikki.videoplayer.player.AbstractPlayer;

/**
 * ExoPlayer 内核适配器（DKPlayer 的 {@link AbstractPlayer} 实现）。
 *
 * **它是"让 Android 4 也能播"的关键那一环**，原因在**网络层**：
 * ExoPlayer 取流走的是 Java 的 `HttpURLConnection`（见 {@code DefaultHttpDataSource}），
 * 因此能吃到我们为老系统装的 **Conscrypt**（现代 TLS 实现，见 TlsCompat）；
 * 而 IJK / 系统 MediaPlayer 都是 native 实现、各自带一套老 TLS 栈，Conscrypt 管不着 ——
 * 实测这两个在 Android 4.4 上都因为握手被拒而播不出来。
 *
 * ⚠️ 版本必须锁在 **2.18**（ExoPlayer 2.19 起 minSdk 提到 21，本项目是 14）。
 *
 * 顺带一提：ExoPlayer 在新系统上也是三者里最稳的（软硬解切换、各种容器的兼容性都好），
 * 所以它被放在内核链的第一位。
 */
public class ExoPlayerKernel extends AbstractPlayer {

    private final Context mAppContext;
    private SimpleExoPlayer mPlayer;
    /** 记住待播放的 URL 与头：ExoPlayer 要等 setSurface 之后才好 prepare，先存着 */
    private String mPendingUrl;
    private Map<String, String> mPendingHeaders;
    /** 渲染目标（SurfaceHolder 或 Surface），ExoPlayer 需要它才能出画面 */
    private SurfaceHolder mHolder;
    private Surface mSurface;
    private boolean mPrepared;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    public ExoPlayerKernel(Context context) {
        mAppContext = context.getApplicationContext();
    }

    @Override
    public void initPlayer() {
        mPlayer = new SimpleExoPlayer.Builder(mAppContext).build();
        mPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);
        mPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    mPrepared = true;
                    if (mPlayerEventListener != null) mPlayerEventListener.onPrepared();
                } else if (state == Player.STATE_ENDED) {
                    if (mPlayerEventListener != null) mPlayerEventListener.onCompletion();
                } else if (state == Player.STATE_BUFFERING) {
                    if (mPlayerEventListener != null) {
                        mPlayerEventListener.onInfo(AbstractPlayer.MEDIA_INFO_BUFFERING_START, 0);
                    }
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                AppLog.i("player", "ExoPlayer 播放出错: " + error.getErrorCodeName()
                        + " 原因=" + error.getCause());
                if (mPlayerEventListener != null) mPlayerEventListener.onError();
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                if (mPlayerEventListener != null) {
                    mPlayerEventListener.onVideoSizeChanged(size.width, size.height);
                }
            }
        });
    }

    /** 真正开始取流。ExoPlayer 允许在 prepare 之后再设 surface，所以这里不强制等 surface。 */
    private void prepareInternal() {
        if (mPlayer == null || mPendingUrl == null) return;
        try {
            // ⚠️ 请求头（Referer）必须在这一层给：抖音 CDN 对无 Referer 的请求一律 403。
            //    ExoPlayer 走 Java 网络栈 → 顺带也享受到了 Conscrypt 的现代 TLS。
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent(com.laorendouyin.app.net.DouyinApi.UA)
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(15_000)
                    .setAllowCrossProtocolRedirects(true);
            if (mPendingHeaders != null && !mPendingHeaders.isEmpty()) {
                http.setDefaultRequestProperties(mPendingHeaders);
            }
            DefaultDataSource.Factory ds = new DefaultDataSource.Factory(mAppContext, http);
            MediaSource source = new ProgressiveMediaSource.Factory(ds)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(mPendingUrl)));
            mPlayer.setMediaSource(source);
            if (mHolder != null) mPlayer.setVideoSurfaceHolder(mHolder);
            if (mSurface != null) mPlayer.setVideoSurface(mSurface);
            mPlayer.prepare();
            mPrepared = true;
        } catch (Throwable t) {
            AppLog.i("player", "ExoPlayer prepare 失败: " + t);
            if (mPlayerEventListener != null) mPlayerEventListener.onError();
        }
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        mPendingUrl = path;
        mPendingHeaders = headers;
        if (mPlayer != null) {
            mMain.post(new Runnable() {
                @Override
                public void run() {
                    prepareInternal();
                }
            });
        }
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        try {
            mPendingUrl = fd.getFileDescriptor() != null ? "fd:asset" : null;
            // asset 场景本项目用不到（没有内置视频），留着不影响
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void start() {
        try {
            if (mPlayer != null) mPlayer.setPlayWhenReady(true);
        } catch (Throwable t) {
            AppLog.i("player", "ExoPlayer start 失败: " + t);
        }
    }

    @Override
    public void pause() {
        try {
            if (mPlayer != null) mPlayer.setPlayWhenReady(false);
        } catch (Throwable t) {
            // 忽略
        }
    }

    @Override
    public void stop() {
        try {
            if (mPlayer != null) mPlayer.stop();
        } catch (Throwable t) {
            // 忽略
        }
    }

    @Override
    public void prepareAsync() {
        mMain.post(new Runnable() {
            @Override
            public void run() {
                prepareInternal();
            }
        });
    }

    @Override
    public void reset() {
        try {
            if (mPlayer != null) {
                mPlayer.stop();
                mPlayer.clearMediaItems();
            }
            mPrepared = false;
            mPendingUrl = null;
        } catch (Throwable t) {
            // 忽略
        }
    }

    @Override
    public boolean isPlaying() {
        try {
            return mPlayer != null && mPlayer.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void seekTo(long time) {
        try {
            if (mPlayer != null) mPlayer.seekTo(time);
        } catch (Throwable t) {
            // 忽略
        }
    }

    @Override
    public void release() {
        try {
            if (mPlayer != null) {
                mPlayer.release();
                mPlayer = null;
            }
        } catch (Throwable t) {
            AppLog.i("player", "ExoPlayer release 失败: " + t);
        }
    }

    @Override
    public long getCurrentPosition() {
        try {
            return mPlayer == null ? 0 : mPlayer.getCurrentPosition();
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public long getDuration() {
        try {
            if (mPlayer == null) return 0;
            long d = mPlayer.getDuration();
            return d == C.TIME_UNSET ? 0 : d;
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public int getBufferedPercentage() {
        try {
            return mPlayer == null ? 0 : mPlayer.getBufferedPercentage();
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public void setSurface(Surface surface) {
        mSurface = surface;
        mHolder = null;
        try {
            if (mPlayer != null) mPlayer.setVideoSurface(surface);
        } catch (Throwable t) {
            AppLog.i("player", "ExoPlayer setSurface 失败: " + t);
        }
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        mHolder = holder;
        mSurface = null;
        try {
            if (mPlayer != null) mPlayer.setVideoSurfaceHolder(holder);
        } catch (Throwable t) {
            AppLog.i("player", "ExoPlayer setDisplay 失败: " + t);
        }
    }

    @Override
    public void setVolume(float v1, float v2) {
        try {
            if (mPlayer != null) mPlayer.setVolume(v1);
        } catch (Throwable t) {
            // 忽略
        }
    }

    @Override
    public void setLooping(boolean isLooping) {
        try {
            if (mPlayer != null) {
                mPlayer.setRepeatMode(isLooping ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
            }
        } catch (Throwable t) {
            // 忽略
        }
    }

    @Override
    public void setOptions() {
        // ExoPlayer 用构造器/DataSource 配置，没有统一 options 入口，留空
    }

    @Override
    public void setSpeed(float speed) {
        try {
            if (mPlayer != null) mPlayer.setPlaybackParameters(new PlaybackParameters(speed, 1f));
        } catch (Throwable t) {
            // 忽略
        }
    }

    @Override
    public float getSpeed() {
        try {
            return mPlayer == null ? 1f : mPlayer.getPlaybackParameters().speed;
        } catch (Throwable t) {
            return 1f;
        }
    }

    @Override
    public long getTcpSpeed() {
        // ExoPlayer 不直接暴露 TCP 速度，返回 0（本项目没用到）
        return 0;
    }
}
