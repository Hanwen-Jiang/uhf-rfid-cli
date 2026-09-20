#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""import_tags.py —— 批量导入标签（上传 + 命名），并逐条报告完整性校验结果。

用法：
    python cloud/import_tags.py                 # 用下面的内置列表
    python cloud/import_tags.py tags.csv        # 或者从 CSV 读：名称,EPC,TID

CSV 格式（一行一个，允许 # 注释和空行）：
    周-教职,E28069150000402008B83072,E28069152000402008B83072
"""
from __future__ import annotations

import csv
import json
import os
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
KEY_FILE = r'Z:\rfid\.tools\cf-key.txt'
BASE = 'https://rfid-tags.root-60a.workers.dev'
UA = 'uhf-rfid-cli/1.0'

# 内置列表：(名称, EPC, TID)
TAGS = [
    ('周-教职', 'E28069150000402008B83072', 'E28069152000402008B83072'),
    ('外-后',   'E28069150000502008B25C75', 'E28069152000502008B25C75'),
    ('外-社区', 'E28069150000502008B3A45C', 'E28069152000502008B3A45C'),
    ('外-委',   'E28069150000502008B4485F', 'E28069152000502008B4485F'),
    ('外-其他', 'E28069150000502008B13090', 'E28069152000502008B13090'),
    ('外-其他', 'E28069150000502008B79490', 'E28069152000502008B79490'),
]

OPERATOR = '电脑端导入'


def load_key(path=KEY_FILE):
    if os.path.exists(path):
        return open(path).read().strip()
    return ''


def req(key, path, method='GET', body=None, tries=4):
    """发一个请求，失败重试几次。

    Cloudflare 偶尔会直接掐掉连接（SSL: UNEXPECTED_EOF_WHILE_READING），
    这是它的正常抽风，不是请求有问题。
    """
    h = {'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json',
         'User-Agent': UA}
    last = None
    for attempt in range(tries):
        r = urllib.request.Request(
            BASE + path, method=method,
            data=json.dumps(body).encode('utf-8') if body is not None else None,
            headers=h)
        try:
            return json.loads(urllib.request.urlopen(r, timeout=25).read().decode('utf-8'))
        except urllib.error.HTTPError as e:
            # 4xx 是请求本身的问题，重试没意义
            raise RuntimeError('HTTP %d: %s' % (e.code, e.read().decode('utf-8', 'replace')))
        except Exception as e:
            last = e
            time.sleep(0.8 * (attempt + 1))
    raise RuntimeError('重试 %d 次仍失败: %s' % (tries, last))


def read_csv(path):
    out = []
    with open(path, encoding='utf-8-sig', newline='') as fh:
        for row in csv.reader(fh):
            if not row or row[0].strip().startswith('#'):
                continue
            if len(row) < 3:
                continue
            out.append((row[0].strip(), row[1].strip(), row[2].strip()))
    return out


def main():
    key = load_key()
    if not key:
        print('找不到 API key（%s）' % KEY_FILE)
        return 2

    tags = read_csv(sys.argv[1]) if len(sys.argv) > 1 else TAGS
    print('要导入 %d 条\n' % len(tags))

    print('%-10s %-28s %-8s %-12s %s'
          % ('名称', 'EPC', '校验', '云端', '备注'))
    print('-' * 96)

    bad = 0
    for name, epc, tid in tags:
        # 先只做校验，看服务端怎么判
        try:
            v = req(key, '/api/check?epc=%s&tid=%s' % (epc, tid))
        except RuntimeError as e:
            print('%-10s %-28s 校验失败: %s' % (name, epc, e))
            bad += 1
            continue

        verdict = '✓ 合格' if v.get('complete') else '✗ ' + str(v.get('reason'))
        note = ''
        if not v.get('complete'):
            note = str(v.get('detail') or '')
            bad += 1

        # 上传（不合格的带异常标记，不伪装成正常）
        body = {'epc': epc, 'tid': tid, 'operator': OPERATOR}
        if not v.get('complete'):
            body['status'] = 'anomaly'
            body['anomalyNote'] = note
        try:
            up = req(key, '/api/tags', 'POST', body)
            tag = up['tag']
            # 命名
            named = req(key, '/api/tags/%d' % tag['id'], 'PATCH', {'name': name})
            t = named['tag']
            print('%-10s %-28s %-8s %-12s id=%d %s reads=%d'
                  % (name, epc, verdict, '新建' if up.get('created') else '更新',
                     t['id'], note, t['reads']))
        except RuntimeError as e:
            print('%-10s %-28s %-8s 上传失败: %s' % (name, epc, verdict, e))
            bad += 1

    print()
    total = req(key, '/api/tags')
    print('库里现在共 %d 条：' % total['count'])
    for t in total['tags']:
        print('  id=%-3s %-12s 后5位=%s  %-8s %s'
              % (t['id'], t['name'] or '(未命名)', t['suffix'], t['status'],
                 '异常' if t['status'] == 'anomaly' else ''))

    if bad:
        print('\n有 %d 条不合格或导入失败（已按异常入库，见上面"备注"）' % bad)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
