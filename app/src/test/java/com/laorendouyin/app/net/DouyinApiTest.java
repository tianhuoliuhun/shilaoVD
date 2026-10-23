package com.laorendouyin.app.net;

// DouyinApi 纯函数单测（离线）：解析真实接口响应样本 + query 构造含签名 + **两个方向的参数差异**。
// 联网冒烟测试见 FeedFetchTest（默认跳过，-Dnetwork.test=true 时执行）。
//
// shilaoVD 删减：作者头像相关断言随"头像不再显示"一起移除。

import com.laorendouyin.app.feed.FeedVideo;
import com.laorendouyin.app.sign.Signer;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DouyinApiTest {

    private static Signer newSigner() throws Exception {
        File dir = new File("src/main/assets/sign");
        List<String> js = Arrays.asList(
                read(new File(dir, "utils.js")),
                read(new File(dir, "sm3.js")),
                read(new File(dir, "vm_decode.js")));
        return new Signer(js);
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void parseFeed_realResponseSample() throws Exception {
        File f = new File("src/test/resources/feed_sample.json");
        assertTrue("缺少测试样本: " + f.getAbsolutePath(), f.exists());
        List<FeedVideo> list = DouyinApi.parseFeed(read(f));
        assertFalse("样本应解析出视频", list.isEmpty());
        FeedVideo v = list.get(0);
        assertNotNull(v.playUrl);
        assertTrue("playUrl 异常: " + v.playUrl, v.playUrl.startsWith("http"));
        assertTrue("coverUrl 异常: " + v.coverUrl, v.coverUrl.startsWith("http"));

        // 真实样本里必须带得上宽高（横竖屏分流的判据；缺了就只能全按竖屏兜底）
        assertTrue("样本应解析出 video.width/height: " + v.videoWidth + "x" + v.videoHeight,
                v.videoWidth > 0 && v.videoHeight > 0);

        // ⚠️ 只断言 startsWith("http") 是不合格的：封面 bug 版本拼出的
        // https://www.douyin.com/aweme/v1/play/?video_id=<封面uri> 同样以 http 开头，
        // 于是 bug 在测里完全隐身（真机上表现为封面永远黑屏）。
        int withCover = 0;
        for (FeedVideo it : list) {
            if (!it.coverUrl.isEmpty()) {
                withCover++;
                assertFalse("封面不能是播放端点: " + it.coverUrl, it.coverUrl.contains("/aweme/v1/play/"));
                assertTrue("封面应来自图床: " + it.coverUrl, it.coverUrl.contains("douyinpic.com"));
            }
        }
        assertTrue("真实样本里应至少有 1 条带封面（否则解析其实没生效）", withCover > 0);
    }

    @Test
    public void buildFeedQuery_containsSignature() throws Exception {
        String q = DouyinApi.buildFeedQuery(10, newSigner());
        assertTrue("缺 a_bogus: " + q, q.contains("&a_bogus="));
        assertTrue("缺 count", q.contains("count=10"));
        assertTrue("缺设备参数", q.contains("device_platform=webapp"));
    }

    /**
     * 两个方向必须用**不同**的 aid / channel / device_platform ——
     * 实测这三件套决定抖音给什么方向的流（PC 那套 → 93% 横屏；移动端那套 → 75% 竖屏）。
     * 这条测试把"两套参数确实不同"钉死，免得以后有人"顺手统一一下参数"就把分流搞坏。
     */
    @Test
    public void buildFeedQuery_encodesFeedMode() throws Exception {
        Signer signer = newSigner();
        String land = DouyinApi.buildFeedQuery(10, 1, DouyinApi.FeedMode.LANDSCAPE, signer);
        String port = DouyinApi.buildFeedQuery(10, 1, DouyinApi.FeedMode.PORTRAIT, signer);
        // 横屏流：PC web 那套
        assertTrue("横屏流 aid=6383，实际: " + land, land.contains("aid=6383"));
        assertTrue("横屏流 channel=channel_pc_web", land.contains("channel=channel_pc_web"));
        assertTrue("横屏流 device_platform=webapp", land.contains("device_platform=webapp"));
        // 竖屏流：移动端那套
        assertTrue("竖屏流 aid=1128，实际: " + port, port.contains("aid=1128"));
        assertTrue("竖屏流 channel=channel_web_mobile", port.contains("channel=channel_web_mobile"));
        assertTrue("竖屏流 device_platform=android", port.contains("device_platform=android"));
        // 两个方向都得带签名，且 query 不能是同一个
        assertTrue("两个方向都必须签名", land.contains("&a_bogus=") && port.contains("&a_bogus="));
        assertNotEquals("两个方向的 query 不能相同", land, port);
    }

    /**
     * 选档：H.264 优先、同档取最低码率；HEVC 一律不选（低端 SoC 无硬解）。
     *
     * shilaoVD 的默认画质档是 1（平衡 = 720p 优先，原基座手表端是 0 = 540p）。
     * 本条用例显式设定档位，不依赖全局默认值。
     */
    @Test
    public void pickGearUrl_prefersH264_lowestBitrateAtDefaultQuality() {
        String json = "{"
                + "\"bit_rate\":["
                + gear("normal_1080_0", 1993040, 0, 0, "g1080") + ","
                + gear("normal_540_0", 900000, 1, 0, "g540hevc") + ","
                + gear("low_540_0", 1127383, 0, 0, "g540low") + ","
                + gear("normal_540_0", 1683402, 0, 0, "g540hi") + ","
                + gear("normal_720_0", 1332178, 0, 0, "g720")
                + "]}";
        com.google.gson.JsonObject video =
                com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        int saved = DouyinApi.sQuality;
        try {
            DouyinApi.sQuality = 1;   // 平衡档：720p 优先
            String url = DouyinApi.pickGearUrl(video, "7412");
            assertTrue("平衡档应优先 720p，实际: " + url, url.contains("g720"));
            assertFalse("不该选 HEVC 档: " + url, url.contains("g540hevc"));
            assertFalse("不该选 1080p: " + url, url.contains("g1080"));

            DouyinApi.sQuality = 0;   // 省流量档：540p 优先
            String url540 = DouyinApi.pickGearUrl(video, "7412");
            assertTrue("省流量档应选 H.264 的 540p 档，实际: " + url540, url540.contains("g540low"));
            assertFalse("同档应取更低码率: " + url540, url540.contains("g540hi"));
            assertFalse("不该选 HEVC 档: " + url540, url540.contains("g540hevc"));
        } finally {
            DouyinApi.sQuality = saved;
        }
    }

    /** 没有 H.264 档时不能硬凑，返回空让调用方回退默认 play_addr */
    @Test
    public void pickGearUrl_returnsEmptyWhenOnlyHevc() {
        String json = "{\"bit_rate\":[" + gear("normal_540_0", 900000, 1, 0, "a") + ","
                + gear("normal_1080_0", 900000, 0, 1, "b") + "]}";
        com.google.gson.JsonObject video =
                com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        assertTrue(DouyinApi.pickGearUrl(video, "7412").isEmpty());
    }

    /** is_bytevc1 是字节的 HEVC 变体，同样要排除 */
    @Test
    public void pickGearUrl_excludesBytevc1() {
        String json = "{\"bit_rate\":[" + gear("normal_540_0", 1000, 0, 1, "bytevc") + "]}";
        com.google.gson.JsonObject video =
                com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        assertTrue(DouyinApi.pickGearUrl(video, "7412").isEmpty());
    }

    /**
     * DASH 档是单轨流（站点另发请求配音频），MediaPlayer 播它会没声音 →
     * 即使档位更优也必须跳过，退而选非 DASH 的那一档。
     */
    @Test
    public void pickGearUrl_skipsDashGearToKeepAudio() {
        String json = "{\"bit_rate\":["
                + dashGear("normal_540_0", 500000, "dash540") + ","   // 更优档，但是 DASH
                + gear("normal_720_0", 1300000, 0, 0, "mp4720")       // 次优但带音轨
                + "]}";
        com.google.gson.JsonObject video =
                com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        String url = DouyinApi.pickGearUrl(video, "7412");
        assertTrue("应避开 DASH 单轨档，实际: " + url, url.contains("mp4720"));
        assertFalse("不能返回 DASH 地址（会没声音）: " + url, url.contains("dash540"));
    }

    /** 全是 DASH 时宁可返回空，让上层回退默认 play_addr，也不能给用户无声视频 */
    @Test
    public void pickGearUrl_returnsEmptyWhenAllDash() {
        String json = "{\"bit_rate\":[" + dashGear("normal_540_0", 500000, "a") + ","
                + dashGear("normal_720_0", 900000, "b") + "]}";
        com.google.gson.JsonObject video =
                com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        assertTrue(DouyinApi.pickGearUrl(video, "7412").isEmpty());
    }

    /** 画质开关要真的改变选档结果：省流量走 540p，清晰档才允许 1080p */
    @Test
    public void pickGearUrl_respectsQualitySetting() {
        String json = "{\"bit_rate\":["
                + gear("normal_1080_0", 1993040, 0, 0, "g1080") + ","
                + gear("normal_720_0", 1332178, 0, 0, "g720") + ","
                + gear("adapt_low_540_0", 478000, 0, 0, "g540")
                + "]}";
        com.google.gson.JsonObject video =
                com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        int saved = DouyinApi.sQuality;
        try {
            DouyinApi.sQuality = 0;   // 省流量
            assertTrue(DouyinApi.pickGearUrl(video, "7412").contains("g540"));
            DouyinApi.sQuality = 1;   // 平衡
            assertTrue(DouyinApi.pickGearUrl(video, "7412").contains("g720"));
            DouyinApi.sQuality = 2;   // 清晰
            assertTrue(DouyinApi.pickGearUrl(video, "7412").contains("g1080"));
        } finally {
            DouyinApi.sQuality = saved;
        }
    }

    private static String gear(String name, long br, int h265, int bytevc1, String marker) {
        return "{\"gear_name\":\"" + name + "\",\"bit_rate\":" + br
                + ",\"is_h265\":" + h265 + ",\"is_bytevc1\":" + bytevc1
                + ",\"play_addr\":{\"uri\":\"v0200fg10000d98f4jno\",\"url_list\":["
                + "\"https://v11-weba.douyinvod.com/x/" + marker + "\","
                + "\"https://www.douyin.com/aweme/v1/play/?file_id=" + marker + "&sign=abc\"]}}";
    }

    /** DASH 档：www 候选路径是 /aweme/v1/play/dash/，单轨无音频 */
    private static String dashGear(String name, long br, String marker) {
        return "{\"gear_name\":\"" + name + "\",\"bit_rate\":" + br
                + ",\"is_h265\":0,\"is_bytevc1\":0"
                + ",\"play_addr\":{\"uri\":\"v0200fg10000d98f4jno\",\"url_list\":["
                + "\"https://v11-weba.douyinvod.com/x/" + marker + "\","
                + "\"https://www.douyin.com/aweme/v1/play/dash/?file_id=" + marker + "&sign=abc\"]}}";
    }

    /**
     * `related`（相关视频）**不是频道** —— 它必须走 aweme/related 端点。
     * 漏了这条判断，它就会被拼上 `tag_id=related` 掉进 channel/feed，白拿一个空结果。
     */
    @Test
    public void relatedSource_isNotTreatedAsChannel() {
        assertTrue("频道 tag_id 应判为频道", DouyinApi.isChannelSource("300204"));
        assertFalse("推荐流不是频道", DouyinApi.isChannelSource("recommend"));
        assertFalse("related 不是频道", DouyinApi.isChannelSource(DouyinApi.SRC_RELATED));
    }

    /** 相关视频 query：必须带种子 id、翻页游标与签名 */
    @Test
    public void buildRelatedQuery_carriesSeedAndSignature() throws Exception {
        String q = DouyinApi.buildRelatedQuery("7666778335199989034", 10, 2, newSigner());
        assertTrue("缺 aweme_id: " + q, q.contains("aweme_id=7666778335199989034"));
        assertTrue("缺 filterGid: " + q, q.contains("filterGid=7666778335199989034"));
        assertTrue("缺 count: " + q, q.contains("count=10"));
        assertTrue("缺 refresh_index: " + q, q.contains("refresh_index=2"));
        assertTrue("缺签名: " + q, q.contains("&a_bogus="));
    }

    /**
     * 种子只挑与当前方向一致的视频；一条都不符合时**保留旧种子**
     * （不能清空 —— 否则横竖来回切换会反复把种子弄丢，related 就永久失效了）。
     */
    @Test
    public void rememberSeed_picksSameOrientationAndKeepsOldOnMismatch() {
        List<FeedVideo> mixed = Arrays.asList(
                new FeedVideo("横", "http://a", "", "id-land", "", 1920, 1080),
                new FeedVideo("竖", "http://b", "", "id-portrait", "", 1080, 1920));

        DouyinApi.rememberSeed(DouyinApi.FeedMode.LANDSCAPE, mixed);
        assertEquals("id-land", DouyinApi.seedFor(DouyinApi.FeedMode.LANDSCAPE));

        DouyinApi.rememberSeed(DouyinApi.FeedMode.PORTRAIT, mixed);
        assertEquals("id-portrait", DouyinApi.seedFor(DouyinApi.FeedMode.PORTRAIT));

        // 全是反方向的：旧种子必须留着
        DouyinApi.rememberSeed(DouyinApi.FeedMode.LANDSCAPE,
                Arrays.asList(new FeedVideo("竖", "http://c", "", "id-p2", "", 1080, 1920)));
        assertEquals("id-land", DouyinApi.seedFor(DouyinApi.FeedMode.LANDSCAPE));
    }
}
