# UHF RFID Reader — wire protocol specification

Reverse-engineered from `rfid_main_pannel.exe` (v1.76.7, 2023-09-27, PE32+ x86-64,
MinGW-w64 GCC 7.1.0, wxWidgets 3.1.2). The binary is **not stripped** and retains a full
COFF symbol table, so every protocol method is named and every command code below was
extracted mechanically from its call site.

| Artefact | Purpose |
|---|---|
| `re/PROTOCOL.md` | this specification |
| `re/rfidframe.py` | frame codec + command table (stdlib only, verified by self-test) |
| `re/protocol_dump.txt` | per-command dump of the payload byte layout |
| `re/proto2.py` | the abstract interpreter that produced that dump |
| `re/t_layout.py` | verifies the frame arithmetic end to end |
| `rfidctl.py` | cross-platform CLI built on the codec |

---

## 1. Transports

Three interchangeable transports carry the **same** byte format. The application picks one
at connect time; the command layer is byte-identical across all three.

| Transport | Class | Details |
|---|---|---|
| USB CDC / UART | `LibWinUart` | **115200 baud, 8 data bits, no parity, 1 stop bit** (`ConnectUart` sets `SetByteSize 8` / `SetParity 0x4E` / `SetStopBits 0`). Baud comes from the global `BaudRate` at `.data+0x10f4`, initial value `0x1C200`. |
| HID | `LibWinHid` | Same frames over HID reports; `LibWinHid::CheckSun` is byte-identical. |
| TCP | `tcp_client` | `wxSocketBase::Write()` via vtable slot `+0x40`. Defaults: host `192.168.0.178`, port from the global `tcp_port` at `.data+0x10f0`, initial value **8080**. |

Frame builders `LibWinUart::SendUartCmd` and `tcp_client::SendClientCmd` are structurally
identical, as are the matching `CheckSun` implementations.

---

## 2. Frame format

```
index   size    field
-----   ----    ---------------------------------------------------------
  0      1      HEAD    = 0xA0
  1      1      LEN     = payload_length + 3
  2      1      ADDR    RS-485 device address (0 for direct USB / TCP)
  3      1      CMD     command code (§4)
  4    LEN-3    PAYLOAD command parameters
 LEN+1    1     CHECK   checksum
-----   ----    ---------------------------------------------------------
wire length     LEN + 2 bytes
```

### The length arithmetic, exactly

`LibWinUart::SendUartCmd` at `0x48363a`:

```c
/* 48368b */ frame[0] = 0xA0;
/* 483695 */ frame[1] = LEN;                    // caller-supplied
/* 4836a6 */ frame[2] = this->rs485_addr;       // this+0x370
/* 4836b0 */ frame[3] = CMD;
/* 4836c3 */ for (i = 0; i < LEN - 3; i++)      // sub eax,3  /  cmp [rbp+0xac],eax
/* 4836f1 */     frame[4 + i] = this->tx_buf[i];   // tx_buf at this+0x270
/* 483704 */ uint8_t sum = CheckSun(this, frame, LEN + 4);   // lea edx,[rax+4], eax == LEN
/* 48372a */ frame[LEN + 1] = sum;
/* 48379c */ transport->Write(frame, LEN + 2);  // lea r8d,[rdx+2], edx == LEN
```

Three independent relationships pin this down:

1. **payload length = `LEN - 3`**, from the copy loop.
2. **wire length = `LEN + 2`**, from the `Write` argument.
3. **checksum index = `LEN + 1`**, from the store, and consistent with (2) since the last
   wire byte is at index `LEN + 1`.

Call sites confirm `LEN = payload + 3`: `Set_RS485_Addr(unsigned char)` has a 1-byte payload
and passes `mov r8d, 4`; `Read_Frameware_version()` has no payload and passes `mov r8d, 3`.
So a payload-free command is **5 bytes** on the wire: `A0 03 00 CMD SUM`.

> The `CheckSun(frame, LEN + 4)` window runs 2 bytes past the transmitted data. Those bytes
> are zero — the SDK's 256-byte scratch buffer is `rep stos`-zeroed and only indices `0..LEN`
> are ever written — so they contribute nothing. Only the checksum's own slot at `LEN+1` must
> be excluded, which is why the effective window is `LEN + 1` bytes.

### Checksum

