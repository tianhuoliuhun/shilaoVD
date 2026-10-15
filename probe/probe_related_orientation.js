// 关键验证：用「同方向种子」拉 related，返回内容是否也偏同方向？（决定方向过滤后还剩几条可用）
// 顺带复查 tab/feed 的 tag_id 是否仍无效，并多试几个候选端点。
'use strict';
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');
function say(s) { process.stdout.write(String(s) + '\n'); }

const MODE = {
  PORTRAIT: ['1128', 'channel_web_mobile', 'android'],
  LANDSCAPE: ['6383', 'channel_pc_web', 'webapp'],
};

// 复刻 DouyinApi.buildFeedQuery 的参数构造（含方向三件套，保持键序）
function feedParams(mode, count, refreshIndex, tagId) {
  const m = MODE[mode];
  return baseParams().map(([k, v]) => {
    if (k === 'aid') return [k, m[0]];
    if (k === 'channel') return [k, m[1]];
    if (k === 'device_platform') return [k, m[2]];
    if (k === 'round_trip_time') return [k, '500'];
    return [k, v];
  }).concat([['count', String(count)], ['refresh_index', String(refreshIndex)], ['tag_id', tagId || '']]);
}

async function get(ctx, cookie, path, params) {
  const q = params.map(([k, v]) => k + '=' + v).join('&');
  const url = 'https://www.douyin.com' + path + '?' + q + '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
  const r = await fetch(url, {
    headers: { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': cookie },
  });
  const t = await r.text();
  let j = null; try { j = JSON.parse(t); } catch (e) {}
  return { http: r.status, json: j, raw: t };
}

function ori(a) {
  const v = (a && a.video) || {};
  const w = v.width || 0, h = v.height || 0;
  if (!w || !h) return '?';
  return h > w ? '竖' : '横';
}
function hasUrl(a) {
  const v = (a && a.video) || {};
  return ((((v.play_addr || {}).url_list) || []).length) > 0;
}
function stats(list) {
  let p = 0, l = 0, u = 0;
  for (const a of list) {
    const o = ori(a);
    if (o === '竖') p++; else if (o === '横') l++;
    if (hasUrl(a)) u++;
  }
  return `竖=${p} 横=${l} 有直链=${u}/${list.length}`;
}

(async () => {
  const ctx = createSigner();
  const ttwid = await getTtwid();
  const cookie = 'ttwid=' + ttwid;

  for (const mode of ['PORTRAIT', 'LANDSCAPE']) {
    const base = await get(ctx, cookie, '/aweme/v1/web/tab/feed/', feedParams(mode, 10, 3, ''));
    const list0 = (base.json && base.json.aweme_list) || [];
    say('=== ' + mode + ' 推荐流: items=' + list0.length + '  ' + stats(list0));

    // 挑一条与 mode 同方向的种子
    const want = mode === 'PORTRAIT' ? '竖' : '横';
    const seed = list0.find(a => ori(a) === want) || list0[0];
    if (!seed) { say('   没有可用种子'); continue; }
    say('   种子: ' + ori(seed) + ' aweme_id=' + seed.aweme_id + ' | ' + String(seed.desc || '').slice(0, 34).replace(/\n/g, ' '));

    for (const ri of [1, 2]) {
      const rel = await get(ctx, cookie, '/aweme/v1/web/aweme/related/', [
        ['aweme_id', seed.aweme_id], ['count', '20'], ['filterGid', seed.aweme_id], ['refresh_index', String(ri)],
      ]);
      const rl = (rel.json && rel.json.aweme_list) || [];
      const same = rl.filter(a => ori(a) === want).length;
      say('   related ri=' + ri + ': http=' + rel.http + ' status=' + (rel.json && rel.json.status_code)
        + ' 条数=' + rl.length + '  ' + stats(rl) + '  → 同方向(' + want + ')=' + same + ' 条');
    }
  }

  say('');
  say('=== 其它候选端点复查 ===');
  const cases = [
    ['tab/feed + tag_id(复验应为无效)', '/aweme/v1/web/tab/feed/', feedParams('LANDSCAPE', 8, 4, '300204')],
    ['热搜榜(看结构)', '/aweme/v1/web/hot/search/list/', baseParams()],
    ['热搜词视频', '/aweme/v1/web/hot/search/video/list/', baseParams().concat([['count', '10'], ['offset', '0']])],
    ['相似视频 aweme/similar', '/aweme/v1/web/aweme/similar/', baseParams().concat([['aweme_id', '7666778335199989034'], ['count', '10']])],
    ['根 feed', '/aweme/v1/web/feed/', baseParams().concat([['count', '8'], ['refresh_index', '1']])],
  ];
  for (const [name, path, params] of cases) {
    try {
      const r = await get(ctx, cookie, path, params);
      const j = r.json || {};
      const arrKeys = ['aweme_list', 'data', 'word_list', 'card_list'];
      let n = -1;
      for (const k of arrKeys) if (Array.isArray(j[k])) { n = j[k].length; break; }
      say('【' + name + '】http=' + r.http + ' status=' + j.status_code + ' 顶层键=' +
        Object.keys(j).slice(0, 8).join(',') + ' 条数=' + n);
    } catch (e) {
      say('【' + name + '】ERROR ' + (e && e.message || e));
    }
  }
  say('');
  say('DONE');
})().catch(e => say('FATAL ' + (e && e.stack || e)));
