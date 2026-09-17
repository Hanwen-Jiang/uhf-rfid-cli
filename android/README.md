# Android App —— 手机直接插 OTG 线读/写标签

手机通过 USB Host（OTG）直接跟读写器讲串口，协议和电脑端完全一样。
界面：连接 → 读 EPC/TID → 可选写 EPC/TID，底下有原始报文窗口。

## 编译

需要 Android Studio（或 JDK 17 + Android SDK）。

```bash
cd android
./gradlew assembleDebug            # Windows: gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 5.6 MB），直接拷到手机安装
（需要允许「安装未知来源应用」）。

用 Android Studio 的话，直接 `File → Open` 选 `android/` 目录，点运行即可。

**已验证**：本工程在本机用 JDK 17 + Gradle 8.14 + AGP 8.13.2 + compileSdk 36
（build-tools 36.1.0）实际编译通过，产出 APK。

> 注：首次编译需要联网拉 AGP、AndroidX 和 `usb-serial-for-android`（JitPack 源）。

## 用起来

1. 手机插 OTG 线，接上读写器的 USB 转串口适配器（CH340/CP210x/FTDI/PL2303 都认）。
2. 打开 App → 弹窗问 USB 权限 → 点「允许」。
3. 点「连接」。App 会按顺序做：复位(可关) → 清 EPC 匹配 → 关蜂鸣器(可选) →
   设天线/功率 → 读固件版本确认链路。
4. 点「读 EPC/TID」。
5. 要写就把 EPC/TID 填进去点「写入」。

界面上两个勾选框对应电脑端的 `--no-restart` 和 `--mute`：
**连接后复位**默认开（和原 GUI 一致），**关蜂鸣器**默认关
（读写器每盘存到一个标签就响一声，连读时很吵）。

## Android 不需要装驱动（和 macOS 正好相反）

**这是 Android 相对 macOS 最大的好处**：CH340/CH341 在 Android 上**免驱**，
不用 root、不用装 kext、不用重启。

原因是 Android 内核里同样没有这些芯片的驱动，但 `usb-serial-for-android` 这个库
**在用户态用 Java 自己实现了 CH341 的 USB 协议**，直接通过 Android 的
USB Host API 读写原始 USB 端点。库的作者原话：

> No root access, ADK, or special kernel drivers are required;
> all drivers are implemented in [user space]

它支持的芯片（全部免驱）：

- Qinheng **CH340、CH341A**、CH343、CH9102
- FTDI FT232R/FT232H/FT2232H/FT4232H/FT230X/FT231X/FT234XD
- Prolific PL2303
- Silabs CP2102、CP210x
- CDC-ACM（按 USB 接口类型自动识别）

所以你在 Mac 上折腾的那个 CH341 驱动，安卓这边**完全不用管**。

### 插上没反应的排查顺序

1. **手机支持 OTG 吗**：绝大多数近几年的安卓机都支持，但少数低端机没有 USB Host。
2. **OTG 开关**：小米/红米（HyperOS/MIUI）在「设置 → 更多设置 → OTG」里，
   而且**默认 10 分钟不用会自动关**，插线前先打开。
3. **OTG 线**：有些线只能充电不能传数据，换一根；Type-C 口建议用带 OTG 芯片的转接头。
4. **供电**：读写器自己供电的话没问题（手机只给适配器供电，几十毫安）。
   如果适配器都带不动，用一个**带供电的 OTG Hub**（Y 型线）。
5. 权限弹窗点错「拒绝」了：到「设置 → 应用 → UHF RFID → 清除默认」或者拔插一次重新授权。

## 代码结构

| 文件 | 作用 |
|---|---|
| `RfidFrame.java` | 协议编解码，`re/rfidframe.py` 的 Java 移植。纯 Java，不依赖 Android |
| `Transport.java` | 传输层基类（收发 + 累积解析），USB 和 TCP 共用 |
| `SerialTransport.java` | USB-OTG 串口（usb-serial-for-android），115200 8N1 |
| `TcpTransport.java` | WiFi：连读写器网口，或连电脑上的桥 |
| `RfidReader.java` | 高层操作：复位、射频参数、蜂鸣器、读 EPC/TID、写 EPC/TID |
| `RfidSession.java` | 全局单例，持有连接 —— 让 Activity 重建后连接不丢 |
| `MainActivity.java` | 界面 + USB 权限 + 后台线程 |
| `selftest/RfidFrameSelfTest.java` | 协议自测，纯 JVM 跑，不需要 Android |

## 在模拟器里调试

模拟器**测不了 USB 直连**（见下），但测界面和 WiFi 那条路很好用。

```bash
# 列出可用 AVD
"$ANDROID_HOME/emulator/emulator.exe" -list-avds

