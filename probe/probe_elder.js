// 抓候选频道各 20 条，用真实内容判断"哪些适合不识字的老人"
'use strict';
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');
function say(s) { process.stdout.write(String(s) + '\n'); }
const CAND = [
  ['300219','三农'],['300209','音乐'],['300204','美食'],['300220','动物'],
  ['300215','影视'],['300221','旅行'],['300217','亲子'],['300207','体育'],
];
(async () => {
  const ctx = createSigner();
  const ttwid = await getTtwid();
  const H = { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': 'ttwid=' + ttwid };
  for (const [id, name] of CAND) {
    const q = baseParams().concat([['tag_id', id], ['count', '20'], ['refresh_index', '1']]).map(([k, v]) => k + '=' + v).join('&');
    const url = 'https://www.douyin.com/aweme/v1/web/channel/feed/?' + q + '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
    try {
      const r = await fetch(url, { headers: H });
      const j = await r.json();
      const list = j.aweme_list || [];
      let v = 0, h = 0;
      say('=== ' + name + ' (' + id + ')  items=' + list.length);
      list.slice(0, 12).forEach((a, i) => {
        const vv = a.video || {};
        const isV = (vv.height || 0) > (vv.width || 0);
        if (isV) v++; else h++;
        const dur = Math.round((vv.duration || 0) / 1000);
        say('   ' + (isV ? '竖' : '横') + ' ' + (dur ? dur + 's' : '?') + '  ' + String(a.desc || '').slice(0, 44).replace(/\n/g, ' '));
      });
      say('   (前12条 竖/横 = ' + v + '/' + h + ')');
    } catch (e) { say('=== ' + name + ' FAIL ' + e.message); }
    await new Promise(r => setTimeout(r, 400));
  }
})().catch(e => say('FATAL ' + (e && e.stack || e)));
