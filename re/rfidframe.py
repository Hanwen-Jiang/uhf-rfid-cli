"""RFID reader frame codec, recovered from rfid_main_pannel.exe (v1.76.7).

Everything here is derived from the disassembly of `LibWinUart::SendUartCmd`
(@0x48363a), `LibWinUart::CheckSun` (@0x483b90) and `LibWinUart::GetUartData`
(@0x4833fe); `tcp_client::SendClientCmd`/`::CheckSun` and `LibWinHid` are
byte-identical.  See `re/PROTOCOL.md`.

FRAME LAYOUT
------------
    index  0       0xA0                    head
    index  1       LEN                     length byte = payload_length + 3
    index  2       ADDR                    RS-485 address
    index  3       CMD                     command code
    index  4 .. LEN                   payload   (LEN - 3 bytes)
    index  LEN+1   CHECK                   checksum
    wire length    LEN + 2 bytes

The payload byte count `LEN - 3` comes from the copy loop bound
(`sub eax,3` @0x4836c3, `cmp [rbp+0xac],eax` @0x4836c6) and is corroborated by
the call sites: `Set_RS485_Addr(unsigned char)` passes LEN = 4 with a 1-byte
payload.

CHECK = CheckSun(buf, LEN + 2) = (-(int8_t sum of ALL LEN+2 wire bytes)) & 0xFF.
Because every byte is sign-extended before summing and the total is negated,
a valid frame satisfies: signed sum of every byte on the wire == 0 (mod 256).

Verification of this layout: `re/t_layout.py`.

Standard library only, so it runs on macOS, Linux, Windows and mobile Python
runtimes.
"""

HEAD = 0xA0
LEN_BIAS = 3          # LEN = payload_length + 3
WIRE_BIAS = 2         # wire length = LEN + 2
CHECK_OFFSET = 1      # checksum index = LEN + 1

# ------------------------------------------------------------------ commands
CMD = {
    # inventory
    'realtime_inventory':   0x89,
    'buffered_inventory':   0x80,
    'stop_inventory':       0x8C,
    'custom_inventory':     0x8A,
    'custom_session_inv':   0x8B,
    'switch_ant_inventory': 0x87,
    'get_tag_buffer':       0x90,
    'get_and_clear_buffer': 0x91,
    'get_tag_count':        0x92,
    'clear_tag_buffer':     0x93,
    # ISO 18000-6C (Gen2)
    'read':                 0x81,
    'write':                0x82,
    'lock':                 0x83,
    'kill':                 0x84,
    'set_epc_match':        0x85,
    'get_epc_match':        0x86,
    # GB/T 29768
    'gb_read':              0x45,
    'gb_write':             0x46,
    'gb_lock':              0x47,
    'gb_kill':              0x49,
    'gb_se_read':           0x43,
    'gb_se_write':          0x42,
    'gb_double_identify':   0x98,
    # RF configuration
    'set_work_ant':         0x74,
    'get_work_ant':         0x75,
    'set_ant_power':        0x76,
    'get_ant_power':        0x77,
    'set_multi_ant_power':  0x5F,
    'set_eight_ant_power':  0x5E,
    'set_freq_standard':    0x78,   # LEN 6
    'set_frequency':        0x78,   # LEN 10
    'get_frequency':        0x79,
    'set_rf_link':          0x69,
    'get_rf_link':          0x6A,
    'set_fast_epc':         0x51,
    'get_fast_epc':         0x50,
    # device / system
    'restart':              0x70,
    'version':              0x72,
    'temperature':          0x7B,
    'set_beep':             0x7A,
    'set_baudrate':         0x71,
    'set_rs485_addr':       0x73,
    'read_gpio':            0x60,
    'set_gpio':             0x61,
    'save_params':          0x4A,
    'factory_reset':        0x4B,
}

CMD_NAME = {}
for _n, _c in CMD.items():
    CMD_NAME.setdefault(_c, _n)

BANK = {'reserved': 0, 'epc': 1, 'tid': 2, 'user': 3}


# ------------------------------------------------------------------ checksum
def check_sun(buf, length):
    """Exact transliteration of the reader's CheckSun().

    Each byte is sign-extended (`movsx eax, al`), summed, the total negated and
    truncated to 8 bits.
    """
    total = 0
    for i in range(length):
        b = buf[i]
        total += b - 256 if b >= 0x80 else b      # (int8_t)
    return (-total) & 0xFF


