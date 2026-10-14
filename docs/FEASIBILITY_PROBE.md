# 抖音接口实测报告（未登录直连）

> 目的：在动手裁剪代码之前，先证明「不登录也能拿到推荐流」这条路线走得通，并确定「第二个分区」到底能不能做。
> 方法：Node 22 + **基座内嵌的 `ylcangel/douyin_sign` 签名 JS**（`utils.js` / `sm3.js` / `vm_decode.js`）生成 `a_bogus`；从 `live.douyin.com` 取游客 `ttwid`；请求头 `UA(Chrome135/Win) + Referer`。
> 脚本：`probe/*.js`　原始结果：`probe/*.json`　日期：2026-10-13（2026-10-14 补充第四节「档位机制」）

---

## 一、结果总览

| 能力 | 端点 / 参数 | 结果 |
|---|---|---|
| **推荐流（免登录）** | `GET /aweme/v1/web/tab/feed/` | ✅ **200 + `status_code=0` + 真实视频** |
| **翻页** | 同上 + `refresh_index` 递增 | ✅ 有效（第 1 页 2 条 → 第 2 页 6 条） |
| **频道流** | `GET /aweme/v1/web/channel/feed/?tag_id=<频道id>` | ✅ **200 + 10 条，内容与频道确实匹配** |
| **频道表** | `GET /aweme/v1/web/home/channel/setting/` | ✅ 返回 17 个频道定义 |
| **播放地址** | `play_addr.url_list` 中的 `www.douyin.com` 跳转候选 | ✅ **HTTP 206 `video/mp4`**（带 UA + Referer + Range） |
| **档位（清晰度）控制** | 自拼 `play/` + `video_id` + `ratio=540p/720p/1080p` | ✅ **`ratio` 精确决定服务端给哪一档**（见第四节） |
| 同城 / 附近 | `nearby/feed`、`poi/nearby`、`location/feed` | ❌ **全部 404**（`Unsupported path(Janus)`） |
| `tag_id` 用在推荐接口 | `tab/feed?tag_id=300204` | ❌ **不生效**（返回内容与标签无关，都是泛推荐池） |
| `module/feed` | `/aweme/v1/web/module/feed/` | ❌ `status_code=4`（参数不对） |
| `module/feed` v2 | `/aweme/v2/web/module/feed/` | ❌ 404 |

### 实测证据（真实返回）

```
[sign] a_bogus len=180 sample=dyUfDzUiOZ5bKdKt8OcA9G1U...
[ttwid] live.douyin.com HTTP 200, set-cookie count=3

[A: tab/feed 推荐, 未登录] HTTP 200 status_code=0 awemes=2
     ├ 7679327437762727219  「石板煎牛排+锡纸烤土豆…」 @晚安食光机
     └ 7677165526757494051  「2026 洞穴恐怖片…」 @木鱼讲
[B: tab/feed refresh_index=2] HTTP 200 status_code=0 awemes=6      ← 翻页有效

[频道流 tag_id=300204 美食] HTTP 200 status_code=0 awemes=10
     ├ 7679376498364583177  「巧乐兹：早知道，烂在工厂了」 @努力的大黄啊
     ├ 7688573472280218943  「这一桌全熊宴…」 @野生阿翔和CC
     └ 7687513878061731098  「牛肉界的劳斯莱斯…和牛…」 @舌尖风味记      ← 确实是美食

[play] 7665569366657764654 -> HTTP 206 video/mp4 bytes=2048           ← 真能播
```

---

## 二、web 端可用的「分区」= 这 17 个频道

来自 `/aweme/v1/web/home/channel/setting/` 的真实返回：

| tag_id | 频道 | tag_id | 频道 |
|---|---|---|---|
| `0` | 全部 | `300213` | 知识 |
| `100000` | 公开课 | `300214` | 小剧场 |
| `300204` | **美食** | `300215` | **影视** |
| `300205` | **游戏** | `300216` | 生活vlog |
| `300206` | **二次元** | `300217` | 亲子 |
| `300207` | 体育 | `300218` | 汽车 |
| `300209` | **音乐** | `300219` | 三农 |
| `300220` | 动物 | `300221` | 旅行 |
| `300222` | 美妆穿搭 | | |

**注意：列表里没有「同城」，也没有「移动推荐」。**

---

## 三、关键结论

1. **免登录路线完全可行** ✅
   只需两样东西：游客 `ttwid`（从 `live.douyin.com` 一跳获取）+ `a_bogus`（Rhino 跑 3 个 JS）。
   不需要任何 Cookie / 登录态，推荐流照样给真实内容。

