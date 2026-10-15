// 探测「老人向」的上海话关键词：独脚戏 / 上海说唱 / 评弹 / 申曲 / 上海老歌 等，
// 看哪些有内容、且不是「教学/年轻人向」的。
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
function strip(s) { return String(s || '').replace(/<[^>]*>/g, ''); }

const KWS = ['独脚戏', '上海说唱', '评弹', '申曲', '上海老歌', '上海滑稽戏',
  '上海老人', '上海早晨', '老上海', '上海故事'];

(async () => {
  try {
    const home = await fetch('https://www.bilibili.com/', { headers: { 'User-Agent': UA } });
    const sc = typeof home.headers.getSetCookie === 'function' ? home.headers.getSetCookie() : [];
    const jar = {};
    for (const c of sc) { const m = String(c).match(/^([A-Za-z0-9_]+)=([^;]*)/); if (m) jar[m[1]] = m[2]; }
    COOKIE = Object.entries(jar).map(([k, v]) => k + '=' + v).join('; ');
  } catch (e) {}
  say('cookie=' + (COOKIE || '(空)').slice(0, 36));
  say('');

  for (const kw of KWS) {
    const r = await apiGet('https://api.bilibili.com/x/web-interface/search/type?search_type=video&page=1&keyword=' + encodeURIComponent(kw));
    const j = r.json || {};
    const res = (j.data && j.data.result) || [];
    say('【' + kw + '】code=' + j.code + ' 本页=' + res.length);
    res.slice(0, 4).forEach((a, i) => {
      say('   [' + i + '] ' + strip(a.title).slice(0, 46) + ' | ' + (a.author || '').slice(0, 12) + ' | ' + (a.duration || ''));
    });
    await sleep(1300);
  }
  say('');
  say('DONE');
})().catch(e => say('FATAL ' + (e && e.stack || e)));
