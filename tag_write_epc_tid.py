#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tag_write_epc_tid.py — 通过 RS232 连接读写器，对 6C 标签的 EPC 区和 TID 区各写一次。

对应 rfid_main_pannel.exe 的「标签测试 → 标签操作 → 读写标签 → 6C」页面操作：

    1) EPC 区：访问密码 00000000，起始地址 2，数据长度 6，数据 = EPC
    2) TID 区：访问密码 00000000，起始地址 0，数据长度 6，数据 = TID

协议取自 rfid_main_pannel.exe 的逆向结果（见 re/PROTOCOL.md），
本脚本不依赖原程序，也不需要任何 Windows DLL。

用法
----
    交互式（推荐）：
        python tag_write_epc_tid.py

    直接给参数：
        python tag_write_epc_tid.py --port COM4 \
            --epc E28069150000502008B25C75 \
            --tid E28069152000502008B25C75 --yes

    只看报文不连设备：
        python tag_write_epc_tid.py --dry-run

    列出可用串口：
        python tag_write_epc_tid.py --list-ports

依赖：pip install pyserial
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 're'))
try:
    import rfidframe as F
except ImportError:
    sys.exit('找不到 re/rfidframe.py，请保持目录结构不变。')

# ---------------------------------------------------------------- 默认参数
BAUD = 115200              # 与 rfid_main_pannel.exe 的 BaudRate 全局量一致
ADDR = 0                   # RS-485 地址；直连 USB/串口时为 0

EPC_BANK = F.BANK['epc']   # 1
TID_BANK = F.BANK['tid']   # 2

DEFAULT_EPC = 'E28069150000502008B25C75'
DEFAULT_TID = 'E28069152000502008B25C75'

PASSWORD = 0x00000000
EPC_START_WORD = 2
TID_START_WORD = 0
WORDS = 6                  # 数据长度（字），6 字 = 12 字节 = 24 个 Hex 字符

TIMEOUT = 1.0
RETRIES = 2

# 写入失败（例如状态码 0x41）就重写，最多这么多轮，以回读校验为准
WRITE_ATTEMPTS = 8


# ---------------------------------------------------------------- 小工具
def hr(title=''):
    print('=' * 68)
    if title:
        print(title)
        print('=' * 68)


def hexs(data: bytes) -> str:
    return data.hex(' ').upper()


def print_hex_block(tag: str, data: bytes, per_line: int = 16) -> None:
    if not data:
        print('  %-10s (空)' % tag)
        return
    for i in range(0, len(data), per_line):
        chunk = data[i:i + per_line]
        ascii_part = ''.join(chr(c) if 32 <= c < 127 else '.' for c in chunk)
        print('  %-10s %04X  %-*s  %s' % (
            tag if i == 0 else '', i, per_line * 3 - 1, hexs(chunk), ascii_part))


def clean_hex(text: str) -> str:
    return re.sub(r'[\s:_-]', '', text or '').upper()


def parse_hex(text: str, label: str) -> bytes:
    """把 Hex 文本解析成字节；只做格式校验，不限制长度。"""
    raw = clean_hex(text)
    if not raw:
        raise ValueError('%s 不能为空' % label)
    if not re.fullmatch(r'[0-9A-F]+', raw):
        raise ValueError('%s 含非 Hex 字符：%r' % (label, text))
    if len(raw) % 2:
        raise ValueError('%s 的 Hex 字符数必须是偶数，当前 %d 个' % (label, len(raw)))
    return bytes.fromhex(raw)


def words_of(data: bytes, what: str = '数据') -> int:
    """字节数 = 字数的 2 倍，且字数要能塞进帧的 LEN 字节。"""
    if len(data) % 2:
        raise ValueError('%s 必须是偶数个字节，当前 %d' % (what, len(data)))
    n = len(data) // 2
    if n == 0:
        raise ValueError('%s 不能为空' % what)
    if n > 255:
        raise ValueError('%s 为 %d 字，超过上限 255 字（%d 字节）'
                         % (what, n, 255 * 2))
    return n


def validate_hex_data(text: str, label: str, words: int) -> bytes:
    """按固定字数校验 Hex 数据（交互输入时用）。"""
    data = parse_hex(text, label)
    want = words * 2
    if len(data) != want:
        raise ValueError(
            '%s 应为 %d 字节（%d 个 Hex 字符），当前 %d 字节（%d 个字符）'
            % (label, want, want * 2, len(data), len(clean_hex(text))))
    return data


# ---------------------------------------------------------------- 串口
def _glob_ports():
    """兜底枚举：直接扫常见的设备名（macOS / Linux）。

    pyserial 有时候枚举不到（某些 CH340、蓝牙虚拟口），所以再扫一遍。
    macOS 上要挑 cu.* 而不是 tty.*：tty.* 会等 DCD 信号，读写器不发就卡住。
    """
    found = []
    for pat in ('/dev/ttyUSB*', '/dev/ttyACM*', '/dev/tty.usbserial*',
                '/dev/tty.wchusbserial*', '/dev/cu.usbserial*',
                '/dev/cu.wchusbserial*', '/dev/cu.SLAB_USBtoUART*',
                '/dev/cu.usbmodem*', '/dev/serial/by-id/*'):
        for p in sorted(glob.glob(pat)):
            found.append((p, ''))
    return found


def _windows_ports():
    """Fallback for Windows: ask the registry which COM ports exist."""
    if os.name != 'nt':
        return []
    out = []
    try:
        import winreg
        key = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE,
                             r'HARDWARE\DEVICEMAP\SERIALCOMM')
        i = 0
        while True:
            try:
                name, value, _ = winreg.EnumValue(key, i)
                out.append((value, name))
                i += 1
            except OSError:
                break
        winreg.CloseKey(key)
    except Exception:
        pass
    return sorted(out)


def list_ports():
    """Return [(device, description)].

    Prefers pyserial's enumeration, but falls back to a registry scan on Windows
    and a /dev glob elsewhere, so the adapter is still found when pyserial's
    own enumeration comes up empty (which happens for some CH340 and Bluetooth
    virtual ports).
    """
    seen, out = set(), []
    try:
        from serial.tools import list_ports as lp
        for p in lp.comports():
            if p.device not in seen:
                seen.add(p.device)
                out.append((p.device, p.description or ''))
    except Exception:
        pass
    for dev, desc in _windows_ports() + _glob_ports():
        if dev not in seen:
            seen.add(dev)
            out.append((dev, desc))
    return out


