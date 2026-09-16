#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""probe_mode.py — 最后一个假设：内存操作需要在盘存运行期间下发。

已确认的状态码含义（真机结论）：
    0x10  命令被接受 / 成功        （0x74/0x76/0x85/0x93 等）
    0x01  空 / 未找到              （盘存推送、标签数量）
    0x36  不支持该命令            （0x45/0x98 任意参数都恒定回 36）
    零应答 0x81 / 0x82 —— 不是"错误"，是**完全没有回应**

所以真正的问题是：这个固件对 6C 内存读写命令不作任何回应。
最后一个没试过的可能性是：内存操作必须在盘存持续运行、标签已被激活的状态下发。
这个脚本就试这个。

用法：
    python probe_mode.py --port COM4 --antenna 1 --power 26
"""
from __future__ import annotations

import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 're'))

import rfidframe as F
import tag_write_epc_tid as T

PASSWORD = 0x00000000


def send_and_listen(reader, ser, cmd, payload, expect, timeout, label):
    frame = F.build_frame(cmd, payload, addr=reader.addr)
    try:
        ser.reset_input_buffer()
        ser.write(frame)
        ser.flush()
    except Exception as e:
        print('  %-34s -> 串口错误 %s' % (label, e))
        return None
    buf = bytearray()
    end = time.time() + timeout
    while time.time() < end:
        try:
            chunk = ser.read(512)
        except Exception as e:
            print('  %-34s -> 读取错误 %s' % (label, e))
            return None
        if chunk:
            buf += chunk
        else:
            time.sleep(0.02)
    frames = [f for f in F.parse_frames(bytes(buf)) if f['ok']]
    mine = [f for f in frames if f['cmd'] == expect]
    others = {}
    for f in frames:
        others[f['cmd']] = others.get(f['cmd'], 0) + 1
    if mine:
        p = mine[0]['payload']
        print('  %-34s -> 应答 %s' % (label, p.hex(' ').upper() if p else '(空)'))
        return p
    if others:
        print('  %-34s -> 只有其它帧: %s' % (
            label, ' '.join('0x%02X x%d' % (c, n) for c, n in sorted(others.items()))))
    else:
        print('  %-34s -> 零应答 ✗' % label)
    return None


def main(argv=None):
    ap = argparse.ArgumentParser(description='试盘存运行期间下发内存命令')
    ap.add_argument('--port',
                    help='串口，例如 COM4 或 /dev/cu.usbserial-1420；'
                         '不填就自动挑 USB 转串口适配器')
    ap.add_argument('--baud', type=int, default=T.BAUD)
    ap.add_argument('--addr', type=lambda v: int(v, 0), default=0)
    ap.add_argument('--antenna', type=int, default=1)
    ap.add_argument('--power', type=int, default=26)
    ap.add_argument('--timeout', type=float, default=1.5)
    ap.add_argument('--log', metavar='FILE')
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
    import serial
    try:
        a.port = T.pick_port(a.port)
        ser = serial.Serial(a.port, baudrate=a.baud, bytesize=8, parity='N',
                            stopbits=1, timeout=0.06)
    except Exception as e:
        print('打开串口失败：%s' % e, file=sys.stderr)
        for line in (T.serial_hint(a.port, e) or []):
            print('  ' + line, file=sys.stderr)
        return 3
    time.sleep(0.2)
    ser.reset_input_buffer()
    reader = T.Reader(ser, addr=a.addr, timeout=a.timeout, verbose=False)

    try:
        T.hr('准备')
        T.restart_reader(reader, settle=0.3)
        reader.transact(F.CMD['set_work_ant'], bytes([a.antenna]),
                        expect=F.CMD['set_work_ant'], timeout=a.timeout,
                        retries=0, quiet=True)
        reader.transact(F.CMD['set_ant_power'], bytes([a.power]),
                        expect=F.CMD['set_ant_power'], timeout=a.timeout,
                        retries=0, quiet=True)

        # ---------- 1. 实时盘存模式下试内存读 ----------
        T.hr('1) 实时盘存 (0x89) 运行中，下发 0x81 读 EPC 区')
        reader.transact(F.CMD['realtime_inventory'], bytes([a.antenna]),
                        expect=F.CMD['realtime_inventory'], timeout=a.timeout,
                        retries=0, quiet=True)
        time.sleep(1.0)
        send_and_listen(reader, ser, F.CMD['read'],
                        F.payload_read(1, 2, 6, PASSWORD), F.CMD['read'],
                        a.timeout, '0x81 read (实时盘存中)')
        send_and_listen(reader, ser, F.CMD['read'],
                        F.payload_read(2, 0, 6, PASSWORD), F.CMD['read'],
                        a.timeout, '0x81 read TID (实时盘存中)')
        reader.send_only(F.CMD['stop_inventory'])
        time.sleep(0.8)

        # ---------- 2. 缓存盘存运行中试内存读 ----------
        T.hr('2) 缓存盘存 (0x80) 运行中，下发 0x81 读 EPC 区')
        reader.transact(F.CMD['clear_tag_buffer'],
                        expect=F.CMD['clear_tag_buffer'], timeout=a.timeout,
                        retries=0, quiet=True)
        reader.transact(F.CMD['buffered_inventory'], bytes([a.antenna]),
                        expect=F.CMD['buffered_inventory'], timeout=a.timeout,
                        retries=0, quiet=True)
        time.sleep(1.2)
        send_and_listen(reader, ser, F.CMD['read'],
                        F.payload_read(1, 2, 6, PASSWORD), F.CMD['read'],
                        a.timeout, '0x81 read (缓存盘存中)')
        send_and_listen(reader, ser, F.CMD['write'],
                        F.payload_write(1, 2, 6,
                                        bytes.fromhex('E28069150000402008B8F478'),
                                        PASSWORD),
                        F.CMD['write'], a.timeout, '0x82 write (缓存盘存中)')
        reader.send_only(F.CMD['stop_inventory'])
        time.sleep(0.8)

        # ---------- 3. 先设匹配，再开盘存，再读 ----------
        T.hr('3) 先 0x85 设匹配，再实时盘存，再读')
        epc = bytes.fromhex('E28069150000402008B8')
        reader.transact(F.CMD['set_epc_match'], T.payload_epc_match(True, epc),
                        expect=F.CMD['set_epc_match'], timeout=a.timeout,
                        retries=0, quiet=True)
        reader.transact(F.CMD['realtime_inventory'], bytes([a.antenna]),
                        expect=F.CMD['realtime_inventory'], timeout=a.timeout,
                        retries=0, quiet=True)
        time.sleep(1.0)
        send_and_listen(reader, ser, F.CMD['read'],
                        F.payload_read(1, 2, 6, PASSWORD), F.CMD['read'],
                        a.timeout, '0x81 read (匹配+盘存中)')
        reader.send_only(F.CMD['stop_inventory'])
        time.sleep(0.5)
        reader.transact(F.CMD['set_epc_match'], T.payload_epc_match(False, b''),
                        expect=F.CMD['set_epc_match'], timeout=a.timeout,
                        retries=0, quiet=True)

        T.hr('状态码小结（本轮全部真机观测）')
        print('  0x10 = 接受/成功    0x01 = 空/未找到    0x36 = 不支持')
        print('  0x81 / 0x82 = 完全无回应（任何模式下）')
        return 0
    finally:
        ser.close()


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print('\n已中断。')
        raise SystemExit(130)
