#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_epc_tid.py — 一次性验证「写 EPC 区 + 写 TID 区」是否真的成功。

和 tag_write_epc_tid.py 的区别：这个脚本只做验证和判定，输出一个明确的结论，
并在第一步之前先把标签的原始状态读出来，这样即使写入失败也能看清卡在哪。

流程：
    复位 → 读工作天线/功率 → 读原始 EPC/TID（基线）
    → 写 EPC 区 → 回读比对
    → 写 TID 区 → 回读比对
    → 打印结论表 + 退出码（0 = 两步都成功）

用法：
    python verify_epc_tid.py --port COM4
    python verify_epc_tid.py --port COM4 --antenna 1 --power 30
    python verify_epc_tid.py --port COM4 --length-mode bytes   # 试另一种长度解释
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
EPC_BANK, TID_BANK = F.BANK['epc'], F.BANK['tid']
EPC_START, TID_START = 2, 0
DEFAULT_EPC = 'E28069150000502008B25C75'
DEFAULT_TID = 'E28069152000502008B25C75'


def read_bank(reader, bank, start, words, timeout):
    """读一段内存，返回 (是否应答, 载荷)。"""
    frames = reader.transact(F.CMD['read'], F.payload_read(bank, start, words, PASSWORD),
                             expect=F.CMD['read'], timeout=timeout, retries=0)
    if not frames:
        return False, b''
    return True, frames[0]['payload']


def find_expect(payload, expect):
    """在应答里找期望数据，返回偏移（-1 = 没找到）。"""
    return payload.find(expect)


def run_step(reader, label, bank, start, words, data, timeout, dry):
    """写一次并回读。返回 dict 结果。"""
    print()
    print('-' * 70)
    print('写入 %s 区：bank=%d 起始=%d 长度=%d 字' % (label, bank, start, words))
    print('-' * 70)
    payload = F.payload_write(bank, start, words, data, PASSWORD)
    frame = F.build_frame(F.CMD['write'], payload, addr=reader.addr)
    print('  报文  %s' % frame.hex(' ').upper())
    if dry:
        print('  (dry-run，未发送)')
        return dict(written=None, verified=None, note='dry-run')

    wframes = reader.transact(F.CMD['write'], payload, expect=F.CMD['write'],
                              timeout=timeout, retries=1)
    replied = bool(wframes)
    wbody = wframes[0]['payload'] if wframes else b''
    print('  写应答: %s' % (wbody.hex(' ').upper() if wbody else '（无应答 / 超时）'))

    ok_read, rbody = read_bank(reader, bank, start, words, timeout)
    print('  读应答: %s' % (rbody.hex(' ').upper() if rbody else '（无应答 / 超时）'))

    off = find_expect(rbody, data) if rbody else -1
    if off == 0:
        print('  回读  : 一致 ✓')
        verified = True
    elif off > 0:
        print('  回读  : 一致 ✓（前面有 %d 字节前缀）' % off)
        verified = True
    else:
        print('  回读  : 未找到期望数据 ✗')
        print('    期望: %s' % data.hex(' ').upper())
        verified = False
    return dict(written=replied, verified=verified, write_reply=wbody,
                read_reply=rbody)


