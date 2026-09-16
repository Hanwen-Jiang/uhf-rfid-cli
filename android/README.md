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

## 代码结构

| 文件 | 作用 |
|---|---|
| `RfidFrame.java` | 协议编解码，`re/rfidframe.py` 的 Java 移植。纯 Java，不依赖 Android |
| `SerialTransport.java` | 串口传输层（usb-serial-for-android），115200 8N1 |
| `RfidReader.java` | 高层操作：复位、射频参数、蜂鸣器、读 EPC/TID、写 EPC/TID |
| `MainActivity.java` | 界面 + USB 权限 + 后台线程 |
| `selftest/RfidFrameSelfTest.java` | 协议自测，纯 JVM 跑，不需要 Android |

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
