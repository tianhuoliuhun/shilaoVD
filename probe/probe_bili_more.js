// 探测 B 站「分区（频道）」「搜索」「标签」三类接口在游客态是否可用。
'use strict';
const crypto = require('crypto');

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';
const REF = 'https://www.bilibili.com/';
function say(s) { process.stdout.write(String(s) + '\n'); }

let COOKIE = '';

const MIXIN_TAB = [46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
  33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1,
  60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52];
function mixinKey(o) { return MIXIN_TAB.map(n => o[n]).join('').slice(0, 32); }
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

async function apiGet(url) {
  const h = { 'User-Agent': UA, 'Referer': REF, 'Origin': 'https://www.bilibili.com', 'Accept': 'application/json, text/plain, */*' };
  if (COOKIE) h.Cookie = COOKIE;
  const r = await fetch(url, { headers: h });
  const t = await r.text();
  let j = null; try { j = JSON.parse(t); } catch (e) {}
  return { status: r.status, json: j, raw: t };
}

function countOf(j) {
  if (!j) return -1;
  const cands = [j.data && j.data.list, j.data && j.data.archives, j.data && j.data.result,
    j.data && j.data.medias, j.data && j.data.videos, j.data];
  for (const c of cands) if (Array.isArray(c)) return c.length;
  return 0;
}

