"""Run find_tag.py against the mock reader (tag present and absent)."""
import io
import os
import sys
import contextlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)

import test_tagwrite as T
import tag_write_epc_tid as W
import find_tag as FT

sys.modules['serial'] = T.FakeSerialModule()
W.list_ports = lambda: [('MOCK0', 'mock')]
FT.T.choose_port = lambda p: 'MOCK0'


def run(label, tag_present):
    T.MEM[1] = bytearray(b'\x00\x00' + bytes(12))
    T.MEM[2] = bytearray(bytes.fromhex('E28069152000502008B25C75'))
    orig = T.FakeSerial._handle

    def handle(self, fr, _orig=orig, _present=tag_present):
        if not _present and fr['cmd'] in (T.F.CMD['read'], T.F.CMD['get_tag_count']):
            return T.F.build_frame(fr['cmd'], b'\x00\x00')
        return _orig(self, fr)

    T.FakeSerial._handle = handle
    T.FakeSerial.tag_present = tag_present
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        rc = FT.main(['--port', 'MOCK0', '--antennas', '1,2', '--powers', '20'])
    out = buf.getvalue()
    print('=' * 66)
    print('# %s  -> exit code %s' % (label, rc))
    print('=' * 66)
    for line in out.splitlines():
        s = line.strip()
        if s and (s[0].isdigit() or '天线' in s or '结论' in s or '读到' in s
                  or '建议' in s or '没有任何' in s or s.startswith('*')):
            print(' ', line)
    T.FakeSerial._handle = orig
    return rc


rc_no = run('tag absent (expect exit 1)', False)
print()
rc_yes = run('tag present (expect exit 0)', True)
print()
ok = (rc_no == 1 and rc_yes == 0)
print('RESULT:', 'PASS' if ok else 'FAIL (no=%s yes=%s)' % (rc_no, rc_yes))
raise SystemExit(0 if ok else 1)
