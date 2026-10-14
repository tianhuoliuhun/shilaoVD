package com.laorendouyin.app.cache;

import android.content.Context;

import com.danikula.videocache.HttpProxyCacheServer;
import com.danikula.videocache.headers.HeaderInjector;
import com.laorendouyin.app.net.DouyinApi;
import com.laorendouyin.app.util.AppLog;

import java.io.File;
import java.util.Map;

/**
 * 视频缓存的代理服务（AndroidVideoCache）。
 *
 * 除了构造代理，这里还承担**降级开关**的职责（2026-10-14 新增）：
 * 一旦发现代理不可用（构造失败 / 播放时被判定有问题），就置 {@code sProxyBroken}，
 * 之后所有播放一律直连原始地址 —— 宁可没有缓存加速，也不能因为"代理坏了"而播不出来。
 */
public class ProxyVideoCacheManager {

    /**
     * 视频缓存上限（2026-10-02，代码审计 M7：512MB → 128MB）。
     *
     * 先说清楚上一版审计里说错的地方：这个库**确实**会淘汰（LruDiskUsage 按 lastModified
     * 从旧到新删，到总量 ≤ 上限为止），所以不是"只涨不降"。真正的问题是**上限本身**：
     *   ① 手表整机存储本来就只有几 GB，给视频缓存划 512MB 是手机的量级；
     *   ② 淘汰只在"一次播放会话结束 / 下载完成 / 命中已缓存 URL"时才跑，
     *      而 FileCache.append 不看上限 —— 下载中可以远远冲过 512MB 再一口气删；
     *   ③ 淘汰是**异步单线程**、失败还被静默吞掉（库里的 Logger 默认全关）。
     * 128MB 对"预加载 + 回看最近几条"完全够用（单条约 1MB），且离危险区更远。
     */
    private static final long MAX_CACHE_BYTES = 128L * 1024 * 1024;

    private static HttpProxyCacheServer sharedProxy;

    /**
     * 代理是否已被判定为不可用。
     *
     * 置位后**不再回头**（进程内）：代理服务要么一开始就起不来，要么在播放中被发现有问题，
     * 两种情况都不适合"再试一次"—— 反复在代理上跌倒只会让用户看到反复的播放失败。
     * 用户下次冷启动会重新尝试。
     */
    private static volatile boolean sProxyBroken = false;

    private ProxyVideoCacheManager() {
    }

    /** 代理现在还能用吗？调用方据此决定走代理还是直连 */
    public static boolean isProxyUsable() {
        return !sProxyBroken && sharedProxy != null;
    }

    /** 判定代理不可用 → 之后全程直连（首次置位时落一条日志，方便取证） */
    public static void markProxyBroken(String why) {
        if (!sProxyBroken) {
            sProxyBroken = true;
            AppLog.i("cache", "代理判定不可用，后续全部走直连：" + why);
        }
    }

    public static HttpProxyCacheServer getProxy(Context context) {
        if (sProxyBroken) return null;
        if (sharedProxy == null) {
            synchronized (ProxyVideoCacheManager.class) {
                if (sharedProxy == null && !sProxyBroken) {
                    try {
                        sharedProxy = newProxy(context);
                    } catch (Throwable e) {
                        // 构造失败（端口被占、缓存目录建不了…）→ 直接判定不可用，
                        // 而不是把异常抛给上层让 App 崩掉
                        markProxyBroken("构造失败: " + e.getClass().getSimpleName() + " " + e.getMessage());
                        return null;
                    }
                }
            }
        }
        return sharedProxy;
    }

    private static HttpProxyCacheServer newProxy(Context context) {
        return new HttpProxyCacheServer.Builder(context)
                .maxCacheSize(MAX_CACHE_BYTES)
                // 预加载走代理时，源站请求由代理自己发，播放器上的 setUrl 头到不了那里——
                // 所以 Referer 必须在代理这一层注入，否则预加载过的条目仍会 403。
                .headerInjector(new HeaderInjector() {
                    @Override
                    public Map<String, String> addHeaders(String url) {
                        return DouyinApi.playHeaders();
                    }
                })
                //缓存路径，不设置默认在sd_card/Android/data/[app_package_name]/cache中
//                .cacheDirectory()
                .build();
    }

    /**
     * 把某条视频的缓存（正式文件 + 下载中的临时文件）删掉。
     *
     * 用在**播放失败后的降级**里：既然用这条缓存播不出来，就说明它是坏的（写到一半断了、
     * 或者源站已经拒了），留着只会让下次又踩同一个坑 —— 删掉，并改直连重播。
     */
    public static void evict(Context context, String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) return;
        try {
            HttpProxyCacheServer proxy = getProxy(context);
            if (proxy == null) return;
            File finalFile = proxy.getCacheFile(rawUrl);
            if (finalFile != null && finalFile.exists() && finalFile.delete()) {
                AppLog.i("cache", "已清除坏缓存 " + finalFile.getName());
            }
            File tempFile = proxy.getTempCacheFile(rawUrl);
            if (tempFile != null && tempFile.exists() && tempFile.delete()) {
                AppLog.i("cache", "已清除坏临时缓存 " + tempFile.getName());
            }
        } catch (Throwable e) {
            // 清理失败不影响主流程（最坏就是脏缓存留着）
            AppLog.i("cache", "清坏缓存失败：" + e);
        }
    }

    /** 人类可读容量（设置页那行用；手表上看到"128.0 MB"比"134217728"有用） */
    public static String formatSize(long bytes) {
        if (bytes < 0) return "0 MB";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    /** 当前视频缓存占用（字节）。库里没有这个 API，只能自己遍历缓存目录求和 */
    public static long cacheSize(Context context) {
        try {
            HttpProxyCacheServer proxy = getProxy(context);
            if (proxy == null) return 0;
            File[] files = proxy.getCacheRoot().listFiles();
            if (files == null) return 0;
            long total = 0;
            for (File f : files) {
                if (f.isFile()) total += f.length();
            }
            return total;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 清空视频缓存（2026-10-02，代码审计 M7）。
     *
     * 实现取舍（真机实测踩到的）：预加载只读 1MB 就停，所以缓存目录里**绝大多数是
     * `xxx.download` 临时文件**（只有整条下完才会改名成最终文件）。第一版我为了"别删
     * 正在下载中的文件"跳过了全部 .download —— 真机上点"清空视频缓存"的实测结果是
     * 容量纹丝不动（3.3MB → 3.3MB），等于这个按钮是假的。
     *
     * 现在全删。最坏情况是什么？正在播的那条，它的下载线程已经持有文件句柄，
     * 文件被 unlink 后仍然能继续读/写（Linux 语义），只是这一条的缓存没了 ——
     * 正是这一行提示里写明的"正在播放的那条会重下"。用户按的就是"清空"，就按清空做。
     *
     * 注意：`AndroidVideoCache.db` 里的行（URL → mime/长度索引）这个库**没有任何删除 API**，
     * 只能留着。它只存 URL 字符串，体积很小，不影响可用空间。
     */
    public static boolean clearAllCache(Context context) {
        try {
            HttpProxyCacheServer proxy = getProxy(context);
            if (proxy == null) return true;
            File root = proxy.getCacheRoot();
            File[] files = root.listFiles();
            if (files == null) return true;
            boolean allOk = true;
            for (File f : files) {
                if (!f.isFile()) continue;
                if (!f.delete()) allOk = false;
            }
            return allOk;
        } catch (Throwable t) {
            return false;
        }
    }
}
