# -*- coding: utf-8 -*-
"""确认 durl（我们实际播放的单文件）到底是什么编码。
方法：Range 拉前 256KB，在 mp4 里搜 sample entry 四字符码
（avc1=H.264 / hev1,hvc1=H.265 / av01=AV1 / mp4v=MPEG-4 Part2 / vp09=VP9）"""
import json
import urllib.request
import urllib.error

UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36')
HDR = {'User-Agent': UA, 'Referer': 'https://www.bilibili.com/'}

# 前一条是 HEVC（dash 看到 codecid=12），后两条是 H.264 对照
BVIDS = ['BV1kW411j7Vn', 'BV1i9pP6wE2D', 'BV1Ht411z78x', 'BV17b4y1v7bU']

CODECS = {
    b'avc1': 'H.264 (AVC)', b'avc3': 'H.264 (AVC)',
    b'hev1': 'H.265 (HEVC)', b'hvc1': 'H.265 (HEVC)',
    b'av01': 'AV1', b'vp09': 'VP9', b'mp4v': 'MPEG-4 Part 2', b'apch': 'ProRes',
}


def get(url, hdr=None, raw=False):
    req = urllib.request.Request(url, headers=hdr or HDR)
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.read() if raw else json.loads(r.read().decode('utf-8'))


for bvid in BVIDS:
    print('=' * 66)
    print(bvid)
    try:
        vw = get('https://api.bilibili.com/x/web-interface/view?bvid=' + bvid)
        d = vw.get('data') or {}
        cid = d.get('cid')
        dim = d.get('dimension') or {}
        print('  view: cid=%s  %sx%s' % (cid, dim.get('width'), dim.get('height')))
        if not cid:
            print('  取不到 cid，跳过')
            continue

        for fnval, tag in ((16, 'dash'), (1, 'durl')):
            pu = get('https://api.bilibili.com/x/player/playurl?bvid=%s&cid=%s&qn=64&fnval=%d&fnver=0&fourk=1'
                     % (bvid, cid, fnval))
            dd = pu.get('data') or {}
            if fnval == 16:
                vs = dd.get('dash', {}).get('video', [])
                note = ', '.join('id=%s codecid=%s' % (v.get('id'), v.get('codecid')) for v in vs)
                print('  dash 档位: ' + (note or '(无)'))
            else:
                du = dd.get('durl') or []
                if not du:
                    print('  durl: (无)')
                    continue
                url = du[0]['url']
                print('  durl: quality=%s size=%.1fMB' % (dd.get('quality'), du[0]['size'] / 1048576))
                # Range 拉前 256KB，在 moov/stsd 里找 sample entry
                try:
                    req = urllib.request.Request(url, headers=dict(HDR, Range='bytes=0-262143'))
                    with urllib.request.urlopen(req, timeout=25) as r:
                        head = r.read()
                    found = []
                    for k, v in CODECS.items():
                        if k in head:
                            found.append('%s -> %s' % (k.decode(), v))
                    print('  实际编码: ' + ('; '.join(found) if found else '(前 256KB 里没找到 stsd，moov 可能在文件尾)'))
                    print('  首 16 字节: %r' % head[:16])
                except urllib.error.HTTPError as e:
                    print('  拉流失败 HTTP %s' % e.code)
                except Exception as e:
                    print('  拉流失败: %s' % e)
    except Exception as e:
        print('  ERR %s' % e)
