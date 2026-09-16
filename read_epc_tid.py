#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""read_epc_tid.py — 一条命令查清标签的 EPC 和 TID 并打印出来。

用读写器的「自定义盘存」(0x8A) + 0x53 应答，**一次拿到 EPC 和 TID**，
不用分两次读内存，所以很快（一条命令 + 一次应答）。

一次 0x8A 查询会回来 4 个帧（通常是一整块 96 字节，约 120~220 ms）：
    A0 04 00 8A 10 C2      0x8A 的 ACK，载荷 10 = 已接受
    A0 28 00 53 ...        数据帧 #1，载荷 37 字节，含 EPC + TID
    A0 28 00 53 ...        数据帧 #2，同样的 EPC/TID，尾部字节不同
    A0 04 00 53 12 F7      结束帧，载荷只有 0x12 —— 别把它当成数据！

0x53 数据载荷布局（由真机抓包定位，存档在 re/capture53.hex）：
    payload[0]      = 0x01
    payload[1]      = 0x30
    payload[2]      = 0x00
    payload[3:15]   = EPC          12 字节
    payload[15:17]  = CRC
    payload[17]     = 0x0C         长度标记 = 12
    payload[18:30]  = TID          12 字节
    payload[30:]    = 尾部（每次读数变化，可忽略）

用法：
    python read_epc_tid.py                              # 自动挑串口，复位后查询
    python read_epc_tid.py --antenna 1 --power 26       # 顺便指定天线和功率
    python read_epc_tid.py --port COM4
    python read_epc_tid.py --list-ports                 # 看有哪些串口
    python read_epc_tid.py --json                       # 输出 JSON，便于脚本调用
    python read_epc_tid.py --count 5 --mute             # 连读 5 次，先关蜂鸣器
    python read_epc_tid.py --no-restart                 # 跳过复位，快约 1.5 秒

串口不填就自动挑：优先挑 USB 转串口（CH340/CH341/CP210x/FTDI…），
不会弹菜单等你输入。默认**复位**读写器，和原 GUI 连接时的动作一致；
嫌慢可以加 --no-restart。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 're'))

import rfidframe as F
import tag_write_epc_tid as T

EPC_OFF, EPC_LEN = 3, 12
TID_OFF, TID_LEN = 18, 12


def parse_53(payload):
    """从 0x53 载荷里取出 EPC 和 TID。

    返回 (epc, tid, tail)；解析不出来时对应项为 None。
    尾部每次读数会变，单独返回便于调用方忽略。
    """
    if len(payload) < TID_OFF + TID_LEN:
        return None, None, b''
    epc = payload[EPC_OFF:EPC_OFF + EPC_LEN]
    tid = payload[TID_OFF:TID_OFF + TID_LEN]
    tail = payload[TID_OFF + TID_LEN:]
    # 全 0 视为无效
    if not any(epc):
        epc = None
    if not any(tid):
        tid = None
    return epc, tid, tail


def fmt_hex(b, group=4):
    """按组格式化 hex，便于肉眼核对。"""
    if not b:
        return '(无)'
    h = b.hex().upper()
    return ' '.join(h[i:i + group] for i in range(0, len(h), group))


def drain(ser, seconds=0.25):
    try:
        ser.reset_input_buffer()
    except Exception:
        pass
    end = time.time() + seconds
    while time.time() < end:
        try:
            if not ser.read(512):
                time.sleep(0.02)
        except Exception:
            break


def query_once(ser, reader, antenna, timeout, retries=3):
    """发一次自定义盘存，返回 (epc, tid, tail, raw_payload)。

    复位后第一次查询常常只回来一个字节（读写器还没就绪），所以内部自动重试。
    """
    frame = F.build_frame(F.CMD['custom_inventory'], bytes([1, 1, 1, 6]))
    for attempt in range(retries):
        if reader.verbose:
            print('  TX  %s' % frame.hex(' ').upper())
        try:
            ser.reset_input_buffer()
            ser.write(frame)
            ser.flush()
        except Exception:
            time.sleep(0.2)
            continue

        buf = bytearray()
        end = time.time() + timeout
        while time.time() < end:
            try:
                chunk = ser.read(1024)
            except Exception:
                break
            if chunk:
                buf += chunk
                if reader.verbose:
                    print('  RX  %d 字节' % len(chunk))
            # 注意：一次查询会回来好几个 0x53 帧 —— 1~2 个数据帧（带 EPC/TID）
            # 后面还跟着一个结束帧 A0 04 00 53 12 F7（载荷只有 0x12）。
            # 所以不能取最后一个，必须挑「能解出 EPC 的那个」。
            for f in F.parse_frames(bytes(buf)):
                if not f['ok'] or f['cmd'] != 0x53:
                    continue
                epc, tid, tail = parse_53(f['payload'])
                if epc:
                    return epc, tid, tail, f['payload']
            if not chunk:
                time.sleep(0.02)
        if reader.verbose:
            print('  RX 累计 %d 字节，没解出 0x53：%s'
                  % (len(buf), buf.hex(' ').upper() if buf else '(空)'))
        if attempt + 1 < retries:
            time.sleep(0.25)
    return None, None, b'', b''


