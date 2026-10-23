// 用基座内嵌的 a_bogus 签名 JS，在 Node 里实测抖音 Web 接口。
// 输出：stdout 只打 ASCII 状态行；详细结果写 result.json（避免 Windows 控制台中文乱码）。
'use strict';
const fs = require('fs');
const vm = require('vm');
const path = require('path');

const SIGN_DIR = '../.base/wanshang-yinfu/app/src/main/assets/sign';
const OUT = './result.json';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';

const log = [];
function say(s) { log.push(s); process.stdout.write(s.replace(/[^\x20-\x7e\n]/g, '?') + '\n'); }

// ---------- 1. 载入签名器 ----------
function loadSigner() {
  const ctx = vm.createContext({});
  const prelude = `
    var window = this; var self = this;
    var console = { log:function(){}, info:function(){}, warn:function(){}, error:function(){} };
    var navigator = { userAgent: ${JSON.stringify(UA)}, platform: 'Win32' };
    var innerWidth = 2048, innerHeight = 960, outerWidth = 2554, outerHeight = 1386;
    var screen = { availWidth: 2560, availHeight: 1392, width: 2560, height: 1440 };
  `;
  vm.runInContext(prelude, ctx, { filename: 'env.js' });
  for (const f of ['utils.js', 'sm3.js', 'vm_decode.js']) {
    const src = fs.readFileSync(path.join(SIGN_DIR, f), 'utf8');
    try {
      vm.runInContext(src, ctx, { filename: f });
      say('[sign] loaded ' + f + ' (' + src.length + ' bytes)');
    } catch (e) {
      say('[sign] FAILED ' + f + ': ' + e.message);
      throw e;
    }
  }
  say('[sign] typeof makeABogus = ' + typeof ctx.makeABogus);
  return ctx;
}

// ---------- 2. query 构造（严格复刻 DouyinApi.buildFeedQuery 的键序）----------
function buildFeedQuery(count, refreshIndex, tagId) {
  const p = [
    ['device_platform', 'webapp'], ['aid', '6383'], ['channel', 'channel_pc_web'],
    ['update_version_code', '170400'], ['pc_client_type', '1'], ['pc_libra_divert', 'Windows'],
    ['support_h265', '1'], ['support_dash', '1'], ['version_code', '170400'], ['version_name', '17.4.0'],
    ['cookie_enabled', 'true'], ['screen_width', '2560'], ['screen_height', '1440'],
    ['browser_language', 'zh-CN'], ['browser_platform', 'Win32'], ['browser_name', 'Chrome'],
    ['browser_version', '135.0.0.0'], ['browser_online', 'true'], ['engine_name', 'Blink'],
    ['engine_version', '135.0.0.0'], ['os_name', 'Windows'], ['os_version', '10'],
    ['cpu_core_num', '20'], ['device_memory', '8'], ['platform', 'PC'],
    ['downlink', '0.55'], ['effective_type', '3g'], ['round_trip_time', '500'],
    ['count', String(count)], ['refresh_index', String(refreshIndex)], ['tag_id', tagId || ''],
  ];
  return p.map(([k, v]) => k + '=' + v).join('&');
}

// ---------- 3. HTTP ----------
async function getTtwid() {
  const r = await fetch('https://live.douyin.com/646454278948', {
    headers: { 'User-Agent': UA }, redirect: 'follow',
  });
  const sc = typeof r.headers.getSetCookie === 'function'
    ? r.headers.getSetCookie() : [r.headers.get('set-cookie')].filter(Boolean);
  say('[ttwid] live.douyin.com HTTP ' + r.status + ', set-cookie count=' + sc.length);
  const m = sc.join('; ').match(/ttwid=([^;]+)/);
  return m ? m[1] : null;
}

