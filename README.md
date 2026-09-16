# uhf-rfid-cli

用命令行驱动一台 UHF RFID 读写器（ISO 18000-6C / Gen2），**Windows / macOS / Linux 通用**，
不需要原厂那个 Windows GUI。

协议是从原厂 Windows 程序（wxWidgets + MinGW 编译的 `rfid_main_pannel.exe` v1.76.7，
未 strip，带完整符号表）逆向出来的，帧格式、校验、各命令载荷都对着真机抓包验证过。
规格见 **[`re/PROTOCOL.md`](re/PROTOCOL.md)**。

> 本仓库**只包含自己写的代码**，不含原厂的 exe / dll。

---

## 快速开始

```bash
pip install -r requirements.txt        # 其实只要 pyserial

# 一条命令读出标签的 EPC 和 TID
python read_epc_tid.py --antenna 1 --power 26
```

```
自动选择串口：COM4  USB-SERIAL CH341 (COM4)   （另有 COM5、COM6；要指定用 --port，看全部用 --list-ports）
复位读写器...
  已发送复位命令 (0x70)，等待读写器重启
  复位完成（第 2 次探测），固件 02 03 01  -> 版本 2.3.1
  EPC : E280 6915 0000 4020 08B8 3072
        E28069150000402008B83072
  TID : E280 6915 2000 4020 08B8 3072
        E28069152000402008B83072
```

`--port` 可以不填：会自动挑 USB 转串口适配器（CH340/CH341/CP210x/FTDI…），
**不会弹菜单等你输入**，挑中哪个会打印出来。多串口时用 `--list-ports` 看清楚。

---

## macOS

同一套脚本直接用，`python3` 即可：

```bash
pip3 install pyserial

python3 read_epc_tid.py --list-ports
#   /dev/cu.usbserial-1420
#   /dev/cu.Bluetooth-Incoming-Port

python3 read_epc_tid.py --port /dev/cu.usbserial-1420 --antenna 1 --power 26
# 或者干脆不填 --port，自动挑
python3 read_epc_tid.py --antenna 1 --power 26
```

几个 macOS 上要注意的点：

- **选 `cu.*` 不要选 `tty.*`**。macOS 的 `tty.*` 会等 DCD 信号，读写器不发就会卡住不动。
  自动挑口时 `cu.*` 优先。
- **驱动**：CH340/CH341 需要装 WCH 的 macOS 驱动；CP210x 装 Silicon Labs 的
  （新版 macOS 多数已免驱）；FTDI 是系统自带免驱。插上后 `ls /dev/cu.*` 应该能看到新设备。
- 蓝牙虚拟串口（`/dev/cu.Bluetooth-Incoming-Port`）会被排到最后，不会被自动挑中。
- 如果提示端口被占用：`lsof /dev/cu.usbserial-1420` 看是谁占着。

---

## 命令一览

| 脚本 | 用途 |
|---|---|
| `read_epc_tid.py` | **一条命令读出 EPC + TID**（最快，一次应答同时带回两者） |
| `tag_write_epc_tid.py` | 按原 GUI「6C 读写标签」页的设置写 EPC 和 TID，写完回读校验 |
| `find_tag.py` | 扫天线口 × 功率，找出能读到标签的组合 |
| `confirm_tag.py` | 读回 EPC/TID 并与期望值比对 |
| `verify_epc_tid.py` | 基线读取 → 设匹配 → 写 EPC → 校验 → 写 TID → 校验，输出判定表 |
| `read_tag.py` / `probe_*.py` | 硬件诊断：逐条命令试，打印原始应答 |
| `rfidctl.py` | 通用 CLI：任意命令、串口 + TCP 两种传输、`build`/`raw` 离线模式 |

常用参数（两个主力脚本都支持）：

| | 复位（默认开） | 跳过复位 | 关蜂鸣器 | 开蜂鸣器 |
|---|---|---|---|---|
| `read_epc_tid.py` | 默认 | `--no-restart` | `--mute` | `--beep-on` |
| `tag_write_epc_tid.py` | 默认 | `--no-restart` | `--mute` | `--beep-on` |

读写器**每盘存到一个标签就响一声**，反复读写很吵——`--mute` 就是干这个的
（发 `0x7A 00`，读写器回 `10` 表示已接受）。

---

## 写 EPC / TID

对应原 GUI 的「标签测试 → 标签操作 → 读写标签 → 6C」页：
访问密码 `00000000`，EPC 区起始地址 `2`，TID 区起始地址 `0`，数据长度 6 字（= 12 字节）。

```bash
python tag_write_epc_tid.py --yes --antenna 1 --power 26 \
    --epc E28069150000402008B83072 --epc-start 2 \
    --tid E28069152000402008B83072 --tid-start 0
```

```bash
python tag_write_epc_tid.py --dry-run     # 只打印要发的报文，不连设备
python tag_write_epc_tid.py --diagnose    # 逐步执行并打印每步原始应答，不写入
```

