"""End-to-end test of tag_write_epc_tid.py against a mock RS232 device.

Injects a fake `serial` module so the script runs its real code path —
argument parsing, port selection, link check, both writes, and read-back.
"""
import io
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, ROOT)
sys.path.insert(0, HERE)

import rfidframe as F

# Gen2 EPC bank layout: words 0-1 = CRC ("PC"), words 2.. = the EPC itself.
# A 6-word EPC therefore spans words 2..7, i.e. bytes 4..15, so the bank is
# 2*2 + 6*2 = 16 bytes.  That is exactly why the app uses "start address = 2":
# it writes the EPC without disturbing the CRC.
# The TID bank starts with the TID bytes directly (start address 0).
MEM = {1: bytearray(b'\x00\x00' + bytes(12)),   # 2 CRC words + 6 EPC words = 16 B
       2: bytearray(bytes.fromhex('E28069152000502008B25C75'))}   # 6 TID words = 12 B


class FakeSerial:
    """A tag that is always present in the field, with writable bank memory."""

    def __init__(self, port, **kw):
        self.port = port
        self.rx = bytearray()
        self.tx = bytearray()
        self.tag_present = True

    def reset_input_buffer(self):
        self.rx = bytearray()

    def write(self, data):
        self.tx += data
        for fr in F.parse_frames(bytes(data)):
            if fr['ok']:
                self.rx += self._handle(fr)

    def flush(self):
        pass

    def read(self, n):
        out = bytes(self.rx[:n])
        del self.rx[:n]
        return out

    def close(self):
        pass

    def _handle(self, fr):
        cmd, p = fr['cmd'], fr['payload']
        if cmd == F.CMD['version']:
            return F.build_frame(cmd, b'V1.76.7')
        if cmd == F.CMD['buffered_inventory']:
            self.tag_present = True
            return F.build_frame(cmd, bytes([0]))
        if cmd == F.CMD['stop_inventory']:
            return F.build_frame(cmd, bytes([0]))
        if cmd == F.CMD['get_tag_count']:
            return F.build_frame(cmd, (1 if self.tag_present else 0).to_bytes(2, 'little'))
        if cmd == F.CMD['set_epc_match']:
            return F.build_frame(cmd, bytes([0]))
        if cmd == F.CMD['write']:
            # payload as the reader takes it: 00, pwd BE24, bank, addr BE32,
            # count BE16, then 2*count data bytes.  Address and count are
            # BIG-endian -- confirmed against the GUI's own serial capture
            # (see re/final_compare.py).
            bank = p[4]
            addr = int.from_bytes(p[5:9], 'big')
            count = int.from_bytes(p[9:11], 'big')
            data = p[11:11 + count * 2]
            assert len(data) == count * 2, 'write payload truncated'
            m = MEM.setdefault(bank, bytearray(64))
            need = addr * 2 + len(data)
            while len(m) < need:
                m.append(0)
            m[addr * 2:addr * 2 + len(data)] = data
            return F.build_frame(cmd, bytes([0]))      # reply: status 0 only
        if cmd == F.CMD['read']:
            bank = p[0]
            addr = int.from_bytes(p[1:5], 'big')
            count = int.from_bytes(p[5:7], 'big')
            m = MEM.get(bank, bytearray(64))
            s = addr * 2
            return F.build_frame(cmd, m[s:s + count * 2])
        return F.build_frame(cmd, bytes([0]))


class FakeSerialModule:
    Serial = FakeSerial
    tools = None


def main():
    fake = FakeSerialModule()
    sys.modules['serial'] = fake

    # make port selection deterministic
    import tag_write_epc_tid as T
    T.list_ports = lambda: [('MOCK0', 'mock device')]

    print('#' * 68)
    print('# full run: EPC + TID write with read-back verification')
    print('#' * 68)
    rc = T.main(['--port', 'MOCK0', '--yes',
                 '--epc', 'E28069150000502008B25C75',
                 '--tid', 'E28069152000502008B25C75'])
    print('\n>>> exit code:', rc)

    print()
    print('memory after run:')
    print('  EPC bank:', MEM[1].hex().upper())
    print('  TID bank:', MEM[2].hex().upper())

    # word 0-1 = CRC (untouched, still 0000), word 2-7 = the EPC we wrote
    expected_epc = '00000000E28069150000502008B25C75'
    expected_tid = 'E28069152000502008B25C75'
    got_epc = MEM[1].hex().upper()
    got_tid = MEM[2].hex().upper()
    ok = got_epc == expected_epc and got_tid == expected_tid
    print()
    print('EPC bank (CRC + EPC): %s  expected %s  -> %s'
          % (got_epc, expected_epc, got_epc == expected_epc))
    print('TID bank           : %s  expected %s  -> %s'
          % (got_tid, expected_tid, got_tid == expected_tid))
    return 0 if (rc == 0 and ok) else 1


if __name__ == '__main__':
    raise SystemExit(main())
