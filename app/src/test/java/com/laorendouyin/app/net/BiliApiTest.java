package com.laorendouyin.app.net;

import com.laorendouyin.app.feed.FeedVideo;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B 站内容源的纯函数单测（离线）。
 *
 * 重点盯住两条"错了就静默播不出来"的规则：
 *   ① 播放地址只能取 **durl 单文件**，绝不能退回 dash —— dash 是纯视频轨，播出来没声音；
 *   ② **Referer 必须按源区分** —— 抖音与 B 站 CDN 互不认对方的 Referer（实测交叉必 403）。
 */
public class BiliApiTest {

    /** 热门列表样本（字段名照抄真实响应 + 一条缺 bvid 的脏数据） */
    private static final String POPULAR_JSON = "{"
            + "\"code\":0,\"data\":{\"list\":["
            + "{\"bvid\":\"BV1VVpo6gEYg\",\"cid\":42682355579,\"title\":\"测试视频一\","
            + "\"pic\":\"http://i2.hdslb.com/bfs/archive/a.jpg\","
            + "\"dimension\":{\"width\":3840,\"height\":2160},"
            + "\"owner\":{\"name\":\"UP主甲\"}},"
            + "{\"bvid\":\"BV1test2\",\"cid\":12345,\"title\":\"测试视频二\","
            + "\"pic\":\"https://i0.hdslb.com/bfs/archive/b.jpg\","
            + "\"dimension\":{\"width\":1080,\"height\":1920},"
            + "\"owner\":{\"name\":\"UP主乙\"}},"
            + "{\"bvid\":\"\",\"cid\":999,\"title\":\"没有 bvid 的脏数据\"}"
            + "]}}";

    @Test
    public void parseRankingLike_readsFieldsAndSkipsBrokenRows() {
        List<BiliApi.Entry> list = BiliApi.parseRankingLike(POPULAR_JSON);
        assertEquals("缺 bvid 的行必须丢掉", 2, list.size());

        BiliApi.Entry a = list.get(0);
        assertEquals("BV1VVpo6gEYg", a.bvid);
        assertEquals(42682355579L, a.cid);
        assertEquals("测试视频一", a.title);
        assertEquals("UP主甲", a.authorName);
        assertEquals(3840, a.width);
        assertEquals(2160, a.height);
        assertTrue("封面 http 必须升成 https: " + a.cover, a.cover.startsWith("https://"));
    }

    /** durl 单文件直链正常取出 */
    @Test
    public void pickDurl_takesSingleFileUrl() {
        String json = "{\"code\":0,\"data\":{\"quality\":64,\"durl\":["
                + "{\"order\":1,\"size\":11706808,\"url\":\"https://x.mountaintoys.cn:4483/a.mp4?e=1\"}]}}";
        assertEquals("https://x.mountaintoys.cn:4483/a.mp4?e=1", BiliApi.pickDurl(json));
    }

    /**
     * durl 里 url 不是 http 开头（脏数据）时返回空，宁可不播也不给坏地址
     */
    @Test
    public void pickDurl_rejectsNonHttpUrl() {
        String json = "{\"code\":0,\"data\":{\"durl\":[{\"url\":\"\"}]}}";
        assertTrue(BiliApi.pickDurl(json).isEmpty());
        assertTrue(BiliApi.pickDurl("{\"data\":{}}").isEmpty());
        assertTrue(BiliApi.pickDurl("garbage").isEmpty());
    }

    /**
     * 只有 dash（音视频分离）时必须返回空 —— 绝不能"退而求其次"拿 dash 的视频轨地址，
     * 那样播出来是**没有声音**的，对老人等于坏视频。
     */
    @Test
    public void pickDurl_returnsEmptyWhenOnlyDash() {
        String json = "{\"code\":0,\"data\":{\"quality\":32,\"durl\":[],"
                + "\"dash\":{\"video\":[{\"baseUrl\":\"https://v.bilivideo.com/video.m4s\"}],"
                + "\"audio\":[{\"baseUrl\":\"https://v.bilivideo.com/audio.m4s\"}]}}}";
        assertTrue("只有 dash 时必须返回空: " + BiliApi.pickDurl(json), BiliApi.pickDurl(json).isEmpty());
    }