# 建一个（只需要一次）
"$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager.bat" create avd \
    -n rfid34 -k "system-images;android-34;google_apis;x86_64" -d pixel_5

# 启动（带窗口）
"$ANDROID_HOME/emulator/emulator.exe" -avd rfid34 -no-snapshot -no-boot-anim \
    -gpu auto -skin 480x1040
```

**`-skin 480x1040` 之后一定要调密度**，否则像素密度还是 440dpi，dp 宽度只有 174
（正常手机 360~420dp），界面文字会乱换行：

```bash
adb shell wm density 200      # 480px / 200dpi * 160 ≈ 384dp，正常手机宽度
```

配对调试（模拟器连宿主机的假读写器）：

```bash
python re/mock_reader_tcp.py --port 18080     # 宿主机上起假读写器
adb reverse tcp:18080 tcp:18080               # 模拟器的 18080 转到宿主机
# App 里选「WiFi」，IP 填 127.0.0.1，端口 18080，点连接
```

## 关于模拟器的 USB 透传

官方模拟器**有**这个选项：

```
-usb-passthrough vendorid=VID,productid=PID[,hostbus=BUS,hostport=PORT]
```

Windows 上还需要先装 SDK 自带的 USB 辅助驱动，否则会报
`Cannot load Android USB Assistant Driver ... USB pass-through might not work`：

```bat
:: 需要管理员权限
cd %ANDROID_HOME%\emulator\drivers
Android_USB_Assistant_Install.bat     :: pnputil -i -a Android_USB_Assistant.Inf
UsbAssist_Install.bat                 :: 装并启动 UsbAssist 内核服务
```

**实测结论（Windows 11 26200 + emulator 37.1.11 + WHPX）：装完驱动、UsbAssist
服务也在 RUNNING、驱动错误确实消失了，但模拟器一加 `-usb-passthrough` 就立刻
退出；不加参数则正常启动。** 所以这台机器上透传走不通，**USB 直连模式只能在
真手机上验证**。

卸载（如果不想留）：

```bat
cd %ANDROID_HOME%\emulator\drivers
UsbAssist_Install.bat -u
pnputil /delete-driver oem84.inf /uninstall
```

## 协议自测（不需要手机，也不需要 Android SDK）

`RfidFrame.java` 是纯 Java，可以单独编出来跟 Python 实现逐字节比对：

```bash
cd ..            # 回到仓库根目录
javac -encoding UTF-8 -d /tmp/jt \
  android/app/src/main/java/com/hanwenjiang/uhfrfid/RfidFrame.java \
  android/selftest/com/hanwenjiang/uhfrfid/RfidFrameSelfTest.java
java -cp /tmp/jt com.hanwenjiang.uhfrfid.RfidFrameSelfTest re/capture53.hex
```

它会检查：各命令帧的字节、真机验证过的 6C 读写帧、地址是不是大端、
校验和是不是按有符号累加，以及能否从真机抓包里正确解出 EPC/TID
（包括「最后一个 0x53 是结束帧」那个坑）。共 26 项。

## 已知限制

- **TID 通常写不了**：多数 Gen2 标签的 TID 是只读的，读写器会接受写命令但没有效果。
  要写可改的存储区请用 USER 区（bank 3）。
- 只做了单天线口 + 单次读写，没有做连续盘存/防碰撞列表。
- 没有做断线自动重连：拔了线要重新点「连接」。
- 界面文字是中文，没有做多语言。
