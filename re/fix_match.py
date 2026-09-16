#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""fix_match.py — 查看并清除读写器里可能残留的 EPC 匹配过滤器。

背景：0x85 Set_Epc_Match 设下的过滤条件会留在读写器里。如果它指向一个场上
不存在的 EPC，读写器可能把标签全部过滤掉，表现就是 0x8A / 0x81 完全没反应 ——
看起来像「标签不在场」。

**但这条路径没有被证实。** 当初看到「连续 10 次读不到」，最可能的原因其实是
标签被拿远了（读写器每读到一次就响一声，太吵）；后面这次探测里 0x86 读回来的
匹配本来就是 `00`（未启用），所以「清除匹配让它恢复了」这个因果并不成立。
真正常见的读不到，永远是这两个：标签不在场、天线/功率不对。

用法：
    python re/fix_match.py COM4        # 查看当前匹配 -> 清掉 -> 用 0x8A 验一次
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)

import serial
import rfidframe as F
import tag_write_epc_tid as T
import read_epc_tid as R


def show_match(reader):
    """0x86 Get_Epc_Match -> 看看现在设的是什么。"""
    frames = reader.transact(F.CMD['get_epc_match'],
                             expect=F.CMD['get_epc_match'], timeout=1.5,
                             retries=0, quiet=True)
    for f in frames:
        if f['cmd'] == F.CMD['get_epc_match']:
            p = f['payload']
            print('  当前匹配: %s   (enable=%d, len=%d)'
                  % (p.hex(' ').upper(), p[0], p[1] if len(p) > 1 else -1))
            if len(p) > 2:
                print('             EPC = %s' % p[2:2 + p[1]].hex().upper())
            return p
    print('  0x86 无应答')
    return None


def main():
    port = sys.argv[1] if len(sys.argv) > 1 else 'COM4'
    ser = serial.Serial(port, baudrate=T.BAUD, bytesize=8, parity='N',
                        stopbits=1, timeout=0.06)
    time.sleep(0.2)
    ser.reset_input_buffer()
    reader = T.Reader(ser, addr=0, timeout=1.5, verbose=False)
    try:
        T.hr('1. 复位')
        T.restart_reader(reader, settle=0.3)

        T.hr('2. 清除前的匹配状态')
        show_match(reader)

        T.hr('3. 发 0x85 [enable=0][len=0] 清除')
        frame = F.build_frame(F.CMD['set_epc_match'], bytes([0, 0]))
        print('  报文: %s' % frame.hex(' ').upper())
        for f in reader.transact(F.CMD['set_epc_match'], bytes([0, 0]),
                                 expect=F.CMD['set_epc_match'], timeout=1.5,
                                 retries=0, quiet=True):
            if f['cmd'] == F.CMD['set_epc_match']:
                print('  应答: %s' % f['payload'].hex(' ').upper())
        time.sleep(0.3)

        T.hr('4. 清除后的匹配状态')
        show_match(reader)

        T.hr('5. 设天线 1 / 功率 26，再用 0x8A 验一次')
        for cmd, val in ((F.CMD['set_work_ant'], 1), (F.CMD['set_ant_power'], 26)):
            ser.reset_input_buffer()
            ser.write(F.build_frame(cmd, bytes([val])))
            ser.flush()
            time.sleep(0.3)
            ser.read(128)
        epc, tid, tail, raw = R.query_once(ser, reader, 1, 2.0, retries=3)
        if epc:
            print('  EPC = %s' % epc.hex().upper())
            print('  TID = %s' % (tid.hex().upper() if tid else '(无)'))
            return 0
        print('  还是读不到（原始载荷: %s）'
              % (raw.hex(' ').upper() if raw else '(空)'))
        print('  最常见的原因是标签不在场：先把标签贴近天线，')
        print('  再用 python find_tag.py --port %s 扫一下天线口和功率。' % port)
        return 1
    finally:
        ser.close()


if __name__ == '__main__':
    raise SystemExit(main())
