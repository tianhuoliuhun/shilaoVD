// 验证 aweme/detail（按 id 取详情）游客态可用性 —— 决定「内置 id 列表」方案是否成立
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
  const f = await get(ctx, cookie, '/aweme/v1/web/tab/feed/', [['count', '5'], ['refresh_index', '1'], ['tag_id', '']]);
  const list = (f.json && f.json.aweme_list) || [];
  const id = list[0] && list[0].aweme_id;
  say('参照 aweme_id = ' + id);
  const r = await get(ctx, cookie, '/aweme/v1/web/aweme/detail/', [['aweme_id', String(id)]]);
  const j = r.json || {};
  say('aweme/detail -> http=' + r.http + ' status_code=' + j.status_code + ' msg=' + (j.status_msg || '') + ' rawLen=' + r.raw.length);
  const d = j.aweme_detail;
  if (d) {
    say('  aweme_detail: id=' + d.aweme_id + ' desc=' + String(d.desc || '').slice(0, 30));
    const pa = d.video && d.video.play_addr;
    say('  play_addr.url_list 条数=' + ((pa && pa.url_list && pa.url_list.length) || 0));
    if (pa && pa.url_list) say('  第一条=' + String(pa.url_list[0]).slice(0, 90));
  } else {
    say('  raw=' + r.raw.slice(0, 200));
  }
})().catch(e => say('FATAL ' + (e && e.stack || e)));
