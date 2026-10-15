// 验证 B 站直链「能不能直接播」+ 内容「横竖比」（影响方向分流）。
'use strict';

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';
const BILI_REF = 'https://www.bilibili.com/';
const DY_REF = 'https://www.douyin.com/';
function say(s) { process.stdout.write(String(s) + '\n'); }

let COOKIE = '';
async function apiGet(url) {
  const h = { 'User-Agent': UA, 'Referer': BILI_REF, 'Origin': 'https://www.bilibili.com', 'Accept': 'application/json, text/plain, */*' };
  if (COOKIE) h.Cookie = COOKIE;
  const r = await fetch(url, { headers: h });
  const t = await r.text();
  let j = null; try { j = JSON.parse(t); } catch (e) {}
  return { status: r.status, json: j, raw: t };
}

/** 用 Range 请求探测直链可播性 */
async function probeRange(url, headers) {
  try {
    const r = await fetch(url, { headers: Object.assign({}, headers, { Range: 'bytes=0-1023' }) });
    const buf = Buffer.from(await r.arrayBuffer());
    return { status: r.status, len: buf.length, ct: r.headers.get('content-type') };
  } catch (e) {
    return { status: -1, len: 0, ct: '', err: e && e.message };
  }
}

(async () => {
  // cookie
  try {
    const home = await fetch('https://www.bilibili.com/', { headers: { 'User-Agent': UA } });
    const sc = typeof home.headers.getSetCookie === 'function' ? home.headers.getSetCookie() : [];
    const jar = {};
    for (const c of sc) { const m = String(c).match(/^([A-Za-z0-9_]+)=([^;]*)/); if (m) jar[m[1]] = m[2]; }
    COOKIE = Object.entries(jar).map(([k, v]) => k + '=' + v).join('; ');
  } catch (e) {}

  // ---- 1) 横竖比统计（热门 50 条）----
  say('=== 内容方向分布 ===');
  for (const [name, url] of [
    ['热门 popular', 'https://api.bilibili.com/x/web-interface/popular?ps=50&pn=1'],
    ['排行 ranking', 'https://api.bilibili.com/x/web-interface/ranking/v2?rid=0&type=all'],
  ]) {
    const r = await apiGet(url);
    const arr = (r.json && r.json.data && (r.json.data.list || r.json.data.archives)) || [];
    let land = 0, port = 0, unk = 0, noCid = 0;
    for (const a of arr) {
      const dim = a.dimension;
      if (!dim || !dim.width || !dim.height) { unk++; continue; }
      if (dim.height > dim.width) port++; else land++;
      if (!a.cid) noCid++;
    }
    say('【' + name + '】共 ' + arr.length + ' 条 → 横=' + land + ' 竖=' + port + ' 尺寸缺=' + unk + ' 无cid=' + noCid);
  }
  say('');

  // ---- 2) 直链可播性 ----
  say('=== 直链可播性（Range: bytes=0-1023）===');
  const pop = await apiGet('https://api.bilibili.com/x/web-interface/popular?ps=8&pn=1');
  const list = (pop.json && pop.json.data && pop.json.data.list) || [];
  let tested = 0;
  for (const a of list) {
    if (!a.cid) continue;
    const u = 'https://api.bilibili.com/x/player/playurl?bvid=' + a.bvid + '&cid=' + a.cid
      + '&qn=32&fnval=1&fnver=0&fourk=0';
    const pu = await apiGet(u);
    const durl = pu.json && pu.json.data && pu.json.data.durl && pu.json.data.durl[0];
    if (!durl) { say('  ' + a.bvid + ' 无 durl'); continue; }
    const url = durl.url;

    const withBili = await probeRange(url, { 'User-Agent': UA, 'Referer': BILI_REF });
    const withDy = await probeRange(url, { 'User-Agent': UA, 'Referer': DY_REF });
    const noRef = await probeRange(url, { 'User-Agent': UA });
    const noUA = await probeRange(url, { 'Referer': BILI_REF });

    say('  ' + a.bvid + ' qn=' + (pu.json.data.quality) + ' size=' + durl.size
      + '\n     带B站Referer: ' + withBili.status + ' ' + withBili.len + 'B ' + withBili.ct
      + '\n     带抖音Referer: ' + withDy.status + ' ' + withDy.len + 'B '
      + '\n     无Referer:      ' + noRef.status + ' ' + noRef.len + 'B '
      + '\n     无UA:           ' + noUA.status + ' ' + noUA.len + 'B' + (noUA.err || ''));
    tested++;
    if (tested >= 4) break;
  }
  say('');

  // ---- 3) 清晰度能否提升 ----
  say('=== 清晰度试探（游客态 qn 上限）===');
  const first = list.find(a => a.cid);
  if (first) {
    for (const qn of [16, 32, 64, 80, 112]) {
      const pu = await apiGet('https://api.bilibili.com/x/player/playurl?bvid=' + first.bvid
        + '&cid=' + first.cid + '&qn=' + qn + '&fnval=1&fnver=0&fourk=1');
      const dd = (pu.json && pu.json.data) || {};
      say('  请求 qn=' + qn + ' → 实际 quality=' + dd.quality
        + ' durl=' + ((dd.durl || []).length) + ' accept=' + JSON.stringify(dd.accept_quality || []));
    }
  }
  say('');
  say('DONE');
})().catch(e => say('FATAL ' + (e && e.stack || e)));
