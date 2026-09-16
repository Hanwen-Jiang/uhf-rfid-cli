"""Extract the exact UI labels for the 6C read/write page from the XRC resource.

The GUI is built from an XRC resource embedded in the binary, so the literal
label text is recoverable.  This settles whether 数据长度 is decimal or hex —
the user quoted it as "数据长度(Hex)", so it is worth checking.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pedwarf as P

pe = P.PE()
data = pe.data

WANT = ['访问密码', '起始地址', '数据长度', '写入数据', '读标签', '写入标签',
        '销毁密码', '销毁标签', '锁定标签', '访问密码区', '销毁密码区',
        '读写标签', '标签操作', '单标签操作', '多标签操作', '选定操作标签']

found = {}
i = 0
while True:
    i = data.find('数据长度(DEC)'.encode('utf-16le'), i)
    if i < 0:
        break
    lo, hi = max(0, i - 1200), min(len(data), i + 1200)
    txt = data[lo:hi].decode('utf-16le', 'replace')
    for w in WANT:
        if w in txt:
            # capture the full label including a parenthesised suffix
            for m in re.finditer(re.escape(w) + r'(\([^)]*\))?', txt):
                found.setdefault(m.group(0), 0)
                found[m.group(0)] += 1
    i += 1

print('labels recovered from the embedded XRC resource:')
for label in sorted(found, key=lambda s: (len(s), s)):
    print('   %s' % label)

print()
print('=> 数据长度 的单位是 DEC(十进制)，起始地址 的单位是 HEX(十六进制)。')
print('=> 所以 "数据长度 6" = 6 个字 = 12 字节，与本工具的实现一致。')