    @Test
    public void normalizeCover_upgradesHttpOnly() {
        assertEquals("https://a/b.jpg", BiliApi.normalizeCover("http://a/b.jpg"));
        assertEquals("https://a/b.jpg", BiliApi.normalizeCover("https://a/b.jpg"));
        assertEquals("", BiliApi.normalizeCover(""));
        assertEquals("", BiliApi.normalizeCover(null));
    }

    /**
     * 🔴 Referer 必须按源区分：B 站域名给 bilibili、其余给抖音。
     */
    @Test
    public void refererForUrl_detectsBilibiliDomains() {
        String[] bili = {
                "https://809al93l.edge.mountaintoys.cn:4483/upgcxcode/x.mp4",
                "https://xy.mcdn.bilivideo.cn:8082/v1/resource/x.m4s",
                "http://i0.hdslb.com/bfs/archive/a.jpg",
                "https://upos-sz-mirrorcos.bilivideo.com/x.mp4",
                "https://api.bilibili.com/x/web-interface/popular",
        };
        for (String u : bili) {
            assertEquals("应判为 B 站: " + u, FeedVideo.REFERER_BILIBILI, DouyinApi.refererForUrl(u));
        }
        String[] douyin = {
                "https://v11-weba.douyinvod.com/x/video.mp4",
                "https://p3-pc-sign.douyinpic.com/image/a.jpeg",
                "https://www.douyin.com/aweme/v1/play/?video_id=x",
        };
        for (String u : douyin) {
            assertEquals("应判为抖音: " + u, FeedVideo.REFERER_DOUYIN, DouyinApi.refererForUrl(u));
        }
        assertEquals("空 URL 兜底抖音", FeedVideo.REFERER_DOUYIN, DouyinApi.refererForUrl(""));
        assertEquals("null 兜底抖音", FeedVideo.REFERER_DOUYIN, DouyinApi.refererForUrl(null));
    }

    /** 播放头真的带上对应 Referer（漏了就是 403） */
    @Test
    public void playHeaders_usesGivenReferer() {
        Map<String, String> b = DouyinApi.playHeaders(FeedVideo.REFERER_BILIBILI);
        assertEquals(FeedVideo.REFERER_BILIBILI, b.get("Referer"));
        Map<String, String> d = DouyinApi.playHeaders();
        assertEquals(FeedVideo.REFERER_DOUYIN, d.get("Referer"));
        Map<String, String> e = DouyinApi.playHeaders("");
        assertEquals("空 referer 兜底抖音", FeedVideo.REFERER_DOUYIN, e.get("Referer"));
    }

    /** bili 不是频道 tag_id —— 漏了会被拼上假 tag_id 掉进 channel/feed */
    @Test
    public void biliSource_isNotTreatedAsChannel() {
        assertFalse(DouyinApi.isChannelSource(DouyinApi.SRC_BILI));
        assertFalse(DouyinApi.isChannelSource(DouyinApi.SRC_RELATED));
        assertFalse(DouyinApi.isChannelSource("recommend"));
        assertTrue(DouyinApi.isChannelSource("300204"));
    }

    /** B 站条目必须自带 B 站 Referer（抖音条目则带抖音的） */
    @Test
    public void feedVideo_carriesItsOwnReferer() {
        FeedVideo bili = new FeedVideo("t", "http://x/a.mp4", "", "BV1", "up", 1920, 1080,
                FeedVideo.REFERER_BILIBILI);
        assertEquals(FeedVideo.REFERER_BILIBILI, bili.referer);

        FeedVideo douyin = new FeedVideo("t", "http://x/a.mp4", "", "123", "up", 1920, 1080);
        assertEquals("旧构造默认抖音", FeedVideo.REFERER_DOUYIN, douyin.referer);

        FeedVideo blank = new FeedVideo("t", "http://x/a.mp4", "", "123", "up", 1920, 1080, "");
        assertEquals("空 referer 兜底抖音", FeedVideo.REFERER_DOUYIN, blank.referer);
    }

    // ==================== 四种源名 ====================

