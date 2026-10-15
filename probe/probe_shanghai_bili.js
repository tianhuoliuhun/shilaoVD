// 探测「上海话专版」可行性：B 站搜索各候选关键词在游客态的内容量与质量。
'use strict';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';
const REF = 'https://www.bilibili.com/';
function say(s) { process.stdout.write(String(s) + '\n'); }
let COOKIE = '';
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function apiGet(url) {
  const h = { 'User-Agent': UA, 'Referer': REF, 'Origin': 'https://www.bilibili.com', 'Accept': 'application/json, text/plain, */*' };
  if (COOKIE) h.Cookie = COOKIE;
  const r = await fetch(url, { headers: h });
  const t = await r.text();
  let j = null; try { j = JSON.parse(t); } catch (e) {}
  return { status: r.status, json: j, raw: t };
}
function strip(s) { return String(s || '').replace(/<[^>]*>/g, ''); }

const KW = ['上海话', '沪语', '上海方言', '上海闲话', '滑稽戏', '上海滑稽', '沪剧', '老娘舅',
  '学说上海话', '上海话教学'];

(async () => {
  try {
    const home = await fetch('https://www.bilibili.com/', { headers: { 'User-Agent': UA } });
    const sc = typeof home.headers.getSetCookie === 'function' ? home.headers.getSetCookie() : [];
    const jar = {};
    for (const c of sc) { const m = String(c).match(/^([A-Za-z0-9_]+)=([^;]*)/); if (m) jar[m[1]] = m[2]; }
    COOKIE = Object.entries(jar).map(([k, v]) => k + '=' + v).join('; ');
  } catch (e) {}
  say('cookie=' + (COOKIE || '(空)').slice(0, 60));
  say('');

  let best = null;
  for (const kw of KW) {
    const r = await apiGet('https://api.bilibili.com/x/web-interface/search/type?search_type=video&page=1&keyword=' + encodeURIComponent(kw));
    const j = r.json || {};
    const res = (j.data && j.data.result) || [];
    const total = (j.data && (j.data.numResults !== undefined ? j.data.numResults : j.data.numPages)) || '?';
    say('【' + kw + '】code=' + j.code + ' 本页=' + res.length + ' 结果总数=' + total);
    res.slice(0, 3).forEach((a, i) => {
      say('   [' + i + '] ' + strip(a.title).slice(0, 42) + ' | ' + (a.author || '') + ' | ' + (a.duration || ''));
    });
    if (res.length && (!best || res.length > best.n)) best = { kw: kw, n: res.length, list: res };
    await sleep(1300);
  }

  say('');
  say('=== 方向分布（对「' + (best ? best.kw : '无') + '」取前 8 条查 view）===');
  if (best) {
    let land = 0, port = 0, fail = 0;
    for (const a of best.list.slice(0, 8)) {
      const r = await apiGet('https://api.bilibili.com/x/web-interface/view?bvid=' + a.bvid);
      const d = r.json && r.json.data;
      if (!d || !d.dimension) {
        fail++;
        say('   ' + a.bvid + ' view 失败 (code=' + (r.json && r.json.code) + ')');
      } else {
        const w = d.dimension.width, h = d.dimension.height;
        if (h > w) port++; else land++;
        say('   ' + a.bvid + ' ' + (h > w ? '竖' : '横') + ' ' + w + 'x' + h + ' | ' + strip(d.title).slice(0, 32));
      }
      await sleep(1300);
    }
    say('   合计: 横=' + land + ' 竖=' + port + ' 失败=' + fail);
  }
  say('');
  say('DONE');
})().catch(e => say('FATAL ' + (e && e.stack || e)));
