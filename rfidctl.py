#!/usr/bin/env python3
"""rfidctl - a small command-line API for the UHF RFID reader used by
           rfid_main_pannel.exe (v1.76.7).

Speaks the reader's native frame protocol directly, so it needs no Windows DLLs
and runs on macOS, Linux and Windows.  See PROTOCOL.md for the wire format and
re/rfidframe.py for the codec, both recovered from the original binary.

    rfidctl --port /dev/cu.usbserial-1420 info
    rfidctl --port COM4 --baud 115200 inv -n 5
    rfidctl --tcp 192.168.0.178:8080 read --bank tid --addr 0 --words 6
    rfidctl --port COM4 raw A0 03 00 72 EB

Run `rfidctl --help` or `rfidctl <command> --help` for details.
"""
from __future__ import annotations

import argparse
import os
import socket
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 're'))

try:
    from rfidframe import (HEAD, CMD, BANK, build_frame, parse_frames, check_sun,
                           le, payload_read, payload_write, payload_lock,
                           payload_kill, CMD_NAME)
except ImportError:  # allow running from inside re/
    from rfidframe import (HEAD, CMD, BANK, build_frame, parse_frames, check_sun,
                           le, payload_read, payload_write, payload_lock,
                           payload_kill, CMD_NAME)

DEFAULT_BAUD = 115200          # global `BaudRate` in the binary
DEFAULT_TCP_PORT = 8080        # global `tcp_port` in the binary


# ------------------------------------------------------------------ transports
class Transport:
    def write(self, data: bytes) -> None:
        raise NotImplementedError

    def read(self, timeout: float, max_bytes: int = 4096) -> bytes:
        raise NotImplementedError

    def close(self) -> None:
        pass


class SerialTransport(Transport):
    def __init__(self, port, baud=DEFAULT_BAUD, timeout=1.0):
        try:
            import serial                       # pyserial
        except ImportError:
            raise SystemExit(
                'pyserial is required for serial ports:  pip install pyserial')
        self.ser = serial.Serial(port, baudrate=baud, bytesize=8,
                                 parity='N', stopbits=1, timeout=timeout)
        # the reader needs a moment after DTR/RTS settle
        time.sleep(0.15)
        try:
            self.ser.reset_input_buffer()
        except Exception:
            pass

    def write(self, data):
        self.ser.write(data)
        self.ser.flush()

    def read(self, timeout, max_bytes=4096):
        self.ser.timeout = timeout
        return self.ser.read(max_bytes)

    def close(self):
        try:
            self.ser.close()
        except Exception:
            pass


