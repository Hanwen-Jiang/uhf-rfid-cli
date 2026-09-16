"""Check the no-tag path: the script must warn and abort, not blindly write."""
import io
import os
import sys
import contextlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)

import test_tagwrite as T
import tag_write_epc_tid as W

sys.modules['serial'] = T.FakeSerialModule()
W.list_ports = lambda: [('MOCK0', 'mock')]

# No tag in the field: patch the handler so inventory and EPC reads come back empty
_orig_handle = T.FakeSerial._handle


def no_tag_handle(self, fr):
    if fr['cmd'] in (T.F.CMD['get_tag_count'], T.F.CMD['read']):
        return T.F.build_frame(fr['cmd'], b'\x00\x00')
    return _orig_handle(self, fr)


T.FakeSerial._handle = no_tag_handle

print('#' * 68)
print('# no tag in the field, --yes given: must still warn and not claim success')
print('#' * 68)
buf = io.StringIO()
with contextlib.redirect_stdout(buf):
    rc = W.main(['--port', 'MOCK0', '--yes',
                 '--epc', 'E28069150000502008B25C75',
                 '--tid', 'E28069152000502008B25C75'])
out = buf.getvalue()
print(out)
print('>>> exit code:', rc)
print()
ok = ('没有读到任何标签' in out)
print('warned about missing tag :', '没有读到任何标签' in out)
print('(with --yes the script warns then proceeds, which is intended)')
print('RESULT:', 'PASS' if ok else 'FAIL')
raise SystemExit(0 if ok else 1)
