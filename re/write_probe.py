#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""write_probe.py —— 反复写 EPC 区，统计应答状态码，用来判断写入失败是不是偶发。

用法：
    python re/write_probe.py --port COM4 --antenna 1 --power 26 --rounds 5
"""
from __future__ import annotations

import argparse
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)

import rfidframe as F
import tag_write_epc_tid as T

A = 'E28069150000402008B83072'
B = 'E28069150000502008B25C75'


def read_epc(reader, air):
    frames = reader.transact(F.CMD['read'],
                             F.payload_read(1, 2, 6, 0),
                             expect=F.CMD['read'], timeout=air, retries=1,
                             quiet=True)
    for f in frames:
        if f['cmd'] == F.CMD['read'] and len(f['payload']) >= 17:
            return f['payload'][5:17].hex().upper()
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--port', default='COM4')
    ap.add_argument('--antenna', type=int, default=1)
    ap.add_argument('--power', type=int, default=26)
    ap.add_argument('--rounds', type=int, default=5)
    ap.add_argument('--via-8a', action='store_true',
                    help='复刻 App 的流程：用 0x8A 盘存拿 EPC 再设匹配')
    ap.add_argument('--stop', action='store_true',
                    help='0x8A 之后补发一次 0x8C 停止盘存，看能否解决写失败')
    a = ap.parse_args()

    import serial
    ser = serial.Serial(a.port, baudrate=T.BAUD, bytesize=8, parity='N',
                        stopbits=1, timeout=0.1)
    time.sleep(0.2)
    ser.reset_input_buffer()
    reader = T.Reader(ser, addr=0, timeout=1.0, verbose=False)
    air = 3.0

    try:
        T.restart_reader(reader, settle=0.3)
        reader.transact(F.CMD['set_work_ant'], bytes([a.antenna]),
                        expect=F.CMD['set_work_ant'], timeout=air, retries=0, quiet=True)
        reader.transact(F.CMD['set_ant_power'], bytes([a.power]),
                        expect=F.CMD['set_ant_power'], timeout=air, retries=0, quiet=True)
        print('天线 %d  功率 %d\n' % (a.antenna, a.power))

        target = A
        print('流程: %s\n' % ('0x8A 盘存取 EPC（复刻 App）' if a.via_8a
                              else '0x81 读 EPC 区取 EPC'))
        print('%-5s %-30s %-10s %-30s %s' % ('轮次', '写入值', '状态码', '回读', '结果'))
        print('-' * 92)
        for i in range(a.rounds):
            if a.via_8a:
                # 和 App 一样：0x8A 自定义盘存，从 0x53 里取 EPC
                frames = reader.transact(F.CMD['custom_inventory'],
                                         bytes([1, 1, 1, 6]), timeout=2.0,
                                         retries=1, quiet=True)
                if a.stop:
                    reader.send_only(F.CMD['stop_inventory'])
                    time.sleep(0.3)
                    try:
                        ser.reset_input_buffer()
                    except Exception:
                        pass
                cur = None
                for f in frames:
                    if f['cmd'] == 0x53 and len(f['payload']) >= 30:
                        epc = f['payload'][3:15]
                        if any(epc):
                            cur = epc.hex().upper()
                            break
            else:
                cur = read_epc(reader, air)
            if cur is None:
                print('%-5d 读不到标签' % (i + 1))
                continue
            # 锁定当前标签
            reader.transact(F.CMD['set_epc_match'],
                            bytes([1, len(cur) // 2]) + bytes.fromhex(cur),
                            expect=F.CMD['set_epc_match'], timeout=air,
                            retries=0, quiet=True)
            data = bytes.fromhex(target)
            frames = reader.transact(F.CMD['write'],
                                     F.payload_write(1, 2, len(data) // 2, data, 0),
                                     expect=F.CMD['write'], timeout=air,
                                     retries=0, quiet=True)
            code = '-'
            for f in frames:
                if f['cmd'] == F.CMD['write']:
                    p = f['payload']
                    # 成功时是 21 字节，状态在第 3 字节；失败时只有 1 字节
                    code = ('%02X(21B)' % p[2]) if len(p) >= 3 else ('%02X(1B)' % p[0])
            time.sleep(0.3)
            back = read_epc(reader, air)
            ok = (back == target)
            print('%-5d %-30s %-10s %-30s %s'
                  % (i + 1, target, code, back, '✓ 成功' if ok else '✗ 没写进去'))
            target = B if target == A else A
            time.sleep(0.3)
        # 收尾：清匹配
        reader.transact(F.CMD['set_epc_match'], bytes([0, 0]),
                        expect=F.CMD['set_epc_match'], timeout=air,
                        retries=0, quiet=True)
    finally:
        ser.close()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
