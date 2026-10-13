// 验证：换成手机端 UA / 移动端参数后，抖音是否返回竖屏信息流
'use strict';
const fs = require('fs');
const { createSigner, baseParams, getTtwid } = require('./signer.js');

const OUT = 'D:/Projects/myDVQ/probe/mobile.json';
const PC_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';
const ANDROID_UA = 'Mozilla/5.0 (Linux; Android 13; SM-S9080) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36';
const IPHONE_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1';

function say(s) { process.stdout.write(s.replace(/[^\x20-\x7e\n]/g, '?') + '\n'); }

function classify(list) {
  let landscape = 0, portrait = 0, noDim = 0;
  for (const it of (list || [])) {
    const v = it.video || {};
    const w = v.width, h = v.height;
    if (!w || !h) noDim++;
    else if (w > h) landscape++;
    else portrait++;
  }
  return { total: (list || []).length, landscape, portrait, noDim };
}

// override: 覆盖 baseParams 里的键
function buildParams(override) {
  let p = baseParams().filter(([k]) => !(k in override));
  for (const k of Object.keys(override)) p.push([k, String(override[k])]);
  p.push(['round_trip_time', '500']);
  p.push(['count', '10']);
  p.push(['tag_id', '']);
  return p;
}

async function run(ctx, cookie, label, reqUA, override, pages) {
  const all = [];
  for (let p = 1; p <= pages; p++) {
    const params = buildParams(override);
    params.push(['refresh_index', String(p)]);
    const q = params.map(([k, v]) => k + '=' + v).join('&');
    const url = 'https://www.douyin.com/aweme/v1/web/tab/feed/?' + q +
      '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
    const r = await fetch(url, {
      headers: { 'User-Agent': reqUA, 'Referer': 'https://www.douyin.com/', 'Cookie': cookie },
    });
    const body = await r.text();
    let j = null; try { j = JSON.parse(body); } catch (e) { }
    const list = (j && j.aweme_list) || [];
    const sc = j ? j.status_code : 'n/a';
    say('  page' + p + ' HTTP ' + r.status + ' sc=' + sc + ' 条数=' + list.length +
      (list.length === 0 && j ? ' msg=' + (j.status_msg || '') : ''));
    if (list.length === 0 && j && j.status_code !== 0) {
      say('    (被风控/参数错误，body头: ' + body.slice(0, 100).replace(/\s+/g, ' ') + ')');
    }
    all.push(...list);
    await new Promise(r2 => setTimeout(r2, 400));
  }
  const c = classify(all);
  say('[' + label + '] 共 ' + c.total + ' 条 → 横屏 ' + c.landscape + ' / 竖屏 ' + c.portrait);
  return { label, reqUA: reqUA.slice(0, 40), override, ...c };
}

(async () => {
  const out = [];
  try {
    const ttwid = await getTtwid();
    const cookie = 'ttwid=' + ttwid;

    // A: 只换请求头 UA（签名仍用 PC UA）
    say('=== A. 仅请求头换 Android UA（签名环境仍 PC）===');
    const ctxPC = createSigner();
    out.push(await run(ctxPC, cookie, 'A: 仅头换Android UA', ANDROID_UA, {}, 3));

    // B: UA 头 + 签名环境 UA 都换 Android，参数保持 PC
    say('=== B. 头与签名环境都换 Android UA ===');
    const ctxAnd = createSigner(ANDROID_UA);
    out.push(await run(ctxAnd, cookie, 'B: 全换Android UA', ANDROID_UA, {}, 3));

    // C: 全换 Android + 移动端参数（屏幕/平台/OS）
    say('=== C. Android UA + 移动端参数 ===');
    out.push(await run(ctxAnd, cookie, 'C: Android UA + 移动参数', ANDROID_UA, {
      screen_width: 412, screen_height: 915,
      os_name: 'Android', os_version: '13',
      platform: 'ANDROID', browser_platform: 'Linux armv8l',
      pc_client_type: 0,
    }, 3));

    // D: iPhone UA + 移动参数
    say('=== D. iPhone UA + 移动端参数 ===');
    const ctxIos = createSigner(IPHONE_UA);
    out.push(await run(ctxIos, cookie, 'D: iPhone UA + 移动参数', IPHONE_UA, {
      screen_width: 390, screen_height: 844,
      os_name: 'iOS', os_version: '17.5',
      platform: 'IPHONE', browser_platform: 'iPhone',
      pc_client_type: 0,
    }, 3));

    fs.writeFileSync(OUT, JSON.stringify(out, null, 2), 'utf8');
    say('[done] wrote ' + OUT);
  } catch (e) {
    say('[FATAL] ' + e.message);
    fs.writeFileSync(OUT, JSON.stringify({ error: String(e), out }, null, 2), 'utf8');
  }
})();