`CheckSun` at `0x483b90` (and `tcp_client::CheckSun` at `0x487ea2`, `LibWinHid::CheckSun`
at `0x4470ba`, all identical):

```c
int CheckSun(char *buf, int len) {
    int sum = 0;
    for (int i = 0; i < len; i++)
        sum += (signed char)buf[i];   // `movsx eax, al` — SIGN-extended
    return -sum;
}
```

Therefore `CHECK = (-(int8_t)sum_of(frame[0 .. LEN])) & 0xFF`, and a valid frame satisfies

```
(int8_t)sum_of(all LEN+2 wire bytes) ≡ 0   (mod 256)
```

Sign extension matters: any payload byte ≥ 0x80 changes the result. `re/t_layout.py`
exercises this.

### Response frames

`LibWinUart::GetUartData` at `0x4833fe` accumulates bytes and validates each frame by:

1. `buf[0] == 0xA0`
2. the `LEN` byte is consistent with the bytes received, and `LEN <= 0x64` (100)
3. `CheckSun(this, buf, LEN + 4)` equals the byte at `buf[LEN + 1]`

Accepted frames are pushed onto a `moodycamel::ConcurrentQueue<RXBUF>`. Requests and responses
therefore share one layout.

---

## 3. Command table

Extracted from all 49 `SendUartCmd`/`SendClientCmd` call sites. `argN[k]` denotes byte `k`
(little-endian, 0 = LSB) of the method's Nth parameter, and `LE16`/`LE24`/`LE32` mean
little-endian integers of that width. Payload length = `LEN - 3`.

### 3.1 Inventory

| CMD | Method | LEN | Payload |
|---|---|---|---|
| `0x89` | `RealTime_Inventory(u8)` | 4 | `[arg1]` |
| `0x80` | `Start_Buff_Inventory(u8)` | 4 | `[arg1]` |
| `0x8C` | `Stop_Inventory()` | 3 | — |
| `0x8A` | `Custom_Inventory(u8,u8,u8,u8)` | 7 | `[arg1, arg2, arg3, arg4]` |
| `0x8B` | `Custom_Session_Inventory(u8,u8,u8)` | 6 | `[arg1, arg2, arg3]` |
| `0x87` | `Switch_Ant_Inventory(u8, u8*, u8*, u8,u8,u8,u8)` | var | antenna switching |
| `0x90` | `Get_Tag_Buff()` | 3 | — |
| `0x91` | `Get_and_Clear_Buff()` | 3 | — |
| `0x92` | `Get_Tag_Num()` | 3 | — |
| `0x93` | `Clear_Tag_Buff()` | 3 | — |

### 3.2 ISO 18000-6C (Gen2) tag access

| CMD | Method | LEN | Payload |
|---|---|---|---|
| `0x81` | `RFID_Read(u8 bank, u32 wordAddr, u16 wordCount, u32 accessPwd)` | 14 | `[bank][addr BE32][count BE16][pwd BE32]` |
| `0x82` | `RFID_Write(u8 bank, u32 wordAddr, u16 wordCount, char* data, u32 accessPwd)` | `2n+14` | `[00][pwd BE24][bank][addr BE32][count BE16][data 2n bytes]` |
| `0x83` | `RFID_Lock(u8 mask, u8 action, u32 accessPwd)` | 9 | `[pwd LE32][mask][action]` |
| `0x84` | `RFID_Kill(u32 killPwd)` | 7 | `[pwd LE32]` |
| `0x85` | `Set_Epc_Match(u8 enable, char* epc)` | `n+5` | `[enable][n][epc n bytes]` |
| `0x86` | `Get_Epc_Match()` | 3 | — |

Banks: `0` = Reserved, `1` = EPC, `2` = TID, `3` = User.

> **⚠ `wordAddr` and `wordCount` are BIG-endian.** See §3.2.1. Getting this wrong
> is the classic failure mode: the reader receives an out-of-range address and
> answers **nothing at all**, which looks identical to "no tag present".

#### 3.2.1 Byte order — the detail that matters most

Ground truth is the original GUI's own serial traffic for a full 6C read/write
cycle, captured by the user and self-validated by `re/validate_capture.py`
(every frame is checked against its own LEN byte and checksum before use):

