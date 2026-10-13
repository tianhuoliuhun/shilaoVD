// 验证 tag_id 是否能切换频道内容（决定"第二个分区"能用什么）
'use strict';
const fs = require('fs');
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');

const OUT = 'D:/Projects/myDVQ/probe/tagid.json';
function say(s) { process.stdout.write(s.replace(/[^\x20-\x7e\n]/g, '?') + '\n'); }

async function probe(ctx, cookie, label, tagId) {
  const p = baseParams();
  p.push(['round_trip_time', '500']);
  p.push(['count', '10']);
  p.push(['refresh_index', '1']);
  p.push(['tag_id', tagId]);
  const q = p.map(([k, v]) => k + '=' + v).join('&');
  const url = 'https://www.douyin.com/aweme/v1/web/tab/feed/?' + q +
    '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
  const r = await fetch(url, { headers: { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': cookie } });
  const body = await r.text();
  let j = null; try { j = JSON.parse(body); } catch (e) { }
  const rec = { label, tagId, status: r.status, bytes: body.length };
  if (j) {
    rec.status_code = j.status_code;
    const L = j.aweme_list || [];
    rec.count = L.length;
    rec.items = L.map(x => ({
      id: x.aweme_id,
      desc: (x.desc || '').slice(0, 40),
      author: x.author && x.author.nickname,
    }));
  }
  say('[' + label + ' tag_id=' + tagId + '] HTTP ' + r.status +
    (rec.status_code !== undefined ? ' status_code=' + rec.status_code : '') +
    ' count=' + (rec.count === undefined ? '-' : rec.count));
  return rec;
}

(async () => {
  const res = [];
  try {
    const ctx = createSigner();
    const ttwid = await getTtwid();
    const cookie = 'ttwid=' + ttwid;
    for (const [label, tag] of [
      ['默认(空)', ''],
      ['全部', '0'],
      ['美食', '300204'],
      ['游戏', '300205'],
      ['影视', '300215'],
      ['二次元', '300206'],
    ]) {
      res.push(await probe(ctx, cookie, label, tag));
    }
    fs.writeFileSync(OUT, JSON.stringify(res, null, 2), 'utf8');
    say('[done] wrote ' + OUT);
  } catch (e) {
    say('[FATAL] ' + e.message);
    fs.writeFileSync(OUT, JSON.stringify({ error: String(e), res }, null, 2), 'utf8');
  }
})();