class TcpTransport(Transport):
    def __init__(self, host, port=DEFAULT_TCP_PORT, timeout=2.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(timeout)

    def write(self, data):
        self.sock.sendall(data)

    def read(self, timeout, max_bytes=4096):
        self.sock.settimeout(timeout)
        try:
            return self.sock.recv(max_bytes)
        except socket.timeout:
            return b''

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass


class MockTransport(Transport):
    """Dry run: prints frames and returns nothing."""
    def __init__(self, echo=None):
        self.echo = echo or b''

    def write(self, data):
        print('  TX  %s' % data.hex(' ').upper())

    def read(self, timeout, max_bytes=4096):
        time.sleep(min(timeout, 0.05))
        out, self.echo = self.echo, b''
        return out


# ------------------------------------------------------------------ the client
class Reader:
    def __init__(self, transport, addr=0, verbose=True, retries=2):
        self.t = transport
        self.addr = addr
        self.verbose = verbose
        self.retries = retries

    def request(self, cmd, payload=b'', expect=None, timeout=1.0, quiet=False):
        """Send one command, return the parsed reply dicts (checksum-validated)."""
        frame = build_frame(cmd, payload, addr=self.addr)
        for attempt in range(self.retries + 1):
            if self.verbose and not quiet:
                print('  TX  %s' % frame.hex(' ').upper())
            self.t.write(frame)

            buf = bytearray()
            deadline = time.time() + timeout
            while time.time() < deadline:
                chunk = self.t.read(max(0.05, deadline - time.time()))
                if not chunk:
                    break
                buf += chunk
                if self.verbose and not quiet:
                    print('  RX  %s' % chunk.hex(' ').upper())
                frames = parse_frames(bytes(buf))
                good = [f for f in frames if f['ok']]
                # prefer a reply that echoes our command byte
                if good:
                    for f in good:
                        if expect is None or f['cmd'] == expect:
                            return good
                    if all(len(f['raw']) <= len(buf) for f in good):
                        return good
            if attempt < self.retries:
                if self.verbose and not quiet:
                    print('  ..  no reply, retrying')
                time.sleep(0.15)
        return []

    def send_only(self, cmd, payload=b''):
        frame = build_frame(cmd, payload, addr=self.addr)
        if self.verbose:
            print('  TX  %s' % frame.hex(' ').upper())
        self.t.write(frame)


# ------------------------------------------------------------------ helpers
def hexdump(data):
    return data.hex(' ').upper()


def parse_int(text, base=0):
    return int(str(text), base)


def parse_hex_bytes(text):
    text = text.replace(':', '').replace('-', '').replace(' ', '')
    if len(text) % 2:
        raise argparse.ArgumentTypeError('hex string must have an even length')
    return bytes.fromhex(text)


def epc_from_reply(frame):
    """Best-effort EPC extraction from an inventory reply.

    Reply contents were not fully mapped in the original binary, so this is
    deliberately conservative and clearly reported as best-effort.
    """
    return frame['payload']


# ------------------------------------------------------------------ commands
def cmd_info(r, a):
    print('reader at addr %d' % r.addr)
    got = r.request(CMD['version'], expect=CMD['version'], timeout=a.timeout)
    if got:
        for f in got:
            p = f['payload']
            print('  version reply : %s' % hexdump(p))
            if p:
                txt = ''.join(chr(c) if 32 <= c < 127 else '.' for c in p)
                print('                  -> "%s"' % txt)
    else:
        print('  version reply : (none)')

    got = r.request(CMD['temperature'], expect=CMD['temperature'], timeout=a.timeout)
    if got:
        for f in got:
            p = f['payload']
            if p:
                print('  temperature   : 0x%02X -> %d C' % (p[0], p[0] & 0x7F))
            else:
                print('  temperature   : (empty)')

    got = r.request(CMD['get_work_ant'], expect=CMD['get_work_ant'], timeout=a.timeout)
    if got and got[0]['payload']:
        print('  work antenna  : %d' % got[0]['payload'][0])

    got = r.request(CMD['get_ant_power'], expect=CMD['get_ant_power'], timeout=a.timeout)
    if got and got[0]['payload']:
        print('  antenna power : %d' % got[0]['payload'][0])


def cmd_inv(r, a):
    """Inventory.  Uses buffered inventory then polls the tag count."""
    ant = a.antenna
    r.send_only(CMD['clear_tag_buffer'])
    time.sleep(0.15)
    got = r.request(CMD['buffered_inventory'], bytes([ant]),
                    expect=CMD['buffered_inventory'], timeout=a.timeout)
    print('  started buffered inventory on antenna %d' % ant)

    deadline = time.time() + a.duration
    seen = None
    while time.time() < deadline:
        time.sleep(a.interval)
        got = r.request(CMD['get_tag_count'], expect=CMD['get_tag_count'],
                        timeout=a.timeout, quiet=True)
        if got and got[0]['payload']:
            n = got[0]['payload'][0] + (got[0]['payload'][1] << 8
                                        if len(got[0]['payload']) > 1 else 0)
            if n != seen:
                seen = n
                print('  tags seen so far: %d' % n)
            if a.count and n >= a.count:
                break

    r.send_only(CMD['stop_inventory'])
    time.sleep(0.1)
    got = r.request(CMD['get_and_clear_buffer'], expect=CMD['get_and_clear_buffer'],
                    timeout=a.timeout)
    if got:
        for f in got:
            p = f['payload']
            if not p:
                continue
            print('  buffer reply (%d bytes): %s' % (len(p), hexdump(p)))
            # Typical layout: count byte then repeated 12-byte EPCs
            for i in range(0, min(len(p) - 1, 255)):
                pass
    print('  final count: %s' % (seen if seen is not None else '?'))


def cmd_read(r, a):
    bank = BANK[a.bank] if isinstance(a.bank, str) else a.bank
    payload = payload_read(bank, a.addr_word, a.words, a.password)
    got = r.request(CMD['read'], payload, expect=CMD['read'], timeout=a.timeout)
    if not got:
        print('  no reply', file=sys.stderr)
        return 1
    for f in got:
        p = f['payload']
        print('  bank=%s word=%d words=%d -> %s' % (
            a.bank, a.addr_word, a.words, hexdump(p) if p else '(empty)'))
        if a.ascii and p:
            printable = ''.join(chr(c) if 32 <= c < 127 else '.' for c in p)
            print('  ascii: %s' % printable)
    return 0


def cmd_write(r, a):
    bank = BANK[a.bank] if isinstance(a.bank, str) else a.bank
    data = parse_hex_bytes(a.data)
    words = len(data) // 2
    payload = payload_write(bank, a.addr_word, words, data, a.password)
    got = r.request(CMD['write'], payload, expect=CMD['write'], timeout=a.timeout)
    print('  wrote %d byte(s) to bank=%s word=%d' % (len(data), a.bank, a.addr_word))
    if got:
        for f in got:
            print('  reply: %s' % (hexdump(f['payload']) or '(empty)'))
    return 0


def cmd_lock(r, a):
    payload = payload_lock(a.mask, a.action, a.password)
    got = r.request(CMD['lock'], payload, expect=CMD['lock'], timeout=a.timeout)
    print('  lock mask=0x%02X action=0x%02X' % (a.mask, a.action))
    for f in (got or []):
        print('  reply: %s' % (hexdump(f['payload']) or '(empty)'))
    return 0


def cmd_kill(r, a):
    payload = payload_kill(a.password)
    got = r.request(CMD['kill'], payload, expect=CMD['kill'], timeout=a.timeout)
    print('  kill pwd=0x%08X' % a.password)
    for f in (got or []):
        print('  reply: %s' % (hexdump(f['payload']) or '(empty)'))
    return 0


def cmd_rf(r, a):
    if a.antenna_power is not None:
        r.request(CMD['set_ant_power'], bytes([a.antenna_power]),
                  expect=CMD['set_ant_power'], timeout=a.timeout)
        print('  antenna power set to %d' % a.antenna_power)
    if a.work_antenna is not None:
        r.request(CMD['set_work_ant'], bytes([a.work_antenna]),
                  expect=CMD['set_work_ant'], timeout=a.timeout)
        print('  work antenna set to %d' % a.work_antenna)
    if a.frequency is not None:
        payload = bytes([4, 0, 1, 1]) + le(a.frequency, 3)
        r.request(CMD['set_frequency'], payload,
                  expect=CMD['set_frequency'], timeout=a.timeout)
        print('  frequency set to 0x%06X' % a.frequency)
    if a.beep is not None:
        r.request(CMD['set_beep'], bytes([a.beep]),
                  expect=CMD['set_beep'], timeout=a.timeout)
        print('  beeper set to %d' % a.beep)
    if a.save:
        r.send_only(CMD['save_params'])
        print('  parameters saved')
    return 0


def cmd_sys(r, a):
    if a.restart:
        r.send_only(CMD['restart'])
        print('  restart command sent')
    if a.reset:
        r.send_only(CMD['factory_reset'])
        print('  factory reset sent')
    if a.save:
        r.send_only(CMD['save_params'])
        print('  parameters saved')
    if a.rs485 is not None:
        r.request(CMD['set_rs485_addr'], bytes([a.rs485]),
                  expect=CMD['set_rs485_addr'], timeout=a.timeout)
        print('  RS-485 address set to %d (reconnect with --addr)' % a.rs485)
    if a.baud_index is not None:
        r.request(CMD['set_baudrate'], bytes([a.baud_index]),
                  expect=CMD['set_baudrate'], timeout=a.timeout)
        print('  baudrate code set to %d' % a.baud_index)
    return 0


def cmd_raw(r, a):
    frame = parse_hex_bytes(a.hex)
    print('  TX  %s' % hexdump(frame))
    r.t.write(frame)
    buf = bytearray()
    deadline = time.time() + a.timeout
    while time.time() < deadline:
        chunk = r.t.read(max(0.05, deadline - time.time()))
        if not chunk:
            break
        buf += chunk
        print('  RX  %s' % hexdump(chunk))
    if not buf:
        print('  (no reply)')
        return 1
    for f in parse_frames(bytes(buf)):
        print('  frame cmd=0x%02X (%s) addr=%d checksum=%s payload=%s' % (
            f['cmd'], f['cmd_name'], f['addr'],
            'OK' if f['ok'] else 'BAD', hexdump(f['payload']) or '(empty)'))
    return 0


def cmd_build(r, a):
    """Offline: print the frame(s) for a command without touching a device."""
    if a.command == 'read':
        payload = payload_read(BANK[a.bank], a.addr_word, a.words, a.password)
        cmd = CMD['read']
    elif a.command == 'write':
        data = parse_hex_bytes(a.data)
        payload = payload_write(BANK[a.bank], a.addr_word, len(data) // 2, data, a.password)
        cmd = CMD['write']
    elif a.command == 'kill':
        payload, cmd = payload_kill(a.password), CMD['kill']
    elif a.command == 'lock':
        payload, cmd = payload_lock(a.mask, a.action, a.password), CMD['lock']
    elif a.command in CMD:
        cmd = CMD[a.command]
        payload = parse_hex_bytes(a.hex) if a.hex else b''
    else:
        print('unknown command %r' % a.command, file=sys.stderr)
        return 2
    frame = build_frame(cmd, payload, addr=a.addr)
    print('%s  (cmd=0x%02X LEN=%d wire=%d)' % (hexdump(frame), cmd, frame[1], len(frame)))
    return 0


def cmd_commands(r, a):
    """List every command code with the payload layout recovered from the binary."""
    layouts = {
        'read': 'bank, wordAddr LE32, wordCount LE16, pwd LE32',
        'write': '00, pwd LE24, bank, wordAddr LE32, wordCount LE16, data',
        'lock': 'pwd LE32, mask, action',
        'kill': 'pwd LE32',
        'gb_read': 'bank, u16, u16, u32',
        'gb_write': '00, u32 LE24, bank, u16, u16, data',
        'gb_lock': 'u32 LE32, mask, action',
        'gb_kill': 'u32 LE32',
        'gb_se_read': 'as gb_read',
        'gb_se_write': 'as gb_write',
        'gb_double_identify': '(19 bytes, loop-built)',
        'buffered_inventory': 'antenna',
        'realtime_inventory': 'mode',
        'custom_inventory': 'u8 x4',
        'custom_session_inv': 'u8 x3',
        'switch_ant_inventory': '(variable, loop-built)',
        'set_multi_ant_power': 'u8 x4 (antenna powers)',
        'set_eight_ant_power': 'u8 x8 (antenna powers)',
        'set_frequency': '04 00, u16, u8, freq LE24   [LEN=10]',
        'set_freq_standard': 'u8, u8, u8                [LEN=6, shares CMD 0x78]',
        'set_ant_power': 'power',
        'set_work_ant': 'antenna',
        'set_fast_epc': 'u8',
        'set_rf_link': 'u8 profile',
        'set_beep': 'on/off',
        'set_rs485_addr': 'address',
        'set_baudrate': 'baud code (not the baud rate itself)',
        'set_gpio': 'gpio, level',
        'read_gpio': 'gpio',
        'set_epc_match': '(variable: enable + EPC)',
    }
    no_payload = ('stop_inventory', 'clear_tag_buffer', 'get_tag_buffer',
                  'get_and_clear_buffer', 'get_tag_count', 'get_work_ant',
                  'get_ant_power', 'get_frequency', 'get_rf_link', 'get_fast_epc',
                  'get_epc_match', 'restart', 'version', 'temperature',
                  'save_params', 'factory_reset')

    print('%-24s %-6s %-5s %s' % ('name', 'CMD', 'LEN', 'payload (as recovered from the binary)'))
    print('-' * 84)
    for name, code in sorted(CMD.items(), key=lambda kv: (kv[1], kv[0])):
        if name in no_payload:
            ln, layout = '3', '(none)'
        else:
            layout = layouts.get(name, '')
            ln = 'var'
        print('%-24s 0x%02X   %-5s %s' % (name, code, ln, layout))
    print()
    print('LEN = payload_length + 3; wire length = LEN + 2.  See re/PROTOCOL.md.')
    return 0


# ------------------------------------------------------------------ CLI
def build_parser():
    p = argparse.ArgumentParser(
        prog='rfidctl',
        description='Command-line API for the UHF RFID reader used by rfid_main_pannel.exe',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog='examples:\n'
               '  rfidctl --port COM4 info\n'
               '  rfidctl --port /dev/cu.usbserial-1420 inv -n 5\n'
               '  rfidctl --tcp 192.168.0.178:8080 read --bank tid --words 6\n'
               '  rfidctl --build read --bank epc --words 2\n')

    conn = p.add_argument_group('connection')
    conn.add_argument('--port', help='serial port, e.g. COM4 or /dev/cu.usbserial-1420')
    conn.add_argument('--baud', type=int, default=DEFAULT_BAUD,
                      help='baud rate (default %d)' % DEFAULT_BAUD)
    conn.add_argument('--tcp', help='host[:port] for network readers (default port %d)'
                      % DEFAULT_TCP_PORT)
    conn.add_argument('--addr', type=lambda v: int(v, 0), default=0,
                      help='RS-485 device address (default 0)')
    conn.add_argument('--timeout', type=float, default=1.0,
                      help='reply timeout in seconds (default 1.0)')
    conn.add_argument('-q', '--quiet', action='store_true',
                      help='do not echo raw frames')
    conn.add_argument('--dry-run', action='store_true',
                      help='print frames without opening a device')

    sub = p.add_subparsers(dest='action', required=True)

    sp = sub.add_parser('info', help='read firmware version, temperature, antenna settings')
    sp.set_defaults(func=cmd_info)

    sp = sub.add_parser('inv', help='run an inventory and count tags')
    sp.add_argument('-a', '--antenna', type=int, default=1)
    sp.add_argument('-n', '--count', type=int, default=0,
                    help='stop once this many tags are seen (0 = run for --duration)')
    sp.add_argument('-d', '--duration', type=float, default=3.0)
    sp.add_argument('-i', '--interval', type=float, default=0.3)
    sp.set_defaults(func=cmd_inv)

    sp = sub.add_parser('read', help='read tag memory (ISO 18000-6C)')
    sp.add_argument('--bank', default='epc', choices=list(BANK))
    sp.add_argument('--addr-word', type=lambda v: int(v, 0), default=0,
                    help='starting word address')
    sp.add_argument('--words', type=lambda v: int(v, 0), default=6,
                    help='number of 16-bit words')
    sp.add_argument('--password', type=lambda v: int(v, 0), default=0)
    sp.add_argument('--ascii', action='store_true', help='also show printable ASCII')
    sp.set_defaults(func=cmd_read)

    sp = sub.add_parser('write', help='write tag memory (ISO 18000-6C)')
    sp.add_argument('--bank', default='epc', choices=list(BANK))
    sp.add_argument('--addr-word', type=lambda v: int(v, 0), default=0)
    sp.add_argument('--data', required=True, help='hex bytes, e.g. DEADBEEF')
    sp.add_argument('--password', type=lambda v: int(v, 0), default=0)
    sp.set_defaults(func=cmd_write)

    sp = sub.add_parser('lock', help='lock tag memory')
    sp.add_argument('--mask', type=lambda v: int(v, 0), required=True)
    sp.add_argument('--action', type=lambda v: int(v, 0), required=True)
    sp.add_argument('--password', type=lambda v: int(v, 0), default=0)
    sp.set_defaults(func=cmd_lock)

    sp = sub.add_parser('kill', help='permanently kill a tag')
    sp.add_argument('--password', type=lambda v: int(v, 0), required=True)
    sp.set_defaults(func=cmd_kill)

    sp = sub.add_parser('rf', help='RF parameters')
    sp.add_argument('--antenna-power', type=int)
    sp.add_argument('--work-antenna', type=int)
    sp.add_argument('--frequency', type=lambda v: int(v, 0),
                    help='3-byte frequency value, e.g. 0x0F5A0A')
    sp.add_argument('--beep', type=int, choices=[0, 1])
    sp.add_argument('--save', action='store_true', help='persist the new settings')
    sp.set_defaults(func=cmd_rf)

    sp = sub.add_parser('sys', help='system commands')
    sp.add_argument('--restart', action='store_true')
    sp.add_argument('--reset', action='store_true', help='factory reset')
    sp.add_argument('--save', action='store_true')
    sp.add_argument('--rs485', type=lambda v: int(v, 0))
    sp.add_argument('--baud-index', type=lambda v: int(v, 0))
    sp.set_defaults(func=cmd_sys)

    sp = sub.add_parser('raw', help='send raw hex bytes and print the reply')
    sp.add_argument('hex', help='e.g. "A0 03 00 72 EB"')
    sp.set_defaults(func=cmd_raw)

    sp = sub.add_parser('build', help='print a frame without sending it')
    sp.add_argument('command')
    sp.add_argument('--bank', default='epc', choices=list(BANK))
    sp.add_argument('--addr-word', type=lambda v: int(v, 0), default=0)
    sp.add_argument('--words', type=lambda v: int(v, 0), default=2)
    sp.add_argument('--data', default='DEADBEEF')
    sp.add_argument('--mask', type=lambda v: int(v, 0), default=1)
    sp.add_argument('--action', type=lambda v: int(v, 0), default=2)
    sp.add_argument('--password', type=lambda v: int(v, 0), default=0)
    sp.add_argument('--hex', default='')
    sp.set_defaults(func=cmd_build)

    sp = sub.add_parser('commands', help='list every known command code')
    sp.set_defaults(func=cmd_commands)

    return p


def serial_hint(port, exc):
    """Return a human hint for a failed serial open, or None."""
    s = str(exc)
    if any(k in s for k in ('PermissionError', '拒绝访问', 'Access is denied',
                            'being used by another process')):
        return ('串口 %s 被其它程序占用。请关掉 rfid_main_pannel.exe、串口调试助手，\n'
                '        或占用该口的其它 python 进程后重试。' % port)
    if any(k in s for k in ('could not open port', 'FileNotFoundError',
                            'No such file')):
        return ('找不到串口 %s。用 --list-ports 看可用串口，'
                'macOS 通常是 /dev/cu.usbserial*。' % port)
    return None


def open_transport(a):
    if a.dry_run:
        return MockTransport()
    if a.tcp:
        host, _, port = a.tcp.partition(':')
        port = int(port) if port else DEFAULT_TCP_PORT
        return TcpTransport(host, port, timeout=a.timeout)
    if a.port:
        try:
            return SerialTransport(a.port, a.baud, timeout=a.timeout)
        except SystemExit:
            raise
        except Exception as e:
            print('打开串口 %s 失败：%s' % (a.port, e), file=sys.stderr)
            h = serial_hint(a.port, e)
            if h:
                print('  ' + h, file=sys.stderr)
            raise SystemExit(3)
    raise SystemExit('specify --port, --tcp or --dry-run')


def main(argv=None):
    p = build_parser()
    a = p.parse_args(argv)

    # `build` and `commands` are pure offline utilities: no device needed.
    if a.func in (cmd_build, cmd_commands):
        return a.func(None, a) or 0

    t = open_transport(a)
    try:
        r = Reader(t, addr=a.addr, verbose=not a.quiet, retries=1)
        return a.func(r, a) or 0
    except KeyboardInterrupt:
        print('\ninterrupted', file=sys.stderr)
        return 130
    finally:
        t.close()


if __name__ == '__main__':
    raise SystemExit(main())
