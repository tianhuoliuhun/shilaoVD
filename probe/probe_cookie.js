// 收集抖音首页种下的全套 cookie（找 Uifid），再用它重试 detail / post / search
'use strict';
const { createSigner, baseParams, UA } = require('./signer.js');
function say(s) { process.stdout.write(String(s) + '\n'); }

async function collect() {
  const jar = {};
  for (const u of ['https://www.douyin.com/', 'https://www.douyin.com/discover', 'https://www.douyin.com/hot']) {
    try {
      const r = await fetch(u, { headers: { 'User-Agent': UA }, redirect: 'follow' });
      const sc = (typeof r.headers.getSetCookie === 'function') ? r.headers.getSetCookie() : [];
      sc.forEach(c => { const kv = c.split(';')[0]; const i = kv.indexOf('='); if (i > 0) jar[kv.slice(0, i).trim()] = kv.slice(i + 1).trim(); });
      say('GET ' + u + ' -> ' + r.status + '  set-cookie=' + sc.length);
    } catch (e) { say('GET ' + u + ' FAIL ' + e.message); }
  }
  const names = Object.keys(jar);
  say('收集到 cookie: ' + names.join(','));
  const uif = Object.keys(jar).filter(k => /uif|uid|msToken|passport/i.test(k));
  say('其中疑似风控相关: ' + (uif.join(',') || '(无)'));
  return jar;
}

async function call(ctx, jar, path, extra, label) {
  const p = extra ? baseParams().concat(extra) : baseParams();
  const q = p.map(([k, v]) => k + '=' + v).join('&');
  const url = 'https://www.douyin.com' + path + '?' + q + '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
  const cookie = Object.entries(jar).map(([k, v]) => k + '=' + v).join('; ');
  const r = await fetch(url, { headers: { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': cookie } });
  const t = await r.text();
  let j = null; try { j = JSON.parse(t); } catch (e) {}
  const n = ((j && j.aweme_list) || []).length;
  const hasDetail = !!(j && j.aweme_detail);
  say(label + ' -> http=' + r.http0 || r.status);
  say('   ' + label + ' http=' + r.status + ' sc=' + (j && j.status_code) + ' items=' + n + ' detail=' + hasDetail + ' rawLen=' + t.length + ' head=' + t.slice(0, 120).replace(/\s+/g, ' '));
  return r.status;
}

(async () => {
  const ctx = createSigner();
  const jar = await collect();
  say('');

  // 参照 id
  const q0 = baseParams().concat([['count', '3'], ['refresh_index', '1'], ['tag_id', '']]).map(([k, v]) => k + '=' + v).join('&');
  const u0 = 'https://www.douyin.com/aweme/v1/web/tab/feed/?' + q0 + '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q0));
  const cookie = Object.entries(jar).map(([k, v]) => k + '=' + v).join('; ');
  const r0 = await fetch(u0, { headers: { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': cookie } });
  const j0 = await r0.json();
  const id = ((j0.aweme_list || [])[0] || {}).aweme_id;
  say('参照 aweme_id=' + id + '  (feed 仍可用? sc=' + j0.status_code + ' items=' + ((j0.aweme_list || []).length) + ')');
  say('');

  await call(ctx, jar, '/aweme/v1/web/aweme/detail/', [['aweme_id', String(id)]], 'aweme/detail');
  await call(ctx, jar, '/aweme/v1/web/general/search/single/', [
    ['keyword', '上海话'], ['search_channel', 'aweme_general'], ['sort_type', '0'], ['publish_time', '0'],
    ['filter_duration', '0'], ['search_source', 'normal_search'], ['query_correct_type', '1'], ['is_filter_search', '0'],
    ['offset', '0'], ['count', '10'],
  ], 'search/单页');
})().catch(e => say('FATAL ' + (e && e.stack || e)));