def port_score(dev, desc):
    """小 = 更像我们要找的 USB 转串口适配器。

    名字里带明确芯片/驱动标识的排最前（CH340/CH341/CP210x/FTDI/SLAB…），
    蓝牙虚拟串口、调试控制台这类排在最后，免得自动挑错。
    """
    t = ('%s %s' % (dev, desc)).lower()
    if any(h in t for h in ('ch340', 'ch341', 'cp210', 'ftdi', 'prolific',
                            'wch', 'usb-serial', 'usbserial', 'silabs',
                            'slab', 'usbmodem')):
        return 0
    if 'usb' in t or 'serial' in t or 'uart' in t:
        return 1
    if (dev.upper().startswith('COM') or dev.startswith('/dev/tty')
            or dev.startswith('/dev/cu')):
        return 2
    return 3


def pick_port(explicit=None, quiet=False):
    """不提问的串口选择：显式给的优先，否则自动挑最像 USB 转串口的那个。

    和 choose_port() 的区别是**永远不等待输入** —— 这工具是给脚本用的，
    多串口时弹菜单会卡住。挑中的口会打印出来，方便核对。
    """
    if explicit:
        return explicit
    ports = list_ports()
    if not ports:
        sys.exit('未发现任何串口。请用 --port 指定，例如 --port COM4 或 '
                 '--port /dev/cu.usbserial-1420')
    best = sorted(ports, key=lambda p: (port_score(*p), p[0]))
    dev, desc = best[0]
    if not quiet:
        if len(ports) == 1:
            print('自动选择串口：%s  %s' % (dev, desc))
        else:
            others = '、'.join(d for d, _ in best[1:])
            print('自动选择串口：%s  %s   （另有 %s；要指定用 --port，看全部用 --list-ports）'
                  % (dev, desc, others))
    return dev


def choose_port(explicit: str | None) -> str:
    """交互式选口：多串口时弹菜单等输入。新代码请用 pick_port()。"""
    if explicit:
        return explicit
    ports = list_ports()
    if not ports:
        sys.exit('未发现任何串口。请用 --port 指定，例如 --port COM4 或 '
                 '--port /dev/cu.usbserial-1420')
    if len(ports) == 1:
        print('自动选择串口：%s  %s' % ports[0])
        return ports[0][0]
    print('发现多个串口：')
    for i, (dev, desc) in enumerate(ports, 1):
        print('  [%d] %-28s %s' % (i, dev, desc))
    while True:
        ans = input('请选择串口号 [1-%d]（直接回车=1）：' % len(ports)).strip()
        if not ans:
            return ports[0][0]
        if ans.isdigit() and 1 <= int(ans) <= len(ports):
            return ports[int(ans) - 1][0]
        print('  输入无效，请重试。')


# ---------------------------------------------------------------- 读写器
class Reader:
    def __init__(self, ser, addr=ADDR, verbose=True, timeout=TIMEOUT):
        self.ser = ser
        self.addr = addr
        self.verbose = verbose
        self.timeout = timeout
        self.broken = False
        self.break_reason = ''

    def transact(self, cmd, payload=b'', expect=None, timeout=None, retries=RETRIES,
                 quiet=False):
        """发一条命令，返回校验通过的应答帧列表。"""
        if timeout is None:
            timeout = self.timeout
        show = self.verbose and not quiet
        frame = F.build_frame(cmd, payload, addr=self.addr)
        for attempt in range(retries + 1):
            if show:
                print('  TX  %s' % hexs(frame))
            try:
                self.ser.reset_input_buffer()
                self.ser.write(frame)
                self.ser.flush()
            except Exception as exc:
                # CH341 这类适配器在射频工作时偶发掉线，pyserial 会抛
                # SerialException('WriteFile failed ... PermissionError(13)')。
                # 不一定真断了，所以先重试；连续失败才判定为断链。
                self.write_faults = getattr(self, 'write_faults', 0) + 1
                reason = '%s: %s' % (type(exc).__name__, exc)
                if show:
                    print('  !!  串口写入出错（第 %d 次）：%s'
                          % (self.write_faults, reason))
                if self.write_faults >= 3:
                    self.broken = True
                    self.break_reason = reason
                    return []
                time.sleep(0.3)
                try:
                    self.ser.close()
                    self.ser.open()
                    time.sleep(0.2)
                except Exception:
                    pass
                continue

            buf = bytearray()
            deadline = time.time() + timeout
            while time.time() < deadline:
                try:
                    chunk = self.ser.read(256)
                except Exception as exc:
                    # 串口中途被拔掉/被别的进程抢走时 pyserial 会抛
                    # SerialException('GetOverlappedResult failed ...')。
                    # 不要让它把整个扫描打断，标记为断链后退出重试。
                    self.broken = True
                    self.break_reason = '%s: %s' % (type(exc).__name__, exc)
                    if show:
                        print('  !!  串口读取出错：%s' % self.break_reason)
                    return []
                if chunk:
                    buf += chunk
                    if show:
                        print('  RX  %s' % hexs(chunk))
                frames = [f for f in F.parse_frames(bytes(buf)) if f['ok']]
                if frames:
                    if expect is None:
                        return frames
                    for f in frames:
                        if f['cmd'] == expect:
                            return frames
                if chunk:
                    continue
                time.sleep(0.02)
            if attempt < retries:
                if show:
                    print('  ..  无应答，重试 (%d/%d)' % (attempt + 1, retries))
                time.sleep(0.15)
        return []

    def send_only(self, cmd, payload=b''):
        """只发不收 —— 用于 stop_inventory 这类无需等待应答的命令。"""
        frame = F.build_frame(cmd, payload, addr=self.addr)
        if self.verbose:
            print('  TX  %s   (只发不收)' % hexs(frame))
        try:
            self.ser.write(frame)
            self.ser.flush()
        except Exception as exc:
            self.broken = True
            self.break_reason = '%s: %s' % (type(exc).__name__, exc)
            print('  !!  串口写入出错：%s' % self.break_reason)

    def set_epc_match(self, enable: bool, epc: bytes = b''):
        """设置 EPC 匹配过滤（0x85）。只有一个标签时可以不设。"""
        payload = bytes([1 if enable else 0]) + bytes([len(epc)]) + epc
        return self.transact(F.CMD['set_epc_match'], payload,
                             expect=F.CMD['set_epc_match'])

    def write_words(self, bank, start_word, data, password=PASSWORD, timeout=None):
        """写标签内存：数据长度以「字」计，2 字节 = 1 字。"""
        words = len(data) // 2
        payload = F.payload_write(bank, start_word, words, data, password)
        assert len(payload) + 3 == F.write_len(words), 'LEN 计算不一致'
        return self.transact(F.CMD['write'], payload, expect=F.CMD['write'],
                             timeout=timeout)

    def read_words(self, bank, start_word, words, password=PASSWORD, timeout=None):
        payload = F.payload_read(bank, start_word, words, password)
        return self.transact(F.CMD['read'], payload, expect=F.CMD['read'],
                             timeout=timeout)