def main(argv=None):
    ap = argparse.ArgumentParser(
        description='验证写 EPC 区与 TID 区是否真的成功，并输出明确结论',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog='示例:\n'
               '  python verify_epc_tid.py --port COM4\n'
               '  python verify_epc_tid.py --port COM4 --antenna 1 --power 30\n'
               '  python verify_epc_tid.py --port COM4 --length-mode bytes\n')
    ap.add_argument('--port', help='串口，例如 COM4；不填则自动检测')
    ap.add_argument('--baud', type=int, default=T.BAUD)
    ap.add_argument('--addr', type=lambda v: int(v, 0), default=0)
    ap.add_argument('--epc', default=DEFAULT_EPC)
    ap.add_argument('--tid', default=DEFAULT_TID)
    ap.add_argument('--epc-start', type=lambda v: int(v, 0), default=EPC_START)
    ap.add_argument('--tid-start', type=lambda v: int(v, 0), default=TID_START)
    ap.add_argument('--antenna', type=int, default=None)
    ap.add_argument('--power', type=int, default=None)
    ap.add_argument('--timeout', type=float, default=1.5)
    ap.add_argument('--air-timeout', type=float, default=None)
    ap.add_argument('--no-restart', action='store_true')
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--log', metavar='FILE')
    ap.add_argument('--length-mode', choices=['words', 'bytes'], default='words',
                    help='数据长度的解释：words=6 字=12 字节（默认，与原应用一致）；'
                         'bytes=把 6 当字节（用于排查长度理解错误）')
    ap.add_argument('--match-epc', dest='match_epc', default=None,
                    help='用于 0x85 匹配的完整 EPC（Hex）。默认从盘存记录里取，'
                         '但那可能被截断；已知完整 EPC 时请显式给出。')
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
    epc_data = T.parse_hex(a.epc, 'EPC')
    tid_data = T.parse_hex(a.tid, 'TID')
    if a.length_mode == 'bytes':
        # 把用户给的长度理解成字节：只用前一半数据，长度减半
        epc_data = epc_data[:max(2, len(epc_data) // 2)]
        tid_data = tid_data[:max(2, len(tid_data) // 2)]
    epc_words = len(epc_data) // 2
    tid_words = len(tid_data) // 2

    T.hr('验证计划')
    print('  EPC 区 : bank=%d 起始=%d 长度=%d 字 (%d 字节)  %s'
          % (EPC_BANK, a.epc_start, epc_words, len(epc_data),
             epc_data.hex().upper()))
    print('  TID 区 : bank=%d 起始=%d 长度=%d 字 (%d 字节)  %s'
          % (TID_BANK, a.tid_start, tid_words, len(tid_data),
             tid_data.hex().upper()))
    print('  长度解释: %s' % ('words（6 字 = 12 字节，与原应用一致）'
                              if a.length_mode == 'words' else 'bytes（6 字节）'))

    if a.dry_run:
        air = a.air_timeout or 1.5
        reader = type('R', (), {'addr': a.addr})()
        run_step(reader, 'EPC', EPC_BANK, a.epc_start, epc_words, epc_data, air, True)
        run_step(reader, 'TID', TID_BANK, a.tid_start, tid_words, tid_data, air, True)
        print()
        print('--dry-run：未连接设备。')
        return 0

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
        h = T.serial_hint(port, e) if hasattr(T, 'serial_hint') else None
        if h:
            print('  ' + h, file=sys.stderr)
        return 3
    time.sleep(0.2)
    ser.reset_input_buffer()

    air = a.air_timeout or max(1.5, a.timeout * 2)
    T.hr('连接')
    print('  %s  %d 8N1  空中超时 %.1fs' % (port, a.baud, air))
    reader = T.Reader(ser, addr=a.addr, timeout=a.timeout)

    results = {}
    try:
        if not a.no_restart:
            T.hr('复位')
            T.restart_reader(reader, settle=0.3)

        if a.antenna is not None or a.power is not None:
            T.hr('射频参数')
            T.init_reader(reader, a, air)

        # 基线：写之前先把现状读出来
        T.hr('基线（写入前的原始内容）')
        ok_e, base_epc = read_bank(reader, EPC_BANK, a.epc_start, epc_words, air)
        print('  EPC 区读  : %s' % ('有应答 ' + base_epc.hex(' ').upper()
                                    if ok_e else '无应答 ✗'))
        ok_t, base_tid = read_bank(reader, TID_BANK, a.tid_start, tid_words, air)
        print('  TID 区读  : %s' % ('有应答 ' + base_tid.hex(' ').upper()
                                    if ok_t else '无应答 ✗'))
        if not ok_e and not ok_t:
            print()
            print('  ⚠ 连读都读不到 —— 写之前先解决「读」的问题：')
            print('    场上有标签吗？试试 --antenna 1 --power 30')
            print('    详见 README 的排查表。')

        # 写 EPC
        T.hr('设置 EPC 匹配')
        if a.match_epc:
            match = T.parse_hex(a.match_epc, '--match-epc')
            print('  使用命令行指定的完整 EPC: %s' % match.hex().upper())
        elif ok_e and len(base_epc) >= 12:
            match = base_epc[:12]
            print('  使用读回的 EPC 区内容: %s' % match.hex().upper())
        else:
            # 盘存记录可能被截断（PC 声明 12 字节但记录只给 10 字节），
            # 所以这里退回到「空匹配」，让读写器不限定目标。
            match = b''
            print('  无法确定完整 EPC，改为空匹配（不限定目标）')
        T.arm_epc_match(reader, match, air)
        results['EPC'] = run_step(reader, 'EPC', EPC_BANK, a.epc_start,
                                  epc_words, epc_data, air, False)

        # 写 TID
        T.hr('重新设置 EPC 匹配（EPC 可能已变）')
        if a.match_epc:
            new_match = epc_data          # 写完后标签的 EPC 就是我们写进去的值
        elif match:
            new_match = match
        else:
            new_match = b''
        T.arm_epc_match(reader, new_match, air)
        results['TID'] = run_step(reader, 'TID', TID_BANK, a.tid_start,
                                  tid_words, tid_data, air, False)

        T.clear_epc_match(reader, air)
    finally:
        ser.close()

    T.hr('结论')
    print('  %-6s %-10s %-10s %s' % ('区域', '有应答', '回读一致', '判定'))
    print('  ' + '-' * 44)
    ok_all = True
    for k in ('EPC', 'TID'):
        r = results.get(k) or {}
        w, v = r.get('written'), r.get('verified')
        verdict = ('成功 ✓' if v else ('无应答 ✗' if not w else '写入未生效 ✗'))
        if not v:
            ok_all = False
        print('  %-6s %-10s %-10s %s' % (
            k, '是' if w else '否', '是' if v else '否', verdict))
    print()
    if ok_all:
        print('  两步都成功：EPC 区与 TID 区都已写入并回读一致。')
    else:
        T.report_air_failure()
    return 0 if ok_all else 1


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print('\n已中断。')
        raise SystemExit(130)
