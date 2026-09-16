#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""read_tag.py — 用已确认可用的参数读取标签内存，先把「读」跑通。

天线 1 @ 功率 26 已确认能读到标签（EPC E28069150000402008B6F478）。
这个脚本在那个状态下做完整的读测试：盘存、读 EPC 区、读 TID 区、读 Reserved 区，
并把每次应答按「状态码 + 数据」解析出来。

用法：
    python read_tag.py --port COM4
    python read_tag.py --port COM4 --antenna 1 --power 26
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


def drain(reader, seconds=0.4):
    try:
        reader.ser.reset_input_buffer()
    except Exception:
        pass
    end = time.time() + seconds
    while time.time() < end:
        try:
            if not reader.ser.read(512):
                time.sleep(0.02)
        except Exception:
            break


def status_of(payload):
    """把应答载荷拆成 (状态码, 数据)。"""
    if not payload:
        return None, b''
    return payload[0], payload[1:]


def run_read(reader, bank, name, start, words, timeout):
    frames = reader.transact(F.CMD['read'], F.payload_read(bank, start, words,
                                                           PASSWORD),
                             expect=F.CMD['read'], timeout=timeout, retries=1,
                             quiet=True)
    mine = [f for f in frames if f['cmd'] == F.CMD['read']]
    if not mine:
        print('  %-10s bank=%d 起始=%-2d 长度=%d  ->  【零应答】✗' % (
            name, bank, start, words))
        return None
    p = mine[0]['payload']
    st, data = status_of(p)
    print('  %-10s bank=%d 起始=%-2d 长度=%d  ->  %s   状态=0x%02X 数据=%s' % (
        name, bank, start, words,
        '有应答 ✓' if data or st == 0x10 else '有应答',
        st if st is not None else -1,
        data.hex(' ').upper() if data else '(无)'))
    return data


def main(argv=None):
    ap = argparse.ArgumentParser(description='读取标签内存（EPC/TID/Reserved）')
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
    try:
        import serial
    except ImportError:
        print('需要 pyserial：pip install pyserial', file=sys.stderr)
        return 3
    try:
        a.port = T.pick_port(a.port)
        ser = serial.Serial(a.port, baudrate=a.baud, bytesize=8, parity='N',
                            stopbits=1, timeout=0.1)
    except Exception as e:
        print('打开串口 %s 失败：%s' % (a.port, e), file=sys.stderr)
        for line in (T.serial_hint(a.port, e) or []):
            print('  ' + line, file=sys.stderr)
        return 3
    time.sleep(0.2)
    ser.reset_input_buffer()
    reader = T.Reader(ser, addr=a.addr, timeout=a.timeout, verbose=False)

    try:
        if not a.no_restart:
            T.hr('复位')
            T.restart_reader(reader, settle=0.3)

        T.hr('设置射频参数：天线 %d，功率 %d' % (a.antenna, a.power))
        reader.transact(F.CMD['set_work_ant'], bytes([a.antenna]),
                        expect=F.CMD['set_work_ant'], timeout=a.timeout,
                        retries=0, quiet=True)
        reader.transact(F.CMD['set_ant_power'], bytes([a.power]),
                        expect=F.CMD['set_ant_power'], timeout=a.timeout,
                        retries=0, quiet=True)
        print('  已设置')

        T.hr('盘存确认能读到标签')
        reader.transact(F.CMD['clear_tag_buffer'],
                        expect=F.CMD['clear_tag_buffer'], timeout=a.timeout,
                        retries=0, quiet=True)
        reader.transact(F.CMD['buffered_inventory'], bytes([a.antenna]),
                        expect=F.CMD['buffered_inventory'], timeout=a.timeout,
                        retries=0, quiet=True)
        time.sleep(1.2)
        drain(reader, 0.4)

        frames = reader.transact(F.CMD['get_and_clear_buffer'],
                                 expect=F.CMD['get_and_clear_buffer'],
                                 timeout=a.timeout, retries=0, quiet=True)
        epc = None
        for f in frames:
            if f['cmd'] != F.CMD['get_and_clear_buffer']:
                continue
            p = f['payload']
            print('  缓冲应答: %s' % (p.hex(' ').upper() if p else '(空)'))
            for off in (1, 0):
                if len(p) - off >= 12:
                    epc = p[off:off + 12]
                    break
        reader.send_only(F.CMD['stop_inventory'])
        drain(reader, 0.3)

        if epc:
            print('  读到标签: %s' % epc.hex(' ').upper())
            if len(epc) >= 12:
                print('    CRC = %s' % epc[:2].hex().upper())
                print('    EPC = %s' % epc[2:].hex().upper())
        else:
            print('  ⚠ 没有读到标签。把标签贴近天线，或用 find_tag.py 再扫一遍。')

        # 让读写器处理完推送，再逐个读内存
        time.sleep(0.5)
        drain(reader, 0.5)

        T.hr('读标签内存（这才是关键：写之前先确认读得通）')
        results = {}
        results['EPC'] = run_read(reader, 1, 'EPC区', 2, 6, a.timeout)
        drain(reader, 0.2)
        results['TID'] = run_read(reader, 2, 'TID区', 0, 6, a.timeout)
        drain(reader, 0.2)
        results['USER'] = run_read(reader, 3, 'USER区', 0, 4, a.timeout)
        drain(reader, 0.2)
        results['RESV'] = run_read(reader, 0, 'Reserved', 0, 4, a.timeout)

        T.hr('结论')
        ok = [k for k, v in results.items() if v]
        if ok:
            print('  这些区读成功：%s' % '、'.join(ok))
            print('  读写器的内存访问链路是通的，可以进入写入测试：')
            print('    python verify_epc_tid.py --port %s --antenna %d --power %d'
                  % (a.port, a.antenna, a.power))
            return 0
        print('  所有区都零应答 —— 读写器读得到标签但读不了内存。')
        print('  可能原因：标签已锁定、访问密码非 0、或该标签不支持标准读命令。')
        return 1
    finally:
        ser.close()


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print('\n已中断。')
        raise SystemExit(130)
