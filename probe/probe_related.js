// 细看两个新发现的可用端点：aweme/related（相关视频）与 module/feed（模块卡片流）。
'use strict';
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');
function say(s) { process.stdout.write(String(s) + '\n'); }

async function get(ctx, cookie, path, extra) {
  const p = extra && extra.length ? baseParams().concat(extra) : baseParams();
  const q = p.map(([k, v]) => k + '=' + v).join('&');
  const url = 'https://www.douyin.com' + path + '?' + q + '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
  const r = await fetch(url, {
    headers: { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': cookie },
  });
  const t = await r.text();
  let j = null; try { j = JSON.parse(t); } catch (e) {}
  return { http: r.status, json: j, raw: t };
}

function shape(a) {
  const v = (a && a.video) || {};
  const w = v.width || 0, h = v.height || 0;
  const ori = (w && h) ? (h > w ? '竖' : '横') : '?';
  const addr = v.play_addr || {};
  const urls = (addr.url_list || []).length;
  const bit = addr.bit_rate ? addr.bit_rate.length : 0;
  const dur = v.duration ? Math.round(v.duration / 1000) + 's' : '?';
  return ori + ' ' + (w + 'x' + h) + ' ' + dur + ' url=' + urls + ' bitrates=' + bit;
}

(async () => {
  const ctx = createSigner();
  const ttwid = await getTtwid();
  const cookie = 'ttwid=' + ttwid;

  // 取一条**竖屏**推荐视频做种子
  const base = await get(ctx, cookie, '/aweme/v1/web/tab/feed/', [['count', '8'], ['refresh_index', '2']]);
  const list0 = (base.json && base.json.aweme_list) || [];
  let seed = null;
  for (const a of list0) {
    const v = a.video || {};
    if (v.width && v.height && v.height > v.width) { seed = a; break; }
  }
  if (!seed) seed = list0[0] || {};
  say('种子: aweme_id=' + seed.aweme_id + ' ' + shape(seed));
  say('      desc=' + String(seed.desc || '').slice(0, 50).replace(/\n/g, ' '));
  say('');

  const rel = await get(ctx, cookie, '/aweme/v1/web/aweme/related/', [
    ['aweme_id', seed.aweme_id], ['count', '20'], ['filterGid', seed.aweme_id], ['refresh_index', '1'],
  ]);
  const rl = (rel.json && rel.json.aweme_list) || [];
  say('=== aweme/related === http=' + rel.http + ' status_code=' + (rel.json && rel.json.status_code)
    + ' 条数=' + rl.length + ' has_more=' + (rel.json && rel.json.has_more));
  let portrait = 0, landscape = 0, withUrl = 0;
  rl.forEach((a, i) => {
    const v = (a.video || {});
    const w = v.width || 0, h = v.height || 0;
    if (h > w) portrait++; else if (w > h) landscape++;
    if ((((v.play_addr || {}).url_list) || []).length) withUrl++;
    if (i < 10) {
      say('  [' + i + '] ' + shape(a) + ' | ' + String(a.desc || '').slice(0, 34).replace(/\n/g, ' '));
    }
  });
  say('  合计: 竖=' + portrait + ' 横=' + landscape + ' 有直链=' + withUrl + '/' + rl.length);
  say('');

  // 重复同一个 aweme_id 再拉一次，看是否每次不同（是否能当"翻页"用）
  const rel2 = await get(ctx, cookie, '/aweme/v1/web/aweme/related/', [
    ['aweme_id', seed.aweme_id], ['count', '20'], ['filterGid', seed.aweme_id], ['refresh_index', '2'],
  ]);
  const rl2 = (rel2.json && rel2.json.aweme_list) || [];
  const ids1 = rl.map(a => a.aweme_id).join(',');
  const ids2 = rl2.map(a => a.aweme_id).join(',');
  say('=== 再拉一次（refresh_index=2）=== 条数=' + rl2.length
    + ' 与首次相同? ' + (ids1 === ids2));
  say('');

  // module/feed：cards[].aweme 是 JSON 字符串
  const mod = await get(ctx, cookie, '/aweme/v1/web/module/feed/', [['module_id', '1'], ['count', '10'], ['refresh_index', '1']]);
  const cards = ((mod.json && mod.json.cards) || []);
  say('=== module/feed === http=' + mod.http + ' status_code=' + (mod.json && mod.json.status_code) + ' cards=' + cards.length);
  cards.slice(0, 4).forEach((c, i) => {
    let inner = null;
    try { inner = JSON.parse(c.aweme); } catch (e) {}
    say('  [' + i + '] type=' + c.type + ' title=' + String(c.title || '').slice(0, 30)
      + (inner ? ('  inner.desc=' + String(inner.desc || '').slice(0, 30).replace(/\n/g, ' ')) : '  (aweme 解析失败)'));
  });
  say('');
  say('DONE');
})().catch(e => say('FATAL ' + (e && e.stack || e)));
