package com.laorendouyin.app.net;

// DouyinApi 里几个纯函数/解析边界的单测（离线，不联网）。
// 重点：JsonNull 边界（推荐流里混有图文贴，video 为 null）与**视频宽高解析**（横竖屏分流靠它）。
//
// shilaoVD 删减：评论时间格式化、作者头像相关用例随对应功能一起移除。

import com.laorendouyin.app.feed.FeedVideo;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DouyinApiParseTest {

    /** video 为 null（图文贴）不能让整批解析炸掉，应跳过这一条 */
    @Test
    public void parseFeed_skipsItemWithNullVideo() {
        String json = "{\"aweme_list\":["
                + "{\"aweme_id\":\"1\",\"desc\":\"图文贴\",\"video\":null,"
                + "\"author\":{\"nickname\":\"a\"}},"
                + "{\"aweme_id\":\"2\",\"desc\":\"正常视频\","
                + "\"video\":{\"play_addr\":{\"uri\":\"u\",\"url_list\":["
                + "\"https://www.douyin.com/aweme/v1/play/?x=1\"]}},"
                + "\"author\":{\"nickname\":\"b\"}}"
                + "]}";
        List<FeedVideo> list = DouyinApi.parseFeed(json);
        assertEquals("图文贴应被跳过，只留正常视频", 1, list.size());
        assertEquals("2", list.get(0).awemeId);
    }

    /** statistics 为 null 也不该影响解析 */
    @Test
    public void parseFeed_handlesNullStatistics() {
        String json = "{\"aweme_list\":[{\"aweme_id\":\"9\",\"desc\":\"d\","
                + "\"statistics\":null,"
                + "\"video\":{\"width\":1080,\"height\":1920,"
                + "\"play_addr\":{\"uri\":\"u\",\"url_list\":["
                + "\"https://www.douyin.com/aweme/v1/play/?x=1\"]}}}]}";
        List<FeedVideo> list = DouyinApi.parseFeed(json);
        assertEquals(1, list.size());
        assertTrue(list.get(0).playUrl.startsWith("https://www.douyin.com/"));
    }

    /** author 为 null 也不能炸 */
    @Test
    public void parseFeed_handlesNullAuthor() {
        String json = "{\"aweme_list\":[{\"aweme_id\":\"8\",\"desc\":\"d\","
                + "\"author\":null,"
                + "\"video\":{\"play_addr\":{\"uri\":\"u\",\"url_list\":["
                + "\"https://www.douyin.com/aweme/v1/play/?x=1\"]}}}]}";
        List<FeedVideo> list = DouyinApi.parseFeed(json);
        assertEquals(1, list.size());
        assertEquals("", list.get(0).authorName);
    }

    /** 整条 aweme_list 为 null 时返回空表而不是抛异常 */
    @Test
    public void parseFeed_handlesNullAwemeList() {
        List<FeedVideo> list = DouyinApi.parseFeed("{\"aweme_list\":null}");
        assertTrue(list.isEmpty());
    }

    /** 非 JSON 对象直接返回空表 */
    @Test
    public void parseFeed_handlesNonObject() {
        assertTrue(DouyinApi.parseFeed("[]").isEmpty());
    }

    /** 没有可播地址的条目应被丢弃（返回 null） */
    @Test
    public void parseFeed_skipsItemWithoutPlayUrl() {
        String json = "{\"aweme_list\":[{\"aweme_id\":\"7\",\"desc\":\"d\","
                + "\"video\":{\"play_addr\":{\"uri\":\"u\",\"url_list\":[]}}}]}";
        assertTrue(DouyinApi.parseFeed(json).isEmpty());
    }

    // ---- 视频宽高与方向判定（shilaoVD 的"横竖屏分流"全靠这两个）----

    /** 宽高必须解析出来，且横竖判定与 matchesOrientation 要一致 */
    @Test
    public void parseFeed_readsVideoSize_forOrientationSplit() {
        String json = "{\"aweme_list\":["
                + "{\"aweme_id\":\"a\",\"video\":{\"width\":1080,\"height\":1920,"
                + "\"play_addr\":{\"uri\":\"u1\",\"url_list\":[\"https://www.douyin.com/aweme/v1/play/?x=1\"]}}},"
                + "{\"aweme_id\":\"b\",\"video\":{\"width\":1920,\"height\":1080,"
                + "\"play_addr\":{\"uri\":\"u2\",\"url_list\":[\"https://www.douyin.com/aweme/v1/play/?x=2\"]}}}"
                + "]}";
        List<FeedVideo> list = DouyinApi.parseFeed(json);
        assertEquals(2, list.size());

        FeedVideo portrait = list.get(0);
        assertEquals(1080, portrait.videoWidth);
        assertEquals(1920, portrait.videoHeight);
        assertFalse("1080x1920 是竖屏", portrait.isLandscape());
        assertTrue("竖屏模式应放行", portrait.matchesOrientation(false));
        assertFalse("横屏模式应拦掉", portrait.matchesOrientation(true));

        FeedVideo landscape = list.get(1);
        assertEquals(1920, landscape.videoWidth);
        assertEquals(1080, landscape.videoHeight);
        assertTrue("1920x1080 是横屏", landscape.isLandscape());
        assertTrue("横屏模式应放行", landscape.matchesOrientation(true));
        assertFalse("竖屏模式应拦掉", landscape.matchesOrientation(false));
    }

    /** 尺寸缺失时按竖屏处理：竖屏模式放行、横屏模式拦掉（宁缺毋滥，避免横屏模式混进竖屏内容） */
    @Test
    public void parseFeed_missingSizeTreatedAsPortrait() {
        String json = "{\"aweme_list\":[{\"aweme_id\":\"c\","
                + "\"video\":{\"play_addr\":{\"uri\":\"u\",\"url_list\":["
                + "\"https://www.douyin.com/aweme/v1/play/?x=1\"]}}}]}";
        List<FeedVideo> list = DouyinApi.parseFeed(json);
        assertEquals(1, list.size());
        assertEquals(0, list.get(0).videoWidth);
        assertFalse(list.get(0).isLandscape());
        assertTrue("竖屏模式放行", list.get(0).matchesOrientation(false));
        assertFalse("横屏模式拦掉", list.get(0).matchesOrientation(true));
    }

    // ---- 封面 ----
    //
    // ⚠️ 断言必须能证伪：只断言 coverUrl.startsWith("http") 是**不合格的** ——
    //    bug 版本拼出来的 https://www.douyin.com/aweme/v1/play/?video_id=<封面uri> 同样以 http 开头，
    //    于是 bug 在测试里完全隐身（真机上表现为封面永远黑屏）。
    //    这里改用"宿主必须是图床、且绝不能是播放端点"来判定。

    /** 封面：必须是图床直链，不能是播放端点（urlFromAddr 误用于图片的回归） */
    @Test
    public void parseFeed_coverIsImageUrl_notPlayEndpoint() {
        String json = "{\"aweme_list\":[{\"aweme_id\":\"11\",\"desc\":\"d\","
                + "\"video\":{\"cover\":{\"uri\":\"image-cut-tos-priv/abc\",\"url_list\":["
                + "\"https://p9-pc-sign.douyinpic.com/image-cut-tos-priv/abc~tplv.jpeg?x=1\"]},"
                + "\"play_addr\":{\"uri\":\"v0300\",\"url_list\":["
                + "\"https://www.douyin.com/aweme/v1/play/?video_id=v0300\"]}}}]}";
        List<FeedVideo> list = DouyinApi.parseFeed(json);
        assertEquals(1, list.size());
        String cover = list.get(0).coverUrl;
        assertTrue("封面应取图床直链，实际: " + cover, cover.contains("douyinpic.com"));
        assertFalse("封面绝不能是播放端点（拿播放端点当图床 = 永远黑屏）: " + cover,
                cover.contains("/aweme/v1/play/"));
        // 播放地址不受影响，仍走播放端点
        assertTrue("playUrl 应保持跳转式播放地址", list.get(0).playUrl.contains("/aweme/v1/play/"));
    }

    /** 封面 url_list 为空时返回空串，而不是拿 uri 去拼一个假图片地址 */
    @Test
    public void parseFeed_emptyCoverListYieldsEmpty_notFabricated() {
        String json = "{\"aweme_list\":[{\"aweme_id\":\"12\",\"desc\":\"d\","
                + "\"video\":{\"cover\":{\"uri\":\"image-cut-tos-priv/abc\",\"url_list\":[]},"
                + "\"play_addr\":{\"uri\":\"v0300\",\"url_list\":["
                + "\"https://www.douyin.com/aweme/v1/play/?video_id=v0300\"]}}}]}";
        List<FeedVideo> list = DouyinApi.parseFeed(json);
        assertEquals(1, list.size());
        assertEquals("封面列表为空就该是空串", "", list.get(0).coverUrl);
    }

    /** imageUrl 的安全边界：url_list 里混 JsonNull / 非 http 项时要跳过，不能抛 */
    @Test
    public void imageUrl_skipsNullAndNonHttpEntries() {
        com.google.gson.JsonObject holder = com.google.gson.JsonParser.parseString(
                "{\"cover\":{\"url_list\":[null,\"\",\"ftp://x\","
                        + "\"https://p1.douyinpic.com/ok.jpeg\"]}}").getAsJsonObject();
        assertEquals("https://p1.douyinpic.com/ok.jpeg", DouyinApi.imageUrl(holder, "cover"));
        // 字段不存在 / 为 null / 空列表 → 一律空串
        assertEquals("", DouyinApi.imageUrl(holder, "nope"));
        assertEquals("", DouyinApi.imageUrl(
                com.google.gson.JsonParser.parseString("{\"cover\":null}").getAsJsonObject(), "cover"));
        assertEquals("", DouyinApi.imageUrl(
                com.google.gson.JsonParser.parseString("{\"cover\":{\"url_list\":[]}}").getAsJsonObject(), "cover"));
    }
}
