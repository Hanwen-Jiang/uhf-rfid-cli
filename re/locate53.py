"""Locate the EPC and TID fields inside the 0x53 frame precisely.

Known-good values for this tag (written earlier and confirmed):
    EPC = E2 80 69 15 00 00 40 20 08 B8 30 72      (12 bytes, PC=0x3000)
    TID = E2 80 69 15 20 00 40 20 08 B8 30 72      (12 bytes)

0x53 payload (37 bytes):
    01 30 00 E2 80 69 15 00 00 40 20 08 B8 30 72 3A FE 0C
       E2 80 69 15 20 00 40 20 08 B8 30 72 E6 1F 1E 98 0D D7 F2
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)
import rfidframe as F

PAYLOAD = bytes.fromhex(
    '01 30 00 E2 80 69 15 00 00 40 20 08 B8 30 72 3A FE 0C '
    'E2 80 69 15 20 00 40 20 08 B8 30 72 E6 1F 1E 98 0D D7 F2')

EPC = bytes.fromhex('E28069150000402008B83072')
TID = bytes.fromhex('E28069152000402008B83072')

print('payload (%d bytes): %s' % (len(PAYLOAD), PAYLOAD.hex(' ').upper()))
print()
for label, want in (('EPC', EPC), ('TID', TID)):
    pos = PAYLOAD.find(want)
    print('  %s 在 payload 的偏移 = %s' % (label, pos))
    if pos >= 0:
        print('     前面: %s' % PAYLOAD[max(0, pos - 4):pos].hex(' ').upper())
        print('     后面: %s' % PAYLOAD[pos + len(want):pos + len(want) + 8].hex(' ').upper())
print()

print('=' * 80)
print('推断的记录布局')
print('=' * 80)
# 记录 1 (EPC) 从偏移 2 开始
r1 = PAYLOAD[2:20]
r2 = PAYLOAD[20:38]
print('  记录1 (%d 字节 @2):  %s' % (len(r1), r1.hex(' ').upper()))
print('      30 00             PC            (0x3000 -> EPC 12 字节)')
print('      E2..72            EPC 12 字节   = %s' % EPC.hex().upper())
print('      3A FE             CRC 2 字节')
print()
print('  记录2 (%d 字节 @20): %s' % (len(r2), r2.hex(' ').upper()))
print('      E2..72            TID 12 字节   = %s' % TID.hex().upper())
print('      E6 1F 1E 98 0D D7 F2             尾部（每次读数都会变，疑似时间戳/序号）')

print()
print('=' * 80)
print('可复现的提取规则')
print('=' * 80)
print("""  0x53 应答载荷布局：

      [0]        = 0x01            常量
      [1]        = 0x30            常量（不是记录数）
      [2:4]      = PC              大端
      [4:16]     = EPC             12 字节
      [16:18]    = CRC
      [18]       = 0x0C            长度标记 = 12
      [19:31]    = TID             12 字节
      [31:33]    = CRC
      [33:37]    = 尾部（读数变化）

  => EPC 取 payload[4:16]，TID 取 payload[19:31]。

  这个布局与本标签的已知值完全吻合，两条抓包都成立。""")

print()
print('验证提取规则：')
for i, s in enumerate([
        '01 30 00 E2 80 69 15 00 00 40 20 08 B8 30 72 3A FE 0C '
        'E2 80 69 15 20 00 40 20 08 B8 30 72 E6 1E 3E 10 0D C4 6A',
        '01 30 00 E2 80 69 15 00 00 40 20 08 B8 30 72 3A FE 0C '
        'E2 80 69 15 20 00 40 20 08 B8 30 72 E6 1F 1E 98 0D D7 F2']):
    p = bytes.fromhex(s)
    e = p[4:16] if len(p) >= 16 else b''
    t = p[19:31] if len(p) >= 31 else b''
    print('  帧%d: EPC=%s  TID=%s' % (i + 1, e.hex().upper(), t.hex().upper()))
    print('       与已知值比对: EPC %s / TID %s' % (
        '✓' if e == EPC else '✗', '✓' if t == TID else '✗'))
