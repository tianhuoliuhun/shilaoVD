// 探测哔哩哔哩在**游客态**下能否拿到"老人播放器能播的单个直链"。
// 关键点：
//   1) 列表接口（热门/排行）是否可用
//   2) playurl 是否必须 wbi 签名（本脚本内实现）
//   3) 返回的是 durl（单文件 mp4/flv，能单播）还是 dash（音视频分离，我们的播放器播不了）
//   4) 直链是否带 Referer 即可直接拉流
'use strict';
const crypto = require('crypto');

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';
const BASE_HDRS = {
  'User-Agent': UA,
  'Referer': 'https://www.bilibili.com/',
  'Origin': 'https://www.bilibili.com',
  'Accept': 'application/json, text/plain, */*',
  'Accept-Language': 'zh-CN,zh;q=0.9',
};

function say(s) { process.stdout.write(String(s) + '\n'); }

let COOKIE = '';

async function apiGet(url, extra) {
  const h = Object.assign({}, BASE_HDRS, extra || {});
  if (COOKIE) h.Cookie = COOKIE;
  const r = await fetch(url, { headers: h });
  const t = await r.text();
  let j = null; try { j = JSON.parse(t); } catch (e) {}
  return { status: r.status, json: j, raw: t };
}

// ---------- wbi 签名 ----------
const MIXIN_TAB = [46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
  33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1,
  60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52];

function mixinKey(orig) { return MIXIN_TAB.map(n => orig[n]).join('').slice(0, 32); }
function md5(s) { return crypto.createHash('md5').update(s).digest('hex'); }

function wbiSign(params, imgKey, subKey) {
  const mk = mixinKey(imgKey + subKey);
  const p = Object.assign({}, params, { wts: Math.round(Date.now() / 1000) });
  const q = Object.keys(p).sort().map(k => {
    const v = String(p[k]).replace(/[!'()*]/g, '');
    return encodeURIComponent(k) + '=' + encodeURIComponent(v);
  }).join('&');
  return q + '&w_rid=' + md5(q + mk);
}

(async () => {
  // 1) 先访问首页拿 buvid3 之类的 cookie
  try {
    const home = await fetch('https://www.bilibili.com/', { headers: { 'User-Agent': UA } });
    const sc = typeof home.headers.getSetCookie === 'function'
      ? home.headers.getSetCookie() : [home.headers.get('set-cookie')].filter(Boolean);
    const jar = {};
    for (const c of sc) {
      const m = String(c).match(/^([A-Za-z0-9_]+)=([^;]*)/);
      if (m) jar[m[1]] = m[2];
    }
    COOKIE = Object.entries(jar).map(([k, v]) => k + '=' + v).join('; ');
    say('首页 status=' + home.status + ' cookie=' + (COOKIE || '(空)').slice(0, 140));
  } catch (e) {
    say('首页请求失败: ' + (e && e.message));
  }
  say('');

  // 2) nav 拿 wbi 密钥
  const nav = await apiGet('https://api.bilibili.com/x/web-interface/nav');
  const d = nav.json && nav.json.data;
  say('nav status=' + nav.status + ' code=' + (nav.json && nav.json.code) + ' isLogin=' + (d && d.isLogin));
  const wbi = d && d.wbi_img;
  if (!wbi) { say('取不到 wbi_img，后续签名无法进行'); say('raw=' + nav.raw.slice(0, 200)); return; }
  const imgKey = wbi.img_url.split('/').pop().split('.')[0];
  const subKey = wbi.sub_url.split('/').pop().split('.')[0];
  say('wbi: imgKey=' + imgKey.slice(0, 12) + '... subKey=' + subKey.slice(0, 12) + '...');
  say('');

  // 3) 列表接口（游客态？）
  const lists = [
    ['热门 popular', 'https://api.bilibili.com/x/web-interface/popular?ps=20&pn=1'],
    ['综合排行 ranking/v2', 'https://api.bilibili.com/x/web-interface/ranking/v2?rid=0&type=all'],
  ];
  let sample = null;
  for (const [name, url] of lists) {
    const r = await apiGet(url);
    const j = r.json || {};
    const arr = (j.data && (j.data.list || j.data.archives)) || [];
    say('【' + name + '】status=' + r.status + ' code=' + j.code + ' msg=' + (j.message || '') + ' 条数=' + arr.length);
    if (arr.length) {
      const a = arr[0];
      say('    样本: bvid=' + a.bvid + ' title=' + String(a.title || '').slice(0, 30)
        + ' duration=' + a.duration + 's' + (a.dimension ? (' ' + a.dimension.width + 'x' + a.dimension.height) : ''));
      if (!sample) sample = a;
    } else {
      say('    raw=' + r.raw.slice(0, 160).replace(/\s+/g, ' '));
    }
  }
  say('');

  if (!sample) { say('没有拿到样本，停止'); return; }

  // 4) view 拿 cid
  const view = await apiGet('https://api.bilibili.com/x/web-interface/view?bvid=' + sample.bvid);
  const vd = view.json && view.json.data;
  say('view code=' + (view.json && view.json.code) + ' cid=' + (vd && vd.cid)
    + ' pic=' + ((vd && vd.pic) ? '有' : '无') + ' dim=' + (vd && vd.dimension ? (vd.dimension.width + 'x' + vd.dimension.height) : '?'));
  if (!vd || !vd.cid) { say('取不到 cid，停止'); return; }
  const cid = vd.cid;
  say('');

  // 5) playurl：durl（单文件）vs dash（分离）
  for (const [tag, fnval] of [['fnval=1 (durl 单文件)', 1], ['fnval=16 (dash)', 16]]) {
    for (const sig of [false, true]) {
      const p = { bvid: sample.bvid, cid: cid, qn: 32, fnval: fnval, fnver: 0, fourk: 0 };
      const qs = sig ? wbiSign(p, imgKey, subKey)
        : Object.keys(p).map(k => k + '=' + p[k]).join('&');
      const r = await apiGet('https://api.bilibili.com/x/player/playurl?' + qs);
      const j = r.json || {};
      const dd = j.data || {};
      const durlN = (dd.durl || []).length;
      const dashN = (dd.dash && dd.dash.video || []).length;
      say('【playurl ' + tag + (sig ? ' +wbi' : ' 不签名') + '】code=' + j.code
        + ' msg=' + (j.message || '') + ' quality=' + dd.quality + ' durl=' + durlN + ' dash.video=' + dashN);
      if (durlN) {
        const u = dd.durl[0].url;
        say('    durl 直链: ' + u.slice(0, 110));
        say('    durl size=' + dd.durl[0].size + ' 长度=' + dd.durl[0].length);
      }
      if (dashN) {
        say('    dash 首个视频流: ' + String(dd.dash.video[0].baseUrl || '').slice(0, 100));
        say('    dash 音频流数=' + ((dd.dash.audio || []).length));
      }
      if (!durlN && !dashN) say('    raw=' + r.raw.slice(0, 200).replace(/\s+/g, ' '));
    }
  }
  say('');
  say('DONE');
})().catch(e => say('FATAL ' + (e && e.stack || e)));