(async () => {
  try {
    const home = await fetch('https://www.bilibili.com/', { headers: { 'User-Agent': UA } });
    const sc = typeof home.headers.getSetCookie === 'function' ? home.headers.getSetCookie() : [];
    const jar = {};
    for (const c of sc) { const m = String(c).match(/^([A-Za-z0-9_]+)=([^;]*)/); if (m) jar[m[1]] = m[2]; }
    COOKIE = Object.entries(jar).map(([k, v]) => k + '=' + v).join('; ');
  } catch (e) {}
  say('cookie=' + (COOKIE || '(空)').slice(0, 90));
  say('');

  const nav = await apiGet('https://api.bilibili.com/x/web-interface/nav');
  const wbi = nav.json && nav.json.data && nav.json.data.wbi_img;
  const imgKey = wbi.img_url.split('/').pop().split('.')[0];
  const subKey = wbi.sub_url.split('/').pop().split('.')[0];
  say('wbi 就绪: ' + imgKey.slice(0, 10) + ' / ' + subKey.slice(0, 10));
  say('');

  // ================= 一、频道 / 分区 =================
  say('==================== 一、分区（频道）====================');
  const RIDS = [[1, '动画'], [3, '音乐'], [4, '游戏'], [5, '娱乐'], [36, '科技'], [155, '时尚'],
    [160, '生活'], [177, '纪录片'], [181, '影视'], [188, '数码'], [211, '美食'], [217, '动物圈'],
    [223, '汽车'], [234, '运动']];
  for (const [rid, name] of RIDS) {
    const r = await apiGet('https://api.bilibili.com/x/web-interface/ranking/v2?rid=' + rid + '&type=all');
    const j = r.json || {};
    say('  排行 rid=' + String(rid).padEnd(4) + name.padEnd(5) + ' code=' + j.code + ' 条数=' + countOf(j)
      + (j.code !== 0 ? (' msg=' + (j.message || '')) : ''));
  }
  say('');
  say('-- 分区「最新」接口 dynamic/region --');
  for (const [rid, name] of [[4, '游戏'], [160, '生活'], [211, '美食'], [217, '动物圈']]) {
    const r = await apiGet('https://api.bilibili.com/x/web-interface/dynamic/region?rid=' + rid + '&pn=1&ps=10');
    const j = r.json || {};
    say('  dynamic/region rid=' + String(rid).padEnd(4) + name.padEnd(5) + ' code=' + j.code
      + ' 条数=' + countOf(j) + (j.code !== 0 ? (' msg=' + (j.message || '')) : ''));
  }
  say('');
  say('-- 分区列表接口 --');
  for (const p of ['/x/web-interface/video/region/list', '/x/web-interface/region/list']) {
    const r = await apiGet('https://api.bilibili.com' + p);
    say('  ' + p + ' → http=' + r.status + ' code=' + (r.json && r.json.code)
      + ' raw0=' + r.raw.slice(0, 90).replace(/\s+/g, ' '));
  }
  say('');

  // ================= 二、搜索 =================
  say('==================== 二、搜索 =================');
  const KW = '广场舞';
  // 2.1 不带签名
  {
    const r = await apiGet('https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword=' + encodeURIComponent(KW) + '&page=1');
    const j = r.json || {};
    say('  老接口 search/type（不签名） code=' + j.code + ' 条数=' + countOf(j)
      + ' msg=' + (j.message || '') + ' raw0=' + r.raw.slice(0, 100).replace(/\s+/g, ' '));
  }
  // 2.2 带 wbi 签名
  {
    const qs = wbiSign({ search_type: 'video', keyword: KW, page: 1 }, imgKey, subKey);
    const r = await apiGet('https://api.bilibili.com/x/web-interface/wbi/search/type?' + qs);
    const j = r.json || {};
    const res = j.data && j.data.result;
    say('  新接口 wbi/search/type（带签名） code=' + j.code + ' 条数=' + (Array.isArray(res) ? res.length : 0)
      + ' msg=' + (j.message || ''));
    if (Array.isArray(res) && res.length) {
      const a = res[0];
      say('    样本: bvid=' + a.bvid + ' title=' + String(a.title || '').replace(/<[^>]+>/g, '').slice(0, 30)
        + ' author=' + (a.author || '') + ' dur=' + a.duration);
    } else {
      say('    raw0=' + r.raw.slice(0, 140).replace(/\s+/g, ' '));
    }
  }
  // 2.3 综合搜索
  {
    const qs = wbiSign({ keyword: KW, page: 1 }, imgKey, subKey);
    const r = await apiGet('https://api.bilibili.com/x/web-interface/wbi/search/all/v2?' + qs);
    say('  综合搜索 wbi/search/all/v2 code=' + (r.json && r.json.code) + ' raw0='
      + r.raw.slice(0, 100).replace(/\s+/g, ' '));
  }
  say('');

  // ================= 三、标签 =================
  say('==================== 三、标签 =================');
  // 先拿一个 bvid 用于查它的标签
  const pop = await apiGet('https://api.bilibili.com/x/web-interface/popular?ps=1&pn=1');
  const first = pop.json && pop.json.data && pop.json.data.list && pop.json.data.list[0];
  const bvid = first ? first.bvid : '';
  say('  样本 bvid=' + bvid + '（' + String(first && first.title || '').slice(0, 24) + '）');
  if (bvid) {
    const r = await apiGet('https://api.bilibili.com/x/tag/archive/tags?bvid=' + bvid);
    const arr = (r.json && r.json.data) || [];
    say('  视频标签 x/tag/archive/tags code=' + (r.json && r.json.code) + ' 标签数=' + (Array.isArray(arr) ? arr.length : 0));
    if (Array.isArray(arr)) {
      say('    标签: ' + arr.slice(0, 8).map(t => t.tag_name + '(' + t.tag_id + ')').join(' / '));
      // 用第一个标签试试"标签下视频"
      const t0 = arr[0];
      if (t0) {
        const r2 = await apiGet('https://api.bilibili.com/x/web-interface/tag/top?tid=' + t0.tag_id + '&pn=1&ps=10');
        const d2 = r2.json && r2.json.data;
        say('    标签下视频 tag/top?tid=' + t0.tag_id + ' code=' + (r2.json && r2.json.code)
          + ' 条数=' + (Array.isArray(d2) ? d2.length : 0));
      }
    }
  }
  // 3.2 话题/频道接口
  for (const p of ['/x/web-interface/web/channel/multiple/list?biz_type=all&sort_type=hot&page_size=20',
    '/x/tag/archive/list?tag_id=1&pn=1&ps=10']) {
    const r = await apiGet('https://api.bilibili.com' + p);
    say('  ' + p.slice(0, 58) + ' → code=' + (r.json && r.json.code) + ' 条数=' + countOf(r.json)
      + ' raw0=' + r.raw.slice(0, 80).replace(/\s+/g, ' '));
  }
  say('');
  say('DONE');
})().catch(e => say('FATAL ' + (e && e.stack || e)));