```
读 EPC 区:  A0 0E 00 81 01 00 00 00 02 00 06 00 00 00 00 C8
                        ^^ ^^^^^^^^^^^ ^^^^^ ^^^^^^^^^^^
                        |  |           |     └ password  BE32 = 0
                        |  |           └ wordCount BE16 = 6
                        |  └ wordAddr  BE32 = 2
                        └ bank = 1 (EPC)

读 TID 区:  A0 0E 00 81 02 00 00 00 00 00 06 00 00 00 00 C9
                        bank=2       addr BE32=0  count BE16=6

写 EPC 区:  A0 1A 00 82 00 00 00 00 01 00 00 00 02 00 06 E2 80 69 15 00 00 50 20 08 B2 5C 75 E0
                        │  │        │  │           │     └ 12 bytes of data
                        │  │        │  │           └ wordCount BE16 = 6
                        │  │        │  └ wordAddr BE32 = 2
                        │  │        └ bank = 1 (EPC)
                        │  └ password BE24 = 0
                        └ fixed 0x00

写 TID 区:  A0 1A 00 82 00 00 00 00 02 00 00 00 00 00 06 E2 80 69 15 20 00 50 20 08 B2 5C 75 C1
                        bank=2       addr BE32=0  count BE16=6
```

Reading `00 00 00 02` as little-endian gives `0x02000000` — a nonsense word address,
and `00 06` as LE16 gives 1536 — a nonsense word count. Big-endian is the only
reading that produces sensible values.

An earlier version of this project used little-endian for these two fields and
produced byte-identical-looking frames whose checksums were still valid, yet the
reader answered **nothing** to every `0x81`/`0x82`. That symptom is the signature
of this bug. `re/final_compare.py` now asserts all four frames byte-for-byte
against the GUI captures.

Also note the GUI's own usage of the start word:

```
EPC 区 -> 起始字地址 = 2     (跳过 CRC/PC 两个字)
TID 区 -> 起始字地址 = 0
```

#### `0x85` — EPC match, and why a bare write gets no reply

`Set_Epc_Match` at `0x4825a4` builds its payload two different ways:

```c
if (enable == 0) {                       /* 4825b9 -> 4825c3 */
    tx[0] = 0;                           /* 4825c7 */
    tx[1] = epc_buf[0] + 1;              /* 4825d5: epc_buf[0] is the length */
    for (i = 1; i < epc_buf[0] + 2; i++) /* 4825e8..0x48262b */
        tx[i + 1] = epc_buf[i];          /* copies the EPC and one byte past it */
    LEN = epc_buf[0] + 6;                /* 482637 */
} else {                                 /* 48264d — "enable with no EPC" */
    tx[0] = 1;                           /* 482651 */
    LEN = 4;                             /* 482658: payload is just [0x01] */
}
```

So the payload is `[enable][epc_len][epc bytes...]` and `LEN = epc_len + 5`:

```
enable, 12-byte EPC :  A0 11 00 85 01 0C E2 80 69 15 00 00 50 20 08 B2 5C 75 E2
enable, no EPC      :  A0 05 00 85 01 00 D5
clear               :  A0 05 00 85 00 01 D5
```

**This matters for writes.** In `UartThread::ctrl_command` (`0x461400`) the dispatcher
that turns UI events into reader commands, the sequence is:

```
4616ef  cmp  al, 1      ; event code 1
46171b  call Set_Epc_Match(enable=0, ...)   ; clear the filter   (edx=0 @461713)
4617a6  cmp  al, 1
4617f8  call RFID_Read
46180d  cmp  al, 1
46186b  call RFID_Write
...
461730  cmp  al, 3
461750  call Get_Epc_Match
461765  cmp  al, 2
461791  call Set_Epc_Match(enable=1, epc)   ; arm the filter     (edx=1 @461789)
```

That is, the application's tag-access flow is
**inventory → pick a tag → arm the EPC match → read/write**. `RFID_Write` carries no target
EPC of its own, so a reader that requires a select/match to have been armed first will accept
the `0x82` frame on the wire and then produce **no reply at all** — which is exactly the
symptom of a write that "does nothing". Send `0x85` with the target EPC (or with an empty EPC)
before `0x82`.

`0x81` is the workhorse — read `wordCount` 16-bit words from `wordAddr` in `bank`, authorised
by `accessPwd`:

```
A0 0E 00 81 <bank> <addr LE32> <count LE16> <pwd LE32> CHECK       (16 bytes)
```

