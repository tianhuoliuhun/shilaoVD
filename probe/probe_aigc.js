// 探测：抖音 feed 里有没有「AI 生成内容」的标识字段
// 思路：递归 dump 每条 aweme 里 key 含 ai/aigc/gen/synth/fake 的字段
'use strict';
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');
function say(s) { process.stdout.write(String(s) + '\n'); }
const HIT = /(^|_)(ai|aigc|gen|synth|fake|virtual|digital_human)(_|$)/i;

function walk(obj, path, out, depth) {
  if (!obj || typeof obj !== 'object' || depth > 4) return;
  for (const k of Object.keys(obj)) {
    const v = obj[k];
    const p = path ? path + '.' + k : k;
    if (HIT.test(k)) {
      let sval;
      try { sval = (typeof v === 'object') ? JSON.stringify(v).slice(0, 120) : String(v); } catch (e) { sval = '[obj]'; }
      out.push(p + ' = ' + sval);
    }
    if (typeof v === 'object') walk(v, p, out, depth + 1);
  }
}

(async () => {
  const ctx = createSigner();
  const ttwid = await getTtwid();
  const H = { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': 'ttwid=' + ttwid };
  const q = baseParams().concat([['count', '20'], ['refresh_index', '1'], ['tag_id', '']]).map(([k, v]) => k + '=' + v).join('&');
  const url = 'https://www.douyin.com/aweme/v1/web/tab/feed/?' + q + '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
  const r = await fetch(url, { headers: H });
  const j = await r.json();
  const list = j.aweme_list || [];
  say('feed items=' + list.length);

  const allHits = new Map();
  list.forEach((a, i) => {
    const out = [];
    walk(a, '', out, 0);
    out.forEach(h => { const k = h.split(' = ')[0]; allHits.set(k, (allHits.get(k) || 0) + 1); });
    if (i < 3) {
      say('--- [' + i + '] ' + String(a.desc || '').slice(0, 30));
      out.slice(0, 12).forEach(h => say('      ' + h));
    }
  });
  say('');
  say('=== 20 条里出现过的疑似 AI 字段 ===');
  [...allHits.entries()].sort((a, b) => b[1] - a[1]).forEach(([k, c]) => say('   ' + c + '× ' + k));

  // 另外看 aweme 顶层都有哪些 key（便于发现别的命名）
  if (list[0]) say('');
  if (list[0]) say('aweme 顶层 key: ' + Object.keys(list[0]).join(','));
  if (list[0] && list[0].video) say('video 顶层 key: ' + Object.keys(list[0].video).join(','));
})().catch(e => say('FATAL ' + (e && e.stack || e)));
