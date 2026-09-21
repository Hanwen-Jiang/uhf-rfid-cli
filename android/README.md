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
| `Transport.java` | 传输层基类（收发 + 累积解析） |
| `SerialTransport.java` | USB-OTG 串口（usb-serial-for-android），115200 8N1 |
| `RfidReader.java` | 高层操作：复位、射频参数、蜂鸣器、读 EPC/TID、写 EPC/TID |
| `RfidSession.java` | 全局单例，持有连接 —— 让 Activity 重建后连接不丢 |
| `MainActivity.java` | 界面 + USB 权限 + 后台线程 |
| `selftest/RfidFrameSelfTest.java` | 协议自测，纯 JVM 跑，不需要 Android |

## 在模拟器里测 USB（已跑通，附一键脚本）

**结论：可以。** 已经在 Android 14 模拟器里通过 USB 透传连上真读写器、读到真标签：

```
已连接 USB 串口 (1a86:5523)
固件 02 03 01
天线 1  功率 26

读到标签（463 ms）
EPC  E28069150000502008B25C75
TID  E28069152000502008B25C75
```

一键搭建（需要管理员权限）：

```powershell
powershell -ExecutionPolicy Bypass -File android\tools\setup-emulator-usb.ps1
```

**第一次跑要三四分钟**（冷启动 + 重启 framework），之后脚本会建议存快照，
再开机就是十几秒。想手动存：

```bash
adb emu avd snapshot save rfidusb     # 配好之后存
emulator -avd rfid34 -snapshot rfidusb -usb-passthrough vendorid=0x1a86,productid=0x5523
```

> bind mount 只存在内存里，所以**必须用快照恢复**才能省掉重复配置；冷启动就得重配。

它处理了**三道坎**，缺一个都不行：

1. **`-usb-passthrough vendorid=1a86` 会让模拟器直接崩掉。**
   emulator 把这个值原样转给 QEMU 的 `-device usb-host`，而 QEMU 要求 int64，
   必须写成 `vendorid=0x1a86`。这个报错只出现在 stderr 里：
   `Parameter 'vendorid' expects an int64 value or range`。

2. **Windows 上要装 SDK 自带的 USB 辅助驱动**，否则报
   `Cannot load Android USB Assistant Driver ... might not work`：
   ```bat
   cd %ANDROID_HOME%\emulator\drivers
   Android_USB_Assistant_Install.bat
   UsbAssist_Install.bat
   ```

3. **模拟器的 system 镜像里没有 `android.hardware.usb.host` 声明**，
   所以 `UsbHostManager` 不启动，`UsbManager.getDeviceList()` 永远返回空。
   `/system` 只读，而 `adb remount` 在动态分区上会失败
   （`could not map scratch image`，`disable-verity` + 重启也救不了），
   所以脚本改成 **bind mount** 一份改过的 permissions 目录，再重启 framework。
   > 注意两件事：
   > - bind mount 的 SELinux 标签必须是 `u:object_r:system_file:s0`。
   >   标签不对的话 `system_server` 读不到文件，**zygote 会进入崩溃循环**，
   >   表现是 `pm list features` 一直返回 0。
   > - 那个 XML **不能带 UTF-8 BOM**。`Set-Content -Encoding utf8` 在有些
   >   PowerShell 上会加 BOM，Android 的 XML 解析器就读不出来了。用
   >   `[System.IO.File]::WriteAllText($path, $xml, (New-Object System.Text.UTF8Encoding($false)))`。

   这套只改内存，不动 system 镜像 —— 关掉模拟器重开就恢复原状。

### 手动做的话

```powershell
# 1) 起模拟器（注意 0x 前缀）
emulator -avd rfid34 -no-snapshot -no-boot-anim -gpu auto -skin 480x1040 `
         -usb-passthrough vendorid=0x1a86,productid=0x5523

