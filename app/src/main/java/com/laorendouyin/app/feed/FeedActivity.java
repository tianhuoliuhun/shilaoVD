package com.laorendouyin.app.feed;

// 源自 DKVideoPlayer demo 的 TikTok2Activity（Apache-2.0，Copyright Doikki）——
// 官方推荐的仿抖音实现：VerticalViewPager 上下滑 + AndroidVideoCache 预加载 + 单播放器复用。
//
// myDVQ 改造（对照原基座「腕上音符」的删减清单）：
//   删：点赞 / 收藏 / 评论 / 分享 / 作者主页（原走 WebView 引擎）
//   删：全屏开关、双指缩放、手动旋转、全屏手势层
//   删：暂停与进度条（点画面什么都不发生）
//   删：标题与作者名等一切文字覆盖层
//   删：可见返回键（页面就是全部，系统返回即退出）
//   删：外部塞列表播放的"预置模式"（「我的」页已随互动一起移除）
//   增：**按屏幕方向分流** —— 竖屏只刷竖屏视频，横屏只刷横屏视频（见 onConfigurationChanged）
//   增：**沉浸式全屏** —— 挡掉系统手势误触（横屏上滑 = 系统"回主屏"）
//   增：**转屏保活** —— 换流时当前视频接着播，不重新加载（见 mKeepVideo / startPlay）
//   留：上下滑、单播放器复用、预加载、跨会话去重、翻页游标持久化
//
// License: Apache-2.0（见 lib/LICENSE-DKVideoPlayer.txt）

import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.viewpager.widget.ViewPager;

import com.laorendouyin.app.R;
import com.laorendouyin.app.cache.PreloadManager;
import com.laorendouyin.app.net.DouyinApi;
import com.laorendouyin.app.ui.UiActivity;
import com.laorendouyin.app.widget.VerticalViewPager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import xyz.doikki.videoplayer.player.VideoView;

public class FeedActivity extends UiActivity {

    /** 当前播放位置 */
    private int mCurPos;
    /**
     * 面向屏幕方向的"应用级缓存"：重进页面直接放上次刷到的视频，避免"每次进来都是那两条老视频"。
     * ⚠️ 只缓存**当前方向**的内容（横竖屏切换时会清空，两个方向的流不能混）。
     */
    private static final List<FeedVideo> sCache = new ArrayList<>();
    private DouyinApi mApi;

    /**
     * 当前要走哪一个方向的信息流（由屏幕方向决定）。
     * true = 横屏信息流，false = 竖屏信息流。
     */
    private boolean mWantLandscape;

    /**
     * 转屏保活：切方向时**必须留在列表里**的那个视频（= 用户正在看的那个）。
     *
     * 为什么需要它：换流会把列表整批清掉，如果当前视频也跟着没了，播放器就得重新
     * setUrl、从头缓冲 —— 用户看到的就是"转个屏，正在看的视频重头加载了"。
     * 现在把它单独拎出来放回列表首位，播放器那一路走"只搬视图、不重载"的分支。
     * 第一页新数据到货后置空（用掉即弃，避免用户滑走后它还赖在首位）。
     */
    private FeedVideo mKeepVideo;

    /**
     * 当前正在播的视频 id。转屏挂载时用它判断"要挂的是不是同一个视频"：
     * 是 → 只把播放器搬到新位置，**不 release、不 setUrl**（播放不中断）。
     */
    private String mPlayingAwemeId = "";

    /** 当前播放的**原始地址**（不带代理前缀）—— 要降级直连时用它重播 */
    private String mPlayingRawUrl = "";

    /**
     * 当前这一条是不是走的**代理地址**（= 命中了缓存）。
     * 只有走代理的才有"降级"一说：直连都播不出来，那就是内容本身的问题了。
     */
    private boolean mPlayingViaProxy;

    /**
     * 本条是否已经降级重播过一次。
     * 必须有这个闸门：直连再失败还会触发降级，没有上限就是死循环重播。
     */
    private boolean mProxyFallbackTried;