def extract_data(reply_frames):
    """从应答帧里取出数据部分。

    应答格式：A0 LEN ADDR CMD <状态/数据...> CHECK。
    本工具只做保守提取：如果能明显看出多出一个状态字节，就跳过它。
    """
    if not reply_frames:
        return b'', b''
    f = reply_frames[0]
    body = f['payload']
    return body, body


def decode_version(body):
    """把版本应答翻成可读文本。

    实测你的读写器对 0x72 返回 3 字节（`A0 06 00 72 02 03 01 E2` -> payload
    `02 03 01`），所以是「主.次.修订」三段式，而不是 ASCII 字符串。
    这里两种都试。
    """
    if not body:
        return '（空）'
    txt = ''.join(chr(c) if 32 <= c < 127 else '.' for c in body)
    out = ' '.join('%02X' % b for b in body)
    if all(32 <= c < 127 for c in body):
        out += '  -> "%s"' % txt
    if len(body) == 3:
        out += '  -> 版本 %d.%d.%d' % (body[0], body[1], body[2])
        out += '（或 %d.%d.%d 若为低位在前）' % (body[2], body[1], body[0])
    return out


def interpret_reply(cmd, body):
    """把应答载荷翻成人话。

    回复里"有没有状态码前缀"这件事没有从固件里完全确认，所以这里两种解释都报，
    不做断言，只给判断依据。
    """
    n = len(body)
    if n == 0:
        return '空载荷（长度 0）'
    if n == 1:
        return '单字节状态码 0x%02X' % body[0]
    first, rest = body[0], body[1:]
    known = sum(1 for b in rest if b)
    return ('%d 字节；首字节 0x%02X；其余 %d 字节中 %d 个非零'
            % (n, first, len(rest), known))


def cmd_ok(frames):
    """只要有校验通过的、命令码匹配的应答，就认为读写器执行了这条命令。"""
    return bool(frames)


# ---------------------------------------------------------------- 单步操作
def write_status(body):
    """从 0x82 应答里取出状态码。返回 (状态码, 是否成功)。

    成功时是**长应答**：`00 <字数2> 10 <PC> <数据> <CRC> 10 01`，状态在第 3 字节；
    失败时只有**一个字节**，就是错误码本身（实测 0x41 = 写不进去）。
    注意别把"收到应答"当成"写成功" —— 0x41 也是有应答的。
    """
    if not body:
        return None, False
    if len(body) >= 3:
        return body[2], body[2] == 0x10
    return body[0], body[0] == 0x10


def do_write(reader, label, bank, start_word, data, timeout=None, show_frame=True):
    hr('写入 %s 区' % label)
    words = len(data) // 2
    payload = F.payload_write(bank, start_word, words, data, PASSWORD)
    frame = F.build_frame(F.CMD['write'], payload, addr=reader.addr)

    print('  目标      : %s 区 (bank=%d)' % (label, bank))
    print('  访问密码  : %08X' % PASSWORD)
    print('  起始地址  : %d (0x%X)' % (start_word, start_word))
    print('  数据长度  : %d 字 = %d 字节' % (words, len(data)))
    print('  写入数据  : %s' % clean_hex(data.hex()))
    if show_frame:
        print('  载荷      : %s' % hexs(payload))
        print('  报文      : %s' % hexs(frame))
        print('               (LEN=0x%02X=%d, 线上共 %d 字节 = 2*%d+16)'
              % (frame[1], frame[1], len(frame), words))
    print()

    frames = reader.transact(F.CMD['write'], payload, expect=F.CMD['write'],
                             timeout=timeout)
    if not frames:
        print('  结果      : 无应答（超时）✗')
        return False, False
    body = frames[0]['payload']
    code, ok = write_status(body)
    print('  应答      : %s' % (hexs(body) if body else '(空)'))
    print('  应答解读  : %s' % interpret_reply(F.CMD['write'], body))
    print('  结果      : %s' % ('写入已被接受 ✓' if ok else
                                '写入失败（状态码 0x%02X）✗' % (code or 0)))
    return ok, True


def write_and_verify(reader, label, bank, start_word, data, timeout=None,
                     verify=True, attempts=WRITE_ATTEMPTS):
    """写一个区并回读校验，**不成功就重写，直到成功**（最多 attempts 轮）。

    为什么不看写入应答就完事：0x41 这种失败是偶发的，同一帧重发一次往往就过了；
    也遇到过应答说成功但内容没落地的情况。所以一律以**回读**为准。
    """
    for rnd in range(1, attempts + 1):
        if rnd > 1:
            print()
            print('  ── 第 %d/%d 轮重写 %s 区 ──' % (rnd, attempts, label))
        ok, replied = do_write(reader, label, bank, start_word, data,
                               timeout=timeout, show_frame=(rnd == 1))
        if not verify:
            if ok:
                return True
            if not replied:
                return False        # 完全没应答，重写也是白搭
            continue
        got = do_verify(reader, label, bank, start_word, data, timeout=timeout)
        if got:
            return True
        # 一个应答都没有 = 场上没标签，重写 8 轮只是浪费时间，两轮就收手
        if not replied and rnd >= 2:
            print('  连续没应答 —— 多半是场上没有标签，不再重写')
            return False
        print('  第 %d 轮没写进去，重写…' % rnd)
        time.sleep(0.2)
    print('  重写 %d 轮仍未成功 ✗' % attempts)
    return False


def do_verify(reader, label, bank, start_word, expect, timeout=None):
    """回读校验。

    应答里到底有没有前置状态字节没有完全测绘清楚，所以这里在应答载荷中
    搜索期望数据：命中即通过，并报告偏移量；未命中则明确说明无法确认，
    而不是直接判失败。
    """
    hr('回读校验 %s 区' % label)
    words = len(expect) // 2
    frames = reader.transact(F.CMD['read'], F.payload_read(bank, start_word, words,
                                                           PASSWORD),
                             expect=F.CMD['read'], timeout=timeout)
    if not frames:
        print('  无应答（超时）—— 无法校验')
        return None
    body, _ = extract_data(frames)
    print()
    print_hex_block('应答原始', body)

    want = clean_hex(expect.hex())
    got = clean_hex(body.hex())
    print('  期望      : %s' % want)

    off = body.find(expect)
    if off == 0:
        print('  校验      : 一致 ✓')
        return True
    if off > 0:
        print('  实际      : %s' % got)
        print('  校验      : 一致 ✓（应答前有 %d 字节前缀，已跳过）' % off)
        return True

    print('  实际      : %s' % got)
    print('  校验      : 未在应答中找到期望数据 ✗')
    return False


