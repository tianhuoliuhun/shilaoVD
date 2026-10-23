// 找"竖屏信息流"（= myDV 文案里的「移动推荐」）走哪个接口
'use strict';
const fs = require('fs');
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');

const OUT = './portraitfeed.json';
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

function params(override) {
  let p = baseParams();
  if (override) {
    p = p.filter(([k]) => !(k in override));
    for (const k of Object.keys(override)) p.push([k, String(override[k])]);
  }
  p.push(['count', '10']);
  p.push(['tag_id', '']);
  return p;
}

async function hit(ctx, cookie, label, pathname, override, extra) {
  const all = [];
  for (let page = 1; page <= 2; page++) {
    const ps = params(override);
    for (const [k, v] of (extra || [])) ps.push([k, v]);
    ps.push(['refresh_index', String(page), ]);
    const q = ps.map(([k, v]) => k + '=' + v).join('&');
    const url = 'https://www.douyin.com' + pathname + '?' + q +
      '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
    const r = await fetch(url, { headers: { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': cookie } });
    const body = await r.text();
    let j = null; try { j = JSON.parse(body); } catch (e) { }
    const list = (j && j.aweme_list) || [];
    const sc = j ? (j.status_code !== undefined ? j.status_code : (j.status && j.status.code)) : 'n/a';
    say('  ' + label + ' p' + page + ' HTTP ' + r.status + ' sc=' + sc + ' n=' + list.length);
    all.push(...list);
    await new Promise(x => setTimeout(x, 350));
  }
  const c = classify(all);
  say('  → ' + label + '：横屏 ' + c.landscape + ' / 竖屏 ' + c.portrait + '（共 ' + c.total + '）');
  return { label, pathname, override, extra, ...c };
}

(async () => {
  const out = [];
  try {
    const ctx = createSigner();
    const cookie = 'ttwid=' + (await getTtwid());

    say('=== 1) v2 App 端接口 /aweme/v2/feed/ ===');
    out.push(await hit(ctx, cookie, 'v2/feed', '/aweme/v2/feed/', null, []));

    say('=== 2) tab/feed 换 aid=2329（抖音App）===');
    out.push(await hit(ctx, cookie, 'tab/feed aid2329', '/aweme/v1/web/tab/feed/', { aid: 2329 }, []));

    say('=== 3) tab/feed 换 aid=1128（抖音Android）===');
    out.push(await hit(ctx, cookie, 'tab/feed aid1128', '/aweme/v1/web/tab/feed/', { aid: 1128 }, []));

    say('=== 4) module/feed（v1，换 module_id）===');
    out.push(await hit(ctx, cookie, 'module/feed', '/aweme/v1/web/module/feed/', null, [['module_id', '1']]));

    say('=== 5) tab/feed ＋ tab_id 参数 ===');
    out.push(await hit(ctx, cookie, 'tab/feed tab_id=1', '/aweme/v1/web/tab/feed/', null, [['tab_id', '1']]));

    fs.writeFileSync(OUT, JSON.stringify(out, null, 2), 'utf8');
    say('[done] wrote ' + OUT);
  } catch (e) {
    say('[FATAL] ' + e.message);
    fs.writeFileSync(OUT, JSON.stringify({ error: String(e), out }, null, 2), 'utf8');
  }
})();