    /**
     * 源名拆解：bili 本身是热门；三段式里**第三段整体当参数**（允许关键词自己带冒号），
     * 空段要有兜底。
     */
    @Test
    public void splitSource_parsesAllFourForms() {
        assertArrayEquals(new String[]{"popular", ""}, BiliApi.splitSource("bili"));
        assertArrayEquals(new String[]{"region", "211"}, BiliApi.splitSource("bili:region:211"));
        assertArrayEquals(new String[]{"search", "广场舞"}, BiliApi.splitSource("bili:search:广场舞"));
        assertArrayEquals(new String[]{"tag", "23831577"}, BiliApi.splitSource("bili:tag:23831577"));
        // 关键词自己带冒号 → 只有前两段参与拆分
        assertArrayEquals(new String[]{"search", "abc:def"}, BiliApi.splitSource("bili:search:abc:def"));
        // 容错
        assertArrayEquals(new String[]{"popular", ""}, BiliApi.splitSource("bili:"));
        assertArrayEquals(new String[]{"popular", ""}, BiliApi.splitSource(null));
    }

    // ==================== 三种列表解析 ====================

    /** 热门 / 分区排行：列表在 data.list，带 cid 与宽高 */
    @Test
    public void parseRankingLike_readsCidAndDimension() {
        String json = "{\"code\":0,\"data\":{\"list\":["
                + "{\"bvid\":\"BV1JhZcY9EFN\",\"aid\":114245006003082,\"cid\":29139667446,"
                + "\"title\":\"分区样本\",\"pic\":\"http://i0.hdslb.com/a.jpg\","
                + "\"dimension\":{\"width\":1080,\"height\":1920},\"owner\":{\"name\":\"UP甲\"}}"
                + "]}}";
        List<BiliApi.Entry> l = BiliApi.parseRankingLike(json);
        assertEquals(1, l.size());
        BiliApi.Entry e = l.get(0);
        assertEquals("BV1JhZcY9EFN", e.bvid);
        assertEquals(114245006003082L, e.aid);
        assertEquals(29139667446L, e.cid);
        assertEquals(1080, e.width);
        assertEquals(1920, e.height);
        assertEquals("UP甲", e.authorName);
        assertTrue("封面要升 https: " + e.cover, e.cover.startsWith("https://"));
    }

    /** 标签热门：data 直接是数组（不是 list / result） */
    @Test
    public void parseTagTop_handlesArrayData() {
        String json = "{\"code\":0,\"data\":["
                + "{\"bvid\":\"BV1Xx4y1s7Fd\",\"aid\":1006538953,\"cid\":42734783220,"
                + "\"title\":\"标签样本\",\"dimension\":{\"width\":1918,\"height\":1080}}"
                + "]}";
        List<BiliApi.Entry> l = BiliApi.parseTagTop(json);
        assertEquals(1, l.size());
        assertEquals(42734783220L, l.get(0).cid);
        assertEquals(1918, l.get(0).width);
        // data 不是数组时要给空表而不是炸
        assertTrue(BiliApi.parseTagTop("{\"code\":-404,\"data\":{}}").isEmpty());
        assertTrue(BiliApi.parseTagTop("garbage").isEmpty());
    }

    /**
     * 搜索：列表在 data.result，**没有 cid / 宽高**，作者字段是 author（不是 owner.name），
     * 标题带 em 高亮标签必须清掉。
     */
    @Test
    public void parseSearch_stripsHtmlAndReadsAuthorField() {
        String json = "{\"code\":0,\"data\":{\"result\":["
                + "{\"bvid\":\"BV1GT411h76g\",\"aid\":485590891,"
                + "\"title\":\"57分钟<em class=\\\"keyword\\\">广场舞</em>完整版\","
                + "\"author\":\"彩虹健身模特队\",\"pic\":\"http://i2.hdslb.com/b.jpg\"}"
                + "]}}";
        List<BiliApi.Entry> l = BiliApi.parseSearch(json);
        assertEquals(1, l.size());
        BiliApi.Entry e = l.get(0);
        assertEquals("57分钟广场舞完整版", e.title);
        assertEquals("彩虹健身模特队", e.authorName);
        assertEquals("搜索结果本就没有 cid", 0L, e.cid);
        assertEquals("搜索结果本就没有宽高", 0, e.width);
    }

