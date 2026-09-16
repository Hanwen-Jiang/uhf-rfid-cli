"""Decode the 0x53 custom-inventory frame — it carries EPC *and* TID together.

Captured from the GUI ("自定义盘存"), two responses for the same tag:

  A0 28 00 53 | 01 | 30 00 E2 80 69 15 00 00 40 20 08 B8 30 72 3A FE
                    | 0C E2 80 69 15 20 00 40 20 08 B8 30 72 E6 1E
                    | 3E 10 0D C4 6A 7F

The command that starts it:
  A0 07 00 8A 01 01 01 06 C6     0x8A Custom_Inventory(1,1,1,6)
  A0 04 00 8A 10 C2              ACK
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)
import rfidframe as F

FRAMES = [
    ('capture 1 (t=53.168)',
     'A0 28 00 53 01 30 00 E2 80 69 15 00 00 40 20 08 B8 30 72 3A FE '
     '0C E2 80 69 15 20 00 40 20 08 B8 30 72 E6 1E 3E 10 0D C4 6A 7F'),
    ('capture 2 (t=53.242)',
     'A0 28 00 53 01 30 00 E2 80 69 15 00 00 40 20 08 B8 30 72 3A FE '
     '0C E2 80 69 15 20 00 40 20 08 B8 30 72 E6 1F 1E 98 0D D7 F2 7B'),
]

print('=' * 84)
print('自校验（长度与校验和）')
print('=' * 84)
for name, s in FRAMES:
    b = bytes.fromhex(s.replace(' ', ''))
    ok_len = len(b) == b[1] + 2
    ok_chk = F.check_sun(b, len(b) - 1) == b[-1]
    print('  %-22s %3d 字节  LEN=%d  长度=%s  校验=%s' % (
        name, len(b), b[1], 'OK' if ok_len else 'BAD', 'OK' if ok_chk else 'BAD'))

print()
print('=' * 84)
print('结构解析')
print('=' * 84)
for name, s in FRAMES:
    b = bytes.fromhex(s.replace(' ', ''))
    payload = b[4:len(b) - 1]
    print()
    print('  %s' % name)
    print('    payload (%d 字节): %s' % (len(payload), payload.hex(' ').upper()))
    print('      [0]      = 0x%02X   状态/记录数' % payload[0])
    print('      [1]      = 0x%02X   记录数 = %d' % (payload[1], payload[1]))
    rec = payload[2:2 + 20]
    print('      记录 (%d 字节): %s' % (len(rec), rec.hex(' ').upper()))
    print('        [0]        = 0x%02X   长度标记 = %d' % (rec[0], rec[0]))
    print('        [1:3]      = %s   PC' % rec[1:3].hex(' ').upper())
    print('        [3:15]     = %s   EPC' % rec[3:15].hex(' ').upper())
    print('        [15:17]    = %s   CRC' % rec[15:17].hex(' ').upper())
    print('        [17]       = 0x%02X   长度标记 = %d' % (rec[17], rec[17]))
    print('        [18:20+]   = %s   TID 起始' % rec[18:20].hex(' ').upper())
    tail = payload[2 + 20:]
    print('      尾部 (%d 字节) = %s' % (len(tail), tail.hex(' ').upper()))

print()
print('=' * 84)
print('两条抓包对比 —— 找出变化的部分')
print('=' * 84)
b1 = bytes.fromhex(FRAMES[0][1].replace(' ', ''))
b2 = bytes.fromhex(FRAMES[1][1].replace(' ', ''))
print('  长度: %d vs %d' % (len(b1), len(b2)))
n = min(len(b1), len(b2))
for i in range(n):
    if b1[i] != b2[i]:
        print('    偏移 %-3d (payload[%d]): %02X -> %02X' % (
            i, i - 4, b1[i], b2[i]))
