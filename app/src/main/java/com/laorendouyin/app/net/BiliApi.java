package com.laorendouyin.app.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.laorendouyin.app.feed.FeedVideo;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 哔哩哔哩（B 站）内容源 —— 支持四种取法。
 *
 * <b>源名语法</b>（写在 app/build.gradle 的 CHANNEL_TAG_ID 里，逗号分隔）：
 * <pre>
 *   bili                     热门榜
 *   bili:region:&lt;rid&gt;        某个分区（频道）的排行榜 —— 如 bili:region:211（美食）
 *   bili:search:&lt;关键词&gt;     按关键词搜索       —— 如 bili:search:广场舞
 *   bili:tag:&lt;tag_id&gt;       某个标签下的热门   —— 如 bili:tag:23831577
 * </pre>
 * ⚠️ 源名之间用逗号分隔，所以**关键词里不要带逗号**。
 *
 * <b>为什么统一走 durl 单文件</b>：默认的 `fnval=16` 是 DASH 音视频分离流，
 * 我们的老人播放器只认单个 URL，拿视频轨会播成**没有声音**。
 * 所以一律 `fnval=1` + `qn=64`（720P；⚠️ 不能要 32，那不在游客可用的 accept 清单里，反被降成 360P）。
 *
 * <b>Referer</b>：B 站 CDN 只认 `https://www.bilibili.com/`（实测交叉必 403），
 * 解析出的每条 FeedVideo 都自带这个 referer。
 *
 * <b>风控</b>：实测短时间高频请求会返回 `code=-352`，间隔 1.2 秒即恢复。
 * 所以并发数压在 {@link #RESOLVE_THREADS}，且**任何一条失败只丢它自己**。
 *
 * 实测脚本：probe/probe_bili.js（列表 + 直链）、probe_bili_play.js（Referer 矩阵）、
 * probe_bili_more.js（分区 / 搜索 / 标签）、probe_bili_fields.js（字段与风控复测）。
 */
public final class BiliApi {

    /** B 站 CDN / PCDN 认的 Referer */
    public static final String REFERER = FeedVideo.REFERER_BILIBILI;

    /** 源名前缀：build.gradle 里凡是这个开头的都交给本类 */
    public static final String SRC_PREFIX = "bili";

    /** 与抖音侧一致的浏览器 UA（B 站这边实测 UA 不是必需，但带上更稳） */
    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36";

    private static final String HOST = "https://api.bilibili.com";

    /** 720P。⚠️ 别改成 32：480P 不在游客可用的 accept 清单里，会被降成 360P */
    static final int QN_720P = 64;

    /** 直链解析并发数（每条一次请求）；压到 4 是为了少踩 B 站风控 */
    private static final int RESOLVE_THREADS = 4;

    /** 单次最多解析这么多条：再多要多等好几轮，得不偿失 */
    private static final int MAX_RESOLVE = 8;

    /** 列表翻几页循环 */
    private static final int MAX_PAGE = 3;

    private final OkHttpClient client;

    /** 各源各自的游标，避免"每次都刷到同一批" */
    private final java.util.Map<String, Integer> mCursor = new java.util.concurrent.ConcurrentHashMap<>();

    /** 首次访问 bilibili.com 抓到的 cookie（buvid3 等），避免被风控当成裸请求 */
    private String mCookie = "";

    public BiliApi() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                // 与抖音侧同一套 TLS 协商策略：Android 4.4 默认不启用 TLS 1.2，必须显式排前
                .connectionSpecs(DouyinApi.buildTlsSpecs());
        javax.net.ssl.SSLContext tlsCtx = com.laorendouyin.app.util.TlsCompat.sslContext();
        javax.net.ssl.X509TrustManager tm = com.laorendouyin.app.util.TlsCompat.trustManager();
        if (tlsCtx != null && tm != null) {
            builder.sslSocketFactory(tlsCtx.getSocketFactory(), tm);
        }
        this.client = builder.build();
    }

    /**
     * 拉一批 B 站视频（已带上可直接播放的 720P 直链）。**统一入口**，按源名分派。
     *
     * @param src 形如 `bili` / `bili:region:211` / `bili:search:广场舞` / `bili:tag:23831577`
     * @throws IOException 列表为空、参数非法、或一条都没解析出直链（交给上层换源）
     */
    public List<FeedVideo> fetch(String src, int count, DouyinApi.FeedMode mode) throws IOException {
        ensureCookie();

        String[] ka = splitSource(src);
        String kind = ka[0];
        String arg = ka[1];

        List<Entry> entries;
        if ("region".equals(kind)) {
            entries = loadRegion(arg, mode);
        } else if ("search".equals(kind)) {
            entries = loadSearch(arg);
        } else if ("tag".equals(kind)) {
            entries = loadTag(arg, mode);
        } else {
            entries = loadPopular(mode);
        }
        if (entries.isEmpty()) {
            throw new IOException("B站源 " + src + " 没有可用条目");
        }

        List<FeedVideo> out = resolveAll(entries, mode);
        if (out.isEmpty()) {
            throw new IOException("B站源 " + src + " 没解析出可播直链（" + entries.size() + " 条都失败）");
        }
        com.laorendouyin.app.util.AppLog.i("feed", "B站源 " + src + "：解析出 " + out.size() + " 条可播");
        return out.size() > count ? new ArrayList<>(out.subList(0, count)) : out;
    }

    // ==================== 四种列表 ====================

    /** 热门榜 */
    private List<Entry> loadPopular(DouyinApi.FeedMode mode) throws IOException {
        int page = next("popular", MAX_PAGE) + 1;
        String body = get("/x/web-interface/popular?ps=20&pn=" + page);
        List<Entry> all = parseRankingLike(body);
        if (all.isEmpty()) {
            throw new IOException("B站热门列表为空：" + body.substring(0, Math.min(120, body.length())));
        }
        return filterOrientation(all, mode);
    }

    /**
     * 分区（频道）排行榜。
     *
     * ⚠️ `ranking/v2` **不支持翻页**（永远返回同一批 90+ 条），所以这里用"窗口游标"每次取不同的一段，
     * 否则每次都刷到同样的开头几条。
     */
    private List<Entry> loadRegion(String rid, DouyinApi.FeedMode mode) throws IOException {
        if (!isDigits(rid)) throw new IOException("B站分区 id 必须是数字：" + rid);
        String body = get("/x/web-interface/ranking/v2?rid=" + rid + "&type=all");
        List<Entry> all = filterOrientation(parseRankingLike(body), mode);
        if (all.isEmpty()) {
            throw new IOException("B站分区 " + rid + " 没有可用内容");
        }
        int windows = Math.max(1, all.size() / MAX_RESOLVE);
        int w = next("region:" + rid + ":" + mode, windows);
        int from = Math.min(all.size() - 1, w * MAX_RESOLVE);
        int to = Math.min(all.size(), from + MAX_RESOLVE);
        return new ArrayList<>(all.subList(from, to));
    }

    /** 关键词搜索。⚠️ 搜索结果**不带 cid / 宽高**，解析时要用 view 接口补（见 {@link #resolveOne}） */
    private List<Entry> loadSearch(String keyword) throws IOException {
        if (keyword == null || keyword.isEmpty()) {
            throw new IOException("B站搜索关键词为空（写法：bili:search:关键词）");
        }
        int page = next("search:" + keyword, MAX_PAGE) + 1;
        String body = get("/x/web-interface/search/type?search_type=video&page=" + page
                + "&keyword=" + urlEncode(keyword));
        List<Entry> all = parseSearch(body);
        if (all.isEmpty()) {
            throw new IOException("B站搜索「" + keyword + "」没有结果");
        }
        // 搜索结果没有尺寸，筛不了方向，只能先取前若干条去解析（解析时才知道方向）
        return all.size() > MAX_RESOLVE ? new ArrayList<>(all.subList(0, MAX_RESOLVE)) : all;
    }

    /** 某个标签下的热门 */
    private List<Entry> loadTag(String tid, DouyinApi.FeedMode mode) throws IOException {
        if (!isDigits(tid)) throw new IOException("B站标签 id 必须是数字：" + tid);
        int pn = next("tag:" + tid, MAX_PAGE) + 1;
        String body = get("/x/web-interface/tag/top?tid=" + tid + "&pn=" + pn + "&ps=20");
        List<Entry> all = filterOrientation(parseTagTop(body), mode);
        if (all.isEmpty()) {
            throw new IOException("B站标签 " + tid + " 没有可用内容");
        }
        return all.size() > MAX_RESOLVE ? new ArrayList<>(all.subList(0, MAX_RESOLVE)) : all;
    }

    /** 只留下与当前屏幕方向一致的（尺寸缺失的一律留下，交给解析阶段再判） */
    private static List<Entry> filterOrientation(List<Entry> in, DouyinApi.FeedMode mode) {
        boolean wantLandscape = (mode == DouyinApi.FeedMode.LANDSCAPE);
        List<Entry> out = new ArrayList<>();
        for (Entry e : in) {
            if (e.width > 0 && e.height > 0 && ((e.height > e.width) == wantLandscape)) continue;
            out.add(e);
            if (out.size() >= MAX_RESOLVE) break;
        }
        return out;
    }

    // ==================== 解析播放直链 ====================

    /** 并发解析直链；单条失败只丢它自己 */
    private List<FeedVideo> resolveAll(List<Entry> entries, DouyinApi.FeedMode mode) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(RESOLVE_THREADS, entries.size()));
        try {
            List<Future<FeedVideo>> futures = new ArrayList<>();
            for (final Entry e : entries) {
                futures.add(pool.submit(new Callable<FeedVideo>() {
                    @Override
                    public FeedVideo call() {
                        return resolveOne(e, mode);
                    }
                }));
            }
            List<FeedVideo> out = new ArrayList<>();
            for (Future<FeedVideo> f : futures) {
                try {
                    FeedVideo v = f.get(25, TimeUnit.SECONDS);
                    if (v != null) out.add(v);
                } catch (Throwable ignore) {
                    // 单条超时/失败：跳过（少一条无所谓）
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 把一条列表条目解析成可播放的 FeedVideo；失败或方向不符返回 null。
     *
     * 搜索结果缺 cid 与宽高 —— 先用 `view` 接口补齐（顺便补标题/封面）。
     */
    private FeedVideo resolveOne(Entry e, DouyinApi.FeedMode mode) {
        try {
            if (e.cid <= 0) {
                String q = e.bvid.isEmpty() ? ("aid=" + e.aid) : ("bvid=" + e.bvid);
                JsonObject d = objOf(parse(get("/x/web-interface/view?" + q)), "data");
                if (d == null) return null;
                e.cid = optLong(d, "cid");
                JsonObject dim = objOf(d, "dimension");
                if (dim != null) {
                    e.width = optInt(dim, "width");
                    e.height = optInt(dim, "height");
                }
                if (e.title.isEmpty()) e.title = optString(d, "title");
                if (e.cover.isEmpty()) e.cover = normalizeCover(optString(d, "pic"));
            }
            if (e.cid <= 0) return null;

            // 方向兜底（search 在列表阶段筛不了，只能到这儿才知道）
            if (e.width > 0 && e.height > 0) {
                boolean landscape = (e.width > e.height);
                if (landscape != (mode == DouyinApi.FeedMode.LANDSCAPE)) return null;
            }

            String body = get("/x/player/playurl?bvid=" + e.bvid + "&cid=" + e.cid
                    + "&qn=" + QN_720P + "&fnval=1&fnver=0&fourk=1");
            String url = pickDurl(body);
            if (url.isEmpty()) return null;
            return new FeedVideo(e.title, url, e.cover, e.bvid, e.authorName,
                    e.width, e.height, REFERER);
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== HTTP ====================

    /** 首次使用时访问一次网站首页，拿 buvid3 之类的 cookie（探测时是带着它跑的） */
    private void ensureCookie() {
        if (!mCookie.isEmpty()) return;
        try {
            Request req = new Request.Builder()
                    .url("https://www.bilibili.com/")
                    .header("User-Agent", UA)
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                StringBuilder sb = new StringBuilder();
                for (String c : resp.headers("Set-Cookie")) {
                    String kv = c.split(";", 2)[0];
                    if (kv.startsWith("buvid") || kv.startsWith("b_nut")) {
                        if (sb.length() > 0) sb.append("; ");
                        sb.append(kv);
                    }
                }
                mCookie = sb.toString();
            }
        } catch (Throwable t) {
            mCookie = "";   // 拿不到就算了，直接裸请求（实测也能成功）
        }
    }

    private String get(String path) throws IOException {
        Request.Builder rb = new Request.Builder()
                .url(HOST + path)
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .header("Origin", "https://www.bilibili.com")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9");
        if (!mCookie.isEmpty()) rb.header("Cookie", mCookie);
        try (Response resp = client.newCall(rb.build()).execute()) {
            if (resp.body() == null) throw new IOException("B站空响应");
            String body = resp.body().string();
            if (body.isEmpty()) throw new IOException("B站返回空内容");
            return body;
        }
    }

    /** 游标自增并取模（让同一个源每次拿到不同的一页） */
    private int next(String key, int max) {
        Integer v = mCursor.get(key);
        int cur = (v == null || v < 0) ? 0 : v;
        mCursor.put(key, (cur + 1) % Math.max(1, max));
        return cur;
    }

    // ==================== 纯函数（便于单测） ====================

    /** 列表的一条（还没解析播放地址） */
    static final class Entry {
        String bvid = "";
        long aid;
        long cid;
        String title = "";
        String cover = "";
        String authorName = "";
        int width;
        int height;
    }

    /** 热门 / 分区排行：列表在 `data.list`，**带 cid 与 dimension** */
    static List<Entry> parseRankingLike(String json) {
        return parseList(arrOf(objOf(parse(json), "data"), "list"));
    }

    /** 标签热门：`data` 直接就是数组 */
    static List<Entry> parseTagTop(String json) {
        JsonObject root = parse(json);
        JsonElement el = root.get("data");
        return (el != null && el.isJsonArray()) ? parseList(el.getAsJsonArray()) : new ArrayList<Entry>();
    }

    /** 搜索结果：列表在 `data.result`，**没有 cid 与 dimension**，且标题带 `em` 高亮标签 */
    static List<Entry> parseSearch(String json) {
        List<Entry> out = parseList(arrOf(objOf(parse(json), "data"), "result"));
        for (Entry e : out) {
            e.title = stripTags(e.title);
        }
        return out;
    }

    private static List<Entry> parseList(JsonArray arr) {
        List<Entry> out = new ArrayList<>();
        if (arr == null) return out;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            Entry e = new Entry();
            e.bvid = optString(o, "bvid");
            e.aid = optLong(o, "aid");
            e.cid = optLong(o, "cid");
            e.title = optString(o, "title");
            e.cover = normalizeCover(optString(o, "pic"));
            JsonObject dim = objOf(o, "dimension");
            if (dim != null) {
                e.width = optInt(dim, "width");
                e.height = optInt(dim, "height");
            }
            JsonObject owner = objOf(o, "owner");
            if (owner != null) e.authorName = optString(owner, "name");
            if (e.authorName.isEmpty()) e.authorName = optString(o, "author");
            // 至少要有一个能定位视频的 id
            if (e.bvid.isEmpty() && e.aid <= 0) continue;
            out.add(e);
        }
        return out;
    }

    /** 去掉搜索标题里的 `em` 高亮标签 */
    static String stripTags(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replaceAll("<[^>]*>", "");
    }

    /**
     * 从 playurl 响应里取 **durl 单文件直链**。**纯函数**。
     *
     * 取不到就返回空串（调用方会丢掉这条视频）—— **绝不要退回去用 dash 的地址**：
     * dash 是纯视频轨、没有声音，老人看的视频没声音等于坏的。
     */
    static String pickDurl(String json) {
        JsonObject root = parse(json);
        JsonArray durl = arrOf(objOf(root, "data"), "durl");
        if (durl == null || durl.size() == 0) return "";
        JsonElement firstEl = durl.get(0);
        JsonObject first = (firstEl != null && firstEl.isJsonObject()) ? firstEl.getAsJsonObject() : null;
        String u = first == null ? "" : optString(first, "url");
        return u.startsWith("http") ? u : "";
    }

    /** B 站封面给的是 http，统一升成 https（老系统上明文 http 更容易被拦） */
    static String normalizeCover(String pic) {
        if (pic == null || pic.isEmpty()) return "";
        if (pic.startsWith("http://")) return "https://" + pic.substring("http://".length());
        return pic;
    }

    /**
     * 拆源名：bili 本身 → {popular, ""}；bili:region:211 → {region, 211}；
     * bili:search:广场舞 → {search, 广场舞}（关键词放最后一段，允许它自己带冒号）。
     * **纯函数**。
     */
    static String[] splitSource(String src) {
        String kind = "popular";
        String arg = "";
        String[] seg = (src == null ? "" : src).split(":", 3);
        if (seg.length >= 2 && !seg[1].trim().isEmpty()) kind = seg[1].trim();
        if (seg.length >= 3) arg = seg[2].trim();
        return new String[]{kind, arg};
    }

    static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return true;
    }

    // ---- 小工具（与 DouyinApi 里那几个同源，这里自带一份，避免动到那边的可见性） ----

    /**
     * 宽容解析：接口在风控 / 异常时**可能回 HTML 或空串**，这里不该炸 ——
     * 一律当成"没有内容"（返回空对象），由调用方按"列表为空"去换源。
     */
    private static JsonObject parse(String json) {
        try {
            JsonElement el = JsonParser.parseString(json);
            return (el != null && el.isJsonObject()) ? el.getAsJsonObject() : new JsonObject();
        } catch (Throwable t) {
            return new JsonObject();
        }
    }

    private static JsonObject objOf(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    private static JsonArray arrOf(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
    }

    private static String optString(JsonObject o, String key) {
        if (o == null) return "";
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static int optInt(JsonObject o, String key) {
        if (o == null) return 0;
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsInt();
    }

    private static long optLong(JsonObject o, String key) {
        if (o == null) return 0;
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsLong();
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
}