def main(argv=None):
    ap = argparse.ArgumentParser(
        description='瞬间查清标签的 EPC 和 TID 并打印',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog='示例:\n'
               '  python read_epc_tid.py                       # 自动挑串口，复位后查询\n'
               '  python read_epc_tid.py --antenna 1 --power 26\n'
               '  python read_epc_tid.py --no-restart          # 跳过复位，更快\n'
               '  python read_epc_tid.py --json\n'
               '  python read_epc_tid.py --count 5 --mute\n'
               '  python read_epc_tid.py --list-ports\n')
    ap.add_argument('--port', help='串口，例如 COM4；不填则自动挑一个（不会卡住等你输入）')
    ap.add_argument('--list-ports', action='store_true', help='列出所有串口后退出')
    ap.add_argument('--baud', type=int, default=T.BAUD)
    ap.add_argument('--addr', type=lambda v: int(v, 0), default=0)
    ap.add_argument('--antenna', type=int, default=None,
                    help='工作天线（默认不改动读写器当前设置）')
    ap.add_argument('--power', type=int, default=None,
                    help='天线功率（默认不改动）')
    ap.add_argument('--timeout', type=float, default=2.0, help='等待应答秒数')
    ap.add_argument('--count', type=int, default=1, help='查询次数（默认 1）')
    ap.add_argument('--interval', type=float, default=0.3,
                    help='多次查询之间的间隔秒数')
    ap.add_argument('--json', action='store_true', help='以 JSON 输出')
    ap.add_argument('--verbose', action='store_true', help='打印收发原始帧')
    ap.add_argument('--no-restart', action='store_true',
                    help='跳过复位读写器（0x70）。默认会复位，和原 GUI 连接时一致，'
                         '多花约 1.5 秒；嫌慢可以加这个')
    ap.add_argument('--no-clear-match', action='store_true',
                    help='不发送清除 EPC 匹配的 0x85（默认会清除，见下）')
    ap.add_argument('--mute', action='store_true',
                    help='查询前关掉读写器蜂鸣器（0x7A 00）——每读到一次标签就响一声，'
                         '连读时很吵')
    ap.add_argument('--beep-on', action='store_true',
                    help='查询前打开蜂鸣器（0x7A 01），恢复默认')
    ap.add_argument('--log', metavar='FILE', help='把输出同时写入文件')
    a = ap.parse_args(argv)

    logf = None
    real = sys.stdout
    if a.log:
        logf = open(a.log, 'w', encoding='utf-8-sig')

        class _Tee:
            def write(self, s):
                real.write(s)
                logf.write(s)

            def flush(self):
                real.flush()
                logf.flush()

        sys.stdout = _Tee()
    try:
        return _run(a)
    finally:
        sys.stdout = real
        if logf:
            logf.close()
            print('(日志已写入 %s)' % a.log)


