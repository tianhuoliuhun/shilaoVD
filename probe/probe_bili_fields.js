// 复测：① -352 是不是限流（加延迟重试）② 三个接口各自返回哪些关键字段（cid / dimension 有无）
'use strict';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';
const REF = 'https://www.bilibili.com/';
function say(s) { process.stdout.write(String(s) + '\n'); }
let COOKIE = '';

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function apiGet(url) {
  const h = { 'User-Agent': UA, 'Referer': REF, 'Origin': 'https://www.bilibili.com', 'Accept': 'application/json, text/plain, */*' };
  if (COOKIE) h.Cookie = COOKIE;
  const r = await fetch(url, { headers: h });
  const t = await r.text();
  let j = null; try { j = JSON.parse(t); } catch (e) {}
  return { status: r.status, json: j, raw: t };
}

/** 打印一条视频的关键字段，重点看 cid / dimension 有没有 */
function fields(tag, a) {
  if (!a) { say('    ' + tag + ': (空)'); return; }
  const dim = a.dimension ? (a.dimension.width + 'x' + a.dimension.height) : '';
  say('    ' + tag + ': bvid=' + a.bvid
    + ' cid=' + (a.cid === undefined ? '(无)' : a.cid)
    + ' aid=' + (a.aid === undefined ? '(无)' : a.aid)
    + ' dim=' + (dim || '(无)')
    + ' dur=' + (a.duration === undefined ? '(无)' : a.duration)
    + ' pic=' + (a.pic ? '有' : '(无)'));
}

(async () => {
  try {
    const home = await fetch('https://www.bilibili.com/', { headers: { 'User-Agent': UA } });
    const sc = typeof home.headers.getSetCookie === 'function' ? home.headers.getSetCookie() : [];
    const jar = {};
    for (const c of sc) { const m = String(c).match(/^([A-Za-z0-9_]+)=([^;]*)/); if (m) jar[m[1]] = m[2]; }
    COOKIE = Object.entries(jar).map(([k, v]) => k + '=' + v).join('; ');
  } catch (e) {}
  say('cookie=' + (COOKIE || '(空)').slice(0, 70));
  say('');

  // ---------- 一、复测 -352 ----------
  say('============ 一、复测 -352（每个间隔 1.2s，共 2 轮）============');
  for (let round = 1; round <= 2; round++) {
    for (const [rid, name] of [[211, '美食'], [217, '动物圈'], [234, '运动'], [160, '生活']]) {
      const r = await apiGet('https://api.bilibili.com/x/web-interface/ranking/v2?rid=' + rid + '&type=all');
      const j = r.json || {};
      const n = (j.data && j.data.list || []).length;
      say('  轮' + round + ' rid=' + String(rid).padEnd(4) + name.padEnd(5) + ' code=' + j.code + ' 条数=' + n
        + (j.code !== 0 ? (' msg=' + (j.message || '') + ' raw=' + r.raw.slice(0, 70).replace(/\s+/g, ' ')) : ''));
      await sleep(1200);
    }
  }
  say('');

  // ---------- 二、字段对比 ----------
  say('============ 二、三个接口的字段（有没有 cid / dimension）============');

  // 2.1 分区排行
  {
    const r = await apiGet('https://api.bilibili.com/x/web-interface/ranking/v2?rid=160&type=all');
    const a = r.json && r.json.data && r.json.data.list && r.json.data.list[0];
    say('  ranking/v2?rid=160:');
    fields('第一条', a);
    if (a) say('    顶层附加字段: ' + Object.keys(a).filter(k => /name|owner|stat|dimension/i.test(k)).join(','));
  }
  await sleep(600);

  // 2.2 搜索
  {
    const r = await apiGet('https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword=' + encodeURIComponent('广场舞') + '&page=1');
    const a = r.json && r.json.data && r.json.data.result && r.json.data.result[0];
    say('  search/type?keyword=广场舞:');
    fields('第一条', a);
    if (a) {
      say('    title 原始: ' + String(a.title).slice(0, 70));
      say('    有 arcurl? ' + (a.arcurl ? '是' : '否') + ' | 有 aid? ' + (a.aid === undefined ? '否' : '是'));
    }
  }
  await sleep(600);

  // 2.3 标签下视频
  {
    const r = await apiGet('https://api.bilibili.com/x/web-interface/tag/top?tid=23831577&pn=1&ps=10');
    const d = r.json && r.json.data;
    say('  tag/top?tid=23831577: data 是数组? ' + Array.isArray(d) + ' 条数=' + (Array.isArray(d) ? d.length : 0));
    if (Array.isArray(d) && d.length) {
      fields('第一条', d[0]);
      const raw = JSON.stringify(d[0]);
      say('    （单条 JSON 长度 ' + raw.length + '）');
    } else {
      say('    raw=' + r.raw.slice(0, 200).replace(/\s+/g, ' '));
    }
  }
  say('');
  say('DONE');
})().catch(e => say('FATAL ' + (e && e.stack || e)));
