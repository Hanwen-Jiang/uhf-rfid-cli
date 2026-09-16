"""Regression test for read_epc_tid.py against the REAL 96-byte capture.

The bug this pins down: the reader answers one 0x8A query with
    ACK(0x8A) + data 0x53 + data 0x53 + end-marker 0x53
where the end marker is `A0 04 00 53 12 F7` -- a 0x53 frame with a 1-byte
payload.  Picking the LAST 0x53 therefore picks the end marker and reports
"no tag" even though the EPC/TID were right there in the buffer.

Run:  python re/test_epctid.py
"""
import io
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, ROOT)
sys.path.insert(0, HERE)

import rfidframe as F
import read_epc_tid as R

EPC = 'E28069150000402008B83072'
TID = 'E28069152000402008B83072'

# Verbatim bytes straight off COM4.  Recorded by re/probe53.py -- never typed
# by hand: an earlier hand-copied version of this capture was 94 bytes instead
# of 96, which is exactly the kind of error this file exists to prevent.
CAPTURE_FILE = os.path.join(HERE, 'capture53.hex')
with open(CAPTURE_FILE, encoding='ascii') as _fh:
    RAW = bytes.fromhex(_fh.read().replace(' ', '').strip())


class FakeSerial:
    """Replays the capture in chunks of at most `max_chunk` bytes."""

    def __init__(self, port=None, **kw):
        self.rx = bytearray()
        self.tx = bytearray()
        self.max_chunk = 1024
        self.script = []          # list of byte strings, popped per 0x8A query

    def reset_input_buffer(self):
        self.rx = bytearray()

    def write(self, data):
        self.tx += data
        for fr in F.parse_frames(bytes(data)):
            if fr['ok'] and fr['cmd'] == F.CMD['custom_inventory']:
                self.rx += self.script.pop(0) if self.script else b''

    def flush(self):
        pass

    def read(self, n):
        n = min(n, self.max_chunk)
        out = bytes(self.rx[:n])
        del self.rx[:n]
        return out

    def close(self):
        pass


class FakeSerialModule:
    Serial = FakeSerial
    tools = None


class Reader:
    verbose = False


def case(name, script, max_chunk, want_epc, want_tid):
    ser = FakeSerial()
    ser.max_chunk = max_chunk
    ser.script = list(script)
    epc, tid, tail, raw = R.query_once(ser, Reader(), 1, 2.0, retries=1)
    got_epc = epc.hex().upper() if epc else None
    got_tid = tid.hex().upper() if tid else None
    ok = got_epc == want_epc and got_tid == want_tid
    print('  %-46s -> %s' % (name, 'OK' if ok else 'FAIL'))
    if not ok:
        print('      EPC %s (want %s)' % (got_epc, want_epc))
        print('      TID %s (want %s)' % (got_tid, want_tid))
    return ok


