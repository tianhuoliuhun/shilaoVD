// 找「话题下的视频列表」到底走哪个端点（游客态）
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

  // 先取一个已知有效的话题 id（来自推荐流 text_extra）
  const f = await get(ctx, cookie, '/aweme/v1/web/tab/feed/', [['count', '8'], ['refresh_index', '1'], ['tag_id', '']]);
  let hid = null;
  for (const a of ((f.json && f.json.aweme_list) || [])) {
    for (const t of (a.text_extra || [])) { if (t.hashtag_id) { hid = String(t.hashtag_id); break; } }
    if (hid) break;
  }
  say('参照话题 id = ' + hid);

  // ① detail 的完整结构（看有没有嵌视频列表）
  const d = await get(ctx, cookie, '/aweme/v1/web/challenge/detail/', [['ch_id', hid]]);
  say('');
  say('=== challenge/detail keys ===');
  if (d.json) {
    say('  top keys: ' + Object.keys(d.json).join(','));
    const ci = d.json.ch_info;
    if (ci) {
      say('  ch_info keys: ' + Object.keys(ci).join(','));
      say('  cha_name=' + ci.cha_name + '  user_count=' + ci.user_count + '  view_count=' + ci.view_count);
      if (ci.aweme_list) say('  ch_info.aweme_list = ' + ci.aweme_list.length);
    }
    say('  status_code=' + d.json.status_code);
  }
  say('  raw len=' + d.raw.length);

  // ② 候选端点
  say('');
  say('=== 话题视频列表候选端点 ===');
  const cands = [
    ['/aweme/v1/web/challenge/aweme/', [['ch_id', hid], ['count', '10'], ['offset', '0'], ['sort_type', '0']]],
    ['/aweme/v1/web/challenge/video/', [['ch_id', hid], ['count', '10'], ['offset', '0']]],
    ['/aweme/v1/web/challenge/list/', [['ch_id', hid], ['count', '10'], ['offset', '0']]],
    ['/aweme/v1/web/hot/challenge/aweme/', [['ch_id', hid], ['count', '10'], ['offset', '0']]],
    ['/aweme/v1/web/tab/feed/', [['ch_id', hid], ['count', '10'], ['refresh_index', '1']]],
    ['/aweme/v1/web/feed/challenge/', [['ch_id', hid], ['count', '10'], ['offset', '0']]],
  ];
  for (const [p, ex] of cands) {
    const r = await get(ctx, cookie, p, ex);
    const j = r.json || {};
    const n = (j.aweme_list || []).length;
    say('  ' + p.padEnd(42) + ' http=' + r.http + ' sc=' + j.status_code + (j.status_msg ? (' msg=' + j.status_msg) : '') + ' items=' + n + ' rawLen=' + r.raw.length);
    if (n) (j.aweme_list || []).slice(0, 3).forEach((a, i) => say('        [' + i + '] ' + a.aweme_id + ' ' + String(a.desc || '').slice(0, 30).replace(/\n/g, ' ')));
  }
})().catch(e => say('FATAL ' + (e && e.stack || e)));