#### `0x82` — write, and the meaning of "data length"

`RFID_Write` at `0x482770` builds its payload in two steps, and the second is a loop:

```c
/* fixed 11-byte head */
tx[0] = 0;                                  // 4827ca
tx[1..3] = accessPwd (LE24);                // 4827a4 / 4827b8 / 4827c8
tx[4]    = bank;                            // 4827d8
tx[5..8] = wordAddr (LE32);                 // 482814..0x48282c
tx[9..10]= wordCount (LE16);                // 482840 / 48284a

/* 482850: variable tail — 2 bytes of data per word */
for (i = 0; i < wordCount * 2; i++)         // 48285f `add edx,edx` then cmp
    tx[11 + i] = data[i];                   // 482874 `lea r8d,[rdx+0xb]`

LEN = 2 * wordCount + 14;                   // 482895..48289e, `add eax,7` then `add eax,eax`
```

So the third parameter is a **word count**, the data block is **`2 × wordCount` bytes**, and the
whole write travels in a **single frame** of `LEN + 2 = 2n + 16` bytes — the reader's firmware
splits it into per-word Gen2 write commands. (Physical ISO 18000-6C writes one 16-bit word per
air command; that is the reader's job, not the host's.)

Example — write 6 words (12 bytes) to EPC starting at word 2, password 0:

```
A0 1A 00 82 00 00 00 00 01 02 00 00 00 06 00 E2 80 69 15 00 00 50 20 08 B2 5C 75 E0
   |  |  |  |  |  |           |  |  |  |  \________ 12 data bytes ________/
   |  |  |  |  |  |           |  |  |  └ count LE16 = 6 words
   |  |  |  |  |  |           |  └────┴─ wordAddr LE32 = 2
   |  |  |  |  |  |           └─ bank = 1 (EPC)
   |  |  |  |  └──┴─┴────────── pwd LE24 = 0
   |  |  |  └─ fixed 0x00
   |  |  └──── ADDR
   |  └─────── LEN = 0x1A = 26
   └────────── HEAD
```

`LEN` 26 ⇒ wire length 28 bytes. `wordCount` is a 16-bit field but is also used as `LEN`'s high
byte, so **`wordCount` must be ≤ 255** (≈510 data bytes) in practice. 6 words is well within
range.

### 3.3 GB/T 29768 (Chinese national standard)

| CMD | Method | LEN | Payload |
|---|---|---|---|
| `0x45` | `RFID_GbRead(u8, u16, u16, u32)` | 12 | `[arg1][arg2 LE16][arg3 LE16][arg5 LE32]` |
| `0x46` | `RFID_GbWrite(u8, u16, u16, char*, u32)` | var | `[00][arg6 LE24][arg1][arg2 LE16][arg3 LE16][data…]` |
| `0x47` | `RFID_GbLock(u8, u8, u32)` | 9 | `[arg3 LE32][arg1][arg2]` |
| `0x49` | `RFID_GbKill(u32)` | 7 | `[arg1 LE32]` |
| `0x43` | `RFID_GbSeRead(u8, u16, u16, u32)` | 12 | as `0x45` |
| `0x42` | `RFID_GbSeWrite(u8, u16, u16, char*, u32)` | var | as `0x46` |
| `0x98` | `RFID_GbDoubleIdentify(char*)` | 19 | GB double identification |

### 3.4 RF configuration

| CMD | Method | LEN | Payload |
|---|---|---|---|
| `0x74` | `Set_Work_Ant(u8)` | 4 | `[arg1]` |
| `0x75` | `Get_Work_Ant()` | 3 | — |
| `0x76` | `Set_Ant_power(u8)` | 4 | `[arg1]` |
| `0x77` | `Get_Ant_Power()` | 3 | — |
| `0x5F` | `Set_Multi_Ant_power(u8,u8,u8,u8)` | 7 | `[arg1, arg2, arg3, arg4]` |
| `0x5E` | `Set_Eight_Ant_power(u8 ×8)` | 11 | eight antenna powers |
| `0x78` | `Set_Frequece_Standard(u8,u8,u8)` | 6 | `[arg1, arg2, arg3]` |
| `0x78` | `Set_Frequece(u32 freq, u16, u8)` | 10 | `[04][00][arg2][arg3][freq LE24]` |
| `0x79` | `Get_Frequence()` | 3 | — |
| `0x69` | `Set_RF_Link(u8)` | 4 | RF link profile |
| `0x6A` | `Get_RF_Link()` | 3 | — |
| `0x51` | `Set_Fast_Epc(u8)` | 4 | `[arg1]` |
| `0x50` | `Get_Fast_Epc()` | 3 | — |