# 2) 注入特性声明
adb root
adb shell 'rm -rf /data/local/tmp/perms; mkdir -p /data/local/tmp/perms; cp -a /system/etc/permissions/. /data/local/tmp/perms/'
adb push android.hardware.usb.host.xml /data/local/tmp/perms/
adb shell 'chcon -R u:object_r:system_file:s0 /data/local/tmp/perms'
adb shell 'mount --bind /data/local/tmp/perms /system/etc/permissions'

# 3) 重启 framework（不是重启虚拟机）
adb shell stop ; adb shell start

# 4) 验证
adb shell 'pm list features' | findstr usb.host
adb shell 'cat /sys/bus/usb/devices/1-1/idVendor'     # 应该输出 1a86
adb shell 'wm density 200'                            # 480x1040 窗口下必须调，否则文字乱换行

# 5) 装 App 测试
cd android ; .\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.hanwenjiang.uhfrfid/.MainActivity
```

### 关于 MuMu（实测，结论：不可能）

MuMu 的内核里**根本没有 USB 子系统**：

```
$ adb -s 127.0.0.1:7555 shell 'zcat /proc/config.gz | grep CONFIG_USB_SUPPORT'
# CONFIG_USB_SUPPORT is not set        <- USB 支持没编进内核

$ adb shell ls /sys/bus/usb/devices/
ls: /sys/bus/usb/devices/: No such file or directory
$ adb shell ls /dev/bus/usb
ls: /dev/bus/usb: No such file or directory
$ adb shell 'ls /sys/module/ | grep -i usb'
(空 —— 一个 USB 模块都没有)
```

客户机是 Linux 6.1.90 aarch64，但 `CONFIG_USB_SUPPORT` **未启用**，
`init.svc.usbd` 也是 `stopped`。Android 的 `UsbManager` 拿不到任何设备，
App 里点「扫描 USB 设备」永远是 `0 个`（已实测）。
它的虚拟机确实是 VirtualBox 内核（`C:\Program Files\MuMuNxVbox\`，
`VBoxManage.exe` 都在），但**客户机内核没有 USB 栈，透过去也没人认**。
这条路到此为止。

> 顺带：`VBoxManage` 也指挥不动它，报 `CO_E_SERVER_EXEC_FAILURE` ——
> MuMu 用的是改过的 VirtualBox，COM 服务是它自己的。

## 卸载辅助驱动（不想留的话）

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

## 写标签踩过的三个坑（都已修）

1. **写之前不要做 0x8A 盘存。** 实测：0x8A 自定义盘存之后紧接着写，读写器会稳定回
   状态码 **`0x41`** 并且写不进去（补发 `0x8C` 停止盘存也没用）；改成先用 **0x81 读
   EPC 区**拿到当前 EPC 再写就没问题。对照实验（同一个标签、同样的写入报文）：
   ```
   0x8A 流程：3/6 失败，回读没变
   0x81 流程：6/6 成功，回读跟着变
   ```
   `readEpcTid()`（0x8A）只用于"读"，写路径走 `readCurrentEpc()`（0x81）。

2. **读应答是一条合并记录，偏移不是 5。**
   ```
   00 01 <len> <PC 2字节> <EPC 12字节> <CRC 2字节> <TID 12字节> …
   ```
   **EPC 在偏移 5，TID 在偏移 19。** 一律按偏移 5 取的话，读 TID 时取到的是 EPC，
   于是"回读永远不一致"，重写多少轮都没用。现在改成在应答里**查找**写入的数据。

3. **0x41 是偶发的，要重写。** 写入失败会一直重写到成功（最多 8 轮），
   以**回读**为准而不是只看应答码。窗口里能看到第几轮成功、状态码是什么。

另外两个只在特定 Android 版本上才会犯的错：

- **USB 权限的 PendingIntent 必须 `FLAG_MUTABLE`**，否则系统塞不进
  `EXTRA_DEVICE` / `EXTRA_PERMISSION_GRANTED`，表现是"弹窗一闪而过、毫无反应"。
  但 targetSdk 34+ 又禁止 `FLAG_MUTABLE` 配**隐式** Intent（直接抛
  `IllegalArgumentException`），所以还要 `intent.setPackage(getPackageName())`
  把它变成显式 Intent。两个条件得同时满足。
- **打开串口本身会偶发失败**（CH34x 初始化握手报
  `Expected 0x0 byte, but get 0xec [init #1]`），所以连接也做了 3 次重试。

## 界面结构

底部四个标签页，读写器的连接状态在顶栏那颗 USB 图标上（绿=已连，灰带斜杠=没连）。

| 页 | 干什么 |
|---|---|
| **读 / 写** | 「读标签」和「上传到云端」并排 —— 自动上传失败时立刻能手点重传。下面是从标签库里挑一颗直接写。 |
| **标签库** | 云端列表：命名、删除、一键写入。也是补全数据的刷新入口。 |
| **古法写入** | 手敲 EPC / TID 的十六进制值，边敲边按云端已有标签补全。 |
| **设置** | 天线口、功率、连接后复位、关蜂鸣器、自动上传、云端地址/key/使用者。 |

正常写标签就是：**主页点「选择标签写入…」→ 挑一颗 → 确认 → 写**。
「古法写入」只在需要手工指定值的时候才用。

### UI 框架

用的就是官方 **Material Components**（`com.google.android.material:material:1.12.0`，
项目里本来就有，没引第三方）。用到的：`BottomNavigationView` 底部导航、
`MaterialToolbar` 顶栏、`MaterialCardView` 卡片、`TextInputLayout` 带浮动标签的输入框、
`MaterialButton`、`MaterialCheckBox`。

### 代码结构

```
MainActivity        宿主：USB 连接 + 标签页切换
HomeFragment        读标签 / 上传 / 从库里挑一颗来写
LibraryFragment     云端标签库
ManualFragment      古法写入
SettingsFragment    设置
TagRowAdapter       标签列表的一行（标签库和挑弹窗共用）
Bg                  单线程后台执行器
```

> `Bg` 是**单线程**的，这是刻意的：读、写、查云端都要碰同一个串口，
> 两个页面同时操作会互相踩。串行从根上避免这件事。

**测试卡片动画**：设置页有「预览动画」按钮，用假数据演一遍，**不碰读写器、蜂鸣器不响**。
（这台机器的蜂鸣器是硬件驱动的，`0x7A 00` 关不掉 —— 调试 UI 别用真标签。）

## 云端标签库（Cloudflare Worker + D1）

App 可以把读到的标签传到云端、命名、以及从云端列表**一键写入**。

```
读 EPC/TID → 完整性校验 → 上传到云端 → 立刻命名
打开云端标签库 → 选一颗 → 写入（用当前已连接的读写器）
```

后端在 [`cloud/`](../cloud/)，已经部署好：`https://rfid-tags.root-60a.workers.dev`。

**地址和 API key 已经编译进 APK 了，装完就能用，不用先配置。**
更具体的说：

| 项 | 默认值 | 怎么改 |
|---|---|---|
| Worker 地址 | `https://rfid-tags.root-60a.workers.dev` | 设置里改；**清空 = 回到这个默认值** |
| API key | 编译时注入（`build.gradle` 的 `buildConfigField`） | 同上 |
| 使用者 | **手机自己的名字**（`Settings.Global.DEVICE_NAME`，取不到就退回型号） | 设置里填了就优先用填的 |
| 读完自动上传 | **开** | 设置里可以关掉，关掉后读完点「上传到云端」 |

设置界面（云端标签库 → 设置）还有个「恢复默认」，一键清掉所有自定义。

### 读完之后会发生什么

```
读 EPC/TID
  ├─ 完整性 ✓  ──→ 先查云端
  │                 ├─ 已在库里 ──→ 蓝卡：命中「名称」，不再重复上传
  │                 └─ 库里没有 ──→ 上传 ──→ 琥珀卡 + 命名框
  └─ 完整性 ✗  ──→ 再读一次
                    ├─ 两次读数完全相同 ──→ 按「异常」破例上传 + 记录
                    └─ 两次不同         ──→ 提示不合格，不上传
```

### 云端记的两个数

| 字段 | 含义 | 什么时候 +1 |
|---|---|---|
| `seenCount` / `lastSeen` | 读到过几次 / 最后读到 | App 每次读到（并命中云端）时上报 |
| `writeCount` / `lastWritten` | 写成功几次 / 最后写入 | **EPC 和 TID 两个区都写成功**才 +1，失败不算 |

老字段 `reads`（历史上传次数）保留兼容，但界面不再显示 —— 现在命中就不重复上传了，
那个数不会再涨，显示出来只会误导。

### 测试卡片动画（不会响蜂鸣器）

**长按主页顶部的大标题「UHF RFID」** → 用假数据依次演一遍「命中云端」和「写入成功」
两张卡片，**完全不碰读写器**。

为什么需要这个：想看动画就得读一次标签，而读写器每读到一次就响一声 ——
你这台机器的蜂鸣器是**硬件级**的，`0x7A` 静音命令读写器会回"已接受"但实际不生效
（见下面的「蜂鸣器」一节）。所以调 UI 时用预览，别用真标签。

### 蜂鸣器

App 连接时会发 `0x7A 00`（关蜂鸣器），设置里的「关蜂鸣器」**默认勾选**且会记住。

> ⚠ **但在这台读写器上关不掉。** 实测读写器对 `0x7A 00` 回 `0x10`（已接受），
> 蜂鸣器照响 —— 说明它的蜂鸣器是**射频检测直接驱动**的硬件电路，固件没接这个开关。
> 这不是 App 的问题，软件层面做不到。想安静就别让它读到标签（比如用上面的动画预览）。

### 完整性规则（{完整}）

这颗标签的 EPC 和 TID **只差第 9 个 hex 位**：EPC 是 `0`、TID 是 `2`，其余一致。

```
EPC  E2806915 0 000502008B25C75
TID  E2806915 2 000502008B25C75
            ↑ 第 9 位
```

读完之后 App 立刻给出结论（绿色 ✓ / 红色 ✗ 并说明第几位不符）。

**不合格怎么办**：不直接丢弃 —— 会再读一次，**如果两次读数完全相同**，就按"异常"
破例上传，并在云端 `anomalies` 表留一条流水。理由是：连续两次读到同样的数据本身
就是有价值的信息，但它必须能一眼看出不合格。

规则在两处独立实现，**必须保持一致**：
- App：`TagValidator.java`
- 服务端：`cloud/src/index.js` 的 `checkComplete()`

有脚本专门比对两者：

```bash
python cloud/check_agree.py        # 9 条用例同时喂给两边，逐条比对结论
```

改规则时**两边一起改**再跑这个脚本 —— 之前就因为用例表写了两份而漂移过一次。

### 已验证 / 未验证

| 功能 | 状态 |
|---|---|
| 云端列表、中文、异常标记、后 5 位 | ✅ 模拟器实测 |
| 命名 / 删除 | ✅ 模拟器实测 |
| 未连接时点「写入」的提示 | ✅ 模拟器实测 |
| 校验规则两端一致 | ✅ 9/9 用例一致 |
| 读完标签后上传 + 立刻命名 | ⚠ 需要真读写器 + 场上有标签 |
| 从列表一键写入 | ⚠ 同上 |
| 「连续两次相同读数」破例上传 | ⚠ 同上 |

> 云端功能**不需要读写器**，所以测这些不用占用串口。测写入才需要接上读写器。

## 已知限制

- **TID 能不能写取决于标签**。我们测的这颗（`E2806915…`）是**可写的**，实测写入并
  回读一致；标准 Gen2 的 TID 是出厂只读的。程序不再假设 TID 只读，写完回读校验，
  写不进去会如实报告并提示可以改用 USER 区（bank 3）。
- 只做了单天线口 + 单次读写，没有做连续盘存/防碰撞列表。
- 没有做断线自动重连：拔了线要重新点「连接」。
- 在模拟器里跑 USB 透传时**连接偶发失败**（4 次里失败 2 次），是 QEMU 透传控制
  传输不稳，已用重试兜住；真机上没这个问题。
- 界面文字是中文，没有做多语言。
