"""Regression test using REAL frames captured from the user's reader.

The user ran:

    TX  A0 03 00 72 EB
    RX  A0 06 00 72 02 03 01 E2

against rfid_main_pannel.exe's target reader on COM4.  That single response is
the only ground truth we have from real hardware, so it is worth pinning down:
it tells us the frame layout, the checksum algorithm, and the reply shape for
command 0x72.

This test checks the codec against that frame and a set of derived cases.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)

import rfidframe as F

FAILS = []


def check(label, cond, detail=''):
    status = 'OK  ' if cond else 'FAIL'
    print('  [%s] %-46s %s' % (status, label, detail))
    if not cond:
        FAILS.append(label)


print('=' * 78)
print('real captured frames (from the user\'s reader on COM4)')
print('=' * 78)

TX = bytes.fromhex('A0030072EB')
RX = bytes.fromhex('A006007202030 1E2'.replace(' ', ''))

print('  TX = %s' % TX.hex(' ').upper())
print('  RX = %s' % RX.hex(' ').upper())
print()

# --- the request we build must match the request the GUI sent, byte for byte
built = F.build_frame(F.CMD['version'])
check('our 0x72 request == GUI request', built == TX,
      '%s vs %s' % (built.hex(' ').upper(), TX.hex(' ').upper()))

# --- the real response must parse as a valid frame
frames = F.parse_frames(RX)
check('real response parses to exactly 1 frame', len(frames) == 1,
      'got %d' % len(frames))
if frames:
    f = frames[0]
    check('response checksum validates', f['ok'], 'ok=%s' % f['ok'])
    check('response LEN byte == 6', RX[1] == 6, 'LEN=%d' % RX[1])
    check('wire length == LEN + 2', len(RX) == RX[1] + F.WIRE_BIAS,
          '%d == %d' % (len(RX), RX[1] + F.WIRE_BIAS))
    check('response CMD == 0x72', f['cmd'] == 0x72, hex(f['cmd']))
    check('response ADDR == 0', f['addr'] == 0, str(f['addr']))
    check('payload == 02 03 01 (3 bytes)', f['payload'] == bytes.fromhex('020301'),
          f['payload'].hex(' ').upper())
    check('payload length == LEN - 3', len(f['payload']) == RX[1] - 3,
          '%d == %d' % (len(f['payload']), RX[1] - 3))

# --- the signed-sum invariant the firmware relies on
signed_total = sum((b - 256 if b >= 0x80 else b) for b in RX)
check('signed byte sum of response is 0 mod 256', signed_total % 256 == 0,
      'sum=%d' % signed_total)

print()
print('=' * 78)
print('checksum implementation vs the real frame')
print('=' * 78)
# CheckSun over the first LEN+1 bytes must equal the last byte
chk = F.check_sun(RX, len(RX) - 1)
check('CheckSun(RX[0:len-1]) == RX[-1]', chk == RX[-1],
      'computed=%02X actual=%02X' % (chk, RX[-1]))

# and the same for our own request
chk_req = F.check_sun(TX, len(TX) - 1)
check('CheckSun(TX[0:len-1]) == TX[-1]', chk_req == TX[-1],
      'computed=%02X actual=%02X' % (chk_req, TX[-1]))

print()
print('=' * 78)
print('deduced reply shapes for the commands this task uses')
print('=' * 78)
# 0x72 -> 3 payload bytes.  Build equivalent-shaped replies for other commands
# and make sure the parser handles them.
SHAPES = [
    ('0x72 version      ', 0x72, bytes.fromhex('020301')),
    ('0x82 write ok     ', 0x82, bytes.fromhex('00')),
    ('0x82 write 3-byte ', 0x82, bytes.fromhex('000000')),
    ('0x81 read 12-byte ', 0x81, bytes.fromhex('E28069150000502008B25C75')),
    ('0x85 match ok     ', 0x85, bytes.fromhex('00')),
    ('0x92 tagcount     ', 0x92, bytes.fromhex('0100')),
    ('0x80 inv start    ', 0x80, bytes.fromhex('00')),
]
for label, cmd, payload in SHAPES:
    fr = F.build_frame(cmd, payload)
    p = F.parse_frames(fr)
    ok = len(p) == 1 and p[0]['ok'] and p[0]['payload'] == payload and p[0]['cmd'] == cmd
    check('round-trip %s' % label, ok,
          '%s  LEN=%d wire=%d' % (fr.hex(' ').upper(), fr[1], len(fr)))

print()
print('=' * 78)
print('stream resync: a real response arriving after garbage / in pieces')
print('=' * 78)
# partial prefix (as a serial read can deliver)
part1, part2 = RX[:3], RX[3:]
check('partial frame yields no complete frame', len(F.parse_frames(part1)) == 0)
check('reassembled halves parse to 1 frame', len(F.parse_frames(part1 + part2)) == 1)
# leading garbage must be skipped
check('garbage prefix is resynced', len(F.parse_frames(b'\x00\xB7\x21' + RX)) == 1)
# two frames back to back
check('two frames back to back parse as 2', len(F.parse_frames(RX + RX)) == 2)
# truncated frame at the end is not reported
check('trailing partial frame ignored', len(F.parse_frames(RX + RX[:2])) == 1)

print()
if FAILS:
    print('%d FAILED: %s' % (len(FAILS), ', '.join(FAILS)))
    raise SystemExit(1)
print('ALL CHECKS PASSED')