async function probe(url, cookie, label) {
  const t0 = Date.now();
  const r = await fetch(url, {
    headers: {
      'User-Agent': UA,
      'Referer': 'https://www.douyin.com/',
      'Cookie': cookie,
    },
  });
  const body = await r.text();
  const ms = Date.now() - t0;
  let j = null;
  try { j = JSON.parse(body); } catch (e) { }
  const info = { label, url: url.slice(0, 160), status: r.status, ms, bytes: body.length };
  if (j) {
    info.status_code = j.status_code;
    info.status_msg = j.status_msg;
    const list = j.aweme_list;
    info.aweme_count = Array.isArray(list) ? list.length : 0;
    if (Array.isArray(list) && list.length) {
      info.first = {
        aweme_id: list[0].aweme_id,
        desc: (list[0].desc || '').slice(0, 60),
        author: list[0].author && list[0].author.nickname,
      };
      info.ids = list.map(x => x.aweme_id);
    }
    if (j.data && typeof j.data === 'object') info.has_data = true;
  } else {
    info.head = body.slice(0, 200);
  }
  say('[' + label + '] HTTP ' + r.status + ' ' + ms + 'ms bytes=' + body.length +
    (info.status_code !== undefined ? ' status_code=' + info.status_code : '') +
    (info.aweme_count !== undefined ? ' awemes=' + info.aweme_count : ''));
  return info;
}

(async () => {
  const results = [];
  try {
    const ctx = loadSigner();

    // 签名自检
    const q0 = buildFeedQuery(10, 1, '');
    const ab0 = ctx.makeABogus(q0);
    say('[sign] a_bogus len=' + ab0.length + ' sample=' + ab0.slice(0, 24));
    results.push({ step: 'abogus', len: ab0.length, sample: ab0.slice(0, 60) });

    const ttwid = await getTtwid();
    say('[ttwid] ' + (ttwid ? ttwid.slice(0, 30) + '...' : 'null'));
    if (!ttwid) throw new Error('no ttwid');
    const cookie = 'ttwid=' + ttwid;

    // ---- A. 推荐流（基座主源）----
    const qA = buildFeedQuery(10, 1, '');
    const urlA = 'https://www.douyin.com/aweme/v1/web/tab/feed/?' + qA +
      '&a_bogus=' + encodeURIComponent(ctx.makeABogus(qA));
    results.push(await probe(urlA, cookie, 'A: tab/feed (推荐, 未登录)'));

    // ---- B. 同一次调用换个 refresh_index，看是否真是翻页 ----
    const qB = buildFeedQuery(10, 2, '');
    const urlB = 'https://www.douyin.com/aweme/v1/web/tab/feed/?' + qB +
      '&a_bogus=' + encodeURIComponent(ctx.makeABogus(qB));
    results.push(await probe(urlB, cookie, 'B: tab/feed refresh_index=2'));

    // ---- C. 同城/附近 候选 1：module/feed ----
    const qC = buildFeedQuery(10, 1, '');
    const urlC = 'https://www.douyin.com/aweme/v1/web/module/feed/?' + qC +
      '&a_bogus=' + encodeURIComponent(ctx.makeABogus(qC));
    results.push(await probe(urlC, cookie, 'C: module/feed'));

    // ---- D. v2 module/feed（myDV dex 里出现过的）----
    const urlD = 'https://www.douyin.com/aweme/v2/web/module/feed/?' + qC +
      '&a_bogus=' + encodeURIComponent(ctx.makeABogus(qC));
    results.push(await probe(urlD, cookie, 'D: v2 module/feed'));

    // ---- E. 频道设置接口（列 web 端有哪些频道/分区）----
    const qE = buildFeedQuery(10, 1, '');
    const urlE = 'https://www.douyin.com/aweme/v1/web/home/channel/setting/?' + qE +
      '&a_bogus=' + encodeURIComponent(ctx.makeABogus(qE));
    results.push(await probe(urlE, cookie, 'E: home/channel/setting (频道表)'));

    fs.writeFileSync(OUT, JSON.stringify(results, null, 2), 'utf8');
    say('[done] wrote ' + OUT);
  } catch (e) {
    say('[FATAL] ' + e.stack);
    fs.writeFileSync(OUT, JSON.stringify({ error: String(e), stack: e.stack, log }, null, 2), 'utf8');
  }
})();