# ------------------------------------------------------------------ codec
def build_frame(cmd, payload=b'', addr=0):
    """Build one command frame exactly as the SDK's SendUartCmd does."""
    payload = bytes(payload)
    ln = len(payload) + LEN_BIAS
    total = ln + WIRE_BIAS
    # The SDK uses a 256-byte rep-stos-zeroed stack buffer, so the checksum
    # window always reads defined (zero) bytes.
    buf = bytearray(256)
    buf[0] = HEAD
    buf[1] = ln
    buf[2] = addr & 0xFF
    buf[3] = cmd & 0xFF
    buf[4:4 + len(payload)] = payload
    # The checksum covers every byte before it: indices 0 .. ln (CHECK_OFFSET
    # past that is the checksum's own slot).  So the window length is ln + 1.
    buf[ln + CHECK_OFFSET] = check_sun(buf, ln + CHECK_OFFSET)
    return bytes(buf[:total])


def checksum_ok(frame):
    """True when the frame's checksum field matches its content."""
    if len(frame) < 4 or frame[0] != HEAD:
        return False
    ln = frame[1]
    if len(frame) != ln + WIRE_BIAS:
        return False
    buf = bytearray(256)
    buf[0:len(frame)] = frame
    return check_sun(buf, ln + CHECK_OFFSET) == frame[ln + CHECK_OFFSET]


def parse_frames(stream):
    """Split a byte stream into frames, resynchronising on 0xA0.

    Returns a list of dicts: raw, addr, cmd, payload, ok.
    """
    out = []
    i, n = 0, len(stream)
    while i + 1 < n:
        if stream[i] != HEAD:
            i += 1
            continue
        ln = stream[i + 1]
        if ln < LEN_BIAS:
            i += 1
            continue
        total = ln + WIRE_BIAS
        if i + total > n:
            break
        raw = bytes(stream[i:i + total])
        buf = bytearray(256)
        buf[0:total] = raw
        ok = check_sun(buf, ln + CHECK_OFFSET) == raw[ln + CHECK_OFFSET]
        # payload length is LEN-3 for every command in this binary
        out.append(dict(raw=raw, addr=raw[2], cmd=raw[3],
                        payload=raw[4:4 + max(0, ln - LEN_BIAS)], ok=ok,
                        cmd_name=CMD_NAME.get(raw[3], '?')))
        i += total
    return out


# ------------------------------------------------------------------ parameters
def be(value, nbytes):
    """Big-endian encoding.

    Measured against the original GUI's own serial traffic (see
    `re/diff_write.py` and `re/PROTOCOL.md`): the reader takes `wordAddr` and
    `wordCount` **big-endian**.  A capture of the GUI writing 6 words at word
    address 2 to the EPC bank carries `00 00 00 02` / `00 06`, which only reads
    sensibly big-endian — little-endian would mean address 0x02000000.
    """
    return (value & ((1 << (8 * nbytes)) - 1)).to_bytes(nbytes, 'big')


def le(value, nbytes):
    """Little-endian encoding (kept for the fields that really are LE)."""
    return (value & ((1 << (8 * nbytes)) - 1)).to_bytes(nbytes, 'little')


def payload_read(bank, word_addr, word_count, password=0):
    """RFID_Read(unsigned char bank, unsigned int wordAddr, unsigned short
    wordCount, unsigned int accessPwd) -> 11 payload bytes.

    Layout and byte order confirmed against the GUI's serial capture:

        payload[0]     = bank
        payload[1..4]  = wordAddr   (BE32)
        payload[5..6]  = wordCount  (BE16)
        payload[7..10] = accessPwd  (BE32)

    e.g. the GUI reading the EPC bank from word 2, 6 words:
        01 | 00 00 00 02 | 00 06 | 00 00 00 00
    """
    return (bytes([bank & 0xFF]) + be(word_addr, 4) + be(word_count, 2)
            + be(password, 4))


