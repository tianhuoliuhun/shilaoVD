// 找出「上海话」相关视频最常用的 B 站标签（tag_id），用于配置 bili:tag:<tid> 源。
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

const KWS = ['滑稽戏', '沪剧', '上海话', '上海嘎讪胡'];

(async () => {
  try {
    const home = await fetch('https://www.bilibili.com/', { headers: { 'User-Agent': UA } });
    const sc = typeof home.headers.getSetCookie === 'function' ? home.headers.getSetCookie() : [];
    const jar = {};
    for (const c of sc) { const m = String(c).match(/^([A-Za-z0-9_]+)=([^;]*)/); if (m) jar[m[1]] = m[2]; }
    COOKIE = Object.entries(jar).map(([k, v]) => k + '=' + v).join('; ');
  } catch (e) {}
  say('cookie=' + (COOKIE || '(空)').slice(0, 50));
  say('');

  // 1) 从四个关键词各取前 4 条视频
  const bvids = [];
  for (const kw of KWS) {
    const r = await apiGet('https://api.bilibili.com/x/web-interface/search/type?search_type=video&page=1&keyword=' + encodeURIComponent(kw));
    const res = ((r.json && r.json.data && r.json.data.result) || []);
    res.slice(0, 4).forEach(a => { if (a.bvid) bvids.push({ bvid: a.bvid, kw: kw }); });
    say('【' + kw + '】取 ' + Math.min(4, res.length) + ' 条');
    await sleep(1200);
  }
  say('共收集 ' + bvids.length + ' 个视频，开始查各自标签…');
  say('');

  // 2) 查每个视频的标签，统计频次
  const stat = {};
  for (const it of bvids) {
    const r = await apiGet('https://api.bilibili.com/x/tag/archive/tags?bvid=' + it.bvid);
    const arr = (r.json && r.json.data) || [];
    if (!Array.isArray(arr)) { say('  ' + it.bvid + ' 取标签失败 code=' + (r.json && r.json.code)); await sleep(1200); continue; }
    for (const t of arr) {
      const id = String(t.tag_id);
      if (!stat[id]) stat[id] = { name: t.tag_name, n: 0, kws: {} };
      stat[id].n++;
      stat[id].kws[it.kw] = (stat[id].kws[it.kw] || 0) + 1;
    }
    await sleep(1200);
  }

  // 3) 按频次排序
  const rows = Object.entries(stat).map(([id, v]) => ({ id: id, name: v.name, n: v.n, kws: Object.keys(v.kws).join('/') }))
    .sort((a, b) => b.n - a.n);
  say('=== 标签频次（前 30）===');
  rows.slice(0, 30).forEach(r => {
    say('  ' + String(r.n).padStart(2) + 'x  tag_id=' + String(r.id).padEnd(11) + r.name + '   [' + r.kws + ']');
  });

  // 4) 挑出名字里带上海话特征的，实测它们的 tag/top 有没有内容
  say('');
  say('=== 候选标签实测 tag/top ===');
  const picked = rows.filter(r => /上海|沪|滑稽|独脚|评弹|戏曲|方言|吴语|本地/.test(r.name)).slice(0, 6);
  for (const c of picked) {
    const r = await apiGet('https://api.bilibili.com/x/web-interface/tag/top?tid=' + c.id + '&pn=1&ps=20');
    const d = r.json && r.json.data;
    const n = Array.isArray(d) ? d.length : 0;
    let land = 0, port = 0;
    if (Array.isArray(d)) {
      for (const a of d) {
        const dm = a.dimension;
        if (dm && dm.width && dm.height) { if (dm.height > dm.width) port++; else land++; }
      }
    }
    say('  tag_id=' + String(c.id).padEnd(11) + c.name.padEnd(14) + ' code=' + (r.json && r.json.code)
      + ' 条数=' + n + ' (横' + land + '/竖' + port + ')');
    await sleep(1200);
  }
  say('');
  say('DONE');
})().catch(e => say('FATAL ' + (e && e.stack || e)));
