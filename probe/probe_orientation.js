// 关键验证：把请求里的 screen_width/screen_height 换成竖屏比例，抖音是否就返回竖屏信息流
'use strict';
const fs = require('fs');
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');

const OUT = 'D:/Projects/myDVQ/probe/orientation.json';
function say(s) { process.stdout.write(s.replace(/[^\x20-\x7e\n]/g, '?') + '\n'); }

function withScreen(params, w, h) {
  return params.map(([k, v]) =>
    k === 'screen_width' ? [k, String(w)] :
      k === 'screen_height' ? [k, String(h)] : [k, v]);
}

function classify(list) {
  let landscape = 0, portrait = 0, noDim = 0;
  const detail = [];
  for (const it of (list || [])) {
    const v = it.video || {};
    const w = v.width, h = v.height;
    if (!w || !h) noDim++;
    else if (w > h) landscape++;
    else portrait++;
    detail.push({ id: it.aweme_id, w, h, desc: (it.desc || '').slice(0, 24) });
  }
  return { total: (list || []).length, landscape, portrait, noDim, detail };
}

async function run(ctx, cookie, label, screenW, screenH, pages) {
  const all = [];
  for (let p = 1; p <= pages; p++) {
    let params = withScreen(baseParams(), screenW, screenH);
    params.push(['round_trip_time', '500']);
    params.push(['count', '10']);
    params.push(['refresh_index', String(p)]);
    params.push(['tag_id', '']);
    const q = params.map(([k, v]) => k + '=' + v).join('&');
    const url = 'https://www.douyin.com/aweme/v1/web/tab/feed/?' + q +
      '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
    const r = await fetch(url, { headers: { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': cookie } });
    const body = await r.text();
    let j = null; try { j = JSON.parse(body); } catch (e) { }
    const list = (j && j.aweme_list) || [];
    say('  page' + p + ' HTTP ' + r.status + ' 条数=' + list.length);
    all.push(...list);
    await new Promise(r2 => setTimeout(r2, 350));
  }
  const c = classify(all);
  say('[' + label + ' screen=' + screenW + 'x' + screenH + '] 共 ' + c.total +
    ' 条 → 横屏 ' + c.landscape + ' / 竖屏 ' + c.portrait + ' / 无尺寸 ' + c.noDim);
  return { label, screenW, screenH, ...c };
}

(async () => {
  const out = [];
  try {
    const ctx = createSigner();
    const ttwid = await getTtwid();
    const cookie = 'ttwid=' + ttwid;

    say('=== 横屏参数 2560x1440（基线）===');
    out.push(await run(ctx, cookie, '横屏基线', 2560, 1440, 3));

    say('=== 竖屏参数 1440x2560（手机竖屏）===');
    out.push(await run(ctx, cookie, '竖屏参数', 1440, 2560, 3));

    say('=== 竖屏参数 1080x2340（典型手机）===');
    out.push(await run(ctx, cookie, '手机竖屏 1080x2340', 1080, 2340, 3));

    fs.writeFileSync(OUT, JSON.stringify(out, null, 2), 'utf8');
    say('[done] wrote ' + OUT);
  } catch (e) {
    say('[FATAL] ' + e.message);
    fs.writeFileSync(OUT, JSON.stringify({ error: String(e), out }, null, 2), 'utf8');
  }
})();