def payload_epc_match(enable: bool, epc: bytes = b'', extra_len: int = None):
    """0x85 Set_Epc_Match payload.

    From LibWinUart::Set_Epc_Match @0x4825a4:

        if (enable == 0) {
            tx[0] = 0;                      // 4825c7
            tx[1] = epc_buf[0] + 1;         // 4825d5  (epc_buf[0] is the length)
            copy epc_buf[1 .. epc_buf[0]+1] to tx[2 ..]   // 4825e8 loop
            LEN = epc_buf[0] + 6;           // 482637
        } else {
            tx[0] = 1;                      // 482651
            LEN = 4;                        // 482658  -> payload is just [1]
        }

    So the payload is `[enable][epc_len][epc bytes...]` with LEN = epc_len + 5.
    """
    if not enable:
        # "clear the filter": still carries the old length byte
        n = len(epc) if extra_len is None else extra_len
        return bytes([0, (n + 1) & 0xFF]) + bytes(epc)
    return bytes([1]) + bytes([len(epc) & 0xFF]) + bytes(epc)


# ---------------------------------------------------------------- 诊断
def restart_reader(reader, settle=0.3):
    """连接后复位读写器 —— 原应用在 UartThread::Entry 里就是这么做的。

    UartThread::Entry @0x4603c0:
        4605ce  call LibWinUart::Restart_Reader()      ; 0x70 帧
        4606f0  mov  ecx, 0x12c                        ; 300 ms
        4606fc  call <Sleep>
        46070f  call LibWinUart::Check_Serial_Com()

    也就是「打开串口 -> 连接成功 -> 复位成功」，之后才开始正常收发。
    不复位的话读写器可能停在未知状态，后续的内存读/写就会完全不回应答。
    """
    reader.send_only(F.CMD['restart'])
    print('  已发送复位命令 (0x70)，等待读写器重启')
    time.sleep(settle)          # 原应用等 300 ms (0x4606f0: mov ecx,0x12c)
    try:
        reader.ser.reset_input_buffer()
    except Exception:
        pass

    # 原应用复位后只等 300 ms 就继续；这里再轮询确认读写器真的回来了，
    # 避免个别固件重启更慢时后面的命令全部丢在启动过程中。
    deadline = time.time() + max(3.0, settle * 10)
    attempt = 0
    while time.time() < deadline:
        attempt += 1
        frames = reader.transact(F.CMD['version'], expect=F.CMD['version'],
                                 timeout=0.6, retries=0)
        if frames:
            body = frames[0]['payload']
            print('  复位完成（第 %d 次探测），固件 %s' % (attempt, decode_version(body)))
            return True
        time.sleep(0.3)
    print('  复位后一直读不到版本 —— 可能重启更慢，或该命令不被支持。')
    print('  可以加大等待：--restart-wait 1.5；或跳过复位：--no-restart')
    return False


def gui_is_running():
    """Windows: 原 GUI 是否在跑？它开着就会独占 COM 口。"""
    if os.name != 'nt':
        return []
    names = []
    try:
        import ctypes
        from ctypes import wintypes

        TH32CS_SNAPPROCESS = 0x00000002
        MAX_PATH = 260

        class PROCESSENTRY32(ctypes.Structure):
            _fields_ = [('dwSize', wintypes.DWORD),
                        ('cntUsage', wintypes.DWORD),
                        ('th32ProcessID', wintypes.DWORD),
                        ('th32DefaultHeapID', ctypes.POINTER(ctypes.c_ulong)),
                        ('th32ModuleID', wintypes.DWORD),
                        ('cntThreads', wintypes.DWORD),
                        ('th32ParentProcessID', wintypes.DWORD),
                        ('pcPriClassBase', ctypes.c_long),
                        ('dwFlags', wintypes.DWORD),
                        ('szExeFile', ctypes.c_char * MAX_PATH)]

        k32 = ctypes.windll.kernel32
        snap = k32.CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0)
        if snap == -1:
            return []
        entry = PROCESSENTRY32()
        entry.dwSize = ctypes.sizeof(PROCESSENTRY32)
        ok = k32.Process32First(snap, ctypes.byref(entry))
        while ok:
            exe = entry.szExeFile.decode('latin1', 'replace').lower()
            if any(k in exe for k in ('rfid_main_pannel', 'rfid_main_panel',
                                      'rfid_reader')):
                names.append('%s (pid %d)' % (exe, entry.th32ProcessID))
            ok = k32.Process32Next(snap, ctypes.byref(entry))
        k32.CloseHandle(snap)
    except Exception:
        pass
    return names


def serial_hint(port, exc):
    """给串口打开失败一个人话解释。Windows / macOS / Linux 都适用。"""
    s = str(exc)
    if any(k in s for k in ('PermissionError', '拒绝访问', 'Access is denied',
                            'being used by another process',
                            'Resource busy', 'Errno 16', 'Device or resource busy')):
        lines = ['串口 %s 被其它程序占用。' % port]
        gui = gui_is_running()
        if gui:
            lines.append('检测到原应用还在运行：%s' % '、'.join(gui))
            lines.append('它开着的时候会独占串口，必须先关掉：')
            lines.append('    Stop-Process -Name rfid_main_pannel'
                         '        (PowerShell)')
            lines.append('    或  taskkill /IM rfid_main_pannel.exe /F')
        else:
            lines.append('先找出是谁占着它：')
            if os.name == 'nt':
                lines.append('    串口调试助手 / 另一个 python 进程都可能占着')
            else:
                lines.append('    lsof %s        # macOS / Linux' % port)
            lines.append('原厂 GUI（rfid_main_pannel.exe）也会独占串口，'
                         '关了再试。')
        return lines
    if any(k in s for k in ('could not open port', 'FileNotFoundError',
                            'No such file', 'No such file or directory')):
        lines = ['找不到串口 %s。' % port,
                 '用 --list-ports 看当前有哪些串口。']
        if os.name != 'nt':
            lines += [
                'macOS 上 USB 转串口一般是 /dev/cu.usbserial-*、'
                '/dev/cu.wchusbserial-*、/dev/cu.usbmodem*；',
                'Linux 上一般是 /dev/ttyUSB0、/dev/ttyACM0。',
                'macOS 要选 cu.* 而不是 tty.*（tty.* 会等 DCD 信号）。',
                '',
                '线插上了却一个设备节点都没有 = 缺驱动。macOS 从 Monterey 起',
                '连自带的 FTDI 驱动都删了，所以基本上都要自己装：',
                '  CH340/CH341 -> brew install --cask wch-ch34x-usb-serial-driver',
                '                 （或 wch-ic.com 下 CH34xVCPDriver，装完到'
                '「系统设置→隐私与安全性」允许加载，再重启）',
                '  CP210x      -> 装 Silicon Labs 的 CP210x VCP 驱动',
                '  FTDI        -> 装 FTDI 的 VCP 驱动（macOS 12 起不再自带）',
                '  CDC-ACM     -> 免驱，设备名是 /dev/cu.usbmodem*',
            ]
        return lines
    return None