2. **`tag_id` 是两个接口的不同处境** ⚠️
   - 在 `tab/feed`（推荐流）上**无效** —— 加了也不会变内容；
   - 在 `channel/feed` 上**有效** —— 这是频道内容的正确接口。
   （基座 `DouyinApi.buildFeedQuery` 里那个 `tag_id=""` 是照浏览器抄的空值占位，别指望它能切频道。）

3. **「同城/附近」在当前路线上做不出来** ❌
   抖音 web 端**不提供**同城接口（3 个候选端点全部 404）。同城是移动 App 的独有能力，需要 App 端协议（X-Gorgon 一类），成本和风险都远高于 web 路线。

4. **myDV 的「移动推荐」不是 web 端分区**
   它是 myDV 自己的一条数据通道（很可能走 App 端接口或特殊参数），无法用 web 接口复刻。频道表里没有对应项。

5. **播放链路健康** ✅
   `www.douyin.com/aweme/v1/play/...` 跳转候选 → `206 video/mp4`。照基座 `playHeaders()` 带 `UA + Referer` 即可（**Cookie 不需要**）。

---

## 四、播放地址的档位机制（2026-10-14 补测）

`video.bit_rate[]` 是档位表（实测一条视频有 **23~25 档**），每档长这样：

| 字段 | 含义 |
|---|---|
| `gear_name` | 档位名，如 `normal_1080_0` / `normal_720_0` / `low_540_0` / `lower_540_0` |
| `bit_rate` | 码率（bps） |
| `is_h265` / `is_bytevc1` | 是否 HEVC / ByteVC1 —— **要跳过**（老设备没有软硬解支持） |
| `format` | `mp4`（单文件、含音视频）或 `dash`（**单轨**，单 URL 播出来只有画面没声音） |
| `play_addr.uri` | **所有档共用同一个 video_id**（不是每档一个） |
| `play_addr.url_list[]` | ① CDN 直连（带签名的 `/video/tos/...`，**实测一律 403**）；② `www.douyin.com/aweme/v1/play/?…&file_id=…&sign=…`（**档位签名候选**） |

### 两条播放路径

1. **档位签名候选**：URL 自带 `file_id` + `sign` + `biz_sign`，**服务端按签名给档，最准**。
2. **自拼兜底**：当某档的 `url_list` 里**没有** www 候选时（只剩必 403 的 CDN 直连），
   自己拼：

   ```
   https://www.douyin.com/aweme/v1/play/?video_id=<play_addr.uri>&ratio=540p|720p|1080p&line=0
   ```

   这个端点**只认 `ratio` 参数**。

### 实测：同一 video_id，只换 ratio

服务端返回**三条完全不同的 CDN 资源**，与档位表精确对应：

| 请求的 ratio | 返回资源（总大小） | 对应档位 |
|---|---|---|
| `540p` | `o0E99wIH5ij…`（208,642,361 B） | `normal_540_0` |
| `720p` | `5fb2b4690e84…`（202,534,344 B） | `normal_720_0` |
| `1080p` | `okXSIABqAgsE…`（274,401,973 B） | `normal_1080_0` |

> **这条实测的用处**：抖音给的 1080p 是 **H.264 High Profile @ Level 5.0**（`avc1.640032`），
> 老设备的解码器普遍报 `NoSupport` —— 必须在 **URL 这一层**就把档位压到 540p
> （即 High@L3.1），而不是指望播放器"自己挑一档能解的"。
> 复现脚本：`probe/probe_gear.js`

---

## 五、待你决定

原来的「推荐 + 移动（同城）」在 web 路线下只能改成：

| 方案 | 说明 |
|---|---|
| **A. 只保留「推荐」单分区** | 最简。不识字老人连分区切换都不用学，上下滑就是全部 |
| **B. 推荐 + 一个频道** | 从上面 17 个频道里挑（如影视 / 美食 / 音乐） |
| **C. 坚持要同城** | 必须换数据源（App 端协议 + 登录态），工作量和失效风险都大幅上升，不建议 |

---

## 附：探测脚本清单（`probe/`）

| 文件 | 作用 |
|---|---|
| `signer.js` | Node 版 a_bogus 签名器 + 设备参数复刻（各脚本共用） |
| `probe_douyin.js` | 签名自检 + ttwid + 推荐流/翻页 + 若干端点摸底 |
| `probe_segment.js` | 同城候选端点 + 频道表 |
| `probe_tagid.js` | 验证 `tag_id` 在推荐接口上是否生效 |
| `probe_channel.js` | 频道内容接口 + 移动端参数 + 精选候选 |
| `probe_play.js` | 播放地址可用性（HTTP 206 实测） |
| `probe_gear.js` | **档位结构** + `ratio` 与清晰度的对应关系（见第四节） |