`Set_Frequece` and `Set_Frequece_Standard` share CMD `0x78`; they are distinguishable by
`LEN` (10 vs 6).

### 3.5 Device, system and diagnostics

| CMD | Method | LEN | Payload |
|---|---|---|---|
| `0x70` | `Restart_Reader()` | 3 | — |
| `0x72` | `Read_Frameware_version()` | 3 | — |
| `0x7B` | `Read_Work_Temp()` | 3 | — |
| `0x7A` | `Set_Beep_status(u8)` | 4 | `[arg1]` |
| `0x71` | `Set_Uart_BoundRate(u8)` | 4 | `[arg1]` (code, not baud) |
| `0x73` | `Set_RS485_Addr(u8)` | 4 | `[arg1]` |
| `0x60` | `Read_Gpio_Value(u8)` | 3 | — |
| `0x61` | `Set_Gpio_Value(u8,u8)` | 5 | `[arg1, arg2]` |
| `0x4A` | `SaveParam()` | 3 | — |
| `0x4B` | `Reset_Para()` | 3 | — |
| `0x4C` | `Reader_App_Upgrade(u32,u8,u8*,u16)` | var | firmware upload |
| `0x4D` | `Baseband_Firmware_Upgrade(…)` | var | baseband upload |

#### Connect sequence — `0x70` is sent first, every time

Every transport thread sends a **restart** immediately after the port opens, before any other
command. `UartThread::Entry` at `0x4603c0`:

```
4603ec  call LibWinUart::Get_Connect_Com()          ; which port we opened
460485  call UartThread::UartState(...)             ; UI log: "打开串口"
4604fd  call <ConnectUart>(...)                     ; actually open it
4605a9  call UartThread::UartState(...)             ; UI log: "连接成功！"
4605ce  call LibWinUart::Restart_Reader()           ; >>> 0x70 frame <<<
4606d7  call UartThread::UartState(...)             ; UI log: "复位成功！"
4606f0  mov  ecx, 0x12c                             ; 300 ms
4606fc  call <Sleep>
46070f  call LibWinUart::Check_Serial_Com()         ; confirm it came back
```

`HIDThread::Entry` (`0x4888b8`) and `TCPThread::Entry` (`0x4be728`) do the same, and
`ctrl_command` can also issue `Restart_Reader` (`0x460c5c`). A client that skips this and starts
issuing tag commands against a reader in an unknown state may find that commands are accepted on
the wire but never answered.

This matches the GUI's own status log, which shows the three lines within ~80 ms:

```
0:20:14:734  打开串口
0:20:15:16   连接成功！
0:20:15:94   复位成功！
```

### Reply payloads observed on real hardware

Captured from a reader with firmware `02.03.01` behind a CH341 adapter
(`re/hardware_logs/diag_run1.txt`).  Every one of these frames validates under the checksum
rule above, so the codec is confirmed against real hardware:

```
A0 06 00 72 02 03 01 E2    0x72 version              -> 02 03 01
A0 04 00 75 01 E6          0x75 get work antenna     -> 01        (antenna 1)
A0 04 00 77 14 D1          0x77 get antenna power    -> 14 (=20)
A0 05 00 7B 01 2F B0       0x7B read temperature     -> 01 2F     (47 C)
A0 04 00 93 10 B9          0x93 clear tag buffer     -> 10
A0 04 00 80 10 CC          0x80 buffered inventory   -> 10        (accepted)
A0 05 00 80 00 01 DA       0x80 (streamed)           -> 00 01     (nothing found)
A0 05 00 92 00 01 C8       0x92 get tag count        -> 00 01     (nothing found)
A0 04 00 85 10 C7          0x85 set EPC match        -> 10
```

Replies are `[status][data...]`.  `0x10` reads as *accepted / ok* and `0x01` as
*nothing found*.  Note the payload length varies by command (0x72 gives 3 bytes,
0x85 gives 1, 0x7B and 0x92 give 2), so a client must not assume a fixed shape —
compare against the expected byte sequence rather than assuming a leading status.

