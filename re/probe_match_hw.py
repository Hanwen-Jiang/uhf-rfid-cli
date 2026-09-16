"""Decisive hardware test: does a CORRECT full 12-byte EPC match unblock memory reads?

Earlier attempts armed 0x85 with a 10-byte EPC guessed from the inventory record
(`rec[2:]`).  But the record's PC field (0x3000) declares a 12-byte EPC while the
record only carried 10 bytes, so that guess was wrong — and a wrong match would
make the reader block tag-memory access, producing exactly the observed silence.

This script arms the match with the full 12-byte EPC taken from rfid.log and then
tries to read EPC/TID.  Read-only: it never writes to the tag.

**Not a unit test — it talks to real hardware and mutates reader state.**  Hence
the `probe_` prefix rather than `test_`: an armed `0x85` match persists in the
reader, and if it names an EPC that is not in the field it silently filters every
tag out, so the next script reports "no tag" for no visible reason.  Run
`re/fix_match.py` to clear it.  This script disarms the match on the way out.
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)

import rfidframe as F
import tag_write_epc_tid as T

PASSWORD = 0x00000000
FULL_EPC = bytes.fromhex('E28069150000402008B6F478')     # 12 bytes, from rfid.log


def read_bank(reader, bank, name, start, words, timeout):
    frames = reader.transact(F.CMD['read'],
                             F.payload_read(bank, start, words, PASSWORD),
                             expect=F.CMD['read'], timeout=timeout,
                             retries=1, quiet=True)
    mine = [f for f in frames if f['cmd'] == F.CMD['read']]
    if mine:
        p = mine[0]['payload']
        print('  %-8s -> 有应答: %s' % (name, p.hex(' ').upper() if p else '(空)'))
        return p
    print('  %-8s -> 零应答 ✗' % name)
    return None


def main():
    port = sys.argv[1] if len(sys.argv) > 1 else 'COM4'
    ant = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    pw = int(sys.argv[3]) if len(sys.argv) > 3 else 26

    import serial
    ser = serial.Serial(port, baudrate=T.BAUD, bytesize=8, parity='N',
                        stopbits=1, timeout=0.06)
    time.sleep(0.2)
    ser.reset_input_buffer()
    reader = T.Reader(ser, addr=0, timeout=1.5, verbose=False)

    try:
        T.hr('复位 + 射频参数')
        T.restart_reader(reader, settle=0.3)
        reader.transact(F.CMD['set_work_ant'], bytes([ant]),
                        expect=F.CMD['set_work_ant'], timeout=1.5,
                        retries=0, quiet=True)
        reader.transact(F.CMD['set_ant_power'], bytes([pw]),
                        expect=F.CMD['set_ant_power'], timeout=1.5,
                        retries=0, quiet=True)
        print('  天线 %d  功率 %d' % (ant, pw))

        # 让读写器先盘存一次，确保场里有标签被激活过
        T.hr('先盘存一次（确认标签在场）')
        reader.transact(F.CMD['clear_tag_buffer'],
                        expect=F.CMD['clear_tag_buffer'], timeout=1.5,
                        retries=0, quiet=True)
        reader.transact(F.CMD['buffered_inventory'], bytes([ant]),
                        expect=F.CMD['buffered_inventory'], timeout=1.5,
                        retries=0, quiet=True)
        time.sleep(1.2)
        frames = reader.transact(F.CMD['get_tag_buffer'],
                                 expect=F.CMD['get_tag_buffer'], timeout=1.5,
                                 retries=0, quiet=True)
        got = []
        for f in frames:
            if f['cmd'] == F.CMD['get_tag_buffer'] and len(f['payload']) >= 13:
                got.append(f['payload'])
        for p in got:
            print('  盘存载荷: %s' % p.hex(' ').upper())
        reader.send_only(F.CMD['stop_inventory'])
        time.sleep(0.8)
        try:
            ser.reset_input_buffer()
        except Exception:
            pass

        T.hr('对照 1：不设匹配')
        read_bank(reader, 1, 'EPC区', 2, 6, 1.5)
        read_bank(reader, 2, 'TID区', 0, 6, 1.5)

        T.hr('对照 2：用【完整的 12 字节 EPC】设匹配')
        payload = bytes([1, len(FULL_EPC)]) + FULL_EPC
        frame = F.build_frame(F.CMD['set_epc_match'], payload)
        print('  匹配报文: %s' % frame.hex(' ').upper())
        rf = reader.transact(F.CMD['set_epc_match'], payload,
                             expect=F.CMD['set_epc_match'], timeout=1.5,
                             retries=0, quiet=True)
        for f in rf:
            if f['cmd'] == F.CMD['set_epc_match']:
                print('  匹配应答: %s' % f['payload'].hex(' ').upper())
        time.sleep(0.4)
        read_bank(reader, 1, 'EPC区', 2, 6, 1.5)
        read_bank(reader, 2, 'TID区', 0, 6, 1.5)

        T.hr('对照 3：设为【空匹配】(enable=1, len=0)')
        payload = bytes([1, 0])
        reader.transact(F.CMD['set_epc_match'], payload,
                        expect=F.CMD['set_epc_match'], timeout=1.5,
                        retries=0, quiet=True)
        time.sleep(0.4)
        read_bank(reader, 1, 'EPC区', 2, 6, 1.5)
        read_bank(reader, 2, 'TID区', 0, 6, 1.5)

        T.hr('对照 4：盘存运行中 + 正确匹配')
        reader.transact(F.CMD['set_epc_match'],
                        bytes([1, len(FULL_EPC)]) + FULL_EPC,
                        expect=F.CMD['set_epc_match'], timeout=1.5,
                        retries=0, quiet=True)
        reader.transact(F.CMD['realtime_inventory'], bytes([ant]),
                        expect=F.CMD['realtime_inventory'], timeout=1.5,
                        retries=0, quiet=True)
        time.sleep(1.0)
        read_bank(reader, 1, 'EPC区', 2, 6, 1.5)
        read_bank(reader, 2, 'TID区', 0, 6, 1.5)
        reader.send_only(F.CMD['stop_inventory'])
        time.sleep(0.5)

        # Leave the reader clean: an armed match that names an absent EPC would
        # make every later script report "no tag".  [0,0] = disabled, length 0.
        T.hr('收尾：清除匹配，恢复干净状态')
        reader.transact(F.CMD['set_epc_match'], bytes([0, 0]),
                        expect=F.CMD['set_epc_match'], timeout=1.5,
                        retries=0, quiet=True)
        print('  已发 A0 05 00 85 00 00 D6')
        return 0
    finally:
        ser.close()


if __name__ == '__main__':
    raise SystemExit(main())
