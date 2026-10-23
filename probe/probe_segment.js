// 探测「同城 / 移动推荐」到底走哪个接口/参数。
// 输出 detail.json（完整响应），stdout 只打 ASCII 摘要。
'use strict';
const fs = require('fs');
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');

const OUT = './segment.json';
const out = { probes: [] };

function say(s) { process.stdout.write(s.replace(/[^\x20-\x7e\n]/g, '?') + '\n'); }

function buildQuery(extra) {
  const p = baseParams();
  for (const [k, v] of (extra || [])) p.push([k, v]);
  return p.map(([k, v]) => k + '=' + v).join('&');
}

async function probe(ctx, cookie, label, pathname, extra) {
  const q = buildQuery(extra);
  const ab = ctx.makeABogus(q);
  const url = 'https://www.douyin.com' + pathname + '?' + q + '&a_bogus=' + encodeURIComponent(ab);
  const r = await fetch(url, {
    headers: { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': cookie },
  });
  const body = await r.text();
  let j = null; try { j = JSON.parse(body); } catch (e) { }
  const rec = { label, pathname, extra: extra || [], status: r.status, bytes: body.length };
  if (j && typeof j === 'object') {
    rec.status_code = j.status_code;
    rec.status_msg = j.status_msg;
    rec.top_keys = Object.keys(j).slice(0, 30);
    if (Array.isArray(j.aweme_list)) {
      rec.aweme_count = j.aweme_list.length;
      rec.ids = j.aweme_list.map(x => x.aweme_id).slice(0, 8);
    }
    if (j.data !== undefined) rec.data_sample = JSON.stringify(j.data).slice(0, 1200);
    rec.body_sample = body.slice(0, 1500);
  } else {
    rec.head = body.slice(0, 200);
  }
  out.probes.push(rec);
  say('[' + label + '] HTTP ' + r.status + ' bytes=' + body.length +
    (rec.status_code !== undefined ? ' status_code=' + rec.status_code : '') +
    (rec.aweme_count !== undefined ? ' awemes=' + rec.aweme_count : ''));
  return rec;
}

(async () => {
  try {
    const ctx = createSigner();
    const ttwid = await getTtwid();
    if (!ttwid) throw new Error('no ttwid');
    const cookie = 'ttwid=' + ttwid;

    // 1) 频道表（web 端的分区定义）
    await probe(ctx, cookie, 'channel/setting', '/aweme/v1/web/home/channel/setting/', []);

    // 2) 同城候选端点
    await probe(ctx, cookie, 'nearby/feed', '/aweme/v1/web/nearby/feed/', [['count', '10'], ['refresh_index', '1']]);
    await probe(ctx, cookie, 'nearby/poi', '/aweme/v1/web/poi/nearby/', [['count', '10'], ['cursor', '0']]);
    await probe(ctx, cookie, 'location/feed', '/aweme/v1/web/location/feed/', [['count', '10'], ['refresh_index', '1']]);
    await probe(ctx, cookie, 'tab/feed+tag', '/aweme/v1/web/tab/feed/', [['count', '10'], ['refresh_index', '1'], ['tag_id', '300203']]);
    await probe(ctx, cookie, 'tab/feed+city', '/aweme/v1/web/tab/feed/', [['count', '10'], ['refresh_index', '1'], ['tag_id', '']]);
    await probe(ctx, cookie, 'discover', '/aweme/v1/web/discover/search/', [['count', '10'], ['cursor', '0']]);

    fs.writeFileSync(OUT, JSON.stringify(out, null, 2), 'utf8');
    say('[done] wrote ' + OUT);
  } catch (e) {
    say('[FATAL] ' + e.stack);
    fs.writeFileSync(OUT, JSON.stringify({ error: String(e), stack: e.stack, out }, null, 2), 'utf8');
  }
})();
