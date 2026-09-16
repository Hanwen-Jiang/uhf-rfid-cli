#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""probe_tag.py — 稳健地读到标签并尝试读内存。

踩过的坑：
  * 0x80 缓存盘存启动后会**持续推送** `A0 05 00 80 00 01 DA`，把后续应答淹没。
  * 在读到标签前就 reset_input_buffer()/drain 会把真正的标签数据一起丢掉。
  * 盘存结果是间歇性的，必须**反复轮询**而不是查一次。

所以这里的做法是：启动盘存后不清缓冲、不丢弃，反复发 0x92/0x91 直到
拿到非空数据或超时；随后再停下来，逐个区读内存。

用法：
    python probe_tag.py --port COM4 --antenna 1 --power 26
    python probe_tag.py --port COM4 --sweep        # 自动扫天线/功率找标签
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


def read_memory(reader, bank, name, start, words, timeout, tries=3):
    """读一个区，每次尝试都换一个新的文件描述符级读取，返回 (状态, 数据) 或 None。"""
    for attempt in range(tries):
        frames = reader.transact(F.CMD['read'],
                                 F.payload_read(bank, start, words, PASSWORD),
                                 expect=F.CMD['read'], timeout=timeout,
                                 retries=0, quiet=True)
        mine = [f for f in frames if f['cmd'] == F.CMD['read']]
        if mine:
            p = mine[0]['payload']
            st = p[0] if p else None
            data = p[1:] if len(p) > 1 else b''
            print('  %-9s bank=%d 起始=%-2d 长度=%d -> 状态=0x%02X 数据=%s' % (
                name, bank, start, words, st if st is not None else -1,
                data.hex(' ').upper() if data else '(无)'))
            return st, data
        time.sleep(0.15)
    print('  %-9s bank=%d 起始=%-2d 长度=%d -> 【零应答】✗' % (
        name, bank, start, words))
    return None


def collect_tags(reader, seconds=6.0, timeout=0.8):
    """在不清缓冲的前提下反复轮询，收集标签记录。"""
    records = []
    seen = set()
    end = time.time() + seconds
    polls = 0
    while time.time() < end:
        polls += 1
        for cmd, expect in ((F.CMD['get_tag_count'], F.CMD['get_tag_count']),
                            (F.CMD['get_tag_buffer'], F.CMD['get_tag_buffer'])):
            frames = reader.transact(cmd, expect=expect, timeout=timeout,
                                     retries=0, quiet=True)
            for f in frames:
                if f['cmd'] != expect:
                    continue
                p = f['payload']
                if len(p) >= 13:
                    for off in (1, 0):
                        rec = p[off:off + 12]
                        if len(rec) == 12 and any(rec) and rec not in seen:
                            seen.add(rec)
                            records.append(rec)
                            print('  发现标签记录: %s' % rec.hex(' ').upper())
        if records:
            break
        time.sleep(0.25)
    print('  （轮询 %d 次）' % polls)
    return records


def try_antenna_power(reader, antenna, power, timeout, seconds):
    print()
    print('--- 天线 %d  功率 %d ---' % (antenna, power))
    reader.transact(F.CMD['set_work_ant'], bytes([antenna]),
                    expect=F.CMD['set_work_ant'], timeout=timeout,
                    retries=0, quiet=True)
    reader.transact(F.CMD['set_ant_power'], bytes([power]),
                    expect=F.CMD['set_ant_power'],
                    timeout=timeout, retries=0, quiet=True)
    reader.transact(F.CMD['clear_tag_buffer'], expect=F.CMD['clear_tag_buffer'],
                    timeout=timeout, retries=0, quiet=True)
    ack = reader.transact(F.CMD['buffered_inventory'], bytes([antenna]),
                          expect=F.CMD['buffered_inventory'], timeout=timeout,
                          retries=0, quiet=True)
    print('  盘存启动: %s' % ('ACK' if ack else '无应答'))
    recs = collect_tags(reader, seconds=seconds, timeout=timeout)
    reader.send_only(F.CMD['stop_inventory'])
    time.sleep(0.3)
    return recs


def main(argv=None):
    ap = argparse.ArgumentParser(description='稳健地读取标签并尝试读内存')
    ap.add_argument('--port',
                    help='串口，例如 COM4 或 /dev/cu.usbserial-1420；'
                         '不填就自动挑 USB 转串口适配器')
    ap.add_argument('--baud', type=int, default=T.BAUD)
    ap.add_argument('--addr', type=lambda v: int(v, 0), default=0)
    ap.add_argument('--antenna', type=int, default=1)
    ap.add_argument('--power', type=int, default=26)
    ap.add_argument('--sweep', action='store_true', help='自动扫常见天线/功率组合')
    ap.add_argument('--seconds', type=float, default=6.0, help='每组轮询时长')
    ap.add_argument('--timeout', type=float, default=0.8)
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
                            stopbits=1, timeout=0.06)
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

        combos = ([(ant, pw) for ant in (1, 2, 3, 4)
                   for pw in (10, 20, 26, 30)] if a.sweep
                  else [(a.antenna, a.power)])

        found = None
        for ant, pw in combos:
            recs = try_antenna_power(reader, ant, pw, a.timeout, a.seconds)
            if recs:
                found = (ant, pw, recs)
                break

        T.hr('盘存结果')
        if not found:
            print('  没有读到任何标签。')
            print('  建议：把标签贴到天线正前方几厘米，再跑一次 --sweep。')
            return 1
        ant, pw, recs = found
        print('  天线 %d  功率 %d  读到 %d 条记录' % (ant, pw, len(recs)))
        for rec in recs:
            print('    %s   CRC=%s  EPC=%s' % (
                rec.hex(' ').upper(), rec[:2].hex().upper(),
                rec[2:].hex().upper()))

        # 停盘存，等推送停掉，再读内存
        reader.send_only(F.CMD['stop_inventory'])
        time.sleep(0.8)
        try:
            ser.reset_input_buffer()
        except Exception:
            pass

        T.hr('读标签内存')
        res = {}
        res['EPC'] = read_memory(reader, 1, 'EPC区', 2, 6, a.timeout)
        res['TID'] = read_memory(reader, 2, 'TID区', 0, 6, a.timeout)
        res['USER'] = read_memory(reader, 3, 'USER区', 0, 4, a.timeout)
        res['RESV'] = read_memory(reader, 0, 'Reserved', 0, 4, a.timeout)

        T.hr('结论')
        ok = [k for k, v in res.items() if v]
        if ok:
            print('  读成功：%s' % '、'.join(ok))
            print('  内存访问链路通畅，可以尝试写入：')
            print('    python verify_epc_tid.py --port %s --antenna %d --power %d'
                  % (a.port, ant, pw))
            return 0
        print('  盘存能读到标签，但内存读全部零应答。')
        print('  说明写也会一样——问题在标签侧（锁定/密码/不支持），不在命令格式。')
        return 1
    finally:
        ser.close()


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print('\n已中断。')
        raise SystemExit(130)
