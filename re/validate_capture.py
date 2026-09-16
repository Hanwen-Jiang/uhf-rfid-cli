"""Validate the captured GUI frames by their own LEN byte and checksum.

Hand-transcribing hex has burned this analysis twice, so let this script decide:
for each frame string it computes `len(bytes)` and requires `len == LEN + 2`
and a valid checksum.  Anything that fails is a transcription error in MY input,
not a real protocol difference.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)
import rfidframe as F

CAPTURES = {
    '0x8A custom inv':  'A0 07 00 8A 01 01 01 06 C6',
    # 读 EPC：bank=1 起始字地址=2 长度=6 密码=0  (16 字节)
    '0x81 read EPC':    'A0 0E 00 81 01 02 00 00 00 06 00 00 00 00 00 C8',
    '0x82 write EPC':   ('A0 1A 00 82 00 00 00 00 01 00 00 00 02 00 06 '
                         'E2 80 69 15 00 00 50 20 08 B2 5C 75 E0'),
    '0x82 write EPC rx': ('A0 18 00 82 00 06 10 30 00 E2 80 69 15 00 00 40 20 08 B8 '
                          '30 72 3A FE 10 01 95'),
    '0x81 read after':  ('A0 26 00 81 00 01 1C 30 00 E2 80 69 15 00 00 50 20 08 B2 5C 75 '
                         'D9 1B E2 80 69 15 20 00 40 20 08 B8 30 72 00 0C 01 01 CD'),
    # 读 TID：bank=2 起始字地址=0 长度=6 密码=0  (16 字节)
    '0x81 read TID':    'A0 0E 00 81 02 00 00 00 00 06 00 00 00 00 00 C9',
    '0x82 write TID':   ('A0 1A 00 82 00 00 00 00 02 00 00 00 00 00 06 '
                         'E2 80 69 15 20 00 50 20 08 B2 5C 75 C1'),
    '0x82 write TID rx': ('A0 18 00 82 00 06 10 30 00 E2 80 69 15 00 00 50 20 08 B2 5C 75 '
                          'D9 1B 10 01 A0'),
}

print('%-20s %-6s %-8s %-8s %-6s %s' % (
    'capture', 'LEN', 'len(+2)', 'bytes', 'chk', 'verdict'))
print('-' * 78)
bad = []
for name, s in CAPTURES.items():
    b = bytes.fromhex(s.replace(' ', ''))
    ln = b[1]
    expect = ln + 2
    chk = F.check_sun(b, len(b) - 1) == b[-1]
    lenok = len(b) == expect
    verdict = 'OK' if (lenok and chk) else (
        '我抄错了（长度不符）' if not lenok else '我抄错了（校验不符）')
    if not (lenok and chk):
        bad.append(name)
    print('%-20s %-6d %-8d %-8d %-6s %s' % (
        name, ln, expect, len(b), 'OK' if chk else 'BAD', verdict))

print()
if bad:
    print('以下抓包字符串是我转录错了，不能用于对比：')
    for n in bad:
        b = bytes.fromhex(CAPTURES[n].replace(' ', ''))
        print('  %-20s 应有 %d 字节，实际写了 %d 字节' % (n, b[1] + 2, len(b)))
    print()
    print('请把这几条【重新、干净地】贴一次（一次复制整行，别手动断行）。')
else:
    print('全部抓包自校验通过，可以用于对比。')
raise SystemExit(1 if bad else 0)