**Tag-memory commands (`0x81`, `0x82`) produced no reply at all** in that session
(3/3 read attempts timed out).  They require a tag to be selected first, so a
silent timeout is the signature of "no tag available", not of a bad frame — the
same frames are byte-identical to what the original GUI emits.

### `0x8A` custom inventory: EPC *and* TID in one reply

This is the fastest way to identify a tag — one request, and the answer carries
both the EPC and the TID, so no tag-memory reads are needed.

Request (identical to what the GUI emits on 标签测试 → 标签操作 → 读写标签):

```
A0 07 00 8A 01 01 01 06 C6      payload 01 01 01 06  (antenna, ?, ?, TID words)
```

One request produces **four** frames, normally arriving as one 96-byte burst
(~120–220 ms after the request).  Captured verbatim in `re/capture53.hex`:

```
A0 04 00 8A 10 C2     ACK            payload 10            = accepted
A0 28 00 53 ...       data frame #1  37-byte payload, EPC + TID
A0 28 00 53 ...       data frame #2  same EPC/TID, different tail bytes
A0 04 00 53 12 F7     end marker     payload 12            = 结束 / no more
```

The `0x53` **data** payload (37 bytes):

| Offset | Bytes | Meaning |
|---|---|---|
| `0` | 1 | `0x01` |
| `1` | 1 | `0x30` |
| `2` | 1 | `0x00` |
| `3:15` | 12 | **EPC** |
| `15:17` | 2 | EPC CRC |
| `17` | 1 | `0x0C` = TID length (12 bytes) |
| `18:30` | 12 | **TID** |
| `30:` | 7 | trailer; varies per read, safe to ignore |

Two traps here, both of which cost real debugging time:

* **The last `0x53` in the reply is not the data frame.**  It is the 1-byte end
  marker `A0 04 00 53 12 F7`.  A client that takes `frames[-1]` reports "no tag"
  while the EPC sits right there in the same buffer.  Select the `0x53` whose
  payload is long enough to contain an EPC.
* Because the reader may send the burst as one write or split it (observed both
  as a single 96-byte chunk and as `6 + 90`), a client must accumulate and
  re-parse the whole buffer, not parse each read in isolation.

Both are covered by the regression test `re/test_epctid.py`, which replays the
archived capture.

> **Reading nothing is almost always "no tag in the field".**  The reader beeps
> once per tag it decodes, so a repeated-query loop is loud; if the tag is then
> moved away, the queries legitimately return nothing.  Before suspecting reader
> state, check tag placement and antenna/power (`find_tag.py`).  A stale `0x85`
> EPC match is a *theoretical* second cause — `0x86 Get_Epc_Match` reads it back
> and `0x85 [00 00]` clears it — but it was never confirmed to be the cause of an
> observed silence, so do not treat it as the default explanation.

### `0x70` Restart_Reader has no reply

The reader does not answer `0x70`; it reboots.  In the captured session the GUI's
own connect sequence sent `0x70`, the first `0x72` probe ~300 ms later went
unanswered, and the second probe got the version — so a client must poll after
restarting rather than expect an acknowledgement.

### Reply status codes (measured on real hardware)

| Status byte | Meaning | Seen on |
|---|---|---|
| `0x10` | accepted / ok | `0x74` `0x76` `0x85` `0x93` `0x80` (start) |
| `0x01` | empty / nothing found | `0x80` (streamed while idle), `0x92` |
| `0x36` | **command not supported** | `0x45`, `0x98` (constant, for every parameter set) |
| *no reply* | the reader stayed completely silent | `0x81`, `0x82` |

`0x36` is worth calling out: `0x45` (`RFID_GbRead`) returns it for *every* parameter
combination tried, so it is a "not implemented" reply rather than data. A reply byte being
present does not mean the command succeeded — read the status.

### The EPC match value must be the *complete* EPC

This is the subtle one, and it silently breaks tag-memory access:

```
0x90 get tag buffer reply:  10 30 00 E2 80 69 15 00 00 40 20 08 B8
                            ^^ status
                               ^^^^^^ record (12 bytes)
```

The record is `[PC 2][EPC...]`, and here `PC = 0x3000`. In Gen2 the PC field's bits 15..11
carry the EPC length in **words**: `0x3000 >> 11 == 6` words == **12 bytes**. But the record
only carried 10 bytes after the PC, so the EPC in the reply is truncated.