    /** 解析器要能扛住风控页（HTML）与各种脏数据 */
    @Test
    public void allParsers_tolerateGarbage() {
        for (String junk : new String[]{"", "{}", "not json", "<html>风控</html>"}) {
            assertTrue(BiliApi.parseRankingLike(junk).isEmpty());
            assertTrue(BiliApi.parseTagTop(junk).isEmpty());
            assertTrue(BiliApi.parseSearch(junk).isEmpty());
        }
    }

    @Test
    public void stripTags_removesHighlightMarks() {
        assertEquals("广场舞教学", BiliApi.stripTags("<em class=\"keyword\">广场舞</em>教学"));
        assertEquals("", BiliApi.stripTags(null));
        assertEquals("原样", BiliApi.stripTags("原样"));
    }

    /** 分区 / 标签 id 必须是纯数字 —— 否则会拼出畸形 URL */
    @Test
    public void isDigits_acceptsOnlyDigits() {
        assertTrue(BiliApi.isDigits("211"));
        assertFalse(BiliApi.isDigits(""));
        assertFalse(BiliApi.isDigits(null));
        assertFalse(BiliApi.isDigits("211a"));
        assertFalse(BiliApi.isDigits("-1"));
        assertFalse(BiliApi.isDigits("2 11"));
    }

    // ==================== v1.22 在 MuMu 上实测踩到的两个坑 ====================

    /**
     * 🔴 封面地址可能是**协议相对**（`//i2.hdslb.com/...`）——
     * 不补 scheme，Glide 会把它当成**本地文件路径**去找，报 ENOENT，封面永远加载不出来。
     */
    @Test
    public void normalizeCover_handlesProtocolRelativeUrl() {
        assertEquals("https://i2.hdslb.com/bfs/archive/a.jpg",
                BiliApi.normalizeCover("//i2.hdslb.com/bfs/archive/a.jpg"));
        assertEquals("https://i0.hdslb.com/x.jpg",
                BiliApi.normalizeCover("http://i0.hdslb.com/x.jpg"));
        assertEquals("https://i0.hdslb.com/x.jpg",
                BiliApi.normalizeCover("https://i0.hdslb.com/x.jpg"));
        assertEquals("", BiliApi.normalizeCover(""));
        assertEquals("", BiliApi.normalizeCover(null));
    }

    /**
     * 🔴 方向只做**排序**不做过滤：同方向的排前面，反方向的留在后面 ——
     * 否则「上海话」这种天然横屏的内容，竖着拿手机时会被筛成没内容（v1.22 实测）。
     */
    @Test
    public void preferOrientation_sortsInsteadOfFiltering() {
        List<BiliApi.Entry> in = new ArrayList<>();
        in.add(entry("横1", 1920, 1080));
        in.add(entry("竖1", 1080, 1920));
        in.add(entry("横2", 2560, 1440));
        in.add(entry("竖2", 720, 1280));

        List<BiliApi.Entry> land = BiliApi.preferOrientation(in, DouyinApi.FeedMode.LANDSCAPE);
        assertEquals("反方向的不能被丢掉", 4, land.size());
        assertEquals("横1", land.get(0).title);
        assertEquals("横2", land.get(1).title);
        assertEquals("竖1", land.get(2).title);
        assertEquals("竖2", land.get(3).title);

        List<BiliApi.Entry> port = BiliApi.preferOrientation(in, DouyinApi.FeedMode.PORTRAIT);
        assertEquals("竖1", port.get(0).title);
        assertEquals("竖2", port.get(1).title);
        assertEquals("横1", port.get(2).title);
    }

    /** 尺寸缺失的条目一律当「同方向」放前面，不能因为没尺寸就被扔到最后 */
    @Test
    public void preferOrientation_treatsUnknownSizeAsSame() {
        List<BiliApi.Entry> in = new ArrayList<>();
        in.add(entry("横", 1920, 1080));
        in.add(entry("无尺寸", 0, 0));
        List<BiliApi.Entry> port = BiliApi.preferOrientation(in, DouyinApi.FeedMode.PORTRAIT);
        assertEquals("无尺寸", port.get(0).title);
    }

    private static BiliApi.Entry entry(String title, int w, int h) {
        BiliApi.Entry e = new BiliApi.Entry();
        e.title = title;
        e.bvid = "BV" + title;
        e.cid = 1;
        e.width = w;
        e.height = h;
        return e;
    }
}
