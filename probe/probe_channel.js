// 试频道内容接口 + 移动端参数（判断"第二个分区"能否实现）
'use strict';
const fs = require('fs');
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');

const OUT = './channel.json';
function say(s) { process.stdout.write(s.replace(/[^\x20-\x7e\n]/g, '?') + '\n'); }

async function hit(ctx, cookie, label, pathname, params) {
  const q = params.map(([k, v]) => k + '=' + v).join('&');
  const url = 'https://www.douyin.com' + pathname + '?' + q + '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
  const r = await fetch(url, { headers: { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': cookie } });
  const body = await r.text();
  let j = null; try { j = JSON.parse(body); } catch (e) { }
  const rec = { label, pathname, status: r.status, bytes: body.length };
  if (j) {
    rec.status_code = j.status_code;
    rec.status_msg = j.status_msg;
    rec.top_keys = Object.keys(j).slice(0, 20);
    const L = j.aweme_list || (j.data && j.data.aweme_list) || [];
    rec.count = Array.isArray(L) ? L.length : 0;
    rec.items = (L || []).slice(0, 3).map(x => ({ id: x.aweme_id, desc: (x.desc || '').slice(0, 35), author: x.author && x.author.nickname }));
    if (!rec.count) rec.body_sample = body.slice(0, 300);
  } else rec.head = body.slice(0, 120);
  say('[' + label + '] HTTP ' + r.status + ' bytes=' + body.length +
    (rec.status_code !== undefined ? ' sc=' + rec.status_code : '') + ' n=' + (rec.count || 0));
  return rec;
}

(async () => {
  const res = [];
  try {
    const ctx = createSigner();
    const ttwid = await getTtwid();
    const cookie = 'ttwid=' + ttwid;

    const std = () => { const p = baseParams(); p.push(['count', '10']); p.push(['refresh_index', '1']); return p; };

    // 频道内容接口候选
    res.push(await hit(ctx, cookie, 'channel/feed', '/aweme/v1/web/channel/feed/', [...std(), ['tag_id', '300204']]));
    res.push(await hit(ctx, cookie, 'home/channel/feed', '/aweme/v1/web/home/channel/feed/', [...std(), ['tag_id', '300204']]));
    res.push(await hit(ctx, cookie, 'tab/feed+channel_id', '/aweme/v1/web/tab/feed/', [...std(), ['tag_id', '300204'], ['channel_id', '300204']]));
    res.push(await hit(ctx, cookie, 'channel/detail', '/aweme/v1/web/channel/detail/', [...std(), ['tag_id', '300204']]));

    // 移动端参数（myDV 的"移动推荐"嫌疑）
    const mob = baseParams().filter(([k]) => k !== 'pc_libra_divert');
    mob.push(['device_platform', 'android']);
    mob.push(['count', '10']); mob.push(['refresh_index', '1']); mob.push(['tag_id', '']);
    res.push(await hit(ctx, cookie, 'tab/feed(device_platform=android)', '/aweme/v1/web/tab/feed/', mob));

    // 精选（MacDV 提到 myDV 有"横屏精选通道"）
    res.push(await hit(ctx, cookie, 'tab/feed(精选候选)', '/aweme/v1/web/tab/feed/', [...std(), ['tag_id', 'jingxuan']]));

    fs.writeFileSync(OUT, JSON.stringify(res, null, 2), 'utf8');
    say('[done] wrote ' + OUT);
  } catch (e) {
    say('[FATAL] ' + e.message);
    fs.writeFileSync(OUT, JSON.stringify({ error: String(e), res }, null, 2), 'utf8');
  }
})();
