// 复验：频道流 channel/feed 现在还能不能用（这是游客态下唯一能"定向内容"的通道）
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

  const chans = [['300219', '三农'], ['300215', '影视'], ['300209', '音乐'], ['300216', '生活vlog']];
  for (const [id, name] of chans) {
    const r = await get(ctx, cookie, '/aweme/v1/web/channel/feed/', [['tag_id', id], ['count', '10'], ['refresh_index', '1']]);
    const j = r.json || {};
    const al = j.aweme_list || [];
    say('【' + name + ' tag_id=' + id + '】 http=' + r.http + ' status_code=' + j.status_code + ' items=' + al.length);
    al.slice(0, 5).forEach((a, i) => {
      const w = (a.video && a.video.width) || 0, h = (a.video && a.video.height) || 0;
      say('    [' + i + '] ' + (w && h ? (h > w ? '竖' : '横') : '?') + '  ' + String(a.desc || '').slice(0, 40).replace(/\n/g, ' '));
    });
    if (!al.length) say('    raw=' + r.raw.slice(0, 160));
  }
})().catch(e => say('FATAL ' + (e && e.stack || e)));
