// 验证 channel/feed 的字段完整性：现有解析逻辑（playUrl/cover/宽高/档位/作者）能否直接吃下
'use strict';
const { createSigner, baseParams, getTtwid, UA } = require('./signer.js');
function say(s) { process.stdout.write(String(s) + '\n'); }
(async () => {
  const ctx = createSigner();
  const ttwid = await getTtwid();
  const H = { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/', 'Cookie': 'ttwid=' + ttwid };
  const q = baseParams().concat([['tag_id', '300219'], ['count', '6'], ['refresh_index', '1']]).map(([k, v]) => k + '=' + v).join('&');
  const url = 'https://www.douyin.com/aweme/v1/web/channel/feed/?' + q + '&a_bogus=' + encodeURIComponent(ctx.makeABogus(q));
  const r = await fetch(url, { headers: H });
  const j = await r.json();
  say('channel/feed(三农) http=' + r.status + ' status_code=' + j.status_code + ' items=' + ((j.aweme_list || []).length));
  say('top keys: ' + Object.keys(j).join(','));

  const list = j.aweme_list || [];
  let vertical = 0, horiz = 0, gearOk = 0, wwwOk = 0;
  list.forEach((a, i) => {
    const v = a.video || {};
    const w = v.width || 0, h = v.height || 0;
    const isV = h > w;
    if (isV) vertical++; else horiz++;
    const pa = v.play_addr || {};
    const urls = pa.url_list || [];
    const www = urls.filter(u => String(u).startsWith('https://www.douyin.com/')).length;
    if (www) wwwOk++;
    const gears = (v.bit_rate || []).length;
    if (gears) gearOk++;
    say('[' + i + '] id=' + a.aweme_id + '  ' + (isV ? '竖' : '横') + ' ' + w + 'x' + h
      + '  cover=' + ((((v.cover || {}).url_list) || []).length)
      + '  author=' + ((a.author || {}).nickname || '?')
      + '  bit_rate=' + gears + '  www候选=' + www + '/' + urls.length
      + '  uri=' + String(pa.uri || '').slice(0, 18));
    say('     desc: ' + String(a.desc || '').slice(0, 46).replace(/\n/g, ' '));
  });
  say('');
  say('汇总: 竖=' + vertical + ' 横=' + horiz + ' 有档位=' + gearOk + '/' + list.length + ' 有www候选=' + wwwOk + '/' + list.length);
})().catch(e => say('FATAL ' + (e && e.stack || e)));