def main():
    fails = 0

    print('#' * 68)
    print('# 0. the capture itself must self-validate before it is used')
    print('#' * 68)
    frames = [f for f in F.parse_frames(RAW) if f['ok']]
    cmds = [hex(f['cmd']) for f in frames]
    print('  %d bytes -> %d valid frames: %s' % (len(RAW), len(frames), cmds))
    good = (len(RAW) == 96 and cmds == ['0x8a', '0x53', '0x53', '0x53']
            and len(frames[3]['payload']) == 1)
    print('  96 bytes / 4 frames / end marker payload = 1 byte -> %s'
          % ('OK' if good else 'FAIL'))
    fails += 0 if good else 1

    print()
    print('#' * 68)
    print('# 1. query_once picks the data frame, not the last 0x53')
    print('#' * 68)
    # the whole 96 bytes in one read -- this is what used to fail
    fails += not case('one 96-byte burst (incl. end marker)', [RAW], 1024, EPC, TID)
    # ack and data split across reads
    fails += not case('split 6 + 90 bytes', [RAW], 6, EPC, TID)
    # library-style: no end marker yet
    fails += not case('48-byte burst (no end marker yet)', [RAW[:48]], 1024, EPC, TID)
    # only the end marker -> no tag
    fails += not case('end marker only', [RAW[90:]], 1024, None, None)
    # nothing at all
    fails += not case('no reply', [b''], 1024, None, None)

    print()
    print('#' * 68)
    print('# 2. parse_53 field offsets')
    print('#' * 68)
    p = [f for f in F.parse_frames(RAW) if f['ok'] and f['cmd'] == 0x53][0]['payload']
    e, t, tail = R.parse_53(p)
    checks = [
        ('len(payload) == 37', len(p) == 37),
        ('EPC  == %s' % EPC, e.hex().upper() == EPC),
        ('TID  == %s' % TID, t.hex().upper() == TID),
        ('payload[17] == 0x0C (TID length marker)', p[17] == 0x0C),
        ('payload[2]  == 0x00', p[2] == 0x00),
        ('short payload -> (None, None, b"")', R.parse_53(b'\x12') == (None, None, b'')),
    ]
    for label, ok in checks:
        print('  %-44s %s' % (label, 'OK' if ok else 'FAIL'))
        fails += 0 if ok else 1

    print()
    print('#' * 68)
    print('# 3. full CLI path on the mock, incl. --json and exit code')
    print('#' * 68)
    # Plain namespace object: assigning the factory onto the CLASS would make
    # `serial.Serial(...)` bind self, which is not how the real module works.
    class Mod:
        pass

    mod = Mod()
    sys.modules['serial'] = mod
    import tag_write_epc_tid as T
    T.list_ports = lambda: [('MOCK0', 'mock device')]
    T.restart_reader = lambda reader, settle=0.3: True

    def run(args, script=None):
        ser = FakeSerial()
        ser.script = script if script is not None else [RAW, RAW]
        mod.Serial = lambda port=None, **kw: ser
        buf = io.StringIO()
        old = sys.stdout
        sys.stdout = buf
        try:
            rc = R.main(['--port', 'MOCK0', '--antenna', '1', '--power', '26']
                        + args)
        finally:
            sys.stdout = old
        return rc, buf.getvalue(), ser

    rc, out, ser = run([])
    ok = rc == 0 and EPC in out and TID in out
    print('  plain run: rc=%d, EPC+TID printed -> %s' % (rc, 'OK' if ok else 'FAIL'))
    if not ok:
        print(out)
    fails += 0 if ok else 1

    # the antenna/power frames must go out before the inventory
    sent = [hex(f['cmd']) for f in F.parse_frames(bytes(ser.tx)) if f['ok']]
    print('  frames sent: %s' % sent)
    want = [hex(F.CMD[k]) for k in ('set_epc_match', 'set_work_ant',
                                    'set_ant_power', 'custom_inventory')]
    ok = sent == want
    print('  clear-match -> ant -> power -> inventory, in that order -> %s'
          % ('OK' if ok else 'FAIL'))
    fails += 0 if ok else 1

    # a stale 0x85 EPC match silences 0x8A completely, so it must be cleared
    # before the first query -- and --no-clear-match must suppress that frame.
    clear = F.build_frame(F.CMD['set_epc_match'], bytes([0, 0])).hex().upper()
    ok = clear in ser.tx.hex().upper()
    print('  0x85 [00 00] clear sent before querying -> %s' % ('OK' if ok else 'FAIL'))
    fails += 0 if ok else 1

    rc, out, ser = run(['--no-clear-match'])
    ok = rc == 0 and clear not in ser.tx.hex().upper()
    print('  --no-clear-match suppresses it -> %s' % ('OK' if ok else 'FAIL'))
    fails += 0 if ok else 1

    # --mute / --beep-on: 0x7A Set_Beep_status([00|01]).  The reader beeps once
    # per decoded tag, which makes a --count loop painful to be near.
    mute = F.build_frame(F.CMD['set_beep'], bytes([0])).hex().upper()
    on = F.build_frame(F.CMD['set_beep'], bytes([1])).hex().upper()
    rc, out, ser = run(['--mute'])
    tx = ser.tx.hex().upper()
    ok = rc == 0 and mute in tx and on not in tx
    print('  --mute sends 0x7A 00 and not 0x7A 01 -> %s' % ('OK' if ok else 'FAIL'))
    fails += 0 if ok else 1

    rc, out, ser = run(['--beep-on'])
    tx = ser.tx.hex().upper()
    ok = rc == 0 and on in tx and mute not in tx
    print('  --beep-on sends 0x7A 01 -> %s' % ('OK' if ok else 'FAIL'))
    fails += 0 if ok else 1

    rc, out, ser = run([])
    tx = ser.tx.hex().upper()
    ok = rc == 0 and mute not in tx and on not in tx
    print('  default leaves the beeper alone -> %s' % ('OK' if ok else 'FAIL'))
    fails += 0 if ok else 1

    rc, out, ser = run(['--json'])
    ok = rc == 0 and '"epc"' in out and EPC in out
    print('  --json: rc=%d -> %s' % (rc, 'OK' if ok else 'FAIL'))
    if not ok:
        print(out)
    fails += 0 if ok else 1

    rc, out, ser = run(['--count', '2'])
    ok = rc == 0 and out.count(EPC) == 2
    print('  --count 2: rc=%d, EPC printed twice -> %s'
          % (rc, 'OK' if ok else 'FAIL'))
    fails += 0 if ok else 1

    # a reader that never answers must exit 1, not 0
    rc, out, ser = run([], script=[b'', b'', b'', b''])
    ok = rc == 1 and '读不到标签' in out
    print('  dead reader: rc=%d + hint printed -> %s' % (rc, 'OK' if ok else 'FAIL'))
    fails += 0 if ok else 1

    print()
    print('=' * 68)
    print('RESULT: %s' % ('ALL PASS' if fails == 0 else '%d FAILURE(S)' % fails))
    print('=' * 68)
    return 1 if fails else 0


if __name__ == '__main__':
    raise SystemExit(main())
