package com.laorendouyin.app.feed;

/**
 * Feed 视频模型（myDVQ 极简版）。
 *
 * 只保留「播一条视频」真正需要的字段：标题、播放地址、封面、id、作者名，以及**视频宽高**。
 *
 * 宽高是后加的关键字段：本项目要按屏幕方向分流 ——
 *   竖屏时只刷竖屏视频、横屏时只刷横屏视频，判定依据就是 video.width / video.height。
 *
 * 已随交互一起移除的字段（原基座「腕上音符」里有）：
 *   authorSecUid / authorAvatar —— 进作者主页、画头像，本项目都没有
 *   liked / collected —— 点赞收藏的本地乐观状态，本项目没有这两个操作
 *   diggCount / commentCount / collectCount —— 计数在画面上根本不显示
 *   formatCount() —— 把计数格式化成"1.2万"，没人用
 *   Serializable —— 原为「我的」页把列表塞进 Intent 传给播放页，那条路径已删
 */
public class FeedVideo {

    /** 抖音 CDN 认的 Referer */
    public static final String REFERER_DOUYIN = "https://www.douyin.com/";
    /** B 站 CDN 认的 Referer（与抖音**互不通用**：交叉必 403） */
    public static final String REFERER_BILIBILI = "https://www.bilibili.com/";

    public final String title;
    public final String playUrl;
    public final String coverUrl;
    public final String awemeId;
    public final String authorName;
    /** 视频宽（接口 video.width），<=0 表示接口没给 */
    public final int videoWidth;
    /** 视频高（接口 video.height），<=0 表示接口没给 */
    public final int videoHeight;
    /**
     * 播放这条视频时**必须带的 Referer**。
     *
     * 为什么要跟着视频走：抖音 CDN 与 B 站 CDN **互不认对方的 Referer** ——
     * 实测（2026-10-15）B 站直链带抖音 Referer 或空 Referer 一律 403，只有 bilibili.com 才给 206。
     * 所以这个值由各自的接口类在解析时填好，播放时原样带上。
     */
    public final String referer;

    public FeedVideo(String title, String playUrl, String coverUrl, String awemeId,
                     String authorName, int videoWidth, int videoHeight) {
        this(title, playUrl, coverUrl, awemeId, authorName, videoWidth, videoHeight, REFERER_DOUYIN);
    }

    public FeedVideo(String title, String playUrl, String coverUrl, String awemeId,
                     String authorName, int videoWidth, int videoHeight, String referer) {
        this.title = title == null ? "" : title;
        this.playUrl = playUrl == null ? "" : playUrl;
        this.coverUrl = coverUrl == null ? "" : coverUrl;
        this.awemeId = awemeId == null ? "" : awemeId;
        this.authorName = authorName == null ? "" : authorName;
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.referer = (referer == null || referer.isEmpty()) ? REFERER_DOUYIN : referer;
    }

    /** 是否横屏视频（宽 > 高）。尺寸缺失时按竖屏算（推荐流里缺尺寸的绝大多数是竖屏内容） */
    public boolean isLandscape() {
        if (videoWidth <= 0 || videoHeight <= 0) return false;
        return videoWidth > videoHeight;
    }

    /**
     * 这条视频是否属于"想要的方向"的流。
     *
     * 尺寸缺失时按竖屏处理：竖屏模式放行（不漏内容），横屏模式拦掉（宁缺毋滥 ——
     * 横屏模式下混进竖屏视频，观感上就是"转屏没生效"）。
     */
    public boolean matchesOrientation(boolean wantLandscape) {
        return isLandscape() == wantLandscape;
    }
}
