#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""find_tag.py — 扫描天线与功率，找出能读到标签的组合。

诊断日志（re/hardware_logs/diag_run1.txt）显示：
    0x72/0x75/0x77/0x7B/0x80/0x85/0x92/0x93  都有应答
    0x81（读标签内存）3 次全部零应答
且缓存盘存一直回 `00 01`（空），说明读写器在场里没找到任何标签。

内存读/写必须先有一个可被选中的标签，所以第一步是让盘存能读到东西。
这个工具会遍历天线和功率，报告每个组合读到的标签数量，
并在读到标签后立刻把 EPC 打出来。

用法：
    python find_tag.py --port COM4
    python find_tag.py --port COM4 --antennas 1,2,3,4 --powers 10,20,26,30
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
STATUS_OK = {0x10, 0x00}


def drain(reader, seconds=0.4):
    """把读写器主动推上来的帧读干净，避免污染下一条命令的应答。"""
    try:
        reader.ser.reset_input_buffer()
    except Exception:
        pass
    deadline = time.time() + seconds
    got = 0
    while time.time() < deadline:
        chunk = reader.ser.read(512)
        if chunk:
            got += len(chunk)
        else:
            time.sleep(0.02)
    return got


def probe(reader, antenna, power, timeout):
    """在某天线/功率下跑一次缓存盘存，返回 (标签数, EPC列表, 说明)."""
    if getattr(reader, 'broken', False):
        return None, [], '串口已断开'

    # 设定参数
    reader.transact(F.CMD['set_work_ant'], bytes([antenna]),
                    expect=F.CMD['set_work_ant'], timeout=timeout, retries=0,
                    quiet=True)
    reader.transact(F.CMD['set_ant_power'], bytes([power]),
                    expect=F.CMD['set_ant_power'], timeout=timeout, retries=0,
                    quiet=True)

    # 清缓存 -> 启动盘存 -> 收集
    reader.transact(F.CMD['clear_tag_buffer'], expect=F.CMD['clear_tag_buffer'],
                    timeout=timeout, retries=0, quiet=True)
    # 0x80 会先回一个 ACK，随后不断推 `00 01`。只等 ACK，别被推送淹没。
    ack = reader.transact(F.CMD['buffered_inventory'], bytes([antenna]),
                          expect=F.CMD['buffered_inventory'],
                          timeout=timeout, retries=0, quiet=True)
    started = bool(ack)
    # 让标签有时间被读到，然后彻底清空推送，避免污染后面的应答
    time.sleep(1.0)
    drain(reader, 0.3)

    cnt = None
    cframes = reader.transact(F.CMD['get_tag_count'],
                              expect=F.CMD['get_tag_count'],
                              timeout=timeout, retries=0, quiet=True)
    if cframes:
        mine = [f for f in cframes if f['cmd'] == F.CMD['get_tag_count']]
        p = (mine or cframes)[0]['payload']
        if p:
            if len(p) >= 2:
                cnt = max(p[0], p[1])          # 数量通常很小，取较大者更稳
            else:
                cnt = p[0]

    # 取缓存内容里的 EPC
    epcs = []
    dframes = reader.transact(F.CMD['get_and_clear_buffer'],
                              expect=F.CMD['get_and_clear_buffer'],
                              timeout=timeout, retries=0, quiet=True)
    for f in dframes or []:
        if f['cmd'] != F.CMD['get_and_clear_buffer']:
            continue
        p = f['payload']
        for off in (0, 1):
            rest = p[off:]
            if len(rest) >= 12:
                cand = rest[:12]
                if cand not in epcs and any(cand):
                    epcs.append(cand)

    reader.send_only(F.CMD['stop_inventory'])
    drain(reader, 0.3)
    note = '启动应答正常' if started else '启动无应答'
    if getattr(reader, 'broken', False):
        note = '串口断开'
    return cnt, epcs, note


