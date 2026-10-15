// 探测「除 tab/feed、channel/feed 之外还有哪些视频 API 在游客态可用」。
// 思路：先用推荐流拿一条真实视频，取 aweme_id / sec_uid 作为后续接口的输入。
'use strict';
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');
function say(s) { process.stdout.write(String(s) + '\n'); }

async function get(ctx, cookie, path, extra) {
  const p = extra && extra.length ? baseParams().concat(extra) : baseParams();
  const q = p.map(([k, v]) => k + '=' + v).join('&');
  const url = 'https://www.douyin.com' + path + '?' + q + '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
  const r = await fetch(url, {
    headers: { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': cookie },
  });
  const t = await r.text();
  let j = null; try { j = JSON.parse(t); } catch (e) {}
  return { http: r.status, json: j, raw: t };
}

function countItems(j) {
  if (!j || typeof j !== 'object') return -1;
  for (const k of ['aweme_list', 'data', 'word_list', 'category_list', 'card_list', 'banners', 'data_list']) {
    if (Array.isArray(j[k])) return j[k].length;
  }
  return 0;
}

(async () => {
  const ctx = createSigner();
  const ttwid = await getTtwid();
  const cookie = 'ttwid=' + ttwid;
  say('ttwid=' + String(ttwid).slice(0, 24) + '...');

  const base = await get(ctx, cookie, '/aweme/v1/web/tab/feed/', [['count', '6'], ['refresh_index', '1']]);
  const list0 = (base.json && base.json.aweme_list) || [];
  say('【基线·推荐流】 http=' + base.http + ' status_code=' + (base.json && base.json.status_code) + ' items=' + list0.length);
  if (!list0.length) say('    raw=' + base.raw.slice(0, 200));

  const first = list0[0] || {};
  const awemeId = first.aweme_id || '';
  const authorSec = (first.author && (first.author.sec_uid || first.author.sec_uid)) || '';
  say('样本: aweme_id=' + awemeId + '  author.sec_uid=' + String(authorSec).slice(0, 34));
  say('');

  const cases = [
    ['相关视频 related', '/aweme/v1/web/aweme/related/', [['aweme_id', awemeId], ['count', '10'], ['filterGid', ''], ['refresh_index', '1']]],
    ['热搜榜 hot/search', '/aweme/v1/web/hot/search/list/', []],
    ['作者作品 aweme/post', '/aweme/v1/web/aweme/post/', [['sec_user_id', authorSec], ['count', '10'], ['max_cursor', '0']]],
    ['综合搜索 search/single', '/aweme/v1/web/general/search/single/', [['keyword', '三农'], ['offset', '0'], ['count', '10'], ['search_channel', 'aweme_general']]],
    ['视频详情 aweme/detail', '/aweme/v1/web/aweme/detail/', [['aweme_id', awemeId]]],
    ['分类列表 category/list', '/aweme/v1/web/category/list/', []],
    ['模块流 module/feed', '/aweme/v1/web/module/feed/', [['module_id', '1'], ['count', '10'], ['refresh_index', '1']]],
    ['直播列表 live/list', '/aweme/v1/web/live/list/', [['count', '10'], ['offset', '0']]],
  ];

  for (const [name, path, extra] of cases) {
    try {
      const r = await get(ctx, cookie, path, extra);
      const j = r.json || {};
      say('【' + name + '】' + path);
      say('    http=' + r.http + ' status_code=' + j.status_code + ' 条数=' + countItems(j)
        + ' raw0=' + r.raw.slice(0, 130).replace(/\s+/g, ' '));
    } catch (e) {
      say('【' + name + '】' + path + '  ERROR ' + (e && e.message || e));
    }
  }
  say('');
  say('DONE');
})().catch(e => say('FATAL ' + (e && e.stack || e)));
