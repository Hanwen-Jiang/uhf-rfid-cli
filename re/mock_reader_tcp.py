#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""mock_reader_tcp.py —— 一个假的读写器，跑在 TCP 上，用来在没有硬件的情况下
调试手机 App（或任何客户端）。

它讲的是完全一样的协议（帧格式见 re/PROTOCOL.md），默认监听 8080，
也就是原程序里 tcp_port 的默认值。

手机 App 里把连接方式切到「WiFi」，IP 填这台电脑的局域网地址、端口 8080，
就能走完「连接 → 读 EPC/TID → 写 EPC/TID」整个流程。

Android 模拟器连本机用 10.0.2.2（模拟器里的 127.0.0.1 是模拟器自己）。

用法：
    python re/mock_reader_tcp.py                       # 监听 0.0.0.0:8080
    python re/mock_reader_tcp.py --port 9000
    python re/mock_reader_tcp.py --epc E28069150000402008B83072 \\
                                 --tid E28069152000402008B83072
    python re/mock_reader_tcp.py --verbose             # 打印每一帧收发

自测（另开一个终端）：
    python rfidctl.py --tcp 127.0.0.1:8080 info
"""
from __future__ import annotations

import argparse
import os
import socket
import socketserver
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import rfidframe as F

HERE = os.path.dirname(os.path.abspath(__file__))
CAPTURE = os.path.join(HERE, 'capture53.hex')

DEF_EPC = 'E28069150000402008B83072'
DEF_TID = 'E28069152000402008B83072'


def crc16(data: bytes) -> bytes:
    """CRC-16/CCITT（Gen2 标签用的那套）。只为了应答看起来真实，
    我们的客户端不校验它。"""
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc.to_bytes(2, 'big')


def build_53(epc: bytes, tid: bytes) -> bytes:
    """拼一个 0x53 数据帧的载荷（37 字节，真机布局）。

    布局：01 30 00 | EPC(12) | CRC(2) | 0C | TID(12) | CRC(2) | 尾部(5)
    """
    tail = bytes.fromhex('E63C3D0D0DC46A'[:10])       # 真机里尾部前几字节固定
    p = (bytes([0x01, 0x30, 0x00]) + epc + crc16(epc)
         + bytes([len(tid)]) + tid + crc16(tid) + tail)
    assert len(p) == 37, len(p)
    return p


class State:
    """假标签的内存。

    EPC 区的字布局和真标签一致：字 0-1 是 CRC + PC，字 2 起才是 EPC。
    所以「起始地址 2」正好落在 EPC 上、不会碰 CRC —— 这也正是原 GUI 用 2 的原因。
    """

    def __init__(self, epc_hex, tid_hex):
        self.epc = bytes.fromhex(epc_hex)
        self.tid = bytes.fromhex(tid_hex)
        # bank 1: [0:2]=CRC  [2:4]=PC(0x3000)  [4:]=EPC
        self.mem = {1: bytearray(b'\x00\x00' + b'\x30\x00' + self.epc),
                    2: bytearray(self.tid),                   # TID 从字 0 开始
                    3: bytearray(64)}                         # USER 区
        self.antenna = 1
        self.power = 26
        self.beep = True
        self.tag_present = True
        self.epc_match = b''


class Handler(socketserver.BaseRequestHandler):

    def handle(self):
        st = self.server.state
        self.request.settimeout(30)
        peer = '%s:%d' % self.client_address
        print('[+] 客户端接入 %s' % peer)
        try:
            while True:
                chunk = self.request.recv(4096)
                if not chunk:
                    break
                if self.server.verbose:
                    print('    RX  %s' % chunk.hex(' ').upper())
                for reply in self.respond(st, chunk):
                    if self.server.verbose:
                        print('    TX  %s' % reply.hex(' ').upper())
                    self.request.sendall(reply)
        except (socket.timeout, ConnectionResetError, OSError) as e:
            if self.server.verbose:
                print('    (%s)' % e)
        finally:
            print('[-] 客户端断开 %s' % peer)

    def respond(self, st, chunk):
        """按协议回应答。返回要发回去的字节块列表。"""
        out = []
        for fr in F.parse_frames(chunk):
            if not fr['ok']:
                continue
            cmd, p = fr['cmd'], fr['payload']

            if cmd == F.CMD['restart']:                     # 0x70 复位：不应答
                st.epc_match = b''
                time.sleep(0.3)
                continue

            if cmd == F.CMD['version']:                     # 0x72
                out.append(F.build_frame(cmd, bytes([2, 3, 1])))
            elif cmd == F.CMD['set_work_ant']:              # 0x74
                st.antenna = p[0] if p else st.antenna
                out.append(F.build_frame(cmd, bytes([0x10])))
            elif cmd == F.CMD['get_work_ant']:              # 0x75
                out.append(F.build_frame(cmd, bytes([st.antenna])))
            elif cmd == F.CMD['set_ant_power']:             # 0x76
                st.power = p[0] if p else st.power
                out.append(F.build_frame(cmd, bytes([0x10])))
            elif cmd == F.CMD['get_ant_power']:             # 0x77
                out.append(F.build_frame(cmd, bytes([st.power])))
            elif cmd == F.CMD['set_beep']:                  # 0x7A
                st.beep = bool(p[0]) if p else True
                out.append(F.build_frame(cmd, bytes([0x10])))
            elif cmd == F.CMD['set_epc_match']:             # 0x85
                if p and p[0]:
                    st.epc_match = p[2:2 + p[1]]
                else:
                    st.epc_match = b''
                out.append(F.build_frame(cmd, bytes([0x10])))

            elif cmd == F.CMD['custom_inventory']:          # 0x8A —— 关键那条
                # 真机的应答顺序：先 ACK，再两个 0x53 数据帧，最后结束帧
                out.append(F.build_frame(cmd, bytes([0x10])))
                if st.tag_present:
                    payload = build_53(st.epc, st.tid)
                    out.append(F.build_frame(0x53, payload))
                    out.append(F.build_frame(0x53, payload))
                out.append(F.build_frame(0x53, bytes([0x12])))   # 结束帧

            elif cmd == F.CMD['read']:                      # 0x81
                bank = p[0]
                addr = int.from_bytes(p[1:5], 'big')
                cnt = int.from_bytes(p[5:7], 'big')
                m = st.mem.get(bank, bytearray(64))
                s = addr * 2
                data = bytes(m[s:s + cnt * 2])
                # 真机的读应答是 00 01 <长度> <PC 2字节> <数据>
                # （见 re/hardware_logs/write_run1.txt：PC=3000 后跟 EPC）
                head = b'\x00\x01' + bytes([len(data) + 2])
                pc = bytes(m[2:4]) if bank == 1 else b''
                out.append(F.build_frame(cmd, head + pc + data))

            elif cmd == F.CMD['write']:                     # 0x82
                bank = p[4]
                addr = int.from_bytes(p[5:9], 'big')
                cnt = int.from_bytes(p[9:11], 'big')
                data = p[11:11 + cnt * 2]
                m = st.mem.setdefault(bank, bytearray(64))
                need = addr * 2 + len(data)
                while len(m) < need:
                    m.append(0)
                m[addr * 2:addr * 2 + len(data)] = data
                if bank == 1 and addr == 2:                 # 写 EPC -> 盘存也跟着变
                    st.epc = bytes(m[4:4 + len(data)])
                elif bank == 2 and addr == 0:
                    st.tid = bytes(m[:len(data)])
                out.append(F.build_frame(cmd, bytes([0x10])))
                print('    [写] bank=%d addr=%d count=%d data=%s -> 现在 EPC=%s'
                      % (bank, addr, cnt, data.hex().upper(), st.epc.hex().upper()))

            elif cmd == F.CMD['get_tag_count']:             # 0x92
                out.append(F.build_frame(cmd,
                                         (1 if st.tag_present else 0).to_bytes(2, 'little')))
            else:
                out.append(F.build_frame(cmd, bytes([0x10])))    # 其余一律 ACK
        return out


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, addr, handler, state, verbose):
        self.state = state
        self.verbose = verbose
        super().__init__(addr, handler)


def main():
    ap = argparse.ArgumentParser(description='假读写器（TCP），用来在没有硬件时调试客户端')
    ap.add_argument('--host', default='0.0.0.0', help='监听地址（默认所有网卡）')
    ap.add_argument('--port', type=int, default=8080, help='监听端口（默认 8080）')
    ap.add_argument('--epc', default=DEF_EPC, help='假标签的 EPC（Hex）')
    ap.add_argument('--tid', default=DEF_TID, help='假标签的 TID（Hex）')
    ap.add_argument('--no-tag', action='store_true', help='模拟场上没有标签')
    ap.add_argument('--verbose', action='store_true', help='打印每一帧收发')
    a = ap.parse_args()

    st = State(a.epc, a.tid)
    st.tag_present = not a.no_tag

    srv = Server((a.host, a.port), Handler, st, a.verbose)
    print('假读写器已启动  %s:%d' % (a.host, a.port))
    print('  假标签 EPC = %s' % a.epc.upper())
    print('  假标签 TID = %s' % a.tid.upper())
    if a.no_tag:
        print('  （设在「场上没标签」模式）')
    print()
    print('  手机 App：连接方式选「WiFi」，IP 填本机局域网地址，端口 %d' % a.port)
    print('  Android 模拟器：IP 填 10.0.2.2（模拟器里的 127.0.0.1 是它自己）')
    print('  自测：python rfidctl.py --tcp 127.0.0.1:%d info' % a.port)
    print()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print('\n已停止。')
    finally:
        srv.server_close()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
