// (A) 用 text_extra.hashtag_id 当 ch_id，验证 challenge/aweme 游客可用性
// (B) 统计推荐流里「上海」相关内容占比（评估降级方案）
'use strict';
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');
function say(s) { process.stdout.write(String(s) + '\n'); }

async function get(ctx, cookie, path, extra) {
  const p = extra ? baseParams().concat(extra) : baseParams();
  const q = p.map(([k, v]) => k + '=' + v).join('&');
  const url = 'https://www.douyin.com' + path + '?' + q + '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
  const r = await fetch(url, { headers: { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': cookie } });
  const t = await r.text();
  let j = null; try { j = JSON.parse(t); } catch (e) {}
  return { http: r.status, json: j, raw: t };
}

(async () => {
  const ctx = createSigner();
  const ttwid = await getTtwid();
  const cookie = 'ttwid=' + ttwid;

  const feed = await get(ctx, cookie, '/aweme/v1/web/tab/feed/', [['count', '8'], ['refresh_index', '1'], ['tag_id', '']]);
  const list = (feed.json && feed.json.aweme_list) || [];
  let hid = null, hname = null;
  for (const a of list) {
    for (const t of (a.text_extra || [])) {
      if (t.hashtag_id && t.hashtag_name) { hid = t.hashtag_id; hname = t.hashtag_name; break; }
    }
    if (hid) break;
  }
  say('=== (A) 话题通道 ===');
  say('取到话题: id=' + hid + ' name=' + hname);

  const paths = [
    '/aweme/v1/web/challenge/aweme/',
    '/aweme/v1/web/challenge/detail/',
    '/aweme/v1/web/hot/search/list/',
  ];
  for (const p of paths) {
    const extra = p.indexOf('challenge/aweme') >= 0
      ? [['ch_id', String(hid)], ['count', '10'], ['offset', '0'], ['sort_type', '0']]
      : (p.indexOf('detail') >= 0 ? [['ch_id', String(hid)]] : []);
    const r = await get(ctx, cookie, p, extra);
    const j = r.json || {};
    const n = (j.aweme_list || []).length;
    say('  ' + p + ' -> http=' + r.http + ' status_code=' + j.status_code + ' msg=' + j.status_msg + ' items=' + n);
    if (n) (j.aweme_list || []).slice(0, 4).forEach((a, i) => say('      [' + i + '] ' + a.aweme_id + ' ' + String(a.desc || '').slice(0, 30).replace(/\n/g, ' ')));
    if (!n && !j.status_code) say('      raw=' + r.raw.slice(0, 150));
  }

  // (B) 推荐流里「上海」相关占比
  say('');
  say('=== (B) 推荐流「上海」相关内容统计 ===');
  const KEY = ['上海', '沪语', '上海话', '阿拉', '申花', '本帮'];
  let total = 0, hit = 0;
  for (let page = 0; page < 4; page++) {
    const f = await get(ctx, cookie, '/aweme/v1/web/tab/feed/', [['count', '20'], ['refresh_index', String(page + 1)], ['tag_id', '']]);
    const l = (f.json && f.json.aweme_list) || [];
    total += l.length;
    for (const a of l) {
      const text = String(a.desc || '') + ' ' + (a.text_extra || []).map(t => t.hashtag_name || '').join(' ');
      if (KEY.some(k => text.includes(k))) { hit++; say('  命中: ' + String(a.desc || '').slice(0, 44).replace(/\n/g, ' ')); }
    }
    say('  page' + (page + 1) + ' items=' + l.length + ' 累计=' + total + ' 命中=' + hit);
  }
  say('  合计 ' + total + ' 条，含「上海/沪语」等关键词的 ' + hit + ' 条 (' + (total ? (hit * 100 / total).toFixed(1) : 0) + '%)');
})().catch(e => say('FATAL ' + (e && e.stack || e)));