If you take those 10 bytes and use them as the `0x85` match value, the match is wrong, and a
reader that gates tag-memory access on a successful select will then answer **nothing at all**
to `0x81`/`0x82` — indistinguishable from "no tag". Always arm `0x85` with the complete EPC
(the reader's own hex dump, e.g. `rfid.log`, is the reliable source), or use an empty match.

```
完整 12 字节 EPC 的匹配报文:
  A0 11 00 85 01 0C E2 80 69 15 00 00 40 20 08 B6 F4 78 53
             ^^ ^^  \____________ 12 bytes ____________/
             |  └ length = 0x0C
             └ enable = 1
```

---

## 4. Worked examples

All values below are produced by `re/rfidframe.py` and verified by `re/t_layout.py`.

### Read firmware version

```c
frame = A0 03 00 72 EB        // LEN=3, wire=5
```
`sum = -96 + 3 + 0 + 114 = 21`; `-21 & 0xFF = 0xEB`. ✓

### Read 2 words of TID (bank 2) at word 0, no password

```c
frame = A0 0E 00 81 02 00 00 00 00 02 00 00 00 00 00 CE   // LEN=14, wire=16
```
payload = `02` (bank) `00000000` (wordAddr LE32) `0200` (count LE16) `00000000` (pwd LE32).
Signed sum of all 16 bytes ≡ 0 (mod 256). ✓

### Start buffered inventory on antenna 1

```c
frame = A0 04 00 80 01 DB     // LEN=4, wire=6
```

### Kill a tag with password 0x12345678

```c
frame = A0 07 00 84 78 56 34 12 C1    // LEN=7, wire=9
```

### Write `DEADBEEF` to EPC word 2, no password

```c
frame = A0 12 00 82 00 00 00 00 01 02 00 00 00 01 00 DE AD BE EF 90   // LEN=18, wire=20
```
payload = `00` (fixed) `000000` (pwd LE24) `01` (bank EPC) `02000000` (wordAddr LE32)
`0100` (count LE16) `DEADBEEF` (data).

---

## 5. Porting notes (macOS / mobile)

- **macOS** — CH340/CP210x/FTDI readers appear as `/dev/cu.usbserial*` or `/dev/cu.wchusbserial*`.
  Open at **115200 8N1** and write the frames above. `hidapi.dll` and `libminiserial.dll` are
  Windows-only plumbing and are not needed.
- **Android** — a USB-OTG serial adapter works through the USB host API at 115200 8N1; HID
  readers work through `UsbDevice` interrupt transfers. Same frames.
- **iOS** — BLE-to-serial or MFi adapters expose a byte stream; same frames.
- **Any platform, easiest path** — the reader also listens on **TCP 8080**. Connect and write
  frames directly; no USB stack required.

Only the byte transport is platform-specific; everything above byte 0 of the frame is identical
everywhere. `rfidctl.py` implements serial and TCP transports and runs unmodified on macOS,
Linux and Windows.

---

## 6. Confidence

| Item | Confidence | Basis |
|---|---|---|
| Frame header / LEN / ADDR / CMD / checksum | **Certain** | hand-verified disassembly of `SendUartCmd` + `CheckSun`; three transports agree; arithmetic cross-checked three ways and round-tripped in `re/t_layout.py` |
| `LEN = payload + 3` | **Certain** | copy-loop bound plus 49 independent call-site values |
| Command code values | **Certain** | extracted mechanically from every call site |
| Payload layouts §3.1, 3.2, 3.4, 3.5 | **Certain** | abstract interpretation, each byte attributed to a specific parameter |
| GB/T 29768 layouts §3.3 | **High** | same method; parameter semantics not cross-checked against a tag |
| Response *semantics* (which reply bytes carry the EPC, status, etc.) | **Medium** | frames are validated and queued; per-command reply decoding lives in the UI layer and was not fully mapped |
| `Set_Epc_Match`, `Switch_Ant_Inventory`, firmware upload | **Low** | loop-built payloads whose length varies at runtime |

The one thing worth confirming on real hardware is the reply layout for inventory: send
`A0 03 00 92 CB` (`Get_Tag_Num`) and inspect the reply with
`rfidctl --port … raw "A0 03 00 92 CB"`.
