"""Exercise rfidctl against a mock reader that speaks the recovered protocol."""
import os
import sys
import re

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, ROOT)
sys.path.insert(0, HERE)

import rfidctl
import rfidframe as F


class FakeReader(rfidctl.Transport):
    """Replies with correctly-framed responses, as the real device would."""

    def __init__(self):
        self.tx = bytearray()
        self.pending = bytearray()
        self.requests = []

    def write(self, data):
        self.tx += data
        for fr in F.parse_frames(bytes(data)):
            if not fr['ok']:
                continue
            self.requests.append(fr)
            self.pending += self._reply(fr)

    def _reply(self, fr):
        cmd = fr['cmd']
        if cmd == F.CMD['version']:
            body = b'V1.76.7'
        elif cmd == F.CMD['temperature']:
            body = bytes([26])
        elif cmd == F.CMD['get_work_ant']:
            body = bytes([1])
        elif cmd == F.CMD['get_ant_power']:
            body = bytes([30])
        elif cmd == F.CMD['get_tag_count']:
            body = (7).to_bytes(2, 'little')
        elif cmd == F.CMD['get_and_clear_buffer']:
            body = bytes([2]) + bytes.fromhex('E28069150000402008B6F478') + \
                   bytes.fromhex('E28069152000502008B83493')
        elif cmd == F.CMD['read']:
            body = bytes.fromhex('E28069150000402008B6F478')
        elif cmd in (F.CMD['stop_inventory'], F.CMD['clear_tag_buffer'],
                     F.CMD['buffered_inventory'], F.CMD['write'],
                     F.CMD['save_params']):
            body = bytes([0])
        else:
            body = bytes([0])
        return F.build_frame(cmd, body)

    def read(self, timeout, max_bytes=4096):
        out = bytes(self.pending[:max_bytes])
        del self.pending[:max_bytes]
        return out

    def close(self):
        pass


def run(argv):
    print('$ rfidctl ' + ' '.join(argv))
    rc = rfidctl.main(argv)
    print('  -> exit %s\n' % rc)
    return rc


def main():
    dev = FakeReader()
    real_open = rfidctl.open_transport
    rfidctl.open_transport = lambda a: dev
    fails = 0
    try:
        for argv in (
            ['info'],
            ['inv', '-n', '3', '-d', '0.6', '-i', '0.1'],
            ['read', '--bank', 'tid', '--words', '6'],
            ['write', '--bank', 'epc', '--addr-word', '2', '--data', 'DEADBEEF'],
            ['rf', '--antenna-power', '26', '--save'],
            ['sys', '--save'],
        ):
            rc = run(argv)
            if rc != 0:
                fails += 1
    finally:
        rfidctl.open_transport = real_open

    print('=' * 70)
    print('commands the mock device received:')
    for r in dev.requests:
        payload = r['payload'].hex(' ').upper()
        print('  cmd=0x%02X %-22s LEN=%-3d payload=[%s]' % (
            r['cmd'], r['cmd_name'], r['raw'][1], payload))
    bad = [r for r in dev.requests if not r['ok']]
    print()
    print('frames with bad checksum: %d' % len(bad))
    print('%d CLI invocations failed' % fails)
    return 1 if (fails or bad) else 0


if __name__ == '__main__':
    raise SystemExit(main())
