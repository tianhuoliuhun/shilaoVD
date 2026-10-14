// 探针 v2：把原始响应落到文件（UTF-8，保留中文），便于看 status_msg 原文
'use strict';
const fs = require('fs');
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');
function say(s) { process.stdout.write(String(s) + '\n'); }

const OUT = 'shanghai_probe.json';

async function get(ctx, cookie, path, extra) {
  const p = extra ? baseParams().concat(extra) : baseParams();
  const q = p.map(([k, v]) => k + '=' + v).join('&');
  const url = 'https://www.douyin.com' + path + '?' + q + '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
  const r = await fetch(url, {
    headers: { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': cookie },
  });
  const text = await r.text();
  return { url: path, http: r.status, raw: text.slice(0, 2000) };
}

(async () => {
  const ctx = createSigner();
  const ttwid = await getTtwid();
  const cookie = 'ttwid=' + ttwid;
  const out = { cookieLen: cookie.length };

  out.search_single = await get(ctx, cookie, '/aweme/v1/web/general/search/single/', [
    ['keyword', '上海话'], ['search_channel', 'aweme_general'], ['sort_type', '0'],
    ['publish_time', '0'], ['filter_duration', '0'], ['search_source', 'normal_search'],
    ['query_correct_type', '1'], ['is_filter_search', '0'], ['offset', '0'], ['count', '10'],
  ]);
  out.challenge_search = await get(ctx, cookie, '/aweme/v1/web/challenge/search/', [
    ['keyword', '上海话'], ['offset', '0'], ['count', '10'], ['search_source', 'challenge'],
  ]);
  out.search_item = await get(ctx, cookie, '/aweme/v1/web/search/item/', [
    ['keyword', '沪语'], ['offset', '0'], ['count', '10'], ['search_channel', 'aweme_video'],
  ]);
  // 对照：推荐流（已知可用）
  out.tab_feed = await get(ctx, cookie, '/aweme/v1/web/tab/feed/', [
    ['count', '3'], ['refresh_index', '1'], ['tag_id', ''],
  ]);

  fs.writeFileSync(OUT, JSON.stringify(out, null, 2), 'utf8');
  say('wrote ' + OUT);
  ['search_single', 'challenge_search', 'search_item', 'tab_feed'].forEach(k => {
    const v = out[k];
    let sc = '?';
    try { sc = JSON.parse(v.raw).status_code; } catch (e) {}
    say(k + ': http=' + v.http + ' status_code=' + sc + ' len=' + v.raw.length);
  });
})().catch(e => say('FATAL ' + (e && e.stack || e)));