写 TID 需要标签支持，多数 Gen2 标签的 TID 是只读的，写了不会有变化（读写器会接受命令）。
要写可改的存储区，用 USER 区（bank 3）。

---

## 这个协议最容易踩的坑

### 1. 地址和字数是 **大端**

```
读 EPC 区:  A0 0E 00 81 01 00 00 00 02 00 06 00 00 00 00 C8
                        │  │           │     └ password  BE32
                        │  │           └ wordCount BE16 = 6
                        │  └ wordAddr   BE32 = 2
                        └ bank = 1 (EPC)
```

`00 00 00 02` 按大端才是地址 2；按小端读成 `0x02000000`，是个乱地址。
**症状极具迷惑性**：报文格式正确、校验也对，读写器收下了，然后**一声不吭**——
和「场上没有标签」一模一样，很容易往错的方向查。

### 2. `0x8A` 自定义盘存的应答有 4 个帧，最后一个不是数据帧

```
A0 04 00 8A 10 C2     ACK           载荷 10           = 已接受
A0 28 00 53 ...      数据帧 #1      载荷 37 字节，含 EPC + TID
A0 28 00 53 ...      数据帧 #2      同样的 EPC/TID
A0 04 00 53 12 F7    结束帧         载荷只有 1 字节 0x12
```

取「最后一个 `0x53`」就会取到结束帧，于是**明明读到了却报「没读到」**。
得挑「载荷够长、能解出 EPC」的那一帧。这 96 字节有时一次读完、有时拆成 `6 + 90` 两次，
所以要把缓冲区累积起来整体解析。

真机原始应答存档在 `re/capture53.hex`（由 `re/probe53.py` 直接落盘，不是手抄的），
`re/test_epctid.py` 用它做回归测试，正好覆盖上面这两个坑。

### 3. 连接后要复位

原 GUI 连接时是「打开串口 → `0x70` 复位 → `Sleep(300ms)` → 校验链路」。
不发这一步，读写器可能处于未知状态，后面的空中命令会不应答。两个主力脚本默认会做。

---

## 测试

不接硬件也能跑（用假串口回放真机抓包）：

```bash
python re/rfidframe.py        # 协议编解码自测（20 个帧往返）
python re/test_epctid.py      # read_epc_tid.py 回归测试（回放真机 96 字节应答）
python re/test_cli.py         # rfidctl.py 对 mock 读写器
python re/test_tagwrite.py    # 写入流程对 mock 读写器（带标签内存模型）
python re/test_real_frame.py  # 真机帧逐字节校验
```

`re/` 里除 `rfidframe.py` 外的逆向工具（`proto2.py` 等）需要 `capstone`。

---

## 手机

| | Android | iOS |
|---|---|---|
| **USB-OTG 串口直连** | ✅ 已实现，见 [`android/`](android/) | ❌ 系统不开放，要 MFi 认证芯片 |
| TCP（读写器带网口/WiFi） | ✅ | ✅ |
| BLE 串口模块 | ✅ | ✅ |

**Android 已经做好可以直接装**：`android/` 里是一个完整的 Android 工程，
手机插 OTG 线就能读/写标签，协议和电脑端完全一样。
已实际编译出 APK（5.6 MB），编译和用法见 [`android/README.md`](android/README.md)。

**iOS 只能走 BLE 或网络**，原因和方案见 [`ios/README.md`](ios/README.md)。

---

## 移植到手机（协议层）

协议本身与传输无关，串口 / HID / TCP 三种传输用的是**完全相同**的帧。
原程序里的 TCP 默认是 `<读写器>:8080`（`tcp_port` 全局变量），
所以**手机最短的路径是走 TCP**：不用 USB 栈、不用驱动、不用权限。

```bash
python3 rfidctl.py --tcp 192.168.0.178:8080 info
```

协议核心可以直接照搬：Python 版是 `re/rfidframe.py`（纯标准库），
Java 版是 `android/app/src/main/java/com/hanwenjiang/uhfrfid/RfidFrame.java`
（同样是纯语言标准库，不依赖 Android），两边有 26 项逐字节比对的自测。

---

## 目录

| 路径 | 内容 |
|---|---|
| `re/rfidframe.py` | 协议编解码 + 命令表（纯标准库，可独立使用） |
| `re/PROTOCOL.md` | 完整协议规格，带反汇编出处 |
| `re/protocol_dump.txt` | 全部命令的载荷布局（从二进制机械提取） |
| `re/proto2.py` 等 | 逆向工具链（PE 解析、DWARF、反汇编、抽象解释） |
| `re/hardware_logs/` | 真机交互日志 |
| `re/capture53.hex` | `0x8A` 应答的真机原始字节 |

---

## 许可

MIT，见 [LICENSE](LICENSE)。

协议信息是通过对合法获得的软件做互操作性分析得到的。
使用前请确认你对自己手上的硬件有操作权限。
