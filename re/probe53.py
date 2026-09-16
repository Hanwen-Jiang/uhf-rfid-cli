#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""probe53.py — 反复发 0x8A 自定义盘存，把每次收发的原始字节全部打出来。
用来搞清 read_epc_tid.py 里「有时读不到」的真实原因。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import serial
import rfidframe as F
import tag_write_epc_tid as T

PORT = sys.argv[1] if len(sys.argv) > 1 else 'COM4'
N = int(sys.argv[2]) if len(sys.argv) > 2 else 6

ser = serial.Serial(PORT, baudrate=T.BAUD, bytesize=8, parity='N',
                    stopbits=1, timeout=0.05)
time.sleep(0.2)
ser.reset_input_buffer()
reader = T.Reader(ser, addr=0, timeout=2.0, verbose=False)
T.restart_reader(reader, settle=0.3)

# 天线/功率
for cmd, val in (('set_work_ant', 1), ('set_ant_power', 26)):
    ser.reset_input_buffer()
    ser.write(F.build_frame(F.CMD[cmd], bytes([val])))
    ser.flush()
    time.sleep(0.3)
    ser.read(128)

frame = F.build_frame(F.CMD['custom_inventory'], bytes([1, 1, 1, 6]))
print('TX frame = %s' % frame.hex(' ').upper())
print()

saved = None
for i in range(N):
    ser.reset_input_buffer()
    ser.write(frame)
    ser.flush()
    t0 = time.time()
    bursts = []
    cur = bytearray()
    while time.time() - t0 < 2.0:
        c = ser.read(1024)
        if c:
            if not cur:
                bursts.append([time.time() - t0, bytearray()])
            cur = bursts[-1][1]
            cur += c
        else:
            cur = bytearray()
            time.sleep(0.01)
    print('=== 第 %d 次 ===' % (i + 1))
    for dt, b in bursts:
        print('  +%.3fs  %3d 字节  %s' % (dt, len(b), b.hex(' ').upper()))
        for f in F.parse_frames(bytes(b)):
            print('        -> cmd=0x%02X ok=%s len(payload)=%d payload=%s'
                  % (f['cmd'], f['ok'], len(f['payload']),
                     f['payload'].hex(' ').upper()))
    if not bursts:
        print('  (无应答)')

    # 抓一份完整应答存档（ACK + 数据帧 + 结束帧），给回归测试用。
    # 手抄 hex 抄错过一次，所以这里直接落盘原始字节。
    if saved is None and len(bursts) == 1 and len(bursts[0][1]) >= 48:
        cmds = [f['cmd'] for f in F.parse_frames(bytes(bursts[0][1])) if f['ok']]
        if 0x8A in cmds and 0x53 in cmds:
            saved = bytes(bursts[0][1])
    time.sleep(0.2)

ser.close()

if saved:
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        'capture53.hex')
    with open(path, 'w', encoding='ascii') as fh:
        fh.write(saved.hex(' ').upper() + '\n')
    print()
    print('已存档完整应答: %s  (%d 字节)' % (path, len(saved)))