def set_beep(reader, on, timeout_air):
    """0x7A Set_Beep_status([arg1])，01 开 / 00 关。

    读写器每盘存到一个标签就响一声，连读/反复写的时候很吵，所以给个开关。
    """
    print('  蜂鸣器 = %s' % ('开' if on else '关'))
    frames = reader.transact(F.CMD['set_beep'], bytes([1 if on else 0]),
                             expect=F.CMD['set_beep'], timeout=timeout_air,
                             retries=0)
    return bool(frames)


def init_reader(reader, a, timeout_air):
    """把读写器设成能读到标签的状态（工作天线、功率、蜂鸣器），对齐 GUI 的行为。"""
    did = False
    if a.antenna is not None:
        print('  设置工作天线 = %d' % a.antenna)
        reader.transact(F.CMD['set_work_ant'], bytes([a.antenna]),
                        expect=F.CMD['set_work_ant'], timeout=timeout_air, retries=0)
        did = True
    if a.power is not None:
        print('  设置天线功率 = %d' % a.power)
        reader.transact(F.CMD['set_ant_power'], bytes([a.power]),
                        expect=F.CMD['set_ant_power'], timeout=timeout_air, retries=0)
        did = True
    if a.mute or a.beep_on:
        set_beep(reader, a.beep_on, timeout_air)
        did = True
    return did


def arm_epc_match(reader, epc, timeout_air, label=''):
    """写入前设置 EPC 匹配，锁定要写的那个标签。

    原 GUI 的流程是「盘存选标签 -> Set_Epc_Match -> 读/写」，写命令本身不带
    目标 EPC，所以不设匹配时读写器可能找不到要写的标签而完全不回应答。
    """
    payload = payload_epc_match(True, epc)
    frames = reader.transact(F.CMD['set_epc_match'], payload,
                             expect=F.CMD['set_epc_match'],
                             timeout=timeout_air, retries=0)
    ok = bool(frames)
    print('  EPC 匹配 %s: %s  (payload=%s)' % (
        label, '已设置 ✓' if ok else '无应答', hexs(payload)))
    return ok


def clear_epc_match(reader, timeout_air):
    reader.transact(F.CMD['set_epc_match'], payload_epc_match(False, b''),
                    expect=F.CMD['set_epc_match'], timeout=timeout_air, retries=0)


def probe_epc(reader, timeout_air, tries=2):
    """读一次 EPC 区，确认场上有标签并拿到它的完整 EPC。返回 EPC 字节或 None。

    这是写标签前唯一的"空中检查"。**不要用实时盘存（0x80/0x92/0x91）那套**：
    它会让读写器持续往串口刷空盘存帧（实测几百帧），既慢又刷屏，而且拿到的
    EPC 还可能被截断。直接读 EPC 区只要一来一回，快且拿到的就是完整 EPC。

    读应答是**合并记录**：
        00 01 <len> <PC 2字节> <EPC 12字节> <CRC 2字节> <TID 12字节> …
    所以 EPC 在**偏移 5**。（以前这里写的是 body[:12]，取到的是
    `00 01 1C 30 …` 这种头部字节，拿去设匹配会匹配不上。）

    ⚠ 也不能改用 0x8A 自定义盘存：实测 0x8A 之后紧接着写，读写器会稳定回
    0x41 并且写不进去。
    """
    hr('空中检查：读 EPC 区')
    for i in range(tries):
        frames = reader.transact(F.CMD['read'], F.payload_read(1, 2, 6, PASSWORD),
                                 expect=F.CMD['read'], timeout=timeout_air,
                                 retries=0)
        if frames:
            body = frames[0]['payload']
            print('  应答      : %s' % (hexs(body) if body else '(空)'))
            if len(body) >= 5 + 12:
                epc = body[5:5 + 12]
                if any(epc):
                    print('  当前 EPC  : %s' % clean_hex(epc.hex()))
                    return epc
        if i + 1 < tries:
            time.sleep(0.2)
    print('  读不到 EPC —— 场上可能没有标签')
    return None


def report_air_failure():
    print()
    print('排查建议：')
    print('  1. 确认场上有 6C 标签，且已贴近天线（写操作必须有标签在场才可能应答）')
    print('  2. 确认标签没有被人为锁定，访问密码确实是 00000000')
    print('  3. 用原 GUI（rfid_main_pannel.exe）的「读写标签」页试同样参数：')
    print('     EPC 区 起始地址 2；TID 区 起始地址 0；长度 6；密码 00000000')
    print('     如果 GUI 也不行，说明是这个标签/这一步本身不成立')
    print('  4. TID 区在多数 Gen2 标签上是只读的，写 TID 常常没有应答')
    print('  5. 增大等待时间再看：加 --air-timeout 3')
    print('  6. 天线功率过低也可能读不到标签：加 --power 30 --antenna 1 试试')


