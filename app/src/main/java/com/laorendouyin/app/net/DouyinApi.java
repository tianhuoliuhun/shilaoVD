package com.laorendouyin.app.net;

// 抖音 Web 数据层：OkHttp + Rhino 签名（Signer）+ Gson 解析。
//
// myDVQ 相对原基座（腕上音符）的删减：只留"信息流"这一条链路。
//   删：喜欢列表 / 作者主页作品 / 作品详情 / 用户信息 / 评论列表 / 登录态注入（本项目全程游客）
//
// 关键实测结论（照抄，勿凭感觉改）：
//   - ttwid 从 live.douyin.com **一跳**即发（www 两跳的老办法已失效）
//   - a_bogus 必须对**最终 query 串**签名，签完再追加 &a_bogus=
//   - 拉流/播放头：UA + Referer 即可（CDN 对无 Referer 的请求一律 403），**不需要 Cookie**
//   - 信息流每页只回 2~10 条，靠 refresh_index 递增翻页

import com.laorendouyin.app.feed.FeedVideo;
import com.laorendouyin.app.sign.Signer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class DouyinApi {

    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36";

    /**
     * 信息流方向。
     *
     * 抖音 Web 端**没有**单独的"横屏接口/竖屏接口"，方向是由请求参数隐含决定的 ——
     * 2026-10-13 实测（probe/probe_orientation.js + probe_portrait2.js，每个组合翻 3 页）：
     *
     *   基线 aid=6383 + channel_pc_web + device_platform=webapp →  横屏 13 / 竖屏 1  （竖屏仅 7%）
     *   aid=1128 + channel_web_mobile + device_platform=android →  横屏  9 / 竖屏 27 （竖屏 75%）
     *
     * 即：**PC 参数拿到的是横屏信息流，移动端参数拿到的是竖屏为主的流**。
     * 客户端再按 video.width/height 过滤一遍，两条流都能做到很纯。
     *
     * （改 screen_width/height 与 UA 都**不能**切换方向，实测无效，别再走弯路。）
     */
    public enum FeedMode {
        /** 横屏信息流：PC / 大屏那一套参数 */
        LANDSCAPE("6383", "channel_pc_web", "webapp"),
        /** 竖屏信息流：移动端那一套参数 */
        PORTRAIT("1128", "channel_web_mobile", "android");

        final String aid;
        final String channel;
        final String devicePlatform;

        FeedMode(String aid, String channel, String devicePlatform) {
            this.aid = aid;
            this.channel = channel;
            this.devicePlatform = devicePlatform;
        }
    }

    /**
     * 拉流/播放必需的请求头。真机 34s ERROR 的根因就在这里：CDN 边缘对无 Referer 的请求一律 403。
     * 实测头矩阵（2026-09-26）：带 Referer 即可 206 video/mp4，Cookie 反而不需要。
     */
    public static Map<String, String> playHeaders() {
        return playHeaders(FeedVideo.REFERER_DOUYIN);
    }

    /**
     * 播放头（按内容源给对应 Referer）。
     *
     * 🔴 抖音与 B 站的 CDN **互不认对方的 Referer**：实测 B 站直链带抖音 Referer 一律 403，
     * 空 Referer 同样 403。所以这个值必须跟着视频走（{@link FeedVideo#referer}），不能全局写死。
     */
    public static Map<String, String> playHeaders(String referer) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", (referer == null || referer.isEmpty()) ? FeedVideo.REFERER_DOUYIN : referer);
        return h;
    }

    /**
     * 只拿到 URL、不知道来源时（本地代理的 HeaderInjector 就是这种情况）按域名推断。
     *
     * 判据取「B 站的 CDN / PCDN 域名」，其余一律当抖音 —— 抖音的 CDN 域名集中
     * （douyinvod / douyinpic），而 B 站的 CDN 又杂又会变（bilivideo、hdslb、akamaized、
     * szbdyd，以及第三方 PCDN 如 mountaintoys）。
     */
    public static String refererForUrl(String url) {
        if (url == null || url.isEmpty()) return FeedVideo.REFERER_DOUYIN;
        String u = url.toLowerCase(java.util.Locale.US);
        if (u.contains("bilivideo") || u.contains("bilibili") || u.contains("hdslb")
                || u.contains("akamaized") || u.contains("szbdyd") || u.contains("mountaintoys")
                || u.contains("biligame") || u.contains("biliapi") || u.contains("b23.tv")) {
            return FeedVideo.REFERER_BILIBILI;
        }
        return FeedVideo.REFERER_DOUYIN;
    }

    private final OkHttpClient client;
    /** B 站内容源（懒创建：只有变体配置里带 bili 源时才用得上） */
    private BiliApi mBiliApi;
    private final Signer signer;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    /** 翻页游标（每个方向各记一份：切横竖屏不该互相干扰） */
    private final Map<FeedMode, Integer> mRefreshIndex = new java.util.EnumMap<>(FeedMode.class);

    /** 游标存取（持久化用：每次进页面从上次进度继续，否则永远第一页=同样视频） */
    public void setRefreshIndex(FeedMode mode, int idx) {
        mRefreshIndex.put(mode, idx);
    }

    public int getRefreshIndex(FeedMode mode) {
        Integer v = mRefreshIndex.get(mode);
        return v == null ? 0 : v;
    }

    public DouyinApi(Signer signer) {
        this.signer = signer;
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                // Android 4.4 及以下**默认不启用 TLS 1.2**，而抖音的接口与视频 CDN 全在 HTTPS 上。
                // 显式把 TLS 1.2 排在协商最前面，让老设备也能连上。
                .connectionSpecs(buildTlsSpecs());
        // 老系统上系统 CA 太旧，认不出抖音的证书链 —— 换成自带的那份（见 TlsCompat）
        javax.net.ssl.SSLContext tlsCtx = com.laorendouyin.app.util.TlsCompat.sslContext();
        javax.net.ssl.X509TrustManager tm = com.laorendouyin.app.util.TlsCompat.trustManager();
        if (tlsCtx != null && tm != null) {
            builder.sslSocketFactory(tlsCtx.getSocketFactory(), tm);
        }
        this.client = builder.build();
    }

    /**
     * TLS 版本优先级：1.2 → 1.1 → 1.0。
     *
     * ⚠️ 套件列表必须用 {@link okhttp3.ConnectionSpec#COMPATIBLE_TLS} 那一套，**不能**用 MODERN_TLS：
     * MODERN_TLS 带的是一组现代加密套件，**Android 4.4 上一个都不支持** —— 协商失败后会退回
     * 系统默认的 TLS 1.0，服务器直接回 `tlsv1 alert protocol version`（实测踩到）。
     * COMPATIBLE_TLS 的套件表包含老系统也能用的那些，再叠加 TLS 1.2 就能在新老设备上都握手成功。
     */
    static java.util.List<okhttp3.ConnectionSpec> buildTlsSpecs() {
        okhttp3.ConnectionSpec tls = new okhttp3.ConnectionSpec.Builder(okhttp3.ConnectionSpec.COMPATIBLE_TLS)
                .tlsVersions(okhttp3.TlsVersion.TLS_1_2,
                        okhttp3.TlsVersion.TLS_1_1,
                        okhttp3.TlsVersion.TLS_1_0)
                .build();
        return java.util.Arrays.asList(tls, okhttp3.ConnectionSpec.CLEARTEXT);
    }

    /** 实测：live.douyin.com 一跳即发 ttwid（www 两跳老办法已失效） */
    public synchronized void ensureTtwid() throws IOException {
        if (cookies.containsKey("ttwid")) return;
        Request req = new Request.Builder()
                .url("https://live.douyin.com/646454278948")
                .header("User-Agent", UA)
                .build();
        try (Response resp = client.newCall(req).execute()) {
            absorbCookies(resp);
        }
        if (!cookies.containsKey("ttwid")) {
            throw new IOException("ttwid 获取失败");
        }
    }

    // ---- 通用 query 构建 ----
    //
    // ⚠️ 键值**一字未改**（照实测探针抄），顺序也不能动 —— a_bogus 是对拼好的串签名。

    /** 各接口共用的设备/浏览器指纹参数 */
    private static Map<String, String> baseDeviceParams(String versionCode, String versionName) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("device_platform", "webapp");
        p.put("aid", "6383");
        p.put("channel", "channel_pc_web");
        p.put("update_version_code", versionCode);
        p.put("pc_client_type", "1");
        p.put("pc_libra_divert", "Windows");
        p.put("support_h265", "1");
        p.put("support_dash", "1");
        p.put("version_code", versionCode);
        p.put("version_name", versionName);
        p.put("cookie_enabled", "true");
        p.put("screen_width", "2560");
        p.put("screen_height", "1440");
        p.put("browser_language", "zh-CN");
        p.put("browser_platform", "Win32");
        p.put("browser_name", "Chrome");
        p.put("browser_version", "135.0.0.0");
        p.put("browser_online", "true");
        p.put("engine_name", "Blink");
        p.put("engine_version", "135.0.0.0");
        p.put("os_name", "Windows");
        p.put("os_version", "10");
        p.put("cpu_core_num", "20");
        p.put("device_memory", "8");
        p.put("platform", "PC");
        p.put("downlink", "0.55");
        p.put("effective_type", "3g");
        p.put("round_trip_time", "0");
        return p;
    }

    /** 通用版本参数族 */
    private static final String VER_COMMON_CODE = "170400", VER_COMMON_NAME = "17.4.0";

    /** 拼 query 并签名（LinkedHashMap 保序：a_bogus 必须对最终串算） */
    private String joinAndSign(Map<String, String> p) {
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (q.length() > 0) q.append('&');
            q.append(e.getKey()).append('=').append(e.getValue());
        }
        return q + "&a_bogus=" + urlEncode(signer.makeABogus(q.toString()));
    }

    /**
     * 当前构建变体的**内容源配置**，由 `app/build.gradle` 的 productFlavors 注入。
     *
     * 语法：**逗号分隔的源列表**，每个元素要么是频道 tag_id，要么是特殊值 `recommend`
     * （表示抖音「推荐」流）。举例：
     * ```
     * ""                                     → 只有推荐流（standard 版）
     * "300219"                               → 只刷三农
     * "recommend,300219,300209,300204,300220" → 推荐流 + 三农/音乐/美食/动物 轮询（老人专版）
     * ```
     * ⚠️ 频道不能靠推荐流的 tag_id 实现（实测无效），必须换到 channel/feed 端点 —— 见 fetchFeed。
     */
    public static final String CHANNEL_TAG_ID = com.laorendouyin.app.BuildConfig.CHANNEL_TAG_ID;

    /**
     * 特殊源名：**「相关视频」**。
     *
     * 它不是频道 tag_id，含义是「用最近看过的那条视频作种子，问抖音要同类内容」
     * （`/aweme/v1/web/aweme/related/`）。2026-10-15 实测（probe/probe_related.js）：
     * 游客态可用、每次 10~12 条、**带直链率 100%**、`refresh_index` 递增能翻页换内容。
     *
     * ⚠️ 它返回的是**混合方向**（竖/横大致各半，用同方向种子也只是略好），
     * 所以只适合当"内容不够时的补充"，别指望它单独撑起一条流。
     *
     * 用法：在 `app/build.gradle` 的 `CHANNEL_TAG_ID` 里按逗号加一项 `related`。
     */
    public static final String SRC_RELATED = "related";

    /**
     * 特殊源名：**「哔哩哔哩热门」**（实际请求在 {@link BiliApi}）。
     *
     * ⚠️ 它比别的源慢：B 站列表接口不给播放地址，每条都要再请求一次 playurl 解析直链
     * （并发 6 条，约 1~2 秒）。所以轮询时排在后面，失败就换源、不拖住整体。
     */
    public static final String SRC_BILI = "bili";

    /** 解析后的源列表（空数组 = 只有推荐流） */
    private static final String[] SOURCES = splitSources(CHANNEL_TAG_ID);

    /**
     * 源轮询 + 失败降级。无源时只有一个「recommend」（即推荐流）。
     * 某源失败会冷却一段时间，见 {@link FeedSourcePicker}。
     */
    private static final FeedSourcePicker sPicker = new FeedSourcePicker(
            SOURCES.length > 0 ? SOURCES : new String[]{"recommend"});

    /**
     * 每个方向「最近看到的、可当种子」的视频 id —— `related` 源的输入。
     * 拉流在后台线程、状态又要跨请求存活，所以用并发容器。
     */
    private static final Map<FeedMode, String> sSeedByMode = new java.util.concurrent.ConcurrentHashMap<>();

    /** `related` 这次没有种子（App 刚启动、还没看到任何视频）—— 不算失败，不该让它进冷却 */
    static final class NoSeedException extends IOException {
        NoSeedException() {
            super("还没有可当种子的视频，related 源暂不可用");
        }
    }

    /** 仅供单测：读某个方向当前的种子 */
    static String seedFor(FeedMode mode) {
        return sSeedByMode.get(mode);
    }

    private static String[] splitSources(String cfg) {
        if (cfg == null || cfg.trim().isEmpty()) return new String[0];
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : cfg.split(",")) {
            p = p.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out.toArray(new String[0]);
    }

    /** 当前变体是不是「带频道的内容专版」 */
    public static boolean isChannelEdition() {
        return SOURCES.length > 0;
    }

    /**
     * 该源是不是**频道 tag_id**（false = 走推荐流或相关流的端点）。
     * ⚠️ `related` 不是频道 —— 漏了它就会被拼上 `tag_id=related` 掉进 channel/feed。
     */
    static boolean isChannelSource(String src) {
        return !"recommend".equals(src) && !SRC_RELATED.equals(src)
                && (src == null || !src.startsWith(BiliApi.SRC_PREFIX));
    }

    /**
     * 拉信息流（**游客身份**）。每次调用按方向递增各自的 refresh_index 翻页。
     *
     * 实测（2026-10-13，probe/probe_douyin.js）：不登录也能拿到真实内容 ——
     *   HTTP 200 + status_code=0，每页 2~10 条，refresh_index 递增后内容变化。
     *
     * @param mode 横屏流 / 竖屏流（决定 aid / channel / device_platform 那套参数）
     */
    public List<FeedVideo> fetchFeed(int count, FeedMode mode) throws IOException {
        ensureTtwid();
        int idx = getRefreshIndex(mode) + 1;
        mRefreshIndex.put(mode, idx);

        // 按轮询顺序尝试候选源；某个源失败就记下冷却、换下一个，全部失败才抛错。
        IOException last = null;
        for (String src : sPicker.candidates(System.currentTimeMillis())) {
            try {
                List<FeedVideo> list = requestFeed(count, idx, mode, src);
                sPicker.markOk(src);
                return list;
            } catch (NoSeedException e) {
                // App 刚启动 / 还没拿到任何视频 —— related 这次没种子可用。
                // ⚠️ 这不是它的错，**绝不能 markFailed**（否则会被无谓冷却 10 分钟）
                com.laorendouyin.app.util.AppLog.i("feed", "相关视频暂无种子，跳过");
            } catch (IOException e) {
                sPicker.markFailed(src, System.currentTimeMillis());
                com.laorendouyin.app.util.AppLog.i("feed", "内容源 " + src + " 失败，换下一个：" + e.getMessage());
                last = e;
            }
        }
        throw last != null ? last : new IOException("没有可用的内容源");
    }

    /** 请求某一个源的一页内容；失败抛 IOException，由 fetchFeed 决定是否换源 */
    private List<FeedVideo> requestFeed(int count, int idx, FeedMode mode, String src) throws IOException {
        // B 站走完全独立的一套接口（列表 + 逐条解析直链），这里直接短路返回。
        // 源名可带参数：bili 热门 / bili:region:<rid> 分区 / bili:search:<词> / bili:tag:<tid>
        if (src != null && src.startsWith(BiliApi.SRC_PREFIX)) {
            if (mBiliApi == null) mBiliApi = new BiliApi();
            return mBiliApi.fetch(src, count, mode);
        }
        boolean related = SRC_RELATED.equals(src);
        boolean channel = isChannelSource(src);
        String tagId = channel ? src : "";
        String srcName = related ? "相关视频" : (channel ? "频道" + src : "推荐");
        String query;
        String path;
        if (related) {
            String seed = sSeedByMode.get(mode);
            if (seed == null || seed.isEmpty()) throw new NoSeedException();
            query = buildRelatedQuery(seed, count, idx, signer);
            path = "/aweme/v1/web/aweme/related/";
        } else {
            query = buildFeedQuery(count, idx, mode, signer, tagId);
            path = channel ? "/aweme/v1/web/channel/feed/" : "/aweme/v1/web/tab/feed/";
        }
        com.laorendouyin.app.util.AppLog.i("feed", "内容源：" + srcName
                + (SOURCES.length > 1 ? "（共 " + SOURCES.length + " 个源轮询）" : ""));
        Request req = new Request.Builder()
                .url("https://www.douyin.com" + path + "?" + query)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/")
                .header("Cookie", cookieHeader())
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (resp.body() == null) throw new IOException("空响应");
            String body = resp.body().string();
            List<FeedVideo> list = parseFeed(body);
            if (list.isEmpty()) {
                throw new IOException("feed 为空，status=" + body.substring(0, Math.min(120, body.length())));
            }
            rememberSeed(mode, list);
            return list;
        }
    }

    /** 构造 feed query（含 a_bogus）；纯函数。默认横屏流（兼容旧调用） */
    public static String buildFeedQuery(int count, Signer signer) {
        return buildFeedQuery(count, 1, FeedMode.LANDSCAPE, signer);
    }

    /** 构造 feed query（含 a_bogus、翻页游标与方向参数）；纯函数。tagId 空串 = 推荐流 */
    public static String buildFeedQuery(int count, int refreshIndex, FeedMode mode, Signer signer) {
        return buildFeedQuery(count, refreshIndex, mode, signer, "");
    }

    /** 构造 feed query；tagId 为频道 tag_id（空串走推荐流）。纯函数 */
    public static String buildFeedQuery(int count, int refreshIndex, FeedMode mode, Signer signer, String tagId) {
        Map<String, String> p = baseDeviceParams(VER_COMMON_CODE, VER_COMMON_NAME);
        // 方向三件套：LinkedHashMap 里已存在这些键，put 只改值、**不动顺序**（顺序必须保持）
        p.put("device_platform", mode.devicePlatform);
        p.put("aid", mode.aid);
        p.put("channel", mode.channel);
        p.put("round_trip_time", "500");     // feed 这个接口实测探针发的是 500，保持原值
        p.put("count", String.valueOf(count));
        p.put("refresh_index", String.valueOf(refreshIndex));
        // 频道源必须真给 tag_id（channel/feed 认它）；推荐流保持空串
        p.put("tag_id", tagId == null ? "" : tagId);
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (q.length() > 0) q.append('&');
            q.append(e.getKey()).append('=').append(e.getValue());
        }
        String ab = signer.makeABogus(q.toString());
        return q + "&a_bogus=" + urlEncode(ab);
    }

    /**
     * 构造「相关视频」query（含 a_bogus）；纯函数。
     *
     * ⚠️ 这里**故意不带方向三件套**（aid / channel / device_platform 保持 baseDeviceParams 默认）——
     * 2026-10-15 的探测就是在这套默认参数下成功的（probe/probe_related_orientation.js），
     * 换成移动端那套**没有验证过**，别顺手"统一"。
     *
     * @param awemeId 种子视频 id（由 {@link #rememberSeed} 维护）
     */
    public static String buildRelatedQuery(String awemeId, int count, int refreshIndex, Signer signer) {
        Map<String, String> p = baseDeviceParams(VER_COMMON_CODE, VER_COMMON_NAME);
        p.put("aweme_id", awemeId == null ? "" : awemeId);
        p.put("filterGid", awemeId == null ? "" : awemeId);
        p.put("count", String.valueOf(count));
        p.put("refresh_index", String.valueOf(refreshIndex));
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (q.length() > 0) q.append('&');
            q.append(e.getKey()).append('=').append(e.getValue());
        }
        String ab = signer.makeABogus(q.toString());
        return q + "&a_bogus=" + urlEncode(ab);
    }

    /**
     * 记住本次结果里**符合当前方向**的最后一条，作为下次 related 的种子。
     *
     * 挑同方向是因为 related 返回混合方向，同方向种子能略微拉高同方向占比；
     * 一条都不符合时**保留旧种子**（清空会导致横竖来回切时反复失去种子、related 永久失效）。
     */
    static void rememberSeed(FeedMode mode, List<FeedVideo> list) {
        if (list == null || list.isEmpty()) return;
        boolean wantLandscape = (mode == FeedMode.LANDSCAPE);
        for (int i = list.size() - 1; i >= 0; i--) {
            FeedVideo v = list.get(i);
            if (v == null || v.awemeId.isEmpty()) continue;
            if (v.videoWidth <= 0 || v.videoHeight <= 0) continue;   // 没尺寸当不了种子
            if (v.isLandscape() != wantLandscape) continue;
            sSeedByMode.put(mode, v.awemeId);
            return;
        }
    }

    /** 解析 feed JSON → FeedVideo 列表；纯函数 */
    public static List<FeedVideo> parseFeed(String json) {
        List<FeedVideo> out = new ArrayList<>();
        JsonElement rootEl = JsonParser.parseString(json);
        if (!rootEl.isJsonObject()) return out;
        JsonObject root = rootEl.getAsJsonObject();
        JsonArray arr = arrOf(root, "aweme_list");
        if (arr == null) return out;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            FeedVideo v = itemToVideo(el.getAsJsonObject());
            if (v != null) out.add(v);
        }
        return out;
    }

    /** 单条 aweme 对象 → FeedVideo；纯函数 */
    static FeedVideo itemToVideo(JsonObject item) {
        String awemeId = optString(item, "aweme_id");
        String title = optString(item, "desc");
        String author = "";
        JsonObject authorObj = objOf(item, "author");
        if (authorObj != null) {
            author = optString(authorObj, "nickname");
        }
        String playUrl = "";
        String coverUrl = "";
        int videoWidth = 0, videoHeight = 0;
        JsonObject video = objOf(item, "video");
        if (video != null) {
            // 宽高是"横竖屏分流"的判据，必须解析出来
            videoWidth = optInt(video, "width");
            videoHeight = optInt(video, "height");
            playUrl = pickGearUrl(video, awemeId);
            if (playUrl.isEmpty()) playUrl = firstUrl(video, "play_addr", awemeId);
            if (playUrl.isEmpty()) playUrl = firstUrl(video, "play_addr_lowbr", awemeId);
            // ⚠️ 封面必须走 imageUrl，**不能**走 firstUrl/urlFromAddr：
            //    后者见到 cover.uri 非空就会拼成播放端点（拿播放端点当图床），Glide 必然失败 → 封面永远黑。
            coverUrl = imageUrl(video, "cover");
            if (coverUrl.isEmpty()) coverUrl = imageUrl(video, "origin_cover");
        }
        if (playUrl.isEmpty()) return null;
        return new FeedVideo(title, playUrl, coverUrl, awemeId, author, videoWidth, videoHeight,
                FeedVideo.REFERER_DOUYIN);
    }

    /**
     * 取「图片类」字段的直链：cover / origin_cover 这类对象的 url_list[0]。
     * 实测：douyinpic.com 的图带不带 Referer 都能取到，Glide 直接加载即可。
     */
    static String imageUrl(JsonObject holder, String key) {
        if (holder == null) return "";
        JsonObject addr = objOf(holder, key);
        if (addr == null) return "";
        JsonArray list = arrOf(addr, "url_list");
        if (list == null || list.size() == 0) return "";
        for (int i = 0; i < list.size(); i++) {
            JsonElement e = list.get(i);
            if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) continue;
            String u = e.getAsString();
            if (u != null && u.startsWith("http")) return u;
        }
        return "";
    }

    /**
     * 安全取对象：字段缺失、为 null（JsonNull）或不是对象时一律返回 null。
     * ⚠️ 不能直接用 getAsJsonObject(key)：字段存在但值是 null 时它会抛
     *    ClassCastException（JsonNull cannot be cast to JsonObject）。真机踩过——
     *    推荐流里混着图文贴，它们的 video 字段就是 null。
     */
    private static JsonObject objOf(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    /** 安全取数组：同上，非数组/缺失/null 一律返回 null */
    private static JsonArray arrOf(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
    }

    private static int optInt(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsInt();
    }

    private static long optLong(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsLong();
    }

    private static String firstUrl(JsonObject video, String key, String awemeId) {
        JsonObject addr = objOf(video, key);
        if (addr == null) return "";
        return urlFromAddr(addr, awemeId, defaultRatio());
    }

    /**
     * 画质偏好：0=省流量(优先540p) 1=平衡(优先720p) 2=清晰(允许1080p)。
     *
     * myDVQ 取值 1（平衡）——原基座是手表（1.4 寸屏，默认 0 省流量），
     * 本项目面向手机/平板，540p 在大屏上偏糊，720p 是流量与清晰度的平衡点。
     */
    public static volatile int sQuality = 1;

    /** 按画质偏好给档位打分，分越高越优先；H.264 / 非 DASH 的硬门槛在 pickGearUrl 里另判 */
    static int gearRank(String gearName) {
        boolean p540 = gearName.contains("_540_");
        boolean p720 = gearName.contains("_720_");
        boolean p1080 = gearName.contains("_1080_");
        if (sQuality >= 2) return p1080 ? 3 : p720 ? 2 : p540 ? 1 : 0;
        if (sQuality == 1) return p720 ? 3 : p540 ? 2 : 1;
        return p540 ? 3 : p720 ? 2 : 1;
    }

    /**
     * 自拼播放地址时的 ratio 参数 —— **这是 Android 4.4「一条都播不了」的根因修复**
     * （2026-10-14 在 4.4 模拟器上实测定位）。
     *
     * 故障链：某些视频的选中档在 url_list 里**没有 www 跳转候选**（只剩必 403 的 CDN 直连）
     * → 走下面的自拼兜底；而自拼端点 `www.douyin.com/aweme/v1/play/?video_id=...`
     * **只认 ratio 参数**决定服务端给哪一档 —— 原来这里**硬编码 ratio=1080p**，
     * 服务端就真给 1080p（实测 274MB / H.264 **High@L5.0** / 1080x1920）。
     * 而 4.4 的软解器 `OMX.google.h264.decoder` 只支持到 Level 4.x → `NoSupport`
     * → ExoPlayer 与系统 MediaPlayer 一起阵亡
     * （日志：format_supported=NO_EXCEEDS_CAPABILITIES / SoftAVC: Decoder failed: -2）。
     *
     * 实测：同一 video_id 只换 ratio，服务端返回三条**不同**的 CDN 资源，与档位表精确对应：
     * ```
     * ratio=540p  → o0E99wIH5ij...   (208MB, 对应 normal_540_0)
     * ratio=720p  → 5fb2b4690e84...  (202MB, 对应 normal_720_0)
     * ratio=1080p → okXSIABqAgsE...  (274MB, 对应 normal_1080_0)
     * ```
     *
     * 规则：按档位名推导；**API < 21 一律夹到 540p** —— 老设备软解上限低，
     * 1080p High@L5.0 必挂，540p（H.264 High@L3.1）才是能播的那一档。
     */
    static String ratioFor(String gearName) {
        String want = "720p";
        if (gearName != null) {
            if (gearName.contains("_1080_")) want = "1080p";
            else if (gearName.contains("_540_")) want = "540p";
            else if (gearName.contains("_720_")) want = "720p";
        }
        // 老系统：软解器只到 Level 4.x，一律降到 540p —— 这是"能不能播"的分界线
        if (android.os.Build.VERSION.SDK_INT < 21) want = "540p";
        return want;
    }

    /** 没带档位信息时（兜底 play_addr / play_addr_lowbr）用的 ratio */
    static String defaultRatio() {
        if (android.os.Build.VERSION.SDK_INT < 21) return "540p";
        if (sQuality >= 2) return "1080p";
        return sQuality == 1 ? "720p" : "540p";
    }

    /**
     * 优先挑解码友好的一档：H.264（HEVC 在低端设备上常无硬解，软解更吃电）
     * + 按 sQuality 选分辨率 + 同档取最低码率。挑不到返回空，由调用方回退默认 play_addr。
     *
     * ⚠️ 必须排除 DASH 档（url 形如 /aweme/v1/play/dash/）：实测 25 档里 11 档给 DASH **单轨**流
     * （站点自己另发一条 media-audio-und-mp4a 请求配音频）。MediaPlayer 单 URL 播它＝有画面没声音。
     */
    static String pickGearUrl(JsonObject video, String awemeId) {
        JsonArray gears = arrOf(video, "bit_rate");
        if (gears == null || gears.size() == 0) return "";
        String bestUrl = "";
        int bestRank = 0;
        long bestBr = 0;
        String bestGear = "";
        for (JsonElement el : gears) {
            if (!el.isJsonObject()) continue;
            JsonObject g = el.getAsJsonObject();
            if (optInt(g, "is_h265") != 0 || optInt(g, "is_bytevc1") != 0) continue;
            JsonObject addr = objOf(g, "play_addr");
            if (addr == null) continue;
            // ⚠️ gear 必须先取出来：自拼 URL 的 ratio 由档位名决定（见 ratioFor）
            String gear = optString(g, "gear_name");
            String url = urlFromAddr(addr, awemeId, ratioFor(gear));
            if (url.isEmpty() || url.contains("/play/dash/")) continue;
            int rank = gearRank(gear);
            long br = optLong(g, "bit_rate");
            if (bestUrl.isEmpty() || rank > bestRank || (rank == bestRank && br < bestBr)) {
                bestUrl = url;
                bestRank = rank;
                bestBr = br;
                bestGear = gear;
            }
        }
        if (bestUrl.isEmpty()) return "";
        // 选档结果落日志：URL 本身看不出档位，不记就没法验证到底播的是哪一档
        // ⚠️ 再记「是否自拼 + 实际 ratio」—— 4.4 上"选 540p 却拿到 1080p"就是靠这两项查出来的
        boolean synth = bestUrl.contains("&ratio=");
        com.laorendouyin.app.util.AppLog.i("feed", "选档 " + awemeId + " → " + bestGear
                + " " + (bestBr / 1000) + "kbps"
                + (synth ? "（自拼 ratio=" + ratioFor(bestGear) + "）" : "（档位签名URL）")
                + " 共 " + gears.size() + " 档");
        return bestUrl;
    }

    private static String urlFromAddr(JsonObject addr, String awemeId, String ratio) {
        JsonArray list = arrOf(addr, "url_list");
        if (list == null || list.size() == 0) return "";
        // ⚠️ 真机取证：url_list 里直连 CDN 候选（v*-web*.douyinvod.com）一律 403（带任何请求头/Cookie 均拒），
        // 只有 www.douyin.com 的跳转候选（302→douyinvod 206 video/mp4）可播
        // → 必须优先选跳转式 URL，否则"只显示封面"（播放器拉流失败被静默吞掉）。
        //
        // 注意：走这条路的 www 候选**自带档位签名**（file_id + sign + biz_sign），
        // 服务端按签名给档，此时 ratio 无关紧要。真正需要 ratio 的是下面的自拼兜底。
        for (int i = 0; i < list.size(); i++) {
            String u = list.get(i).getAsString();
            if (u.startsWith("https://www.douyin.com/")) return u;
        }
        // 没给跳转候选时：自己拼官方跳转式播放地址，用 play_addr.uri（形如 v0200fg...）；
        // 不能再回退 list.get(0)——那是必 403 的直连 CDN。
        // ⚠️ **ratio 就是这一层的档位开关**：自拼端点没有签名，服务端完全按 ratio 决定清晰度。
        //    曾经硬编码 ratio=1080p → 老设备拿到 High@L5.0 1080p 直接解码失败（见 ratioFor 注释）。
        String uri = optString(addr, "uri");
        if (!uri.isEmpty()) {
            return "https://www.douyin.com/aweme/v1/play/?video_id=" + uri
                    + "&ratio=" + ratio + "&line=0"
                    + (awemeId == null || awemeId.isEmpty() ? "" : "&item_id=" + awemeId);
        }
        return "";
    }

    private static String optString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private void absorbCookies(Response resp) {
        for (String c : resp.headers("Set-Cookie")) {
            String kv = c.split(";")[0];
            int i = kv.indexOf('=');
            if (i > 0) cookies.put(kv.substring(0, i).trim(), kv.substring(i + 1).trim());
        }
    }

    /** 只发游客 Cookie（ttwid）：本项目没有任何登录态 */
    private String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