def main(argv=None):
    ap = argparse.ArgumentParser(
        description='扫描天线与功率，找出能读到标签的组合',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog='示例:\n'
               '  python find_tag.py --port COM4\n'
               '  python find_tag.py --port COM4 --antennas 1,2,3,4 --powers 26,30\n')
    ap.add_argument('--port', help='串口，例如 COM4')
    ap.add_argument('--baud', type=int, default=T.BAUD)
    ap.add_argument('--addr', type=lambda v: int(v, 0), default=0)
    ap.add_argument('--antennas', default='1,2,3,4',
                    help='要试的天线号，逗号分隔（1 基），默认 1,2,3,4')
    ap.add_argument('--powers', default='10,20,26,30',
                    help='要试的功率，逗号分隔，默认 10,20,26,30')
    ap.add_argument('--timeout', type=float, default=1.0)
    ap.add_argument('--air-timeout', type=float, default=None)
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
    antennas = [int(x) for x in a.antennas.split(',') if x.strip()]
    powers = [int(x) for x in a.powers.split(',') if x.strip()]

    port = T.pick_port(a.port)
    try:
        import serial
    except ImportError:
        print('需要 pyserial：pip install pyserial', file=sys.stderr)
        return 3
    try:
        ser = serial.Serial(port, baudrate=a.baud, bytesize=8, parity='N',
                            stopbits=1, timeout=0.1)
    except Exception as e:
        print('打开串口 %s 失败：%s' % (port, e), file=sys.stderr)
        for line in (T.serial_hint(port, e) or []):
            print('  ' + line, file=sys.stderr)
        return 3
    time.sleep(0.2)
    ser.reset_input_buffer()

    air = a.air_timeout or max(1.0, a.timeout)
    reader = T.Reader(ser, addr=a.addr, timeout=a.timeout)

    try:
        if not a.no_restart:
            T.hr('复位')
            T.restart_reader(reader, settle=0.3)

        T.hr('扫描天线 x 功率')
        print('  天线: %s' % antennas)
        print('  功率: %s' % powers)
        print()
        print('  %-8s %-8s %-10s %s' % ('天线', '功率', '标签数', '备注'))
        print('  ' + '-' * 56)

        hits = []
        broken = False
        for ant in antennas:
            for pw in powers:
                if getattr(reader, 'broken', False):
                    broken = True
                    print('  天线 %-3d 功率 %-3d  —— 串口已断开，停止扫描' % (ant, pw))
                    break
                cnt, epcs, note = probe(reader, ant, pw, air)
                shown = '?' if cnt is None else str(cnt)
                extra = note
                if epcs:
                    extra = 'EPC=' + epcs[0].hex().upper()
                print('  %-8d %-8d %-10s %s' % (ant, pw, shown, extra))
                if epcs or (cnt or 0) > 0:
                    hits.append((ant, pw, cnt, epcs))
            print()
            if broken:
                break

        T.hr('结论')
        if broken:
            print('  扫描中断：%s' % getattr(reader, 'break_reason', '?'))
            print('  常见原因是串口被别的程序抢走（例如原应用被重新打开）。')
            print('  关掉占用程序后重跑，或把结果发出来。')
            return 2
        if not hits:
            print('  没有任何组合读到标签。这说明问题不在读写器命令层，而在射频侧：')
            print('    * 标签是否在场上？贴到天线正前方几厘米再试')
            print('    * 用的是哪个天线口？换 --antennas 1,2,3,4 逐个试（已做）')
            print('    * 天线线缆是否接好、是否损坏')
            print('    * 标签是否已被写坏/锁定（换个新标签试）')
            print('    * 读写器温度偏高：日志里读到 0x2F=47，确认散热')
            return 1
        print('  读到标签的组合：')
        for ant, pw, cnt, epcs in hits:
            print('    天线 %d  功率 %-3d 标签数 %-4s %s' % (
                ant, pw, cnt, epcs[0].hex().upper() if epcs else ''))
        best = hits[0]
        print()
        print('  建议直接用这组参数跑写入：')
        print('    python tag_write_epc_tid.py --port %s --yes --antenna %d --power %d'
              % (port, best[0], best[1]))
        return 0
    finally:
        ser.close()


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print('\n已中断。')
        raise SystemExit(130)
