// 探测：推荐流/各候选通道里，横屏(宽>高)与竖屏视频各占多少
'use strict';
const fs = require('fs');
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');

const OUT = './landscape.json';
function say(s) { process.stdout.write(s.replace(/[^\x20-\x7e\n]/g, '?') + '\n'); }

async function fetchJson(ctx, cookie, pathname, params) {
  const q = params.map(([k, v]) => k + '=' + v).join('&');
  const url = 'https://www.douyin.com' + pathname + '?' + q + '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
  const r = await fetch(url, { headers: { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': cookie } });
  const body = await r.text();
  try { return { status: r.status, json: JSON.parse(body) }; }
  catch (e) { return { status: r.status, json: null, head: body.slice(0, 120) }; }
}

function classify(list) {
  const stat = { total: 0, landscape: 0, portrait: 0, square: 0, noDim: 0 };
  const detail = [];
  for (const it of (list || [])) {
    const v = it.video || {};
    const w = v.width, h = v.height;
    stat.total++;
    let kind;
    if (!w || !h) { stat.noDim++; kind = 'noDim'; }
    else if (w > h) { stat.landscape++; kind = 'landscape'; }
    else if (w < h) { stat.portrait++; kind = 'portrait'; }
    else { stat.square++; kind = 'square'; }
    detail.push({ id: it.aweme_id, w, h, kind, desc: (it.desc || '').slice(0, 26) });
  }
  return { stat, detail };
}

async function probeChannel(ctx, cookie, label, pathname, extra, pages) {
  const all = [];
  for (let p = 1; p <= pages; p++) {
    const params = baseParams().filter(([k]) => k !== 'pc_libra_divert');
    for (const [k, v] of extra) params.push([k, v]);
    params.push(['count', '10']);
    params.push(['refresh_index', String(p)]);
    const res = await fetchJson(ctx, cookie, pathname, params);
    const list = (res.json && res.json.aweme_list) || [];
    const sc = res.json ? res.json.status_code : 'n/a';
    say('  page' + p + ' HTTP ' + res.status + ' sc=' + sc + ' 条数=' + list.length);
    all.push(...list);
    await new Promise(r => setTimeout(r, 400));
  }
  const { stat, detail } = classify(all);
  say('[' + label + '] 合计 ' + stat.total + ' 条 → 横屏 ' + stat.landscape +
    ' / 竖屏 ' + stat.portrait + ' / 无尺寸 ' + stat.noDim);
  return { label, pathname, extra, stat, detail };
}

(async () => {
  const out = { probes: [] };
  try {
    const ctx = createSigner();
    const ttwid = await getTtwid();
    const cookie = 'ttwid=' + ttwid;

    say('=== A. 普通推荐流 tab/feed（翻 4 页）===');
    out.probes.push(await probeChannel(ctx, cookie, 'tab/feed 推荐',
      '/aweme/v1/web/tab/feed/', [['tag_id', '']], 4));

    say('=== B. channel/feed tag_id=300203（猜测=精选/横屏）===');
    out.probes.push(await probeChannel(ctx, cookie, 'channel/feed 300203',
      '/aweme/v1/web/channel/feed/', [['tag_id', '300203']], 3));

    say('=== C. channel/feed tag_id=0（全部）===');
    out.probes.push(await probeChannel(ctx, cookie, 'channel/feed 0',
      '/aweme/v1/web/channel/feed/', [['tag_id', '0']], 3));

    fs.writeFileSync(OUT, JSON.stringify(out, null, 2), 'utf8');
    say('[done] wrote ' + OUT);
  } catch (e) {
    say('[FATAL] ' + e.message);
    fs.writeFileSync(OUT, JSON.stringify({ error: String(e), out }, null, 2), 'utf8');
  }
})();
