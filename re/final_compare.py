"""Confirm all four 6C frames now match the GUI's self-validated captures.

`re/validate_capture.py` proved the two `0x81` captures the user pasted contained
a stray zero (17 bytes for a LEN=14 frame).  This uses the corrected 16-byte
forms, which pass their own LEN and checksum.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)
import rfidframe as F

EPC = bytes.fromhex('E28069150000502008B25C75')
TID = bytes.fromhex('E28069152000502008B25C75')

GUI = {
    'read EPC  (bank1 start2)':
        'A0 0E 00 81 01 00 00 00 02 00 06 00 00 00 00 C8',
    'read TID  (bank2 start0)':
        'A0 0E 00 81 02 00 00 00 00 00 06 00 00 00 00 C9',
    'write EPC (bank1 start2)':
        'A0 1A 00 82 00 00 00 00 01 00 00 00 02 00 06 '
        'E2 80 69 15 00 00 50 20 08 B2 5C 75 E0',
    'write TID (bank2 start0)':
        'A0 1A 00 82 00 00 00 00 02 00 00 00 00 00 06 '
        'E2 80 69 15 20 00 50 20 08 B2 5C 75 C1',
}

OURS = {
    'read EPC  (bank1 start2)':
        F.build_frame(F.CMD['read'], F.payload_read(1, 2, 6, 0)),
    'read TID  (bank2 start0)':
        F.build_frame(F.CMD['read'], F.payload_read(2, 0, 6, 0)),
    'write EPC (bank1 start2)':
        F.build_frame(F.CMD['write'], F.payload_write(1, 2, 6, EPC, 0)),
    'write TID (bank2 start0)':
        F.build_frame(F.CMD['write'], F.payload_write(2, 0, 6, TID, 0)),
}

print('=' * 88)
print('最终对照：原 GUI 实测帧 vs 修正后的本工具')
print('=' * 88)
allok = True
for k in GUI:
    g = bytes.fromhex(GUI[k].replace(' ', ''))
    # self-validate the reference before trusting it
    assert len(g) == g[1] + 2, '%s: reference frame length wrong' % k
    assert F.check_sun(g, len(g) - 1) == g[-1], '%s: reference checksum wrong' % k
    o = OURS[k]
    same = g == o
    allok &= same
    print()
    print('  %-26s %s' % (k, '逐字节一致 ✓' if same else '不一致 ✗'))
    print('     GUI    : %s' % g.hex(' ').upper())
    print('     本工具 : %s' % o.hex(' ').upper())

print()
print('=' * 88)
print('结论')
print('=' * 88)
if allok:
    print("""  四条 6C 读/写帧全部与 GUI 实测【逐字节一致】。

  根本原因（本轮找到并已修复）：
      RFID_Read / RFID_Write 的 wordAddr 和 wordCount 字段是【大端】的。
      本工具此前按小端发送，地址 2 发成了 02 00 00 00（= 0x02000000），
      读写器把它当成超范围地址，对内存命令【完全不回应答】——
      这正是"零应答"的真正来源。

  修复后：
      读/写 EPC 区: bank=1  起始字地址=2 (BE32 00 00 00 02)  字数=6 (BE16 00 06)
      读/写 TID 区: bank=2  起始字地址=0 (BE32 00 00 00 00)  字数=6 (BE16 00 06)

  下一步：真机验证写入。""")
else:
    print('  仍有差异，见上。')
raise SystemExit(0 if allok else 1)
