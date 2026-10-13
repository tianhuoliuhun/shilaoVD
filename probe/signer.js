// 可复用的 a_bogus 签名器（Node 版），供各探测脚本 require。
'use strict';
const fs = require('fs');
const vm = require('vm');
const path = require('path');

const SIGN_DIR = 'D:/Projects/myDVQ/.base/wanshang-yinfu/app/src/main/assets/sign';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';

function createSigner(uaOverride) {
  const UA_ = uaOverride || UA;
  const ctx = vm.createContext({});
  const prelude = `
    var window = this; var self = this;
    var console = { log:function(){}, info:function(){}, warn:function(){}, error:function(){} };
    var navigator = { userAgent: ${JSON.stringify(UA_)}, platform: 'Win32' };
    var innerWidth = 2048, innerHeight = 960, outerWidth = 2554, outerHeight = 1386;
    var screen = { availWidth: 2560, availHeight: 1392, width: 2560, height: 1440 };
  `;
  vm.runInContext(prelude, ctx, { filename: 'env.js' });
  for (const f of ['utils.js', 'sm3.js', 'vm_decode.js']) {
    vm.runInContext(fs.readFileSync(path.join(SIGN_DIR, f), 'utf8'), ctx, { filename: f });
  }
  return ctx;
}

// 照 DouyinApi.baseDeviceParams 复刻（键序一致）
function baseParams() {
  return [
    ['device_platform', 'webapp'], ['aid', '6383'], ['channel', 'channel_pc_web'],
    ['update_version_code', '170400'], ['pc_client_type', '1'], ['pc_libra_divert', 'Windows'],
    ['support_h265', '1'], ['support_dash', '1'], ['version_code', '170400'], ['version_name', '17.4.0'],
    ['cookie_enabled', 'true'], ['screen_width', '2560'], ['screen_height', '1440'],
    ['browser_language', 'zh-CN'], ['browser_platform', 'Win32'], ['browser_name', 'Chrome'],
    ['browser_version', '135.0.0.0'], ['browser_online', 'true'], ['engine_name', 'Blink'],
    ['engine_version', '135.0.0.0'], ['os_name', 'Windows'], ['os_version', '10'],
    ['cpu_core_num', '20'], ['device_memory', '8'], ['platform', 'PC'],
    ['downlink', '0.55'], ['effective_type', '3g'], ['round_trip_time', '0'],
  ];
}

async function getTtwid() {
  const r = await fetch('https://live.douyin.com/646454278948', {
    headers: { 'User-Agent': UA }, redirect: 'follow',
  });
  const sc = typeof r.headers.getSetCookie === 'function'
    ? r.headers.getSetCookie() : [r.headers.get('set-cookie')].filter(Boolean);
  const m = sc.join('; ').match(/ttwid=([^;]+)/);
  return m ? m[1] : null;
}

module.exports = { createSigner, baseParams, getTtwid, UA, SIGN_DIR };