def payload_write(bank, word_addr, word_count, data, password=0):
    """RFID_Write(u8 bank, u32 wordAddr, u16 wordCount, char* data, u32 accessPwd).

    Layout and byte order confirmed against the GUI's serial capture:

        payload[0]     = 0
        payload[1..3]  = accessPwd (BE24)
        payload[4]     = bank
        payload[5..8]  = wordAddr  (BE32)
        payload[9..10] = wordCount (BE16)
        payload[11..]  = data      (2 * wordCount bytes)

    e.g. the GUI writing 6 words at word 2 to the EPC bank:
        00 | 00 00 00 | 01 | 00 00 00 02 | 00 06 | <12 bytes>

    `word_count` also feeds the frame's LEN byte, so it must be <= 255.
    """
    data = bytes(data)
    if word_count < 0 or word_count > 255:
        raise ValueError('word_count must be 0..255 (it sets the frame LEN byte)')
    if len(data) != 2 * word_count:
        raise ValueError('data must hold exactly 2*word_count = %d bytes, got %d'
                         % (2 * word_count, len(data)))
    return (bytes([0]) + be(password, 3) + bytes([bank & 0xFF])
            + be(word_addr, 4) + be(word_count, 2) + data)


def write_len(word_count):
    """The LEN byte RFID_Write will use, i.e. `2 * word_count + 14`."""
    return 2 * word_count + 14


def payload_lock(mask, action, password=0):
    """RFID_Lock(u8 mask, u8 action, u32 accessPwd): pwd LE32, mask, action."""
    return le(password, 4) + bytes([mask & 0xFF, action & 0xFF])


def payload_kill(password):
    """RFID_Kill(u32 killPwd): 4 bytes little-endian."""
    return le(password, 4)


# ------------------------------------------------------------------ self-test
TESTS = [
    ('version', CMD['version'], b''),
    ('stop_inventory', CMD['stop_inventory'], b''),
    ('get_tag_count', CMD['get_tag_count'], b''),
    ('save_params', CMD['save_params'], b''),
    ('buffered_inventory(1)', CMD['buffered_inventory'], bytes([1])),
    ('set_work_ant(1)', CMD['set_work_ant'], bytes([1])),
    ('set_ant_power(30)', CMD['set_ant_power'], bytes([30])),
    ('set_rs485_addr(1)', CMD['set_rs485_addr'], bytes([1])),
    ('kill(0x12345678)', CMD['kill'], payload_kill(0x12345678)),
    ('lock(1,2,0)', CMD['lock'], payload_lock(1, 2, 0)),
    ('read(epc,0,2,0)', CMD['read'], payload_read(BANK['epc'], 0, 2, 0)),
    ('read(tid,0,6,0)', CMD['read'], payload_read(BANK['tid'], 0, 6, 0)),
    ('read(user,255,4,0xFFFFFFFF)', CMD['read'],
     payload_read(BANK['user'], 255, 4, 0xFFFFFFFF)),
    ('write(epc,2,2,DEADBEEF,0)', CMD['write'],
     payload_write(BANK['epc'], 2, 2, bytes.fromhex('DEADBEEF'), 0)),
    ('write(epc,2,6,12B,0)', CMD['write'],
     payload_write(BANK['epc'], 2, 6,
                   bytes.fromhex('E28069150000502008B25C75'), 0)),
    ('write(tid,0,6,12B,0)', CMD['write'],
     payload_write(BANK['tid'], 0, 6,
                   bytes.fromhex('E28069152000502008B25C75'), 0)),
    ('set_frequency(0x0F5A0A,1,1)', CMD['set_frequency'],
     bytes([4, 0, 1, 1]) + le(0x0F5A0A, 3)),
    ('set_freq_standard(1,2,3)', CMD['set_freq_standard'], bytes([1, 2, 3])),
    ('set_multi_ant_power(4x30)', CMD['set_multi_ant_power'], bytes([30] * 4)),
    ('set_beep(1)', CMD['set_beep'], bytes([1])),
]


def self_test(verbose=True):
    bad = 0
    for name, cmd, payload in TESTS:
        f = build_frame(cmd, payload)
        p = parse_frames(f)
        ok = (len(p) == 1 and p[0]['ok'] and p[0]['cmd'] == cmd
              and p[0]['payload'] == payload)
        # a valid frame's signed byte sum is a multiple of 256
        signed = sum((b - 256 if b >= 0x80 else b) for b in f)
        ok = ok and signed % 256 == 0 and len(f) == f[1] + WIRE_BIAS
        if not ok:
            bad += 1
        if verbose:
            print('  %-30s %-50s LEN=%2d wire=%2d %s' % (
                name, f.hex(' ').upper(), f[1], len(f), 'OK' if ok else 'FAIL'))
    if verbose:
        print()
        print('ALL %d FRAMES ROUND-TRIP' % len(TESTS) if bad == 0
              else '%d FAILURES' % bad)
    return bad


if __name__ == '__main__':
    raise SystemExit(1 if self_test() else 0)
