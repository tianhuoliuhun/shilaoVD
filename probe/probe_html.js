// 验证：视频页 HTML 里是否直接嵌着播放地址（游客态，无需登录）
'use strict';
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');
function say(s) { process.stdout.write(String(s) + '\n'); }
(async () => {
  const ctx = createSigner();
  const ttwid = await getTtwid();
  const cookie = 'ttwid=' + ttwid;
  const H = { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': cookie };

  const q = baseParams().concat([['count', '3'], ['refresh_index', '1'], ['tag_id', '']]).map(([k, v]) => k + '=' + v).join('&');
  const u = 'https://www.douyin.com/aweme/v1/web/tab/feed/?' + q + '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
  const r0 = await fetch(u, { headers: H });
  const t0 = await r0.text();
  let j0 = null; try { j0 = JSON.parse(t0); } catch (e) {}
  const id = ((j0 && j0.aweme_list || [])[0] || {}).aweme_id;
  say('feed http=' + r0.status + ' sc=' + (j0 && j0.status_code) + ' id=' + id);
  if (!id) { say('no id, abort'); return; }

  const vurl = 'https://www.douyin.com/video/' + id;
  const r = await fetch(vurl, { headers: H });
  const t = await r.text();
  say('GET ' + vurl + ' -> http=' + r.status + ' len=' + t.length);
  say('  含 _ROUTER_DATA ? ' + t.includes('_ROUTER_DATA') + '   含 play_addr ? ' + t.includes('play_addr') + '   含 url_list ? ' + t.includes('url_list'));
  const i = t.indexOf('_ROUTER_DATA');
  if (i >= 0) say('  片段: ' + t.slice(i, i + 300).replace(/\s+/g, ' '));
  else say('  head: ' + t.slice(0, 240).replace(/\s+/g, ' '));
})().catch(e => say('FATAL ' + (e && e.stack || e)));
