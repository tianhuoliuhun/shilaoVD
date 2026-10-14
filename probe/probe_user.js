// 验证：用户作品列表 /aweme/v1/web/aweme/post/ 游客态可用性
// （若可用 → "上海话专版"可由若干"上海话创作者"的作品聚合而成）
'use strict';
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');
function say(s) { process.stdout.write(String(s) + '\n'); }

async function get(ctx, cookie, path, extra) {
  const p = extra ? baseParams().concat(extra) : baseParams();
  const q = p.map(([k, v]) => k + '=' + v).join('&');
  const url = 'https://www.douyin.com' + path + '?' + q + '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
  const r = await fetch(url, { headers: { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': cookie } });
  const t = await r.text();
  let j = null; try { j = JSON.parse(t); } catch (e) {}
  return { http: r.status, json: j, raw: t };
}

(async () => {
  const ctx = createSigner();
  const ttwid = await getTtwid();
  const cookie = 'ttwid=' + ttwid;

  // 从推荐流取一个博主的 sec_uid
  const f = await get(ctx, cookie, '/aweme/v1/web/tab/feed/', [['count', '8'], ['refresh_index', '1'], ['tag_id', '']]);
  const list = (f.json && f.json.aweme_list) || [];
  let sec = null, nick = null;
  for (const a of list) {
    const au = a.author || {};
    if (au.sec_uid) { sec = au.sec_uid; nick = au.nickname; break; }
  }
  say('参照博主: ' + nick + '  sec_uid=' + String(sec).slice(0, 24) + '...');

  // ① 作品列表
  const r1 = await get(ctx, cookie, '/aweme/v1/web/aweme/post/', [['sec_user_id', sec], ['count', '20'], ['max_cursor', '0']]);
  const j1 = r1.json || {};
  const al = j1.aweme_list || [];
  say('');
  say('=== (1) aweme/post  作品列表 ===');
  say('  http=' + r1.http + ' status_code=' + j1.status_code + (j1.status_msg ? (' msg=' + j1.status_msg) : '') + ' items=' + al.length + ' has_more=' + j1.has_more + ' rawLen=' + r1.raw.length);
  al.slice(0, 6).forEach((a, i) => say('    [' + i + '] ' + a.aweme_id + '  ' + String(a.desc || '').slice(0, 36).replace(/\n/g, ' ')));

  // ② 用户信息（能不能拿到昵称/签名，便于判内容取向）
  const r2 = await get(ctx, cookie, '/aweme/v1/web/user/profile/other/', [['sec_user_id', sec], ['publish_video_strategy_type', '2']]);
  const j2 = r2.json || {};
  const u = j2.user || {};
  say('');
  say('=== (2) user/profile/other ===');
  say('  http=' + r2.http + ' status_code=' + j2.status_code + ' nickname=' + u.nickname + ' signature=' + String(u.signature || '').slice(0, 40) + ' aweme_count=' + u.aweme_count);

  // ③ 对照组：collection/mix（合集）
  const r3 = await get(ctx, cookie, '/aweme/v1/web/mix/aweme/', [['mix_id', '0'], ['count', '10'], ['cursor', '0']]);
  const j3 = r3.json || {};
  say('');
  say('=== (3) mix/aweme（对照） === http=' + r3.http + ' sc=' + j3.status_code + ' rawLen=' + r3.raw.length + ' msg=' + j3.status_msg);
  if (r3.http === 200 && r3.raw.length === 0) say('  （空 body，推测该端点已下线或被 WAF 拦）');
})().catch(e => say('FATAL ' + (e && e.stack || e)));
