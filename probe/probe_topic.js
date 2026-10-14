// 验证：推荐流视频是否带话题 id；challenge/aweme 游客态能否取到话题视频
'use strict';
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');
function say(s) { process.stdout.write(String(s) + '\n'); }

async function get(ctx, cookie, path, extra) {
  const p = extra ? baseParams().concat(extra) : baseParams();
  const q = p.map(([k, v]) => k + '=' + v).join('&');
  const url = 'https://www.douyin.com' + path + '?' + q + '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
  const r = await fetch(url, { headers: { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': cookie } });
  const t = await r.text();
  return { http: r.status, raw: t };
}

(async () => {
  const ctx = createSigner();
  const ttwid = await getTtwid();
  const cookie = 'ttwid=' + ttwid;

  const feed = await get(ctx, cookie, '/aweme/v1/web/tab/feed/', [['count', '6'], ['refresh_index', '1'], ['tag_id', '']]);
  const j = JSON.parse(feed.raw);
  const list = j.aweme_list || [];
  say('feed status_code=' + j.status_code + ' items=' + list.length);

  const chas = [];
  list.forEach((a, i) => {
    const cl = a.cha_list || [];
    const te = a.text_extra || [];
    say('[' + i + '] ' + String(a.desc || '').slice(0, 34).replace(/\n/g, ' ') + '   cha=' + cl.length + ' text_extra=' + te.length);
    cl.forEach(c => { say('        cid=' + c.cid + '  ' + c.cha_name); chas.push(c); });
    te.forEach(t => { if (t.hashtag_name) say('        tag=' + t.hashtag_id + '  #' + t.hashtag_name); });
  });

  if (chas.length) {
    const cid = chas[0].cid;
    const r = await get(ctx, cookie, '/aweme/v1/web/challenge/aweme/', [['ch_id', String(cid)], ['count', '10'], ['offset', '0'], ['sort_type', '0']]);
    let rj = null;
    try { rj = JSON.parse(r.raw); } catch (e) {}
    say('=== challenge/aweme ch_id=' + cid + ' -> http=' + r.http + ' status_code=' + (rj && rj.status_code) + ' msg=' + (rj && rj.status_msg));
    const al = (rj && rj.aweme_list) || [];
    say('    items=' + al.length);
    al.slice(0, 6).forEach((a, i) => say('    [' + i + '] ' + a.aweme_id + '  ' + String(a.desc || '').slice(0, 34).replace(/\n/g, ' ')));
    if (!al.length) say('    raw=' + r.raw.slice(0, 220));
  } else {
    say('!! feed 里没有任何 cha_list（话题通道需要另找 ch_id）');
  }
})().catch(e => say('FATAL ' + (e && e.stack || e)));