    /**
     * 连续多少页"被过滤掉、一条都没留下"。
     * 过滤有两个来源：看过的（SeenStore）、方向不对的（竖屏模式下的横屏视频等）。
     * 没有上限的话，服务端要是持续返回同一种，就是"转圈不停 + 静默无限翻页"。
     */
    private int mEmptyPages;
    /** 自动补拉的上限（一页最多 10 条，5 页≈几十条都不合适就停，别无限刷） */
    private static final int MAX_EMPTY_PAGES = 5;
    private volatile boolean mLoading;
    private final List<FeedVideo> mVideoList = new ArrayList<>();
    private FeedAdapter mAdapter;
    private VerticalViewPager mViewPager;
    private PreloadManager mPreloadManager;
    private TikTokController mController;
    private VideoView mVideoView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feed);
        // 先定方向：后面拉流、过滤、缓存全按它走
        mWantLandscape = isLandscapeScreen();
        com.laorendouyin.app.util.AppLog.i("feed", "启动方向：" + (mWantLandscape ? "横屏" : "竖屏") + "信息流");
        initViewPager();
        initVideoView();
        mPreloadManager = PreloadManager.getInstance(this);

        loadCacheOrEmpty();
        showHint("正在加载…");
        loadFeed();
        mViewPager.post(new Runnable() {
            @Override
            public void run() {
                if (isDisclaimerAccepted()) {
                    startPlay(0);
                } else {
                    showDisclaimerThenPlay();
                }
            }
        });
    }

    // ---------- 使用须知（首次启动弹窗）----------

    /**
     * 使用须知文案。版本号与同意状态见 {@link com.laorendouyin.app.util.LegalState}。
     * 文案与 README「版权与使用声明」保持一致，改动时两边一起改。
     */
    private static final String DISCLAIMER_TEXT =
            "1. 本软件只是视频播放器，视频内容的版权归抖音及其创作者所有。\n\n"
            + "2. 本软件仅供个人学习研究，请勿用于任何商业用途。\n\n"
            + "3. 本项目与抖音官方没有任何关系，不是官方应用。\n\n"
            + "4. 播放时会在本机临时保存视频片段，退出时自动清空；"
            + "本软件不提供保存、下载或分享视频的功能。\n\n"
            + "5. 请遵守当地法律法规与平台服务条款，相关后果由使用者自行承担。\n\n"
            + "6. 请在下载后 24 小时内删除本软件。";

    private boolean isDisclaimerAccepted() {
        return com.laorendouyin.app.util.LegalState.isDisclaimerAccepted(this);
    }

    /**
     * 首次启动弹出使用须知。
     * 同意 → 记下版本号并开始播放；不同意 → 退出应用。
     * 不可取消（返回键、点空白处都关不掉），必须二选一。
     */
    private void showDisclaimerThenPlay() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("使用须知")
                .setMessage(DISCLAIMER_TEXT)
                .setCancelable(false)
                .setPositiveButton("我同意", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        com.laorendouyin.app.util.LegalState.acceptDisclaimer(FeedActivity.this);
                        startPlay(0);
                    }
                })
                .setNegativeButton("不同意并退出", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .show();
    }

    // ---------- 方向分流 ----------

    /** 当前屏幕是不是横着的 */
    private boolean isLandscapeScreen() {
        return getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    private DouyinApi.FeedMode feedMode() {
        return mWantLandscape ? DouyinApi.FeedMode.LANDSCAPE : DouyinApi.FeedMode.PORTRAIT;
    }

    /**
     * 屏幕方向变了 → 换成对应方向的信息流。
     *
     * Manifest 里 configChanges 含 orientation，所以旋转**不会重建 Activity**
     * （重建会连播放器一起毁掉、还要重新拉流，体验很差），旋转事件走这里。
     *
     * ⚠️ 关键约束（用户明确要求）：**当前正在看的视频不许重新加载**。所以这里：
     *   ① 先把当前视频拎出来（mKeepVideo），清列表后放回首位 —— 保证它还在列表里；
     *   ② 绝不对播放器调 release()；
     *   ③ 重新挂载时 startPlay 会认出"还是同一个视频"，只搬视图、不重设 URL。
     * 其余内容按新方向重新拉（两套参数的内容池不同：实测横屏流 93% 横屏、竖屏流 75% 竖屏）。
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (landscape == mWantLandscape) return;

        // ① 拎出当前视频（此刻列表还是旧方向的）
        final FeedVideo current = (mCurPos >= 0 && mCurPos < mVideoList.size())
                ? mVideoList.get(mCurPos) : null;
        if (current != null) mKeepVideo = current;

        mWantLandscape = landscape;
        com.laorendouyin.app.util.AppLog.i("feed", "屏幕转向 → 切换到"
                + (mWantLandscape ? "横屏" : "竖屏") + "信息流"
                + (current == null ? "（无在播视频）" : "（保活 " + current.awemeId + "）"));

        // ② 换流：列表与缓存换成新方向的内容；**播放器不动**
        mVideoList.clear();
        sCache.clear();
        mEmptyPages = 0;
        if (current != null) {
            mVideoList.add(current);   // 当前视频留在首位 → 播放器有地方可待
            mCurPos = 0;
            // ⚠️ 顺序要紧：**先 notifyDataSetChanged 再 setCurrentItem**。
            //    反过来的话，ViewPager 会拿"旧数据 + 新位置"去算滚动，数据一变就对不上 ——
            //    这是"横竖屏切换闪退"的另一半原因。
            mAdapter.notifyDataSetChanged();
            // ⚠️ 延后一帧再定位：notifyDataSetChanged 之后 ViewPager 得先消化新数据，
            //    同一帧内立刻 setCurrentItem 会被判成"改了数据却没通知"而抛 IllegalStateException
            //    （v1.1 的转屏闪退就是这个，dropbox 里的堆栈直指这一行）。
            mViewPager.post(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing() && !isDestroyed()) {
                        mViewPager.setCurrentItem(0, false);
                    }
                }
            });
            // 重新挂载（转屏会让 item 重新布局；startPlay 会识别出"同一个视频"而只搬视图）
            startPlay(0);
            clearHint();
        } else {
            mAdapter.notifyDataSetChanged();
            mViewPager.post(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing() && !isDestroyed()) {
                        mViewPager.setCurrentItem(0, false);
                    }
                }
            });
            com.laorendouyin.app.ui.Loading.show(this, true);
        }
        loadMore(true);
    }

    /**
     * 四态反馈统一出口：有事说事，没事收起来——刷视频页全屏，
     * 提示行常驻会一直压在画面上。
     */
    private void showHint(String text) {
        android.widget.TextView hint = findViewById(R.id.tv_feed_hint);
        if (hint == null) return;
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
    }

    private void clearHint() {
        android.widget.TextView hint = findViewById(R.id.tv_feed_hint);
        if (hint != null) hint.setVisibility(View.GONE);
    }

    /** 网络优先加载真实 Feed，分页：抖音每页只回几条 */
    private void loadFeed() {
        loadMore(true);
    }

    /**
     * 懒建 DouyinApi（签名 JS + 两个方向各自的翻页游标）；只在后台线程调。
     * myDVQ：**不注入任何登录态** —— 全程游客身份，只靠 ttwid + a_bogus。
     */
    private DouyinApi ensureApi() throws Exception {
        if (mApi != null) return mApi;
        java.util.List<String> js = java.util.Arrays.asList(
                readAll(getAssets().open("sign/utils.js")),
                readAll(getAssets().open("sign/sm3.js")),
                readAll(getAssets().open("sign/vm_decode.js")));
        mApi = new DouyinApi(new com.laorendouyin.app.sign.Signer(js));
        // 翻页游标持久化：两个方向各存一份（切横竖屏不该互相干扰）
        android.content.SharedPreferences sp = getSharedPreferences("feed_state", MODE_PRIVATE);
        mApi.setRefreshIndex(DouyinApi.FeedMode.LANDSCAPE, sp.getInt(cursorKey(DouyinApi.FeedMode.LANDSCAPE), 0));
        mApi.setRefreshIndex(DouyinApi.FeedMode.PORTRAIT, sp.getInt(cursorKey(DouyinApi.FeedMode.PORTRAIT), 0));
        return mApi;
    }

    private static String cursorKey(DouyinApi.FeedMode mode) {
        return "refresh_index_" + mode.name().toLowerCase();
    }

    private void loadMore(final boolean first) {
        // 页面已经在关：别再起新的网络请求（补拉的延时任务会走到这里）
        if (isFinishing() || isDestroyed()) return;
        if (mLoading) return;
        mLoading = true;
        if (first) com.laorendouyin.app.ui.Loading.show(this, true);
        final DouyinApi.FeedMode mode = feedMode();
        com.laorendouyin.app.util.AppLog.i("feed", "拉取下一页（" + mode + "，已 " + mVideoList.size() + " 条）");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final java.util.List<FeedVideo> list = ensureApi().fetchFeed(10, mode);
                    // 游标落盘（按方向分开存）
                    getSharedPreferences("feed_state", MODE_PRIVATE).edit()
                            .putInt(cursorKey(mode), mApi.getRefreshIndex(mode)).apply();
                    com.laorendouyin.app.util.AppLog.i("feed", "本页拿到 " + list.size() + " 条（"
                            + mode + " 游标=" + mApi.getRefreshIndex(mode) + "）");
                    int cov = 0;
                    for (FeedVideo v : list) {
                        if (v.coverUrl != null && !v.coverUrl.isEmpty()) cov++;
                    }
                    com.laorendouyin.app.util.AppLog.i("feed", "本页封面 " + cov + "/" + list.size() + " 条");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mLoading = false;
                            // 列表里若还混着别的方向/缓存的项（URL 是上次会话烤的，签名早过期→必死链），
                            // 首批真数据到货就整批换掉，否则播放器会一直卡在放不出画面的垫场项上。
                            boolean hasStale = false;
                            for (FeedVideo v : mVideoList) {
                                if (v.awemeId == null || v.awemeId.isEmpty()) { hasStale = true; break; }
                            }
                            int added = applyNewItems(list, first || hasStale);
                            com.laorendouyin.app.util.AppLog.i("feed", "新面孔 " + added + "/" + list.size()
                                    + " 条（共 " + mVideoList.size() + "）");
                            // notifyDataSetChanged 会重建页面视图 → 无条件重挂当前播放
                            startPlay(mViewPager.getCurrentItem());
                            if (list.isEmpty()) {
                                mEmptyPages = 0;
                                showHint("没有更多了");
                                com.laorendouyin.app.ui.Loading.show(FeedActivity.this, false);
                            } else if (added == 0) {
                                // 本页一条都没留下（全看过 or 方向都不对）→ 自动补拉下一页
                                mEmptyPages++;
                                com.laorendouyin.app.util.AppLog.i("feed", "本页无可播内容"
                                        + (mWantLandscape ? "（要横屏）" : "（要竖屏）")
                                        + "，自动补拉（第 " + mEmptyPages + "/" + MAX_EMPTY_PAGES + " 页）");
                                if (mEmptyPages >= MAX_EMPTY_PAGES) {
                                    com.laorendouyin.app.util.AppLog.i("feed", "连续 " + MAX_EMPTY_PAGES
                                            + " 页无可播内容，停止自动补拉");
                                    showHint(mWantLandscape ? "暂时没有更多横屏视频" : "暂时没有更多竖屏视频");
                                    com.laorendouyin.app.ui.Loading.show(FeedActivity.this, false);
                                } else {
                                    mViewPager.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (isFinishing() || isDestroyed()) return;
                                            loadMore(false);
                                        }
                                    }, 300);
                                }
                            } else {
                                mEmptyPages = 0;
                                // 有内容就不占画面（全屏刷视频，提示行压在画面上很碍事）
                                clearHint();
                                com.laorendouyin.app.ui.Loading.show(FeedActivity.this, false);
                            }
                        }
                    });
                } catch (Exception e) {
                    com.laorendouyin.app.util.AppLog.i("feed", "网络加载失败: " + e);
                    final String err = String.valueOf(e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mLoading = false;
                            mEmptyPages = 0;   // 失败就重新开始计数，别把上一次的失败算进上限
                            com.laorendouyin.app.ui.Loading.show(FeedActivity.this, false);
                            if (isFinishing() || isDestroyed()) return;
                            showHint((first ? "网络加载失败，稍后自动重试" : "翻页失败: ") + err);
                            // 首屏失败自动重试（老人不会去点"重试"按钮——本项目也没有按钮）
                            if (first) {
                                mViewPager.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (isFinishing() || isDestroyed()) return;
                                        loadMore(true);
                                    }
                                }, 5000);
                            }
                        }
                    });
                }
            }
        }, "feed-load").start();
    }

    private void initVideoView() {
        mVideoView = createVideoView();
    }

    /**
     * 建一个播放器实例（含状态监听与控制器）。
     *
     * 单独抽出来是因为**换播放内核时必须重建 VideoView** —— 它的播放器是构造时
     * 从 PlayerFactory 取的，改不了。
     */
    private VideoView createVideoView() {
        VideoView vv = new VideoView(this);
        vv.setLooping(true);
        // 竖屏视频居中裁剪、横屏默认显示（demo 的 TikTokRenderView 行为）
        vv.setRenderViewFactory(TikTokRenderViewFactory.create());
        mController = new TikTokController(this);
        vv.setVideoController(mController);
        // 锁死"不跟随设备传感器转屏"：本项目转屏由**屏幕方向 → 换流**来做，
        // 不允许播放器自己把画面转成横屏（那会绕过上面的分流逻辑）。
        mController.setEnableOrientation(false);

        // 播放器状态全量落日志（真机教训：拉流失败被静默吞掉=只显封面，无日志根本查不出）
        vv.addOnStateChangeListener(new VideoView.OnStateChangeListener() {
            @Override
            public void onPlayerStateChanged(int playerState) {
                com.laorendouyin.app.util.AppLog.i("feed", "播放器状态: " + playerStateName(playerState));
            }

            @Override
            public void onPlayStateChanged(int playState) {
                com.laorendouyin.app.util.AppLog.i("feed", "播放状态: " + playerStateName(playState));
                // 播不出来时，如果这一条走的是代理 → 降级直连（见 fallbackToDirectPlay）
                if (playState == VideoView.STATE_ERROR) {
                    // 两层降级依次试：
                    //   ① 走了代理却播不出来 → 删坏缓存改直连
                    //   ② 直连也播不出来（或本来就是直连）→ 换播放内核（IJK → 系统）
                    if (!fallbackToDirectPlay()) {
                        fallbackToNextKernel();
                    }
                }
            }
        });
        return vv;
    }

    /**
     * 播放失败的兜底降级（缓存链路的最后一层）。
     *
     * 触发条件：**这一条本来走的是代理**，且**还没降级过**。
     * 做法：删掉这条的缓存（既然播不出来，它就是坏的）、改用直连原始地址重播一次。
     *
     * 为什么必须有这一层：代理地址是本机 127.0.0.1 上的一个小服务，失败模式很多 ——
     * 缓存写到一半断了、源站拒绝续传、端口被系统回收、磁盘写满…… 这些都不是内容的问题，
     * 直连大概率能播。没有这一层，用户看到的就是"这个视频打不开"。
     *
     * ⚠️ 只降级一次：直连还失败就说明确实是内容/网络的问题，再试只会陷入死循环重播。
     */
    private boolean fallbackToDirectPlay() {
        if (!mPlayingViaProxy || mProxyFallbackTried || mPlayingRawUrl.isEmpty()) return false;
        // ⚠️ "必须走代理"的场景**不做这层降级**（2026-10-14 修「安卓 4.4 播不了」）：
        //    直连 https 对 IJK 是死路（它的 FFmpeg 没编 https 协议，必报 Protocol not found），
        //    对老系统的解码器同样无解 —— 这种"代理播不出来"是解码/内容问题，
        //    应该交给下一层「换播放内核」（fallbackToNextKernel），而不是改直连。
        if (com.laorendouyin.app.cache.PreloadManager.mustUseProxy()) return false;
        mProxyFallbackTried = true;
        com.laorendouyin.app.util.AppLog.i("feed", "代理播放失败 → 删掉坏缓存，重取播放地址");
        com.laorendouyin.app.cache.ProxyVideoCacheManager.evict(this, mPlayingRawUrl);
        try {
            mVideoView.release();
            // ⚠️ 不能硬编码直连 rawUrl：删完坏缓存后由 getPlayUrl 重新决定 ——
            //    非 mustProxy 场景缓存已不够门槛，它会自然给出直连（保持原语义）；
            //    mustProxy 场景则继续给代理（坏缓存已删，代理会重新下）。
            String retryUrl = mPreloadManager.getPlayUrl(mPlayingRawUrl);
            mPlayingViaProxy = !retryUrl.equals(mPlayingRawUrl);
            mVideoView.setUrl(retryUrl, com.laorendouyin.app.net.DouyinApi.playHeaders());
            mVideoView.start();
        } catch (Throwable e) {
            com.laorendouyin.app.util.AppLog.i("feed", "降级重播失败：" + e);
        }
        return true;
    }

    /**
     * 第二层兜底：**换播放内核重放**（IJK 播不出来 → 系统 MediaPlayer）。
     *
     * 实测触发场景：IJK 加载成功、绝大多数视频也能播，但个别会报
     * `IJK 播放出错 what=-10000` → 整条视频打不开。这类情况换系统内核往往就好了
     * —— 两个内核的解码路径完全不同，正好互补。
     *
     * 实现要点：VideoView 的播放器是**构造时**从工厂取的，所以换内核必须重建它。
     * 换完只把当前这条重放一遍，不打断用户其他操作。
     */
    private void fallbackToNextKernel() {
        if (mPlayingRawUrl.isEmpty()) return;
        // 已退到最后一个内核（系统 MediaPlayer）就没得退了 —— 再失败是内容/网络的问题
        if (com.laorendouyin.app.player.FallbackPlayerFactory.isLastKernel()) return;
        // ⚠️ 这里**不用**"每条只降一次"的闸门：三个内核最多降两次，
        //    isLastKernel() 本身就保证了有界，不需要额外的计数器。
        final String from = com.laorendouyin.app.player.FallbackPlayerFactory.currentKernelName();
        com.laorendouyin.app.player.FallbackPlayerFactory.fallbackToNext("播放出错");
        final String to = com.laorendouyin.app.player.FallbackPlayerFactory.currentKernelName();
        com.laorendouyin.app.util.AppLog.i("feed", "当前内核（" + from + "）播不出来 → 换 " + to + " 重放");

        try {
            // 记住播放器挂在哪个容器里
            android.view.ViewGroup container = null;
            if (mVideoView.getParent() instanceof android.view.ViewGroup) {
                container = (android.view.ViewGroup) mVideoView.getParent();
                container.removeView(mVideoView);
            }
            mVideoView.release();

            // 用新内核重建
            mVideoView = createVideoView();
            if (container != null) {
                container.addView(mVideoView, 0);
                // ⚠️ 换内核后必须**重新取播放地址**：这一条原来可能是直连 https
                //    （换到 IJK 就必挂：FFmpeg 未编 https）；反过来从 IJK 换到系统内核时，
                //    代理地址也依然可用。统一交给 getPlayUrl 决定。
                String replayUrl = mPreloadManager.getPlayUrl(mPlayingRawUrl);
                mPlayingViaProxy = !replayUrl.equals(mPlayingRawUrl);
                mProxyFallbackTried = false;   // 换了内核＝重新开始，允许新内核再走一次降级
                mVideoView.setUrl(replayUrl, com.laorendouyin.app.net.DouyinApi.playHeaders());
                mVideoView.start();
                com.laorendouyin.app.util.AppLog.i("feed", "已用 " + to + " 内核重放当前视频");
            } else {
                // 容器没了（页面被回收过）→ 走常规挂载流程
                startPlay(mCurPos);
            }
        } catch (Throwable e) {
            com.laorendouyin.app.util.AppLog.i("feed", "换内核重放失败：" + e);
        }
    }

    private static String playerStateName(int s) {
        switch (s) {
            case VideoView.STATE_IDLE: return "IDLE";
            case VideoView.STATE_PREPARING: return "PREPARING(缓冲中)";
            case VideoView.STATE_PREPARED: return "PREPARED";
            case VideoView.STATE_PLAYING: return "PLAYING(首帧已渲染)";
            case VideoView.STATE_PAUSED: return "PAUSED";
            case VideoView.STATE_BUFFERING: return "BUFFERING";
            case VideoView.STATE_BUFFERED: return "BUFFERED";
            case VideoView.STATE_PLAYBACK_COMPLETED: return "COMPLETED";
            case VideoView.STATE_ERROR: return "ERROR(播放失败)";
            default: return "state=" + s;
        }
    }

    private void initViewPager() {
        mViewPager = findViewById(R.id.vvp);
        mViewPager.setOffscreenPageLimit(3);
        mAdapter = new FeedAdapter(mVideoList);
        mViewPager.setAdapter(mAdapter);
        mViewPager.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mViewPager.setOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {

            private int mCurItem;
            private boolean mIsReverseScroll;

            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                if (position == mCurItem) {
                    return;
                }
                mIsReverseScroll = position < mCurItem;
            }

            @Override
            public void onPageSelected(final int position) {
                if (position == mCurPos) return;
                // 等 ViewPager populate 完成（新页实例化）再挂播放器
                mViewPager.post(new Runnable() {
                    @Override
                    public void run() {
                        startPlay(position);
                    }
                });
                // 快到底时预取下一页
                if (position >= mVideoList.size() - 2) {
                    loadMore(false);
                }
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                if (state == VerticalViewPager.SCROLL_STATE_DRAGGING) {
                    mCurItem = mViewPager.getCurrentItem();
                }
                if (state == VerticalViewPager.SCROLL_STATE_IDLE) {
                    mPreloadManager.resumePreload(mCurPos, mIsReverseScroll);
                } else {
                    mPreloadManager.pausePreload(mCurPos, mIsReverseScroll);
                }
            }
        });
    }

    private void startPlay(int position) {
        startPlay(position, 0);
    }

    /**
     * 挂载播放器到指定页。三个真机踩过的坑：
     * ① notifyDataSetChanged 会重建当前页视图、冲掉已挂的播放器 → 数据变更后必须重挂；
     * ② onPageSelected 时新页可能尚未实例化 → 找不到视图不能静默放弃，要重试；
     * ③ **转屏换流后要挂的还是同一个视频** → 只搬视图，绝不 release + setUrl
     *    （否则用户正在看的视频会从头缓冲一遍 —— 这是明确要求避免的）。
     */
    private void startPlay(final int position, final int attempt) {
        // 未同意使用须知前一律不播放（见 showDisclaimerThenPlay）
        if (!isDisclaimerAccepted()) return;
        if (position < 0 || position >= mVideoList.size()) return;
        int count = mViewPager.getChildCount();
        for (int i = 0; i < count; i++) {
            View itemView = mViewPager.getChildAt(i);
            Object tag = itemView.getTag();
            // ⚠️ 空列表期间 ViewPager 里可能挂着占位 View（没有 ViewHolder 标签）——
            //    硬转会 ClassCastException，直接跳过最稳。
            if (!(tag instanceof FeedAdapter.ViewHolder)) continue;
            FeedAdapter.ViewHolder viewHolder = (FeedAdapter.ViewHolder) tag;
            if (viewHolder.mPosition == position) {
                FeedVideo video = mVideoList.get(position);

                // ③ 转屏保活：要挂的就是当前正在播的那个 → 只搬视图，播放不中断
                if (isSameAsPlaying(video)) {
                    if (mVideoView.getParent() instanceof ViewGroup) {
                        ((ViewGroup) mVideoView.getParent()).removeView(mVideoView);
                    }
                    if (mVideoView.getParent() != viewHolder.mPlayerContainer) {
                        viewHolder.mPlayerContainer.addView(mVideoView, 0);
                    }
                    mController.addControlComponent(viewHolder.mTikTokView, true);
                    mCurPos = position;
                    com.laorendouyin.app.util.AppLog.i("feed", "转屏保活：沿用同一播放器，不重新加载 "
                            + video.awemeId);
                    return;
                }

                mVideoView.release();
                if (mVideoView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) mVideoView.getParent()).removeView(mVideoView);
                }

                // 播放即记账（真正的"看过"信号，喂给 SeenStore 做跨会话去重）
                if (video.awemeId != null && !video.awemeId.isEmpty()) {
                    SeenStore.markSeen(this, java.util.Collections.singletonList(video.awemeId));
                    // 给「看过」留一份可读记录（去重账本只存 id，显示要标题和封面）
                    HistoryStore.mark(this, video.awemeId, video.title, video.coverUrl);
                }
                // 记录"这一条是怎么播的"：播放失败时据此决定要不要降级
                String playUrl = mPreloadManager.getPlayUrl(video.playUrl);
                mPlayingRawUrl = video.playUrl;
                mPlayingViaProxy = !playUrl.equals(video.playUrl);
                mProxyFallbackTried = false;
                mVideoView.setUrl(playUrl, DouyinApi.playHeaders());
                mController.addControlComponent(viewHolder.mTikTokView, true);
                viewHolder.mPlayerContainer.addView(mVideoView, 0);
                mVideoView.start();
                mCurPos = position;
                mPlayingAwemeId = video.awemeId == null ? "" : video.awemeId;
                com.laorendouyin.app.util.AppLog.i("feed", "开始播放 #" + position
                        + (video.isLandscape() ? " [横屏" : " [竖屏")
                        + video.videoWidth + "x" + video.videoHeight + "]");
                return;
            }
        }
        // 页面视图还没实例化/刚被重建 → 延时重试
        if (attempt < 6) {
            com.laorendouyin.app.util.AppLog.i("feed", "页视图未就绪，重试挂载 #" + position + " (第" + attempt + "次)");
            mViewPager.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startPlay(position, attempt + 1);
                }
            }, 250);
        } else {
            com.laorendouyin.app.util.AppLog.i("feed", "挂载失败，放弃 #" + position);
        }
    }

    /**
     * 要挂的这条，是不是播放器当前正在放的那条？
     * 用 id 比对（换流时同一个视频可能已被重新解析成新对象，不能用引用比）。
     */
    private boolean isSameAsPlaying(FeedVideo video) {
        if (video == null || mPlayingAwemeId.isEmpty()) return false;
        if (video.awemeId == null || !mPlayingAwemeId.equals(video.awemeId)) return false;
        if (mVideoView == null) return false;
        // 播放器得真的还"有内容"，不能是刚被 release 过的空壳
        int st = mVideoView.getCurrentPlayState();
        return st == VideoView.STATE_PREPARING
                || st == VideoView.STATE_PREPARED
                || st == VideoView.STATE_PLAYING
                || st == VideoView.STATE_PAUSED
                || st == VideoView.STATE_BUFFERING
                || st == VideoView.STATE_BUFFERED;
    }

    /** 真冷启动：空列表 + 提示 */
    private void loadEmpty() {
        applyNewItems(new java.util.ArrayList<FeedVideo>(), false);
        showHint("正在连接网络，首次加载需要几秒…");
    }

    /**
     * 列表变更单一入口：过滤（方向不对 / 看过 / 重复）→ 入列 → 记账 → 缓存同步 → 通知。
     * ⚠️ 所有对 mVideoList 的批量改动必须走这里（真机教训：散落多处的列表改动
     * 难审计，4 次 IndexOutOfBounds 闪退即源于变更与回收的竞态）。
     *
     * @return 实际新增条数
     */
    private int applyNewItems(java.util.List<FeedVideo> incoming, boolean replace) {
        // 转屏保活：整批换血时，正在播的那条要放回列表首位（否则播放器被迫重载）
        final FeedVideo keep = replace ? mKeepVideo : null;
        final String keepId = keep == null ? null : keep.awemeId;

        // 先算后换（原子语义）：accepted 全部算妥才动列表——杜绝"清空后全被过滤=空屏"
        java.util.List<FeedVideo> accepted = new java.util.ArrayList<>();
        java.util.List<String> acceptedIds = new java.util.ArrayList<>();
        int droppedByOrientation = 0;
        for (FeedVideo v : incoming) {
            if (keepId != null && keepId.equals(v.awemeId)) continue;   // 已手动保留在首位，别重复
            // 方向过滤：竖屏模式只留竖屏视频，横屏模式只留横屏视频
            if (!v.matchesOrientation(mWantLandscape)) {
                droppedByOrientation++;
                continue;
            }
            if (SeenStore.isSeen(this, v.awemeId)) continue;
            boolean dup = false;
            // replace=整批换血，无需比对旧列表；append=连旧列表一起去重
            if (!replace) {
                for (FeedVideo old : mVideoList) {
                    if (old.awemeId != null && old.awemeId.equals(v.awemeId)) { dup = true; break; }
                }
            }
            if (!dup) {
                for (FeedVideo a : accepted) {
                    if (a.awemeId != null && a.awemeId.equals(v.awemeId)) { dup = true; break; }
                }
            }
            if (!dup) {
                accepted.add(v);
                acceptedIds.add(v.awemeId);
            }
        }
        if (droppedByOrientation > 0) {
            com.laorendouyin.app.util.AppLog.i("feed", "方向过滤：丢掉 " + droppedByOrientation + " 条"
                    + (mWantLandscape ? "非横屏" : "非竖屏") + "视频");
        }
        // ---- 保底：列表太短就把"看过的"放回来（2026-10-14）----
        //
        // 实测事故：SeenStore 记满 1200 条之后，抖音推来的 6 条里有 5 条被判"看过"，
        // 列表只剩 1 条 → ViewPager 无从滑动 → 用户直接看到"划不动了"。
        //
        // 权衡很清楚：**"偶尔看到几条重复的" 远好于 "划不动"** ——
        // 对老人来说后者等于"这个 App 坏了"。所以这里设一道下限，
        // 不足就从这一批里把看过的补回来（方向过滤仍然照守，不混进反方向的视频）。
        final int MIN_LIST_SIZE = 5;
        if (accepted.size() < MIN_LIST_SIZE) {
            int before = accepted.size();
            for (FeedVideo v : incoming) {
                if (accepted.size() >= MIN_LIST_SIZE) break;
                if (!v.matchesOrientation(mWantLandscape)) continue;
                boolean dup = false;
                for (FeedVideo a : accepted) {
                    if (a.awemeId != null && a.awemeId.equals(v.awemeId)) { dup = true; break; }
                }
                if (!dup) accepted.add(v);
            }
            if (accepted.size() > before) {
                com.laorendouyin.app.util.AppLog.i("feed", "新内容不足，补放 "
                        + (accepted.size() - before) + " 条看过的，避免列表过短划不动");
            }
        }

        if (replace && accepted.isEmpty() && keep == null) {
            // 整批都不合适且没有要保活的视频 → 保持现状不空屏，交由调用方补拉
            com.laorendouyin.app.util.AppLog.i("feed", "整批都不合适，保持现状待补拉");
            return 0;
        }
        if (replace) {
            mVideoList.clear();
            if (keep != null) {
                mVideoList.add(keep);
                // 用掉即弃：用户滑走后它不该还赖在首位
                mKeepVideo = null;
                com.laorendouyin.app.util.AppLog.i("feed", "换流保留在播视频 " + keep.awemeId);
            }
        }
        mVideoList.addAll(accepted);
        SeenStore.markSeen(this, acceptedIds);
        // 应用级缓存：重进页面直接接着放（只缓存当前方向的内容）
        if (!mVideoList.isEmpty()) {
            sCache.clear();
            sCache.addAll(mVideoList);
        }
        mAdapter.notifyDataSetChanged();
        return accepted.size();
    }

    /** 进页面垫场：有缓存=放上次刷到的视频（不闪老面孔）；真冷启动才走空列表 */
    private void loadCacheOrEmpty() {
        if (!sCache.isEmpty()) {
            // ⚠️ 缓存里的条目必须是**当前方向**的：横竖屏切换时会清缓存，
            //    但"从后台回来/重进页面"走的是缓存，方向没变，可以直接用。
            boolean allMatch = true;
            for (FeedVideo v : sCache) {
                if (!v.matchesOrientation(mWantLandscape)) { allMatch = false; break; }
            }
            if (!allMatch) {
                com.laorendouyin.app.util.AppLog.i("feed", "缓存方向与当前屏幕不符 → 丢弃缓存");
                sCache.clear();
                loadEmpty();
                return;
            }
            mVideoList.clear();
            mVideoList.addAll(sCache);
            mAdapter.notifyDataSetChanged();
            com.laorendouyin.app.util.AppLog.i("feed", "载入缓存 " + mVideoList.size() + " 条");
            return;
        }
        loadEmpty();
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        // 用 Charset.forName 而非 StandardCharsets：后者是 API 19 才有的类，Android 4.0–4.3 会崩
        return new String(bos.toByteArray(), Charset.forName("UTF-8"));
    }

    // ---------- 沉浸式全屏 ----------

    /**
     * 隐藏状态栏与导航栏，并用 **IMMERSIVE_STICKY** 降低系统手势误触。
     *
     * 为什么必须做：**横屏时"从下往上滑"正好是系统的「回到主屏」手势** ——
     * 用户想切下一条视频，系统却把他送回桌面，主观感受就是"划不动"（本机实测复现过：
     * 一次上滑直接让 App 进了后台，日志里是 PAUSED→PLAYING）。
     * 沉浸模式下，边缘/底部滑动**第一次只会唤出系统栏**，手势不生效 ——
     * 要再滑一次才会触发系统操作，这就把误触挡掉了。
     *
     * 顺带的好处：刷视频本来就该满屏，状态栏和导航栏纯属干扰。
     */
    private void hideSystemBars() {
        android.view.View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    /** 每次拿回焦点都重挂一次：沉浸态会被系统的弹窗/切后台清掉，需要自动恢复 */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    /** 刷视频要常亮：不然看着看着黑屏 */
    @Override
    protected boolean keepScreenOnWhileVisible() {
        return true;
    }

    // ---------- 顶部信息栏（时间 / 日期 / 星期 / 节日）----------

    /** 信息栏刷新用的定时器 */
    private android.os.Handler mClockHandler;
    private Runnable mClockTick;

    /**
     * 启动信息栏刷新。
     *
     * 为什么是 20 秒一轮而不是每秒：显示精度只到分钟（19:15），每秒刷是白耗电；
     * 而 20 秒这一档又保证了**跨分钟不超过 20 秒误差、跨天不超过 20 秒** ——
     * 老人看时间不会因为差这一秒觉得不对，但日期/星期必须当天就换过来。
     */
    private void startClock() {
        if (mClockHandler == null) {
            mClockHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        if (mClockTick == null) {
            mClockTick = new Runnable() {
                @Override
                public void run() {
                    updateClock();
                    if (mClockHandler != null) mClockHandler.postDelayed(this, 20_000L);
                }
            };
        }
        mClockHandler.removeCallbacks(mClockTick);
        mClockHandler.post(mClockTick);
    }

    private void stopClock() {
        if (mClockHandler != null && mClockTick != null) {
            mClockHandler.removeCallbacks(mClockTick);
        }
    }

    /** 刷新四样东西；节日为空时把那一行收起来（不占位、不留空壳） */
    private void updateClock() {
        android.widget.TextView tvTime = findViewById(R.id.tv_clock_time);
        if (tvTime == null) return;   // 布局里没有信息栏时静默跳过

        tvTime.setText(com.laorendouyin.app.util.DateTimeInfo.time());
        ((android.widget.TextView) findViewById(R.id.tv_clock_date))
                .setText(com.laorendouyin.app.util.DateTimeInfo.date());
        ((android.widget.TextView) findViewById(R.id.tv_clock_lunar))
                .setText(com.laorendouyin.app.util.DateTimeInfo.lunar());
        ((android.widget.TextView) findViewById(R.id.tv_clock_week))
                .setText(com.laorendouyin.app.util.DateTimeInfo.week());

        // 电量 + 预计可播放时长（星期上方）；读不到时整行收起
        android.widget.TextView tvBattery = findViewById(R.id.tv_clock_battery);
        String battery = batteryLine();
        if (battery.isEmpty()) {
            tvBattery.setVisibility(View.GONE);
        } else {
            tvBattery.setText(battery);
            tvBattery.setVisibility(View.VISIBLE);
        }

        android.widget.TextView tvFestival = findViewById(R.id.tv_clock_festival);
        String festival = com.laorendouyin.app.util.DateTimeInfo.festival();
        if (festival.isEmpty()) {
            tvFestival.setVisibility(View.GONE);
        } else {
            tvFestival.setText(festival);
            tvFestival.setVisibility(View.VISIBLE);
        }
    }

    /** 电量估算（只在前台采样，见 BatteryEstimator 类注释） */
    private final com.laorendouyin.app.util.BatteryEstimator mBattery =
            new com.laorendouyin.app.util.BatteryEstimator();

    /**
     * 读系统电量并返回信息栏文案；读不到返回空串。
     * 用粘性广播 ACTION_BATTERY_CHANGED 而不是 BATTERY_PROPERTY_CAPACITY：
     * 后者要 API 21+，本项目 minSdk 是 14。
     */
    private String batteryLine() {
        try {
            android.content.Intent i = registerReceiver(null,
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return "";
            int level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
            int status = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            if (level < 0 || scale <= 0) return "";
            int percent = level * 100 / scale;
            boolean charging =
                    status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                            || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
            return mBattery.update(android.os.SystemClock.elapsedRealtime(), percent, charging);
        } catch (Throwable t) {
            com.laorendouyin.app.util.AppLog.i("clock", "电量读取失败: " + t);
            return "";
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mVideoView != null) mVideoView.resume();
        // 预加载跟着页面可见性走：后台时整队停，回来时把没预热好的补齐
        if (mPreloadManager != null) mPreloadManager.resumeAll();
        // 回到前台立刻对一次表（后台期间可能已经跨分钟/跨天了）
        startClock();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // ⚠️ 原来只停了播放器：预加载队列不管，用户按 Home 走后还在继续下（每条最多 1MB）
        if (mPreloadManager != null) mPreloadManager.pauseAll();
        if (mVideoView != null) mVideoView.pause();
        // 后台没必要再刷时间
        stopClock();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mVideoView != null) mVideoView.release();
        if (mPreloadManager != null) mPreloadManager.removeAllPreloadTask();
        // 退出即清空视频缓存：播放用的临时片段不应在设备上留下副本（见 ProxyVideoCacheManager.clearAll）
        if (isFinishing()) com.laorendouyin.app.cache.ProxyVideoCacheManager.clearAll(this);
    }
}
