// 诊断：真实档位结构 + ratio 参数是否影响服务端返回清晰度
// 背景：4.4 模拟器上实际拿到 avc1.640032 (High@L5.0) 1080x1920，但选档日志说是 540p
'use strict';
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');
function say(s) { process.stdout.write(String(s).replace(/[^\x20-\x7e\n]/g, '?') + '\n'); }

(async () => {
  const ctx = createSigner();
  const ttwid = await getTtwid();
  const cookie = 'ttwid=' + ttwid;
  const p = baseParams();
  p.push(['round_trip_time', '500']); p.push(['count', '5']);
  p.push(['refresh_index', '1']); p.push(['tag_id', '']);
  const q = p.map(([k, v]) => k + '=' + v).join('&');
  const url = 'https://www.douyin.com/aweme/v1/web/tab/feed/?' + q + '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
  const r = await fetch(url, { headers: { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': cookie } });
  const j = await r.json();
  say('feed status=' + j.status_code + ' count=' + (j.aweme_list || []).length);

  const item = (j.aweme_list || []).find(x => x.video && x.video.bit_rate && x.video.bit_rate.length);
  if (!item) { say('no item with bit_rate'); return; }
  const v = item.video;
  say('');
  say('aweme=' + item.aweme_id + '  video.width=' + v.width + ' video.height=' + v.height);
  say('play_addr.uri=' + ((v.play_addr || {}).uri || ''));
  const gears = v.bit_rate || [];
  say('bit_rate count=' + gears.length);
  gears.forEach((g, i) => {
    const pa = g.play_addr || {};
    say(`  [${i}] gear=${g.gear_name} br=${g.bit_rate} h265=${g.is_h265} bvc1=${g.is_bytevc1} ` +
        `dim=${g.width || '?'}x${g.height || '?'} qtype=${g.quality_type} format=${g.format}`);
    say(`        uri=${pa.uri || ''}`);
    (pa.url_list || []).forEach(u => say('        U ' + String(u).slice(0, 240)));
  });

  // 关键实验：同一 video_id，换 ratio 请求，看返回是否不同
  const uri = (v.play_addr || {}).uri;
  say('');
  say('=== ratio 实验（同一 video_id） ===');
  for (const ratio of ['540p', '720p', '1080p']) {
    const u = 'https://www.douyin.com/aweme/v1/play/?video_id=' + uri + '&ratio=' + ratio + '&line=0&item_id=' + item.aweme_id;
    try {
      const hr = await fetch(u, { method: 'GET', redirect: 'follow',
        headers: { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Range': 'bytes=0-1023' } });
      const buf = await hr.arrayBuffer();
      say(`ratio=${ratio} http=${hr.status} ctype=${hr.headers.get('content-type')} ` +
          `crange=${hr.headers.get('content-range')} got=${buf.byteLength}`);
      say('   final=' + String(hr.url).slice(0, 230));
    } catch (e) { say('ratio=' + ratio + ' FAIL ' + e.message); }
  }
})().catch(e => say('FATAL ' + (e && e.stack || e)));