def cmd_diagnose(reader, a, air_timeout):
    """把每一步的原始应答都打出来，用来判断卡在哪一环。"""
    hr('诊断：逐步执行并打印原始应答')

    print('\n[0] 复位读写器 (0x70) —— 原应用连接后就会发这一条')
    reader.send_only(F.CMD['restart'])
    time.sleep(0.3)
    try:
        reader.ser.reset_input_buffer()
    except Exception:
        pass

    print('\n[1] 读固件版本 (0x72)')
    reader.transact(F.CMD['version'], expect=F.CMD['version'], timeout=air_timeout)

    print('\n[2] 读工作天线 (0x75)')
    reader.transact(F.CMD['get_work_ant'], expect=F.CMD['get_work_ant'],
                    timeout=air_timeout)

    print('\n[3] 读天线功率 (0x77)')
    reader.transact(F.CMD['get_ant_power'], expect=F.CMD['get_ant_power'],
                    timeout=air_timeout)

    print('\n[4] 读温度 (0x7B)')
    reader.transact(F.CMD['temperature'], expect=F.CMD['temperature'],
                    timeout=air_timeout)

    print('\n[5] 清空标签缓存 (0x93)')
    reader.transact(F.CMD['clear_tag_buffer'], expect=F.CMD['clear_tag_buffer'],
                    timeout=air_timeout)

    print('\n[6] 启动缓存盘存 (0x80, 天线 1)')
    reader.transact(F.CMD['buffered_inventory'], bytes([1]),
                    expect=F.CMD['buffered_inventory'], timeout=air_timeout)
    time.sleep(1.0)

    print('\n[7] 读标签数量 (0x92)')
    reader.transact(F.CMD['get_tag_count'], expect=F.CMD['get_tag_count'],
                    timeout=air_timeout)

    print('\n[8] 取标签缓存 (0x90)')
    reader.transact(F.CMD['get_tag_buffer'], expect=F.CMD['get_tag_buffer'],
                    timeout=air_timeout)

    print('\n[9] 停止盘存 (0x8C)')
    reader.send_only(F.CMD['stop_inventory'])
    time.sleep(0.2)

    print('\n[10] 读 EPC 区 bank=1 起始=2 长度=6 (0x81)')
    reader.transact(F.CMD['read'], F.payload_read(1, 2, 6, PASSWORD),
                    expect=F.CMD['read'], timeout=air_timeout)

    print('\n[11] 读 TID 区 bank=2 起始=0 长度=6 (0x81)')
    reader.transact(F.CMD['read'], F.payload_read(2, 0, 6, PASSWORD),
                    expect=F.CMD['read'], timeout=air_timeout)

    print('\n[12] 读 Reserved 区 bank=0 起始=0 长度=4 (0x81)')
    reader.transact(F.CMD['read'], F.payload_read(0, 0, 4, PASSWORD),
                    expect=F.CMD['read'], timeout=air_timeout)

    print('\n[13] 设置 EPC 匹配为空 (0x85)')
    reader.transact(F.CMD['set_epc_match'], payload_epc_match(True, b''),
                    expect=F.CMD['set_epc_match'], timeout=air_timeout)

    hr('诊断结束')
    print('把上面完整输出发过来即可判断卡在哪一环：')
    print('  * [1]-[4] 有应答  -> 串口链路正常')
    print('  * [6]-[8] 有数据  -> 场上有标签，且读到 EPC')
    print('  * [10]/[11] 有数据 -> 读标签内存正常')
    print('  * [10]/[11] 无应答 但 [6]-[8] 有数据 -> 读写器读得到标签但读内存失败')
    print('  * [6]-[8] 也没有   -> 场上没标签 / 天线功率不足 / 天线没选对')
    return 0


