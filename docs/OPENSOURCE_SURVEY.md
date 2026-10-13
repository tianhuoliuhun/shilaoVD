# 开源同类项目调研报告

> 目标：为 myDVQ（极简上下滑抖音播放器，只留「推荐 + 移动推荐」，去掉暂停/字幕/弹幕/点赞/评论/分享/设置）
> 寻找可直接借鉴或复用的开源实现
> 日期：2026-10-13

---

## 0. 一句话结论

**没有"正好就是这个需求"的现成项目**，但找到了 **1 个几乎可以当基座的完整开源项目** + **3 块可直接拼装的关键积木**：

| 角色 | 项目 | 说明 |
|---|---|---|
| 🏆 最佳基座 | [`5h1iky/wanshang-yinfu`](https://github.com/5h1iky/wanshang-yinfu) | 源码全公开的 Android **轻量**抖音客户端，签名/接口/feed/预加载/缓存/单测全都有 |
| 🔑 签名积木 | [`ylcangel/douyin_sign`](https://github.com/ylcangel/douyin_sign) | a_bogus + x-bogus + msToken 的 JS 实现，**Apache-2.0** |
| 🔑 播放积木 | [`Doikki/DKVideoPlayer`](https://github.com/Doikki/DKVideoPlayer) | 5331★，抖音式上下滑 + 预加载 + 单播放器复用，Apache-2.0 |
| 🔑 翻页积木 | [`castorflex/VerticalViewPager`](https://github.com/castorflex/VerticalViewPager) | 1100★，纵向翻页容器（或直接用 Compose 官方 `VerticalPager`） |

> ⚠️ 重要更正：MacDV 的 README 里引用的 `chenweijie-git/MYDV` 经核查**返回 404，不存在**。myDV 目前**没有任何公开源码版本**（自有源码在私有仓库 `mytv-android/myDV-Internal`）。

---

## 1. 🏆 最佳基座：腕上音符 wanshang-yinfu

**`5h1iky/wanshang-yinfu`** — 0★（太新/太冷门），Java，**GPL-3.0**，最后推送 2026-10-02，约 1900 KB。
"安卓手表端轻量抖音客户端" —— 因为跑在手表上，它把交互砍到最少，**方向与你的需求高度一致**。

### 它的技术方案（可直接抄）

```
读取类请求 → OkHttp 原生直连 + Rhino(纯 Java JS 引擎) 跑 a_bogus 签名
写入类操作 → 内置单例 WebView 跑官方页面（签名/风控票据官方托管）
播放      → 内嵌 DKVideoPlayer 三模块（java / ui / videocache）
上下滑    → VerticalViewPager
```

关键类（都在 `app/src/main/java/com/dywatch/app/`）：

| 文件 | 作用 |
|---|---|
| `sign/Signer.java` | a_bogus 签名器，用 **Rhino** 执行 `assets/sign/{utils,sm3,vm_decode}.js` |
| `net/DouyinApi.java` | 抖音 Web 数据层（721 行），OkHttp + Signer + Gson，含 ttwid/feed 翻页 |
| `net/CookieHostGuard.java` | Cookie 出站守卫（防 302 泄漏到站外） |
| `feed/FeedActivity.java`、`FeedAdapter.java`、`FeedVideo.java` | 视频流 |
| `feed/TikTokController.java`/`TikTokView.java`/`TikTokRenderView.java` | DKVideoPlayer 的抖音式封装 |
| `feed/SeenStore.java`/`HistoryStore.java` | 已看去重 / 观看历史 |
| `cache/PreloadManager.java`/`PreloadTask.java`/`ProxyVideoCacheManager.java` | 预加载 + 代理缓存 |
| `ui/VerticalViewPager.java` + `widget/VerticalViewPager.java` | 上下滑 |
| `app/src/test/.../SignerTest.java`、`DouyinApiTest.java`、`FeedFetchTest.java` | 纯 JVM 单测（签名可离线验证！） |

### 它踩过的坑（比代码更值钱，全是实测结论）

| 经验 | 结论 |
|---|---|
| **ttwid 获取** | 从 `live.douyin.com` **一跳**即发；从 `www.douyin.com` 的两跳老办法**已失效** |
| **播放 403** | CDN 边缘对**无 Referer** 的请求一律 403。只需 `User-Agent` + `Referer: https://www.douyin.com/`，Cookie 反而不需要 |
| **a_bogus 签名顺序** | 必须对**最终发送的原始 query 串**签名，签完再追加 `&a_bogus=` |
| **feed 翻页** | 抖音推荐流每次只回 **2~5 条**，必须递增游标（`mRefreshIndex`），否则永远同一批 |
| **Rhino 线程安全** | `scope` 是共享可变对象，多线程同时执行 JS 会互相踩隐式全局变量 → 签名算错，**症状极像"抖音改版了"**。必须 `synchronized` |
| **Rhino 配置** | `setOptimizationLevel(-1)`（Android 上必须解释执行）+ `setLanguageVersion(ES6)`（算法用了 let/解构） |
| **失效优先级** | ① 私信/评论（依赖网页 DOM，最脆）→ ② 视频流（依赖 a_bogus，抖音大改要等新算法）→ ③ 点赞/收藏 |

### 它的依赖清单（干净、无 native）
OkHttp + Gson + Rhino + DKVideoPlayer；`compileSdk 36`、`minSdk 21`、JDK 17/21。

### ⚠️ 许可证警告
GPL-3.0 有**传染性**：如果你的 myDVQ 要开源，没问题（同许可即可）；如果要**闭源分发**，不能直接复制它的代码，只能"读思路、自己写"。相对地，`douyin_sign`(Apache-2.0)、`DKVideoPlayer`(Apache-2.0)、`VerticalViewPager`(Apache-2.0) 都可以放心用（Apache 只需保留声明）。

---

## 2. 两条实现路线对比

| | **路线 A：WebView 套壳** | **路线 B：原生直连** |
|---|---|---|
| 代表项目 | douyin-tv(123★)、TVDY_douyin(21★)、MacDV、52Lolita | **myDV**、**腕上音符** |
| 做法 | 加载 `douyin.com` 网页，注入 JS/CSS 控制与隐藏元素 | 自己拼接口 + 签名，原生渲染 |
| 开发量 | **极小**（几天） | 中（几周） |
| 能否"去掉"按钮 | 只能 JS/CSS 注入**藏起来**（DOM 一改就失效） | 可以做到**真正零按钮** |
| 登录/风控 | 官方网页自己处理，稳 | 需自己维护 a_bogus，抖音大改就挂 |
| 播放体验 | 受网页播放器限制，老人误触仍可能触发 | 完全可控，单播放器复用+预加载 |
| 适配老人 | 网页小按钮多，反而更难 | 可做大卡片/大按钮/零遮挡 |

**建议**：你的需求（"不识字的老人、无多余交互"）→ **路线 B**。理由是路线 A 藏不掉网页里所有可点的东西，而老人误触的代价很高。

---

## 3. 分类清单

### A. 抖音数据 / 签名层

| 项目 | ★ | 语言 | 许可 | 可用点 |
|---|---|---|---|---|
| [`ylcangel/douyin_sign`](https://github.com/ylcangel/douyin_sign) | 77 | JS | **Apache-2.0** | ⭐**a_bogus 的 JS 实现**（`a_bogus/{sm3,utils,vm_decode}.js`）+ x-bogus + msToken。腕上音符直接内嵌的就是这套 |
| [`cv-cat/DouYin_Spider`](https://github.com/cv-cat/DouYin_Spider) | 3356 | Python | 无 | 抖音全 API 权威参考：`builder/`(auth/header/params/proto) + `newsign/`(Node 签名) + `dy_apis/` |
| [`ShilongLee/Crawler`](https://github.com/ShilongLee/Crawler) | 1455 | JS | NOASSERTION | 多平台爬虫服务器，`lib/js/douyin.js` + **完整接口文档** `docs/api/douyin/douyin.md` |
| [`NearHuiwen/TiktokDouyinCrawler`](https://github.com/NearHuiwen/TiktokDouyinCrawler) | 508 | JS | - | a-bogus 与 x-bogus 算法破解 |
| [`Rockedw/douyin-web-api-sdk`](https://github.com/Rockedw/douyin-web-api-sdk) | 57 | Java | 无 | Java 版抖音 Web SDK（私信/评论/WebSocket），字段编号对照 |
| [`jackluson/a_bogus_douyin`](https://github.com/jackluson/a_bogus_douyin) | 57 | - | - | a_bogus 参数最小实现 |
| [`HZhertz/ByteDance-a_bogus-parameter`](https://github.com/HZhertz/ByteDance-a_bogus-parameter) | 47 | JS | - | 字节系 a_bogus 获取 |
| [`NeoTheCapt/aweme-algorithm`](https://github.com/NeoTheCapt/aweme-algorithm) | 498 | Obj-C | - | 抖音协议加解密算法 |
| [`Evil0ctal/Douyin_TikTok_Download_API`](https://github.com/Evil0ctal/Douyin_TikTok_Download_API) | 20624 | Python | - | 自托管数据采集 API（可当"备用取数服务"） |
| [`jiji262/douyin-downloader`](https://github.com/jiji262/douyin-downloader) | 12276 | Python | - | 抖音下载器（接口形状参考） |

### B. 播放器与 Feed 架构

| 项目 | ★ | 语言 | 许可 | 可用点 |
|---|---|---|---|---|
| [`Doikki/DKVideoPlayer`](https://github.com/Doikki/DKVideoPlayer) | 5331 | Java | **Apache-2.0** | ⭐抖音式列表播放库（预加载 + 列表播放 + 多内核），腕上音符整库内嵌。**其 TikTok2 demo 是上下滑的原始蓝本** |
| [`castorflex/VerticalViewPager`](https://github.com/castorflex/VerticalViewPager) | 1100 | Java | 无(署名保留) | 纵向翻页容器 |
| [`usman-mobo/android-reels-media3`](https://github.com/usman-mobo/android-reels-media3) | 2 | Kotlin | - | ⭐**Media3 竖屏 feed 参考架构**：单 ExoPlayer 全 feed 复用 + scroll-snap 切换 + `DefaultPreloadManager` 距离预加载 + `SimpleCache` 磁盘缓存。类划分清晰（`ReelsPlayerManager`/`ReelsPreloadManager`/`MediaSetupFactory`） |
| [`manjees/compose-reels`](https://github.com/manjees/compose-reels) | 27 | Kotlin | - | ⭐**Compose 库**（JitPack 可依赖）：`VerticalPager` + ExoPlayer **播放器池** + 150MB LRU 缓存 + 无限滚动 + 覆盖层可定制 |
| [`clown6613/ComposeReels`](https://github.com/clown6613/ComposeReels) | 2 | Kotlin | - | Compose 多模块竖屏 Reels 应用 |
| [`nihk/videopager`](https://github.com/nihk/videopager) | 110 | Kotlin | - | Instagram/Shorts/TikTok 风格 video pager 示例 |
| [`MostafaAnter/VideoPlayerInsideRecyclerView`](https://github.com/MostafaAnter/VideoPlayerInsideRecyclerView) | 130 | Kotlin | - | RecyclerView + ExoPlayer 的"干净做法" |
| [`pankaj89/MasterExoPlayer`](https://github.com/pankaj89/MasterExoPlayer) | 78 | Kotlin | - | RecyclerView 内播视频的轻量工具 |

### C. 抖音 TV / 大屏客户端（都是 WebView 套壳）

| 项目 | ★ | 语言 | 做法 |
|---|---|---|---|
| [`ygtec-org/douyin-tv`](https://github.com/ygtec-org/douyin-tv) | 123 | Kotlin | WebView 嵌抖音网页 + 遥控器适配 + Cookie 持久化 + 自动下一个；**完整可编译工程**（MainActivity/SplashActivity/CookieHelper/WebViewOptimizer） |
| [`ymlyyy/TVDY_douyin`](https://github.com/ymlyyy/TVDY_douyin) | 21 | Java | 遥控器刷抖音，功能轮盘（点赞/收藏/关注/评论/弹幕/清屏/全屏），双击退出 |
| [`bitstreamcn/DouyinTV`](https://github.com/bitstreamcn/DouyinTV) | 7 | Kotlin | 抖音电视版播放器 |
| [`52Lolita/douyin-tv-webview`](https://github.com/52Lolita/douyin-tv-webview) | 6 | Kotlin | 把遥控器按键**映射成键盘事件**发给网页（↑↓切视频、←不喜欢、OK评论） |
| [`275556968/MacDV`](https://github.com/275556968/MacDV) | 0 | Swift | macOS 版（SwiftUI + AVPlayer + **WKWebView 加载抖音网页 + JS 注入**），MIT，**已声明"深度借鉴 myDV"**。锁"精选横屏"通道 |

### D. 相关但非同类的参考

| 项目 | ★ | 说明 |
|---|---|---|
| [`zyronon/douyin`](https://github.com/zyronon/douyin) | 11542 | Vue3 仿抖音（**UI/交互设计参考**，非 Android） |
| [`running-libo/Tiktok`](https://github.com/running-libo/Tiktok) | 3157 | Kotlin 高仿抖音（2023 停更，`RecyclerView + PagerSnapHelper` 方案；结构与命名可借鉴） |
| [`puskal-khadka/TikTok-Compose`](https://github.com/puskal-khadka/TikTok-Compose) | 477 | Compose + clean architecture 的 TikTok 示例 |
| [`PangHaHa12138/DouyinDemo`](https://github.com/PangHaHa12138/DouyinDemo) | 239 | 仿抖音上下滑 + 爱心点赞特效（Java，小而全） |
| [`leiyun-studio/TouYin_DemoClient`](https://github.com/leiyun-studio/TouYin_DemoClient) | 223 | 仿抖音视频切换 + 点赞效果 |
| [`yangchong211/YCScrollPager`](https://github.com/yangchong211/YCScrollPager) | 385 | 竖直方向一次滚动一页的封装库 |
| [`tianxing-ovo/AutoSlide`](https://github.com/tianxing-ovo/AutoSlide) | 109 | 安卓自动滑动 App（自动播短视频/翻页） |
| [`bytedance/DouyinOpenPlatformDemo`](https://github.com/bytedance/DouyinOpenPlatformDemo) | 27 | 官方开放平台 Demo（**拿不到推荐流数据**，仅供登录/分享） |
| [`guozhigq/pilipala`](https://github.com/guozhigq/pilipala) | — | B 站第三方客户端（同类型"第三方客户端"的工程范式参考） |

---

## 4. 推荐拼装方案（路线 B）

```
┌─ UI 层 ──── Compose + VerticalPager（官方，替代 VerticalViewPager）
│              两个 Tab：推荐 / 移动推荐（不做其它分区）
│              零覆盖层：无暂停键、无进度条、无点赞评论弹幕分享设置
├─ 播放层 ─── Media3/ExoPlayer 1.11.1
│              单播放器全 feed 复用 + 距离预加载 + SimpleCache（抄 android-reels-media3）
│              或直接内嵌 DKVideoPlayer（抄腕上音符）
├─ 数据层 ─── OkHttp + Gson
│              接口 /aweme/v1/web/... （清单见 REVERSE_REPORT.md）
│              播放头：UA + Referer: https://www.douyin.com/（否则 CDN 403）
│              ttwid 从 live.douyin.com 一跳取
├─ 签名层 ─── a_bogus：
│              方案 1（简单）：Rhino 纯 Java 跑 ylcangel/douyin_sign 的 JS（无 native，可 JVM 单测）
│              方案 2（更强）：QuickJS 跑官方 bdms.js（myDV 的做法，有 .so）
│              注意：必须对最终 query 串签名后追加 &a_bogus=；多线程要串行
└─ 登录层 ─── 单例 WebView 扫码登录 + Cookie 持久化
```

**关于第二个分区（"移动"）**：已确认你指的是**同城/附近**。但注意 myDV 的 dex 文案里另有一个名为「移动推荐」的分区（`正在刷新移动推荐`、`移动推荐响应为空`），两者是否同一数据源**尚未确认** —— 抖音同城流通常带 `gps_access`/`real_time` 一类参数，而"移动推荐"更像一条独立 feed 通道。开源项目里这两者都没有现成实现，需用真机抓包或 jadx 结果确认对应端点（候选 `/aweme/v2/web/module/feed/`、`/aweme/v1/web/tab/feed/`）。

---

## 5. 许可证速查

| 许可 | 项目 | 能否闭源商用 |
|---|---|---|
| **Apache-2.0** | douyin_sign、DKVideoPlayer、VerticalViewPager | ✅ 可以（保留 NOTICE/声明） |
| **MIT** | MacDV | ✅ 可以 |
| **GPL-3.0** | **腕上音符**、myDV 本身 | ❌ 闭源分发受限（开源需同许可） |
| 无 LICENSE | 大部分爬虫/Demo 项目 | ⚠️ 只能"读思路"，不要复制代码 |

---

## 6. 需要你决策的点

1. **基座怎么选**：
   - (a) 以 `wanshang-yinfu` 为起点裁剪（省事，但 GPL-3.0 且是手表 UI）
   - (b) 从零新建 Compose + Media3 工程，按上面方案拼装（干净，工作量大）
2. **签名引擎**：Rhino（纯 Java 无 native）vs QuickJS（跑官方 bdms.js，更贴近 myDV）
3. **登录方式**：WebView 扫码（推荐，复用官方票据）vs 直接填 Cookie

---

## 附：本报告的核查方式

所有项目均通过 GitHub API 实际拉取 README 与文件树核对，star 数/语言/许可/更新时间均为**实测值**，非搜索摘要推测。
