#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""confirm_tag.py — 最终确认标签内容，并解析读写器特有应答格式。

真机写入已成功（见 re/hardware_logs/write_run1.txt）。这个脚本做最后一次干净确认。

应答格式（真机实测，本工具已按此解析）：
    0x81 读应答:
        [00]                状态 (0x00 = 好)
        [01]                记录数
        [1C]                记录长度标记
        PC 2 字节           例 30 00  (PC = 0x3000 -> EPC 6 字)
        EPC 12 字节         例 E2 80 69 15 00 00 50 20 08 B2 5C 75
        CRC 2 字节          例 D9 1B
        PC 2 字节           例 E2 80  <- 这是【下一条记录】的开头被当成了 PC
        ...
      => 从偏移 4 起每 16 字节一条记录，但本工具只需要第 1 条记录里的 EPC。

    0x82 写应答:
        [00][06][10] + 写入前的旧内容 + [10][01]
      => 可用它确认写操作被接受。

用法：
    python confirm_tag.py --port COM4 --antenna 1 --power 26
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
WANT_EPC = bytes.fromhex('E28069150000502008B25C75')
WANT_TID = bytes.fromhex('E28069152000502008B25C75')


def parse_read_reply(payload):
    """从 0x81 应答里取出第 1 条记录的 PC / EPC / CRC。

    实测布局（'00 01 1C 30 00 <EPC12> D9 1B ...'）:
        [0]      状态  = 0x00
        [1]      记录数
        [2]      记录头 = 0x1C
        [3:5]    PC   (大端)
        [5:17]   EPC  12 字节
        [17:19]  CRC

    注意 PC/EPC/CRC 都在记录头之后，所以 EPC 从下标 5 开始。
    """
    if len(payload) < 17:
        return None, None, None
    pc = payload[3:5]
    epc = payload[5:17]
    crc = payload[17:19] if len(payload) >= 19 else b''
    return pc, epc, crc


def main(argv=None):
    ap = argparse.ArgumentParser(description='最终确认标签内容')
    ap.add_argument('--port',
                    help='串口，例如 COM4 或 /dev/cu.usbserial-1420；'
                         '不填就自动挑 USB 转串口适配器')
    ap.add_argument('--baud', type=int, default=T.BAUD)
    ap.add_argument('--addr', type=lambda v: int(v, 0), default=0)
    ap.add_argument('--antenna', type=int, default=1)
    ap.add_argument('--power', type=int, default=26)
    ap.add_argument('--timeout', type=float, default=1.5)
    ap.add_argument('--no-restart', action='store_true')
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
    time.sleep(0.3)
    ser.reset_input_buffer()
    reader = T.Reader(ser, addr=a.addr, timeout=a.timeout, verbose=False)

    try:
        T.hr('复位')
        if not a.no_restart:
            T.restart_reader(reader, settle=0.3)

        T.hr('设置射频参数：天线 %d 功率 %d' % (a.antenna, a.power))
        for cmd, pl, label in (
                (F.CMD['set_work_ant'], bytes([a.antenna]), '工作天线'),
                (F.CMD['set_ant_power'], bytes([a.power]), '天线功率')):
            fr = F.build_frame(cmd, pl)
            ser.reset_input_buffer()
            ser.write(fr)
            ser.flush()
            time.sleep(0.4)
            rx = ser.read(256)
            print('  %-8s TX %s' % (label, fr.hex(' ').upper()))
            print('  %-8s RX %s' % ('', rx.hex(' ').upper() if rx else '(无)'))

        # 注意：不要先发「清除匹配」——实测那会让读写器对后续读命令回 0x37/0x36。
        # 直接读即可（盘存期间/之后都可以）。
        results = {}
        for bank, name, want, start, words in (
                (1, 'EPC 区', WANT_EPC, 2, 6),
                (2, 'TID 区', WANT_TID, 0, 6)):
            print()
            print('-' * 62)
            print('读 %s：bank=%d 起始字地址=%d 长度=%d 字' % (
                name, bank, start, words))
            print('-' * 62)
            payload = bytes([bank]) + F.be(start, 4) + F.be(words, 2) + F.be(0, 4)
            frame = F.build_frame(F.CMD['read'], payload)
            print('  载荷: %s' % payload.hex(' ').upper())
            print('  报文: %s' % frame.hex(' ').upper())
            ser.reset_input_buffer()
            ser.write(frame)
            ser.flush()
            time.sleep(0.6)
            rx = ser.read(512)
            print('  应答: %s' % (rx.hex(' ').upper() if rx else '(无)'))
            if not rx:
                results[name] = None
                print('  判定: 零应答 ✗')
                continue
            good = [f for f in F.parse_frames(rx) if f['ok'] and f['cmd'] == F.CMD['read']]
            if not good:
                results[name] = None
                print('  判定: 没有 0x81 应答 ✗')
                continue
            p = good[0]['payload']
            print('  载荷解析: %s' % p.hex(' ').upper())
            pc, epc, crc = parse_read_reply(p)
            print('  状态      = 0x%02X' % p[0])
            print('  记录数    = %d' % p[1])
            print('  PC        = %s  (0x%04X -> 声明 EPC 长度 %d 字节)'
                  % (pc.hex(' ').upper(), int.from_bytes(pc, 'big'),
                     (int.from_bytes(pc, 'big') >> 11) * 2))
            print('  EPC/数据  = %s' % epc.hex(' ').upper())
            print('  期望      = %s' % want.hex(' ').upper())
            ok = (epc == want)
            results[name] = ok
            print('  判定      = %s' % ('一致 ✓' if ok else '不一致 ✗'))

        print()
        T.hr('最终结论')
        label = {True: '写入成功并已确认 ✓', False: '内容不符 ✗', None: '读不到 ✗'}
        for name, ok in results.items():
            print('  %-8s %s' % (name, label[ok]))
        allok = all(v is True for v in results.values())
        if allok:
            print()
            print('  标签的 EPC 与 TID 都已是目标值 —— 目标达成。')
        return 0 if allok else 1
    finally:
        ser.close()


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print('\n已中断。')
        raise SystemExit(130)
