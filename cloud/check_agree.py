#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_agree.py —— 确认客户端和服务端的完整性校验结论完全一致。

两边是各自独立实现的：
    Android : android/.../TagValidator.java
    服务端  : cloud/src/index.js 的 checkComplete()

这个脚本把同一批用例同时喂给「本地 Java 实现」和「线上 Worker」，
逐条比对结论。不一致就说明两份实现漂移了，必须马上修 ——
否则会出现客户端说合格、服务端判异常这种自相矛盾的情况。

用法：
    python cloud/check_agree.py            # 需要 java 在 PATH 里
    python cloud/check_agree.py --url https://xxx.workers.dev
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
KEY_FILE = r'Z:\rfid\.tools\cf-key.txt'
UA = 'uhf-rfid-cli/1.0'

CASES = [
    ('E28069150000502008B25C75', 'E28069152000502008B25C75'),
    ('E28069150000402008B83072', 'E28069152000402008B83072'),
    ('E28069150000402008B6F478', 'E28069152000402008B6F478'),
    ('E28069159000502008B25C75', 'E28069152000502008B25C75'),
    ('E28069150000502008B25C75', 'E28069153000502008B25C75'),
    ('E28069150000502008B25C75', 'E28069150000502008B25C75'),
    ('E28069150000502008B25C75', 'E28069152A00502008B25C75'),
    # 长度不一致要用**偶数长度**的合法 hex。奇数长度会被服务端的输入校验
    # 先挡掉（400），那是输入校验，不是完整性判定。
    ('E28069150000502008B25C75', 'E28069152000502008B2'),
    ('E2806915', 'E2806915'),
]


def java_results():
    """本地跑 Java 版，返回 {('epc','tid'): 'COMPLETE'|reason}。"""
    out = tempfile.mkdtemp(prefix='rfid-jt-')
    src = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'java',
                       'com', 'hanwenjiang', 'uhfrfid', 'TagValidator.java')
    test = os.path.join(ROOT, 'android', 'selftest', 'com', 'hanwenjiang',
                        'uhfrfid', 'TagValidatorSelfTest.java')
    subprocess.run(['javac', '-encoding', 'UTF-8', '-d', out, src, test],
                   check=True)
    r = subprocess.run(['java', '-cp', out,
                        'com.hanwenjiang.uhfrfid.TagValidatorSelfTest', 'cases'],
                       check=True, capture_output=True)
    text = r.stdout.decode('utf-8')
    res = {}
    for line in text.splitlines():
        if ' => ' not in line:
            continue
        left, verdict = line.rsplit(' => ', 1)
        parts = left.split(' ', 1)
        res[(parts[0], parts[1] if len(parts) > 1 else '')] = verdict.strip()
    return res


def server_results(base, key):
    """线上 Worker 的结论。"""
    res = {}
    for epc, tid in CASES:
        q = urllib.parse.urlencode({'epc': epc, 'tid': tid})
        req = urllib.request.Request(base + '/api/check?' + q, headers={
            'Authorization': 'Bearer ' + key, 'User-Agent': UA})
        last = None
        for attempt in range(3):          # Cloudflare 偶发 SSL EOF，重试几次
            try:
                d = json.loads(urllib.request.urlopen(req, timeout=20)
                               .read().decode('utf-8'))
                res[(epc, tid)] = 'COMPLETE' if d.get('complete') else d.get('reason')
                last = None
                break
            except urllib.error.HTTPError as e:
                # 400 = 输入本身不合法（非 hex / 奇数长度），不是完整性结论
                res[(epc, tid)] = 'BAD_INPUT(%d)' % e.code
                last = None
                break
            except Exception as e:
                last = e
                time.sleep(0.5)
        if last is not None:
            res[(epc, tid)] = 'ERROR:%s' % last
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--url', default='https://rfid-tags.root-60a.workers.dev')
    ap.add_argument('--key-file', default=KEY_FILE)
    a = ap.parse_args()

    key = ''
    if os.path.exists(a.key_file):
        key = open(a.key_file).read().strip()
    if not key:
        print('找不到 API key（%s）' % a.key_file)
        return 2

    print('跑本地 Java 实现…')
    j = java_results()
    print('跑线上 Worker…')
    s = server_results(a.url, key)

    print()
    print('%-28s %-28s %-16s %-16s %s' % ('EPC', 'TID', 'Java', 'Worker', '一致?'))
    print('-' * 104)
    bad = 0
    for epc, tid in CASES:
        rj = j.get((epc, tid), '(缺)')
        rs = s.get((epc, tid), '(缺)')
        ok = rj == rs
        if not ok:
            bad += 1
        print('%-28s %-28s %-16s %-16s %s'
              % (epc or '(空)', tid or '(空)', rj, rs, '✓' if ok else '✗ 不一致'))
    print()
    if bad:
        print('有 %d 条两边结论不一致 —— 两份实现漂移了，必须修' % bad)
        return 1
    print('全部一致 ✓（%d 条用例）' % len(CASES))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
