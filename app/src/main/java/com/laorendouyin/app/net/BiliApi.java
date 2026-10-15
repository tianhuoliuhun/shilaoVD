package com.laorendouyin.app.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.laorendouyin.app.feed.FeedVideo;

import java.io.IOException;
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
 * 哔哩哔哩（B 站）内容源 —— 热门视频 + 播放直链解析。
 *
 * 与抖音的两处**关键差异**（2026-10-15 实测，脚本见 probe/probe_bili.js、probe_bili_play.js）：
 *
 * ① 🔴 **播放地址必须带 `Referer: https://www.bilibili.com/`**。
 *    实测 4/4 条：带 B 站 Referer → `206 video/mp4`；带抖音 Referer → `403`；空 Referer → `403`。
 *    （UA 反而不重要）所以 Referer 必须**跟着视频走**，见 {@link FeedVideo#referer}。
 *
 * ② **列表接口不给播放地址**，必须对每条视频再请求一次 `x/player/playurl`。
 *    → 这里用固定并发池批量解析，并且**只解析与当前屏幕方向匹配的那几条**，
 *      避免把请求浪费在"解析出来也会被方向过滤掉"的视频上。
 *
 * 为什么用 `fnval=1`：它返回 **durl 单文件 mp4**，而我们的老人播放器只认单个 URL。
 * 默认的 `fnval=16` 是 DASH 音视频分离流（视频轨 + 音频轨分开），直接播会**没有声音**。
 *
 * 清晰度取 `qn=64`（720P）：实测游客态 `accept_quality=[64,16]`，
 * ⚠️ 且**必须直接要 64** —— 要 32（480P）不在清单里，会被降级成 16（360P）。
 */
public final class BiliApi {

    /** B 站 CDN / PCDN 认的 Referer */
    public static final String REFERER = FeedVideo.REFERER_BILIBILI;

    /** 与抖音侧一致的浏览器 UA（B 站这边实测 UA 不是必需，但带上更稳） */
    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36";

    private static final String HOST = "https://api.bilibili.com";

    /** 720P。⚠️ 别改成 32：480P 不在游客可用的 accept 清单里，会被降成 360P */
    static final int QN_720P = 64;

    /** 直链解析的并发数（每条一次请求，串行会慢到不可用） */
    private static final int RESOLVE_THREADS = 6;

    /** 单次最多解析这么多条直链：再多就要多等好几轮，得不偿失 */
    private static final int MAX_RESOLVE = 8;

    /** 热门列表取第几页（1~3 循环，让"刷完一批还有下一批"） */
    private static final int MAX_PAGE = 3;

    private final OkHttpClient client;

    private int mPage = 1;

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
     * 拉一批 B 站热门视频（已带上可直接播放的 720P 直链）。
     *
     * @param mode 只保留与这个方向一致的视频（竖屏拿竖的、横屏拿横的）
     * @throws IOException 列表为空、或一条都没解析出直链时抛（交给上层换源）
     */
    public List<FeedVideo> fetchPopular(int count, DouyinApi.FeedMode mode) throws IOException {
        ensureCookie();
        int ps = Math.max(count * 4, 20);
        String body = get("/x/web-interface/popular?ps=" + ps + "&pn=" + mPage);
        mPage = (mPage % MAX_PAGE) + 1;

        List<Entry> entries = parsePopular(body);
        if (entries.isEmpty()) {
            throw new IOException("B站热门列表为空：" + body.substring(0, Math.min(120, body.length())));
        }

        // 只挑方向匹配的（尺寸缺失的一律跳过 —— B 站列表基本都带 dimension）
        boolean wantLandscape = (mode == DouyinApi.FeedMode.LANDSCAPE);
        List<Entry> wanted = new ArrayList<>();
        for (Entry e : entries) {
            if (e.width <= 0 || e.height <= 0) continue;
            if ((e.height > e.width) == wantLandscape) continue;
            wanted.add(e);
            if (wanted.size() >= MAX_RESOLVE) break;
        }
        if (wanted.isEmpty()) {
            throw new IOException("B站热门这一页没有" + (wantLandscape ? "横" : "竖") + "屏视频");
        }

        List<FeedVideo> out = resolveAll(wanted);
        if (out.isEmpty()) {
            throw new IOException("B站热门没有解析出可用直链（" + wanted.size() + " 条都失败）");
        }
        com.laorendouyin.app.util.AppLog.i("feed", "B站源：解析出 " + out.size() + " 条可播（"
                + (wantLandscape ? "横" : "竖") + "屏）");
        return out.size() > count ? new ArrayList<>(out.subList(0, count)) : out;
    }

    /** 并发解析直链；单条失败只丢它自己，不影响其余 */
    private List<FeedVideo> resolveAll(List<Entry> entries) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(RESOLVE_THREADS, entries.size()));
        try {
            List<Future<FeedVideo>> futures = new ArrayList<>();
            for (final Entry e : entries) {
                futures.add(pool.submit(new Callable<FeedVideo>() {
                    @Override
                    public FeedVideo call() {
                        return resolveOne(e);
                    }
                }));
            }
            List<FeedVideo> out = new ArrayList<>();
            for (Future<FeedVideo> f : futures) {
                try {
                    FeedVideo v = f.get(20, TimeUnit.SECONDS);
                    if (v != null) out.add(v);
                } catch (Throwable ignore) {
                    // 单条超时/失败：跳过（热门列表里少一条无所谓）
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 把一条列表条目解析成可播放的 FeedVideo；失败返回 null */
    private FeedVideo resolveOne(Entry e) {
        try {
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

    // ==================== 纯函数（便于单测） ====================

    /** 热门列表的一条（还没解析播放地址） */
    static final class Entry {
        String bvid = "";
        long cid;
        String title = "";
        String cover = "";
        String authorName = "";
        int width;
        int height;
    }

    /**
     * 解析热门列表 JSON。**纯函数**。
     * 只保留「有 bvid 且有 cid」的条目 —— 缺任何一个都无法继续解析播放地址。
     */
    static List<Entry> parsePopular(String json) {
        List<Entry> out = new ArrayList<>();
        JsonObject root = parse(json);
        JsonArray list = arrOf(objOf(root, "data"), "list");
        if (list == null) return out;
        for (JsonElement el : list) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            Entry e = new Entry();
            e.bvid = optString(o, "bvid");
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
            if (!e.bvid.isEmpty() && e.cid > 0) out.add(e);
        }
        return out;
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

    // ---- 小工具（与 DouyinApi 里那几个同源，这里自带一份，避免动到那边的可见性） ----

    /**
     * 宽容解析：接口在风控 / 异常时**可能回 HTML 或空串**，这里不该炸 ——
     * 一律当成没有内容（返回空对象），由调用方按列表为空去换源。
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
}
