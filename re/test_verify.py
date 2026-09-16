"""Run verify_epc_tid.py against the mock reader."""
import io
import os
import sys
import contextlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)

import test_tagwrite as T
import tag_write_epc_tid as W
import verify_epc_tid as V

sys.modules['serial'] = T.FakeSerialModule()
W.list_ports = lambda: [('MOCK0', 'mock')]
V.T.choose_port = lambda p: 'MOCK0'

buf = io.StringIO()
with contextlib.redirect_stdout(buf):
    rc = V.main(['--port', 'MOCK0', '--epc', 'E28069150000502008B25C75',
                 '--tid', 'E28069152000502008B25C75'])
out = buf.getvalue()
print(out)
print('>>> exit code:', rc)
print('TID bank now:', T.MEM[2].hex().upper())
print('EPC bank now:', T.MEM[1].hex().upper())
ok = rc == 0 and T.MEM[2].hex().upper() == 'E28069152000502008B25C75'
print('RESULT:', 'PASS' if ok else 'FAIL')
raise SystemExit(0 if ok else 1)
