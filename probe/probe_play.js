// 验证播放地址可用性：feed → 取 play_url → 试拉（决定 App 能否真的播）
'use strict';
const fs = require('fs');
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');

const OUT = './play.json';
function say(s) { process.stdout.write(s.replace(/[^\x20-\x7e\n]/g, '?') + '\n'); }

// 照 DouyinApi.urlFromAddr：优先 www.douyin.com 跳转候选，否则用 uri 自拼
function pickPlayUrl(video, awemeId) {
  const list = (video.play_addr && video.play_addr.url_list) || [];
  for (const u of list) if (typeof u === 'string' && u.startsWith('https://www.douyin.com/')) return { url: u, how: 'url_list(www)' };
  const uri = video.play_addr && video.play_addr.uri;
  if (uri) return { url: 'https://www.douyin.com/aweme/v1/play/?video_id=' + uri + '&ratio=1080p&line=0&item_id=' + awemeId, how: 'synthesized' };
  return { url: list[0] ? list[0] : '', how: 'url_list(0)' };
}

(async () => {
  const rec = {};
  try {
    const ctx = createSigner();
    const ttwid = await getTtwid();
    const cookie = 'ttwid=' + ttwid;
    const p = baseParams();
    p.push(['round_trip_time', '500']); p.push(['count', '5']); p.push(['refresh_index', '1']); p.push(['tag_id', '']);
    const q = p.map(([k, v]) => k + '=' + v).join('&');
    const url = 'https://www.douyin.com/aweme/v1/web/tab/feed/?' + q + '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
    const r = await fetch(url, { headers: { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': cookie } });
    const j = await r.json();
    rec.feed_status = j.status_code;
    rec.feed_count = (j.aweme_list || []).length;

    const tries = [];
    for (const item of (j.aweme_list || []).slice(0, 3)) {
      const pick = pickPlayUrl(item.video || {}, item.aweme_id);
      const gearCount = ((item.video || {}).bit_rate || []).length;
      const t = { aweme_id: item.aweme_id, how: pick.how, gearCount };
      if (pick.url) {
        // 模拟 App：带 UA + Referer（DouyinApi.playHeaders）
        const hr = await fetch(pick.url, {
          method: 'GET', redirect: 'follow',
          headers: { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Range': 'bytes=0-2047' },
        });
        t.http = hr.status;
        t.ctype = hr.headers.get('content-type');
        t.clen = hr.headers.get('content-length');
        const buf = await hr.arrayBuffer();
        t.gotBytes = buf.byteLength;
        t.finalHost = new URL(hr.url).host;
        say('[play] ' + item.aweme_id + ' -> HTTP ' + hr.status + ' ' + t.ctype + ' bytes=' + buf.byteLength + ' via ' + pick.how);
      } else {
        say('[play] ' + item.aweme_id + ' -> NO URL');
      }
      tries.push(t);
    }
    rec.plays = tries;
    fs.writeFileSync(OUT, JSON.stringify(rec, null, 2), 'utf8');
    say('[done] wrote ' + OUT);
  } catch (e) {
    say('[FATAL] ' + e.message);
    fs.writeFileSync(OUT, JSON.stringify({ error: String(e), rec }, null, 2), 'utf8');
  }
})();
