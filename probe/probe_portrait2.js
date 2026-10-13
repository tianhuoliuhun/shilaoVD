// 继续压参数：aim 是找到"竖屏为主"的参数组合
'use strict';
const fs = require('fs');
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');

const OUT = 'D:/Projects/myDVQ/probe/portrait2.json';
function say(s) { process.stdout.write(s.replace(/[^\x20-\x7e\n]/g, '?') + '\n'); }

function classify(list) {
  let landscape = 0, portrait = 0, noDim = 0;
  for (const it of (list || [])) {
    const v = it.video || {};
    if (!v.width || !v.height) noDim++;
    else if (v.width > v.height) landscape++;
    else portrait++;
  }
  return { total: (list || []).length, landscape, portrait, noDim };
}

async function run(ctx, cookie, label, override, pages) {
  const all = [];
  let lastSc = 'n/a';
  for (let p = 1; p <= pages; p++) {
    let ps = baseParams().filter(([k]) => !(k in override));
    for (const k of Object.keys(override)) ps.push([k, String(override[k])]);
    ps.push(['count', '10']);
    ps.push(['refresh_index', String(p)]);
    ps.push(['tag_id', '']);
    const q = ps.map(([k, v]) => k + '=' + v).join('&');
    const url = 'https://www.douyin.com/aweme/v1/web/tab/feed/?' + q +
      '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
    const r = await fetch(url, { headers: { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': cookie } });
    const body = await r.text();
    let j = null; try { j = JSON.parse(body); } catch (e) { }
    lastSc = j && j.status_code !== undefined ? j.status_code : 'n/a';
    all.push(...((j && j.aweme_list) || []));
    await new Promise(x => setTimeout(x, 350));
  }
  const c = classify(all);
  const ratio = c.total ? Math.round(c.portrait * 100 / c.total) : 0;
  say('[' + label + '] sc=' + lastSc + ' 共 ' + c.total + ' → 横屏 ' + c.landscape +
    ' / 竖屏 ' + c.portrait + '（竖屏占比 ' + ratio + '%）');
  return { label, override, ...c, portraitRatio: ratio };
}

(async () => {
  const out = [];
  try {
    const ctx = createSigner();
    const cookie = 'ttwid=' + (await getTtwid());

    const cases = [
      ['基线 aid=6383', {}],
      ['aid=1128', { aid: 1128 }],
      ['aid=1128 + device_platform=android', { aid: 1128, device_platform: 'android' }],
      ['aid=1128 + channel=channel_web_mobile', { aid: 1128, channel: 'channel_web_mobile' }],
      ['aid=1128 + android + mobile渠道', { aid: 1128, device_platform: 'android', channel: 'channel_web_mobile' }],
      ['aid=2329 + device_platform=android', { aid: 2329, device_platform: 'android' }],
    ];
    for (const [label, ov] of cases) {
      out.push(await run(ctx, cookie, label, ov, 3));
    }

    fs.writeFileSync(OUT, JSON.stringify(out, null, 2), 'utf8');
    say('[done] wrote ' + OUT);
    // 排名
    const best = out.slice().sort((a, b) => b.portraitRatio - a.portraitRatio)[0];
    say('竖屏占比最高：' + best.label + ' → ' + best.portraitRatio + '%');
  } catch (e) {
    say('[FATAL] ' + e.message);
    fs.writeFileSync(OUT, JSON.stringify({ error: String(e), out }, null, 2), 'utf8');
  }
})();
