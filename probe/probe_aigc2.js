// 深入探测 AI 标识：查 video.meta / web_raw_data / 标签类字段 / 文本里的 AI 关键词
'use strict';
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');
function say(s) { process.stdout.write(String(s) + '\n'); }

async function grab(chId) {
  const ctx = createSigner();
  const ttwid = await getTtwid();
  const H = { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': 'ttwid=' + ttwid };
  const extra = chId ? [['tag_id', chId], ['count', '20'], ['refresh_index', '1']]
                     : [['count', '20'], ['refresh_index', '1'], ['tag_id', '']];
  const q = baseParams().concat(extra).map(([k, v]) => k + '=' + v).join('&');
  const path = chId ? '/aweme/v1/web/channel/feed/' : '/aweme/v1/web/tab/feed/';
  const url = 'https://www.douyin.com' + path + '?' + q + '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
  const r = await fetch(url, { headers: H });
  return (await r.json()).aweme_list || [];
}

(async () => {
  const list = (await grab(null)).concat(await grab('300206'));   // 推荐 + 二次元（AI 图最多）
  say('样本条数 = ' + list.length);

  // ① video.meta 与 web_raw_data 结构
  say('');
  say('=== video.meta ===');
  const metas = new Set();
  list.forEach(a => { if (a.video && typeof a.video.meta === 'string') metas.add(a.video.meta.slice(0, 40)); });
  say('  ' + (metas.size ? [...metas].slice(0, 5).join(' | ') : '(没有 video.meta 或非字符串)'));

  say('=== web_raw_data ===');
  const wr = new Set();
  list.forEach(a => { if (typeof a.web_raw_data === 'string') wr.add(a.web_raw_data.slice(0, 60)); });
  say('  ' + (wr.size ? [...wr].slice(0, 3).join(' | ') : '(无)'));

  // ② 标签类字段里找 AI
  say('');
  say('=== 标签类字段里的 AI 痕迹 ===');
  const hit = new Map();
  list.forEach(a => {
    for (const f of ['video_tag', 'relation_labels', 'risk_infos', 'effect_inflow_effects']) {
      if (a[f]) { let s = ''; try { s = JSON.stringify(a[f]); } catch (e) {} hit.set(f, (hit.get(f) || 0) + 1); if (s) say('   ' + f + ' = ' + s.slice(0, 130)); }
    }
    if ((a.tts_id_list || []).length) hit.set('tts_id_list', (hit.get('tts_id_list') || 0) + 1);
    if ((a.voice_modify_id_list || []).length) hit.set('voice_modify_id_list', (hit.get('voice_modify_id_list') || 0) + 1);
  });
  say('  字段出现次数: ' + (hit.size ? [...hit.entries()].map(([k, c]) => k + '×' + c).join(', ') : '(无)'));

  // ③ 文本层面：标题/话题里出现 AI 词的
  say('');
  say('=== 标题或话题含 AI 相关词的条目 ===');
  const KW = ['ai', 'aigc', '人工智能', 'ai生成', 'ai绘画', 'ai视频', '数字人'];
  let n = 0;
  list.forEach(a => {
    const text = (String(a.desc || '') + ' ' + (a.text_extra || []).map(t => t.hashtag_name || '').join(' ')).toLowerCase();
    const m = KW.filter(k => text.includes(k));
    if (m.length) { n++; if (n <= 8) say('   [' + m.join(',') + '] ' + String(a.desc || '').slice(0, 40).replace(/\n/g, ' ')); }
  });
  say('  命中 ' + n + '/' + list.length + ' 条');
})().catch(e => say('FATAL ' + (e && e.stack || e)));