def _run(a):
    try:
        import serial
    except ImportError:
        print('需要 pyserial：pip install pyserial', file=sys.stderr)
        return 3
    if a.list_ports:
        for dev, desc in T.list_ports():
            print('  %-24s %s' % (dev, desc))
        return 0
    port = T.pick_port(a.port, quiet=a.json)
    try:
        ser = serial.Serial(port, baudrate=a.baud, bytesize=8, parity='N',
                            stopbits=1, timeout=0.06)
    except Exception as e:
        print('打开串口 %s 失败：%s' % (port, e), file=sys.stderr)
        for line in (T.serial_hint(port, e) or []):
            print('  ' + line, file=sys.stderr)
        return 3
    time.sleep(0.2)
    ser.reset_input_buffer()
    reader = T.Reader(ser, addr=a.addr, timeout=a.timeout, verbose=a.verbose)

    results = []
    try:
        # 默认复位：原 GUI 连接时就是「打开串口 -> 复位 -> 校验链路」，
        # 跳过这一步读写器可能处于未知状态，后面的空中命令会不应答。
        if not a.no_restart:
            if not a.json:
                print('复位读写器...')
            T.restart_reader(reader, settle=0.3)

        # 关键一步：清掉读写器里可能残留的 EPC 匹配过滤器（0x85 [00 00]）。
        # 0x85 设下的过滤条件会留在读写器里；如果它指向一个场上不存在的 EPC，
        # 有可能把标签挡掉，0x8A 就毫无反应 —— 看上去像「标签不在场」。
        # 这一步只是保险：真正读不到，绝大多数情况是标签不在场或天线/功率不对。
        # 对 0x8A 盘存来说清掉过滤是安全的（对 0x81/0x82 内存读就不一定了）。
        if not a.no_clear_match:
            if a.verbose:
                print('清除 EPC 匹配过滤器 (0x85 [00 00])')
            try:
                ser.reset_input_buffer()
                ser.write(F.build_frame(F.CMD['set_epc_match'], bytes([0, 0])))
                ser.flush()
                time.sleep(0.2)
                ser.read(128)
            except Exception as e:
                if a.verbose:
                    print('  清除失败（忽略）：%s' % e)

        # 蜂鸣器：0x7A Set_Beep_status([arg1])，01 开 / 00 关。
        # 每盘存到一个标签读写器都会响一声，连读时非常吵，所以给个开关。
        if a.mute or a.beep_on:
            want = 0 if a.mute else 1
            try:
                ser.reset_input_buffer()
                ser.write(F.build_frame(F.CMD['set_beep'], bytes([want])))
                ser.flush()
                time.sleep(0.2)
                ser.read(128)
                if a.verbose:
                    print('蜂鸣器 -> %s (0x7A %02X)'
                          % ('关' if want == 0 else '开', want))
            except Exception as e:
                if a.verbose:
                    print('  设置蜂鸣器失败（忽略）：%s' % e)

        if a.antenna is not None:
            ser.reset_input_buffer()
            ser.write(F.build_frame(F.CMD['set_work_ant'], bytes([a.antenna])))
            ser.flush()
            time.sleep(0.3)
            ser.read(128)
        if a.power is not None:
            ser.reset_input_buffer()
            ser.write(F.build_frame(F.CMD['set_ant_power'], bytes([a.power])))
            ser.flush()
            time.sleep(0.3)
            ser.read(128)

        # 复位后第一条命令偶尔会被吞掉，query_once 内部已经带重试，这里不用预热。

        def sweep():
            """跑完 --count 次查询，返回结果列表。"""
            out = []
            for i in range(max(1, a.count)):
                epc, tid, tail, raw = query_once(ser, reader, a.antenna, a.timeout)
                out.append(dict(epc=epc, tid=tid, tail=tail, raw=raw))
                if a.json:
                    pass
                else:
                    if a.count > 1:
                        print('\n--- 第 %d/%d 次 ---' % (i + 1, a.count))
                    if epc is None:
                        print('  没读到标签（或应答里没有 EPC/TID）')
                        if raw:
                            print('  原始载荷: %s' % raw.hex(' ').upper())
                    else:
                        print('  EPC : %s' % fmt_hex(epc))
                        print('        %s' % epc.hex().upper())
                        if tid:
                            print('  TID : %s' % fmt_hex(tid))
                            print('        %s' % tid.hex().upper())
                        else:
                            print('  TID : (应答里没有)')
                        if a.verbose and tail:
                            print('  尾部: %s   (每次读数变化，可忽略)'
                                  % tail.hex(' ').upper())
                if i + 1 < a.count:
                    time.sleep(a.interval)
            return out

        results = sweep()

        if a.json:
            out = []
            for r in results:
                out.append({
                    'epc': r['epc'].hex().upper() if r['epc'] else None,
                    'tid': r['tid'].hex().upper() if r['tid'] else None,
                    'tail': r['tail'].hex().upper() if r['tail'] else None,
                })
            print(json.dumps(out if a.count > 1 else out[0],
                             ensure_ascii=False, indent=2))
            return 0 if results and results[0]['epc'] else 1

        ok = any(r['epc'] for r in results)
        if not ok:
            print()
            print('  读不到标签。依次检查：')
            print('    * 标签是否贴近天线（读写器每读到一次会响一声）')
            print('    * 天线口/功率：python find_tag.py --port %s' % port)
            print('    * 串口是否被原 GUI 占用')
            return 1
        return 0
    finally:
        ser.close()


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print('\n已中断。')
        raise SystemExit(130)
