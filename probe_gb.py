#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""probe_gb.py — 读写器对 GB/T 29768 命令有应答，对 6C 内存命令零应答。

已确认（真机，天线 1 @ 功率 26）：
    0x45 GB read          -> 应答 36
    0x98 GB double indent -> 应答 36
    0x81 6C read          -> 零应答
    0x82 6C write         -> 零应答

说明这个固件把 GB/T 29768 放在前面。这个脚本用不同参数试 GB 读写，
看能否真正取回标签内存数据。

GB 命令载荷（来自反汇编提取）：
    0x45 RFID_GbRead(u8, u16, u16, u32)
         payload = [arg1][arg2 LE16][arg3 LE16][arg5 LE32]         LEN=12
    0x46 RFID_GbWrite(u8, u16, u16, char* data, u32)
         payload = [00][pwd LE24][arg1][arg2 LE16][arg3 LE16][data]  LEN=2n+14
    0x98 RFID_GbDoubleIdentify(char*)                              LEN=19
"""
from __future__ import annotations

import argparse
import itertools
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 're'))

import rfidframe as F
import tag_write_epc_tid as T

PASSWORD = 0x00000000


def gb_read_payload(a1, a2, a3, a5=PASSWORD):
    """0x45 的载荷：4 个参数，长度 9 字节 -> LEN 12。"""
    return (bytes([a1 & 0xFF]) + (a2 & 0xFFFF).to_bytes(2, 'little')
            + (a3 & 0xFFFF).to_bytes(2, 'little')
            + (a5 & 0xFFFFFFFF).to_bytes(4, 'little'))


def try_cmd(reader, cmd, payload, label, timeout):
    frames = reader.transact(cmd, payload, expect=cmd, timeout=timeout,
                             retries=0, quiet=True)
    mine = [f for f in frames if f['cmd'] == cmd]
    if mine:
        p = mine[0]['payload']
        shown = p.hex(' ').upper() if p else '(空)'
        print('  %-40s -> 应答 %-3d 字节: %s' % (label, len(p), shown))
        return p
    print('  %-40s -> 零应答 ✗' % label)
    return None


def main(argv=None):
    ap = argparse.ArgumentParser(description='试 GB/T 29768 命令取回标签数据')
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
        T.hr('准备：复位 + 天线/功率')
        T.restart_reader(reader, settle=0.3)
        reader.transact(F.CMD['set_work_ant'], bytes([a.antenna]),
                        expect=F.CMD['set_work_ant'], timeout=a.timeout,
                        retries=0, quiet=True)
        reader.transact(F.CMD['set_ant_power'], bytes([a.power]),
                        expect=F.CMD['set_ant_power'], timeout=a.timeout,
                        retries=0, quiet=True)
        reader.transact(F.CMD['clear_tag_buffer'],
                        expect=F.CMD['clear_tag_buffer'], timeout=a.timeout,
                        retries=0, quiet=True)
        reader.transact(F.CMD['buffered_inventory'], bytes([a.antenna]),
                        expect=F.CMD['buffered_inventory'], timeout=a.timeout,
                        retries=0, quiet=True)
        time.sleep(1.2)

        T.hr('0x98 GB 双重识别（不同首字节）')
        for first in (0, 1, 2, 3):
            payload = bytes([first]) + bytes(16)
            try_cmd(reader, F.CMD['gb_double_identify'], payload,
                    'gb_double_identify(first=%d)' % first, a.timeout)

        T.hr('0x45 GB 读（遍历参数组合）')
        reader.send_only(F.CMD['stop_inventory'])
        time.sleep(0.5)
        try:
            ser.reset_input_buffer()
        except Exception:
            pass
        got = []
        for a1, a2, a3 in itertools.product((0, 1, 2, 3), (0, 2), (2, 4, 6)):
            pl = gb_read_payload(a1, a2, a3)
            p = try_cmd(reader, F.CMD['gb_read'], pl,
                        'gb_read(a1=%d a2=%d a3=%d)' % (a1, a2, a3), a.timeout)
            if p and len(p) > 1:
                got.append((a1, a2, a3, p))
        if got:
            print()
            print('  有数据的组合：')
            for a1, a2, a3, p in got[:10]:
                print('    a1=%d a2=%d a3=%d -> %s' % (a1, a2, a3, p.hex(' ').upper()))

        T.hr('0x81/0x82 再确认一次（6C）')
        try_cmd(reader, F.CMD['read'], F.payload_read(1, 2, 6, PASSWORD),
                '6C read EPC', a.timeout)
        try_cmd(reader, F.CMD['write'],
                F.payload_write(1, 2, 6, b'\x00' * 12, PASSWORD),
                '6C write EPC (全 0，勿在生产标签上跑)', a.timeout)

        T.hr('结论')
        print('  把输出发出来。若 0x45 能取回数据，说明要改用 GB 命令族；')
        print('  若 0x45 只回固定值，说明它只是 ACK，仍未真正读标签内存。')
        return 0
    finally:
        ser.close()


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print('\n已中断。')
        raise SystemExit(130)
