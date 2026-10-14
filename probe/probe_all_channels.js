// 遍历全部 17 个频道，确认每个都有内容（决定能否每个都单独出包）
'use strict';
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');
function say(s) { process.stdout.write(String(s) + '\n'); }
const CH = [
  ['0','全部'],['100000','公开课'],['300204','美食'],['300205','游戏'],
  ['300206','二次元'],['300207','体育'],['300209','音乐'],['300213','知识'],
  ['300214','小剧场'],['300215','影视'],['300216','生活vlog'],['300217','亲子'],
  ['300218','汽车'],['300219','三农'],['300220','动物'],['300221','旅行'],['300222','美妆穿搭'],
];
(async () => {
  const ctx = createSigner();
  const ttwid = await getTtwid();
  const H = { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': 'ttwid=' + ttwid };
  let ok = 0, empty = [];
  for (const [id, name] of CH) {
    const q = baseParams().concat([['tag_id', id], ['count', '10'], ['refresh_index', '1']]).map(([k, v]) => k + '=' + v).join('&');
    const url = 'https://www.douyin.com/aweme/v1/web/channel/feed/?' + q + '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
    try {
      const r = await fetch(url, { headers: H });
      const j = await r.json();
      const list = j.aweme_list || [];
      let v = 0, h = 0;
      list.forEach(a => { const w = (a.video || {}).width || 0, hh = (a.video || {}).height || 0; if (hh > w) v++; else h++; });
      const first = list[0] ? String(list[0].desc || '').slice(0, 26).replace(/\n/g, ' ') : '';
      say(name.padEnd(6) + ' tag_id=' + String(id).padEnd(7) + ' sc=' + j.status_code + ' items=' + String(list.length).padEnd(3) + ' 竖/横=' + v + '/' + h + '  首条: ' + first);
      if (list.length) ok++; else empty.push(name + '(' + id + ')');
    } catch (e) {
      say(name + ' tag_id=' + id + ' FAIL ' + e.message);
    }
    await new Promise(r => setTimeout(r, 400));   // 别太密，避免触发风控
  }
  say('');
  say('有内容的频道: ' + ok + '/' + CH.length + (empty.length ? ('   空: ' + empty.join(', ')) : '   （全部都有内容）'));
})().catch(e => say('FATAL ' + (e && e.stack || e)));
