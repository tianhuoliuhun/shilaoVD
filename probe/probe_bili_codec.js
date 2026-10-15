// 排查绿屏：看 B 站这些视频的实际编码（codecid）与分辨率分布。
// codecid: 7=H.264(AVC) / 12=H.265(HEVC) / 13=AV1 —— 非 7 的都可能让系统解码器出绿屏/黑屏。
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

const KWS = ['滑稽戏', '沪剧', '上海嘎讪胡'];

(async () => {
  try {
    const home = await fetch('https://www.bilibili.com/', { headers: { 'User-Agent': UA } });
    const sc = typeof home.headers.getSetCookie === 'function' ? home.headers.getSetCookie() : [];
    const jar = {};
    for (const c of sc) { const m = String(c).match(/^([A-Za-z0-9_]+)=([^;]*)/); if (m) jar[m[1]] = m[2]; }
    COOKIE = Object.entries(jar).map(([k, v]) => k + '=' + v).join('; ');
  } catch (e) {}
  say('cookie=' + (COOKIE || '(空)').slice(0, 40));
  say('');

  const stat = {};
  const small = [];
  let total = 0;

  for (const kw of KWS) {
    const r = await apiGet('https://api.bilibili.com/x/web-interface/search/type?search_type=video&page=1&keyword=' + encodeURIComponent(kw));
    const res = ((r.json && r.json.data && r.json.data.result) || []).slice(0, 3);
    say('【' + kw + '】' + res.length + ' 条');
    for (const a of res) {
      // ⚠️ 搜索结果**不带 cid**，必须先 view 拿一个
      const vw = await apiGet('https://api.bilibili.com/x/web-interface/view?bvid=' + a.bvid);
      const vd = vw.json && vw.json.data;
      if (!vd || !vd.cid) {
        say('  ' + a.bvid + ' 取 cid 失败 code=' + (vw.json && vw.json.code));
        await sleep(1100);
        continue;
      }
      const cid = vd.cid;
      // 用 dash 看 codecid（durl 不带这个字段，但同源转码编码一致）
      const pu = await apiGet('https://api.bilibili.com/x/player/playurl?bvid=' + a.bvid
        + '&cid=' + cid + '&qn=64&fnval=16&fnver=0&fourk=1');
      const d = (pu.json && pu.json.data) || {};
      const vids = (d.dash && d.dash.video) || [];
      // 取清晰度最高的一档看编码
      let best = null;
      for (const v of vids) if (!best || (v.id || 0) > (best.id || 0)) best = v;
      let codec = '(无 dash)';
      if (best) {
        let nm = '未知';
        if (best.codecid === 7) nm = 'H.264';
        else if (best.codecid === 12) nm = 'H.265';
        else if (best.codecid === 13) nm = 'AV1';
        codec = 'codecid=' + best.codecid + ' (' + nm + ')';
      }
      const dim = best ? (best.width + 'x' + best.height) : '?';
      // 再看 durl（我们实际用的）
      const pu2 = await apiGet('https://api.bilibili.com/x/player/playurl?bvid=' + a.bvid
        + '&cid=' + cid + '&qn=64&fnval=1&fnver=0&fourk=1');
      const du = (pu2.json && pu2.json.data && pu2.json.data.durl) || [];
      const durlQ = (pu2.json && pu2.json.data && pu2.json.data.quality);
      const fmt = du[0] ? String(du[0].url).split('?')[0].split('.').pop() : '-';
      const sizeMB = du[0] ? (du[0].size / 1048576).toFixed(1) + 'MB' : '-';

      total++;
      const key = codec + ' durl.' + fmt;
      stat[key] = (stat[key] || 0) + 1;
      if (best && best.width && (best.width < 640 || best.height < 640)) {
        small.push({ bvid: a.bvid, dim: dim, codec: codec, title: strip(a.title).slice(0, 26) });
      }
      say('  ' + a.bvid + ' ' + dim.padEnd(11) + codec.padEnd(24)
        + ' durlQ=' + durlQ + ' .' + fmt + ' ' + sizeMB + ' | ' + strip(a.title).slice(0, 22));
      await sleep(1100);
    }
    say('');
  }

  say('=== 统计（共 ' + total + ' 条）===');
  Object.entries(stat).sort((a, b) => b[1] - a[1]).forEach(([k, v]) => say('  ' + v + 'x  ' + k));
  say('');
  say('=== 小分辨率（<640）的条数：' + small.length + ' ===');
  small.forEach(s => say('  ' + s.bvid + ' ' + s.dim.padEnd(11) + s.codec.padEnd(24) + s.title));
  say('');
  say('DONE');
})().catch(e => say('FATAL ' + (e && e.stack || e)));
