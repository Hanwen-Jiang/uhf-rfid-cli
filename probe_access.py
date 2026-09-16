#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""probe_access.py — 盘存读得到标签，但内存读零应答。逐个排除原因。

已知事实（真机，天线 1 @ 功率 26）：
    * 盘存正常，能读到标签记录（CRC=3000 PC=E280 EPC=69150000402008...）
    * 0x81 读 EPC/TID/USER/Reserved 四个区，全部【零应答】

假设与对应的测试：
    A. 内存访问前必须先 0x85 设置 EPC 匹配（原 GUI 就是这么做的）
       -> 用真实 EPC 设匹配，再读
    B. 该固件对内存操作用的是另一套命令码
       -> 逐个试 0x81/0x82 附近以及 GB 系命令
    C. 标签被锁定 / 访问密码非 0 / 不支持标准读
       -> 若以上都失败，则结论落在标签侧

用法：
    python probe_access.py --port COM4 --antenna 1 --power 26
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


def quiet_read(reader, bank, start, words, timeout):
    frames = reader.transact(F.CMD['read'],
                             F.payload_read(bank, start, words, PASSWORD),
                             expect=F.CMD['read'], timeout=timeout,
                             retries=0, quiet=True)
    return [f for f in frames if f['cmd'] == F.CMD['read']]


def find_tag(reader, antenna, power, timeout, seconds=8.0):
    """盘存并解码出 (EPC, 完整记录)。"""
    reader.transact(F.CMD['set_work_ant'], bytes([antenna]),
                    expect=F.CMD['set_work_ant'], timeout=timeout, retries=0, quiet=True)
    reader.transact(F.CMD['set_ant_power'], bytes([power]),
                    expect=F.CMD['set_ant_power'], timeout=timeout, retries=0, quiet=True)
    reader.transact(F.CMD['clear_tag_buffer'], expect=F.CMD['clear_tag_buffer'],
                    timeout=timeout, retries=0, quiet=True)
    reader.transact(F.CMD['buffered_inventory'], bytes([antenna]),
                    expect=F.CMD['buffered_inventory'], timeout=timeout,
                    retries=0, quiet=True)

    end = time.time() + seconds
    best = None
    while time.time() < end:
        frames = reader.transact(F.CMD['get_tag_buffer'],
                                 expect=F.CMD['get_tag_buffer'],
                                 timeout=timeout, retries=0, quiet=True)
        for f in frames:
            if f['cmd'] != F.CMD['get_tag_buffer']:
                continue
            p = f['payload']
            if len(p) >= 14:
                # payload 看起来是 [状态][CRC 2][PC 2][EPC 8..]
                for off in (1, 0):
                    rec = p[off:off + 12]
                    if len(rec) == 12 and any(rec):
                        epc = rec[2:] if len(rec) > 2 else rec
                        best = (epc, rec)
                        break
            if best:
                break
        if best:
            break
        time.sleep(0.25)
    reader.send_only(F.CMD['stop_inventory'])
    time.sleep(0.4)
    return best


def main(argv=None):
    ap = argparse.ArgumentParser(description='排查内存读零应答的原因')
    ap.add_argument('--port',
                    help='串口，例如 COM4 或 /dev/cu.usbserial-1420；'
                         '不填就自动挑 USB 转串口适配器')
    ap.add_argument('--baud', type=int, default=T.BAUD)
    ap.add_argument('--addr', type=lambda v: int(v, 0), default=0)
    ap.add_argument('--antenna', type=int, default=1)
    ap.add_argument('--power', type=int, default=26)
    ap.add_argument('--timeout', type=float, default=1.2)
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
        T.hr('复位 + 找标签')
        T.restart_reader(reader, settle=0.3)
        found = find_tag(reader, a.antenna, a.power, a.timeout)
        if not found:
            print('  没读到标签，先解决盘存问题。')
            return 1
        epc, rec = found
        print('  标签记录: %s' % rec.hex(' ').upper())
        print('  推算 EPC: %s' % epc.hex().upper())
        time.sleep(0.6)
        try:
            ser.reset_input_buffer()
        except Exception:
            pass

        # ---------- 基线：不设匹配直接读 ----------
        T.hr('测试 A0：不设匹配，直接读 EPC 区')
        r = quiet_read(reader, 1, 2, 6, a.timeout)
        print('  %s' % ('有应答: ' + r[0]['payload'].hex(' ').upper()) if r else '零应答 ✗')

        # ---------- A：先设 EPC 匹配再读 ----------
        T.hr('测试 A：先 0x85 设置 EPC 匹配，再读')
        payload = T.payload_epc_match(True, epc)
        print('  匹配载荷: %s' % payload.hex(' ').upper())
        reader.transact(F.CMD['set_epc_match'], payload,
                        expect=F.CMD['set_epc_match'], timeout=a.timeout,
                        retries=0, quiet=True)
        time.sleep(0.4)
        for bank, name in ((1, 'EPC区'), (2, 'TID区')):
            r = quiet_read(reader, bank, 2 if bank == 1 else 0, 6, a.timeout)
            print('  %-6s %s' % (name,
                                 '有应答: ' + r[0]['payload'].hex(' ').upper()
                                 if r else '零应答 ✗'))
        reader.transact(F.CMD['set_epc_match'], T.payload_epc_match(False, b''),
                        expect=F.CMD['set_epc_match'], timeout=a.timeout,
                        retries=0, quiet=True)

        # ---------- B：试其它命令码 ----------
        T.hr('测试 B：试相关命令码，看固件认哪些')
        CANDIDATES = [
            (0x81, 'read (标准)', lambda: F.payload_read(1, 2, 6, PASSWORD)),
            (0x45, 'GB read', lambda: bytes([1]) + (2).to_bytes(2, 'little')
                + (6).to_bytes(2, 'little') + (0).to_bytes(4, 'little')),
            (0x98, 'GB double identify', lambda: bytes([0]) * 16),
            (0x50, 'get fast EPC', lambda: b''),
            (0x86, 'get EPC match', lambda: b''),
        ]
        for cmd, name, mk in CANDIDATES:
            pl = mk()
            frames = reader.transact(cmd, pl, expect=cmd, timeout=a.timeout,
                                     retries=0, quiet=True)
            mine = [f for f in frames if f['cmd'] == cmd]
            print('  0x%02X %-20s %s' % (
                cmd, name,
                '有应答: ' + mine[0]['payload'].hex(' ').upper() if mine
                else '零应答 ✗'))

        T.hr('结论')
        print('  把上面的输出发出来即可定位。')
        print('  * 若「测试 A」有应答 -> 内存访问必须先设 EPC 匹配')
        print('  * 若「测试 B」某个码有应答 -> 该固件用的是那套命令')
        print('  * 若全部零应答 -> 问题在标签侧（锁定/密码/不支持）')
        return 0
    finally:
        ser.close()


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print('\n已中断。')
        raise SystemExit(130)