# ---------------------------------------------------------------- 主流程
def _run(argv=None):
    ap = argparse.ArgumentParser(
        description='通过 RS232 连接读写器，对 6C 标签 EPC 区和 TID 区各写入一次',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog='示例:\n'
               '  python tag_write_epc_tid.py\n'
               '  python tag_write_epc_tid.py --port COM4 --yes\n'
               '  python tag_write_epc_tid.py --port COM4 --diagnose\n'
               '  python tag_write_epc_tid.py --port COM4 --yes \\\n'
               '      --epc E28069150000502008B25C75 --tid E28069152000502008B25C75\n'
               '  python tag_write_epc_tid.py --port COM4 --yes --antenna 1 --power 30 \\\n'
               '      --epc AABBCCDDEEFF112233445566 --tid 112233445566778899AABBCC\n'
               '  python tag_write_epc_tid.py --dry-run\n'
               '\n'
               '数据长度由 --epc/--tid 的 Hex 串自动推导，EPC 与 TID 可以不一样长。\n')
    ap.add_argument('--port', help='串口，例如 COM4 / /dev/cu.usbserial-1420；'
                                   '不填就自动挑 USB 转串口适配器（不会卡住等你输入）')
    ap.add_argument('--baud', type=int, default=BAUD, help='波特率（默认 %d）' % BAUD)
    ap.add_argument('--addr', type=lambda v: int(v, 0), default=ADDR,
                    help='读写器 RS-485 地址（默认 0）')
    ap.add_argument('--epc', default=None, help='EPC 数据（Hex，默认 %s）' % DEFAULT_EPC)
    ap.add_argument('--tid', default=None, help='TID 数据（Hex，默认 %s）' % DEFAULT_TID)
    ap.add_argument('--epc-start', type=lambda v: int(v, 0), default=EPC_START_WORD,
                    help='EPC 起始地址（字，默认 %d）' % EPC_START_WORD)
    ap.add_argument('--tid-start', type=lambda v: int(v, 0), default=TID_START_WORD,
                    help='TID 起始地址（字，默认 %d）' % TID_START_WORD)
    ap.add_argument('--words', type=int, default=WORDS,
                    help='仅用于提示的参考长度（字，默认 %d）；实际长度由数据推导' % WORDS)
    ap.add_argument('--match-epc', dest='match_epc', action='store_true',
                    default=True,
                    help='写入前先按 EPC 设置匹配（默认开启，对齐原 GUI 流程）')
    ap.add_argument('--no-match', dest='match_epc', action='store_false',
                    help='写入前不设置 EPC 匹配')
    ap.add_argument('--epc-match', default=None, metavar='HEX',
                    help='用于 0x85 匹配的【完整】EPC（Hex）。盘存记录里的 EPC '
                         '可能被截断，匹配值不对会让读写器对内存命令完全不回应答，'
                         '所以已知完整 EPC 时务必显式给出。')
    ap.add_argument('--antenna', type=int, default=None,
                    help='写入前先设置工作天线（例如 1），对齐 GUI 的连接初始化')
    ap.add_argument('--power', type=int, default=None,
                    help='写入前先设置天线功率（0-30），对齐 GUI 的连接初始化')
    ap.add_argument('--yes', action='store_true', help='跳过确认提示')
    ap.add_argument('--dry-run', action='store_true', help='只打印报文，不连设备')
    ap.add_argument('--no-verify', action='store_true', help='写入后不回读校验')
    ap.add_argument('--skip-check', action='store_true',
                    help='跳过写入前的盘存/读标签检查')
    ap.add_argument('--no-restart', action='store_true',
                    help='连接后不发送复位命令（原应用默认会复位，见 UartThread::Entry）')
    ap.add_argument('--restart-wait', type=float, default=0.3,
                    help='复位后等待秒数（原应用为 0.3 秒，见 0x4606f0）')
    ap.add_argument('--diagnose', action='store_true',
                    help='只做诊断：逐步执行并打印每一步的原始应答，不写入')
    ap.add_argument('--air-timeout', type=float, default=None,
                    help='空中命令的等待秒数（默认取 --timeout 的 3 倍或 1.5 秒）')
    ap.add_argument('--timeout', type=float, default=TIMEOUT,
                    help='串口应答超时秒数（默认 %.1f）' % TIMEOUT)
    ap.add_argument('--list-ports', action='store_true', help='列出可用串口后退出')
    ap.add_argument('--mute', action='store_true',
                    help='关掉读写器蜂鸣器（0x7A 00）——每读到一次标签就响一声，'
                         '反复写的时候很吵')
    ap.add_argument('--beep-on', action='store_true',
                    help='打开读写器蜂鸣器（0x7A 01），恢复默认')
    ap.add_argument('--log', metavar='FILE',
                    help='把完整输出同时写入文件（诊断时方便把日志发出来）')
    a = ap.parse_args(argv)

    if a.list_ports:
        ports = list_ports()
        if not ports:
            print('未发现串口。')
        for dev, desc in ports:
            print('%-30s %s' % (dev, desc))
        return 0

    # ---- 参数输入 -------------------------------------------------------
    hr('参数')
    for flag, val in (('--epc', a.epc), ('--tid', a.tid)):
        if val is not None and not clean_hex(val or ''):
            print('参数错误：%s 传了空值。要么省略该参数用默认值，要么给合法 Hex。'
                  % flag, file=sys.stderr)
            return 2

    epc_text = a.epc
    if epc_text is None and not a.dry_run and not a.diagnose:
        epc_text = input('EPC 数据 (Hex, 回车用默认 %s):\n> ' % DEFAULT_EPC).strip() \
            or DEFAULT_EPC
    epc_text = epc_text or DEFAULT_EPC
    tid_text = a.tid
    if tid_text is None and not a.dry_run and not a.diagnose:
        tid_text = input('TID 数据 (Hex, 回车用默认 %s):\n> ' % DEFAULT_TID).strip() \
            or DEFAULT_TID
    tid_text = tid_text or DEFAULT_TID

    try:
        epc_data = parse_hex(epc_text, 'EPC')
        tid_data = parse_hex(tid_text, 'TID')
        # 数据长度（字）直接由数据本身推导，所以 EPC 和 TID 可以不一样长。
        # --words 只在没给数据时作为默认值使用。
        epc_words = words_of(epc_data, 'EPC 数据')
        tid_words = words_of(tid_data, 'TID 数据')
    except ValueError as e:
        print('参数错误：%s' % e, file=sys.stderr)
        return 2

    if a.words != WORDS:
        # 用户显式指定了 --words：长度对不上就提示，但不阻断
        # （EPC 与 TID 本来就可以不一样长）。
        note = []
        if epc_words != a.words:
            note.append('EPC 是 %d 字' % epc_words)
        if tid_words != a.words:
            note.append('TID 是 %d 字' % tid_words)
        if note:
            print('  提示: --words %d 与数据实际长度不一致（%s），'
                  '已按数据长度发送。' % (a.words, '，'.join(note)))

    print('  EPC : %d 字 (%d 字节)  %s' % (
        epc_words, len(epc_data), clean_hex(epc_data.hex())))
    print('  TID : %d 字 (%d 字节)  %s' % (
        tid_words, len(tid_data), clean_hex(tid_data.hex())))

    hr('对应原应用「标签测试 → 标签操作 → 读写标签 → 6C」页的设置')
    print('  访问密码(HEX)   : %08X' % PASSWORD)
    print('  起始地址(HEX)   : EPC 区 %X    TID 区 %X'
          % (a.epc_start, a.tid_start))
    print('  数据长度(DEC)   : EPC 区 %d 字   TID 区 %d 字' % (epc_words, tid_words))
    print('  写入数据(HEX)   : 见下')
    print()
    print('  注意：原应用该页的标签是「数据长度(DEC)」——十进制「字」数，')
    print('        所以 6 字 = 12 字节 = 24 个 Hex 字符。起始地址才是 HEX。')
    print()

    # ---- 预先展示两个报文 ------------------------------------------------
    hr('将要发送的报文')
    epc_payload = F.payload_write(EPC_BANK, a.epc_start, epc_words, epc_data, PASSWORD)
    tid_payload = F.payload_write(TID_BANK, a.tid_start, tid_words, tid_data, PASSWORD)
    epc_frame = F.build_frame(F.CMD['write'], epc_payload, addr=a.addr)
    tid_frame = F.build_frame(F.CMD['write'], tid_payload, addr=a.addr)
    print('  1) EPC 区  起始=%d 长度=%d字  %s' % (a.epc_start, epc_words, hexs(epc_frame)))
    print('  2) TID 区  起始=%d 长度=%d字  %s' % (a.tid_start, tid_words, hexs(tid_frame)))

    if a.dry_run:
        print()
        print('--dry-run：未连接设备，退出。')
        return 0

    # ---- 确认 -----------------------------------------------------------
    if not a.yes and not a.diagnose:
        print()
        print('注意：写 TID 区通常需要标签支持（多数 Gen2 标签的 TID 是只读的），')
        print('      访问密码非 0 时还需先对 Reserved 区解锁。')
        ans = input('确认执行？(yes/N) > ').strip().lower()
        if ans not in ('y', 'yes'):
            print('已取消。')
            return 0

    # ---- 连接 -----------------------------------------------------------
    hr('连接读写器')
    port = pick_port(a.port)
    try:
        import serial
    except ImportError:
        print('需要 pyserial：pip install pyserial', file=sys.stderr)
        return 3
    try:
        ser = serial.Serial(port, baudrate=a.baud, bytesize=8, parity='N',
                            stopbits=1, timeout=0.1)
    except Exception as e:
        perr = str(e)
        print('打开串口 %s 失败：%s' % (port, e), file=sys.stderr)
        hint = serial_hint(port, e)
        if hint:
            print(file=sys.stderr)
            for line in hint:
                print('  ' + line, file=sys.stderr)
        return 3
    time.sleep(0.2)
    ser.reset_input_buffer()
    air_timeout = a.air_timeout if a.air_timeout else max(1.5, a.timeout * 3)
    print('  %s  %d 8N1  串口超时 %.1fs  空中命令超时 %.1fs' % (
        port, a.baud, a.timeout, air_timeout))

    reader = Reader(ser, addr=a.addr, timeout=a.timeout)
    rc = 0
    try:
        # ---- 复位读写器（原应用连接后的预处理）--------------------------
        # restart_reader() 内部已经用 0x72 轮询确认过链路了，所以复位之后
        # 不必再单独做一次「链路检查」—— 那是同一帧读两遍。
        if not a.no_restart:
            hr('复位读写器（对齐原应用的连接预处理）')
            if not restart_reader(reader, settle=a.restart_wait):
                rc = 4
        else:
            hr('链路检查：读固件版本')
            frames = reader.transact(F.CMD['version'], expect=F.CMD['version'])
            if frames:
                print('  固件版本  : %s' % decode_version(frames[0]['payload']))
            else:
                print('  读版本无应答 —— 请检查串口号、波特率、接线（TX/RX 是否交叉）。')
                print('  仍将继续尝试写入。')
                rc = 4

        # ---- 诊断模式：只看不应答，不写入 -------------------------------
        if a.diagnose:
            if a.antenna is not None or a.power is not None or a.mute or a.beep_on:
                hr('初始化射频参数')
                init_reader(reader, a, air_timeout)
            return cmd_diagnose(reader, a, air_timeout)

        # ---- 初始化：工作天线 / 功率（对齐 GUI 的连接后设置）-----------
        if a.antenna is not None or a.power is not None or a.mute or a.beep_on:
            hr('初始化射频参数')
            init_reader(reader, a, air_timeout)

        # ---- 空中检查：场上有没有标签 -----------------------------------
        # 一次 0x81 读就够：既确认有标签，又拿到完整 EPC 用于设匹配。
        tag_epc = None
        if not a.skip_check:
            tag_epc = probe_epc(reader, air_timeout)
            if tag_epc is None:
                print()
                print('  ⚠ 没读到标签。写操作必须有标签在场才能应答，')
                print('    如果现在继续，大概率两个写入都会失败。')
                print('    可以试：--antenna 1 --power 30 提高读到的机会，')
                print('    或先跑 python rfidctl.py --port %s info 看看链路。' % port)
                if not a.yes:
                    ans = input('  仍要继续？(y/N) > ').strip().lower()
                    if ans not in ('y', 'yes'):
                        print('已取消。')
                        return 0
                else:
                    print('    仍按 --yes 继续尝试。')

        # ---- 设置 EPC 匹配（原 GUI 的关键一步）--------------------------
        if a.match_epc:
            hr('设置 EPC 匹配（锁定目标标签）')
            # 优先级：
            #   1. --epc-match 显式指定的完整 EPC（最可靠）
            #   2. 盘存记录里读到的 EPC
            #   3. 退化为空匹配
            # 注意：盘存记录里的 EPC 可能被截断。实测某标签 PC=0x3000 声明
            # EPC 为 12 字节，但 0x90 应答只带回 10 字节，用那 10 字节去设匹配
            # 会**匹配不上**，读写器随后对内存命令完全不回应答。所以已知完整
            # EPC 时一定要用 --epc-match 显式给出。
            if a.epc_match:
                match_target = parse_hex(a.epc_match, '--epc-match')
                src = '命令行 --epc-match'
            elif tag_epc and len(tag_epc) >= 12:
                match_target = tag_epc
                src = '盘存记录（注意可能被截断）'
            else:
                match_target = b''
                src = '空匹配（不限定目标）'
            print('  匹配目标 (%s): %s' % (
                src, clean_hex(match_target.hex()) if match_target else '(空)'))
            arm_epc_match(reader, match_target, air_timeout)
            print('  说明：匹配值不对时，读写器会对内存命令完全不回应答。')

        # ---- 操作 1：写 EPC 区 ------------------------------------------
        # 开场就没读到标签的话，别指望重写能变出来，两轮就收手
        tries = WRITE_ATTEMPTS if tag_epc else 2
        ok1 = write_and_verify(reader, 'EPC', EPC_BANK, a.epc_start, epc_data,
                              timeout=air_timeout, verify=not a.no_verify,
                              attempts=tries)

        # EPC 变了，匹配得跟着更新，否则后面写 TID 锁定的还是旧 EPC
        if a.match_epc and ok1:
            new_epc = probe_epc(reader, air_timeout, tries=1)
            if new_epc and new_epc != tag_epc:
                print('\n  EPC 已变，更新匹配')
                arm_epc_match(reader, new_epc, air_timeout)

        # ---- 操作 2：写 TID 区 ------------------------------------------
        ok2 = write_and_verify(reader, 'TID', TID_BANK, a.tid_start, tid_data,
                              timeout=air_timeout, verify=not a.no_verify,
                              attempts=tries)

        if a.match_epc:
            clear_epc_match(reader, air_timeout)

        hr('完成')
        print('  EPC 区：%s' % ('已执行' if ok1 else '失败'))
        print('  TID 区：%s' % ('已执行' if ok2 else '失败'))
        if not (ok1 and ok2):
            report_air_failure()
            rc = 1
    finally:
        ser.close()
    return rc


def main(argv=None):
    """入口：给 _run 加上 --log 的取流与收尾。"""
    argv = list(sys.argv[1:] if argv is None else argv)
    logf = None
    log_path = None
    if '--log' in argv:
        i = argv.index('--log')
        if i + 1 < len(argv):
            log_path = argv[i + 1]
    real_stdout = sys.stdout
    if log_path:
        try:
            # utf-8-sig so the log opens correctly in Windows Notepad
            logf = open(log_path, 'w', encoding='utf-8-sig')
        except OSError as e:
            print('无法写入日志文件 %s: %s' % (log_path, e), file=sys.stderr)
            return 2

        class _Tee:
            def write(self, s):
                real_stdout.write(s)
                logf.write(s)

            def flush(self):
                real_stdout.flush()
                try:
                    logf.flush()
                except ValueError:
                    pass

        sys.stdout = _Tee()
    try:
        return _run(argv)
    finally:
        sys.stdout = real_stdout
        if logf is not None:
            try:
                logf.close()
                print('(日志已写入 %s)' % log_path)
            except Exception:
                pass


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print('\n已中断。')
        raise SystemExit(130)
