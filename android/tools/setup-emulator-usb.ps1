# setup-emulator-usb.ps1 —— 让 Android 模拟器能用真 USB 串口读写器
#
# 为什么需要这个脚本：官方模拟器**支持** USB 透传，但有三道坎，缺一个都不行。
#
#   1) `-usb-passthrough vendorid=1a86` 会让模拟器**直接崩掉**。
#      emulator 把这个值原样转给 QEMU 的 `-device usb-host`，而 QEMU 要求 int64，
#      必须写成 `vendorid=0x1a86`。报错只在 stderr 里出现：
#          Parameter 'vendorid' expects an int64 value or range
#
#   2) Windows 上还需要 SDK 自带的 USB 辅助驱动，否则报
#      "Cannot load Android USB Assistant Driver ... might not work"。
#
#   3) 模拟器的 system 镜像里**没有** android.hardware.usb.host 这个特性声明，
#      所以 Android 的 UsbHostManager 根本不启动，UsbManager.getDeviceList()
#      永远返回空。system 分区只读，`adb remount` 在动态分区上会失败
#      （could not map scratch image），所以这里用 bind mount 绕过去，
#      再重启 framework 让 PackageManager 重新读特性。
#      bind mount 必须把 SELinux 标签设成 system_file，否则 system_server
#      读不到文件，zygote 会进入崩溃循环。
#
# 用法（需要管理员权限，powershell 或 pwsh 都行）：
#     powershell -ExecutionPolicy Bypass -File android\tools\setup-emulator-usb.ps1
#
# 还原：脚本不改 system 镜像（bind mount 只在内存里），重启模拟器即恢复原状。
param(
    [string]$VendorId  = "0x1a86",   # CH340/CH341 的厂商号
    [string]$ProductId = "0x5523",   # CH341A；CH340 是 0x7523
    [string]$Avd = "rfid34",
    [int]$SkinW = 480,
    [int]$SkinH = 1040,
    [int]$Density = 200,             # 480px/200dpi*160 ≈ 384dp，正常手机宽度
    [string]$Snapshot = "rfidusb",   # 配好后存这个快照，下次秒起
    [switch]$ColdBoot,               # 强制冷启动（不用快照）
    [switch]$NoWindow
)

# 注意：不要设成 'Stop'。adb / emulator 正常运行时也会往 stderr 写东西，
# PowerShell 5.1 会把那当成终止性错误，脚本会莫名其妙中断。
$ErrorActionPreference = 'Continue'

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$emu = Join-Path $sdk 'emulator\emulator.exe'
$adb = Join-Path $sdk 'platform-tools\adb.exe'

function Step($n, $msg) { Write-Host "`n[$n] $msg" -ForegroundColor Cyan }
function Ok($msg)       { Write-Host "    OK  $msg" -ForegroundColor Green }
function Warn($msg)     { Write-Host "    !!  $msg" -ForegroundColor Yellow }

# ---------------------------------------------------------------- 0. 检查
Step 0 '检查环境'
if (-not (Test-Path $emu)) { throw "找不到 $emu —— 先装 emulator：sdkmanager --install emulator" }
$isAdmin = ([Security.Principal.WindowsPrincipal] `
    [Security.Principal.WindowsIdentity]::GetCurrent()
).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) { Warn '不是管理员，装驱动那一步会失败（可以先手动装好再跑）' }
Ok "SDK = $sdk"

# ------------------------------------------------- 1. USB 辅助驱动（Windows）
Step 1 'USB 辅助驱动（UsbAssist）'
$drvDir = Join-Path $sdk 'emulator\drivers'
$svc = Get-Service -Name UsbAssist -ErrorAction SilentlyContinue
if ($svc -and $svc.Status -eq 'Running') {
    Ok 'UsbAssist 服务已在运行'
} else {
    Push-Location $drvDir
    try {
        & cmd /c 'Android_USB_Assistant_Install.bat' 2>&1 | Out-Null
        & cmd /c 'UsbAssist_Install.bat' 2>&1 | Out-Null
    } finally { Pop-Location }
    $svc = Get-Service -Name UsbAssist -ErrorAction SilentlyContinue
    if ($svc -and $svc.Status -eq 'Running') { Ok 'UsbAssist 已安装并启动' }
    else { Warn 'UsbAssist 没起来，透传可能不可用' }
}

# ------------------------------------------------------- 2. 启动模拟器
Step 2 ('启动模拟器（USB 透传 {0}:{1}）' -f $VendorId, $ProductId)
Get-Process -Name 'emulator*','qemu*' -ErrorAction SilentlyContinue |
    Stop-Process -Force -ErrorAction SilentlyContinue
& $adb kill-server 2>$null | Out-Null
Start-Sleep -Seconds 4

$emuArgs = @('-avd', $Avd, '-no-snapshot', '-no-boot-anim', '-gpu', 'auto',
             '-skin', "${SkinW}x${SkinH}",
             '-usb-passthrough', "vendorid=$VendorId,productid=$ProductId")
if ($NoWindow) { $emuArgs += '-no-window' }
Write-Host "    emulator $($emuArgs -join ' ')"
Start-Process -FilePath $emu -ArgumentList $emuArgs `
    -RedirectStandardOutput "$env:TEMP\emu-usb-out.txt" `
    -RedirectStandardError  "$env:TEMP\emu-usb-err.txt" | Out-Null

& $adb start-server 2>$null | Out-Null
$serial = $null
foreach ($i in 1..60) {
    Start-Sleep -Seconds 5
    $line = (& $adb devices) | Select-String 'emulator-\d+\s+device'
    if ($line) { $serial = ($line -split '\s+')[0]; break }
    if (-not (Get-Process -Name 'qemu-system-x86_64*' -ErrorAction SilentlyContinue)) {
        Get-Content "$env:TEMP\emu-usb-err.txt" -Tail 5 -ErrorAction SilentlyContinue
        throw '模拟器退出了 —— 检查 -usb-passthrough 的 vendorid 是否带 0x 前缀'
    }
}
if (-not $serial) { throw '模拟器没上线' }
Ok "设备 $serial"

foreach ($i in 1..60) {
    if (((& $adb -s $serial shell getprop sys.boot_completed 2>&1) -join '') -match '1') { break }
    Start-Sleep -Seconds 5
}
Ok '系统启动完成'

# --------------------------------------------- 3. 注入 usb.host 特性声明
Step 3 '注入 android.hardware.usb.host 声明'
& $adb -s $serial root 2>&1 | Out-Null
Start-Sleep -Seconds 4
& $adb -s $serial wait-for-device

$decl = Join-Path $env:TEMP 'android.hardware.usb.host.xml'
$xml = '<?xml version="1.0" encoding="utf-8"?>' + "`n" +
       '<permissions>' + "`n" +
       '    <feature name="android.hardware.usb.host" />' + "`n" +
       '</permissions>' + "`n"
[System.IO.File]::WriteAllText($decl, $xml, (New-Object System.Text.UTF8Encoding($false)))

& $adb -s $serial shell 'rm -rf /data/local/tmp/perms; mkdir -p /data/local/tmp/perms; cp -a /system/etc/permissions/. /data/local/tmp/perms/' 2>&1 | Out-Null
& $adb -s $serial push $decl /data/local/tmp/perms/ 2>&1 | Out-Null
# 标签不对的话 system_server 读不到，zygote 会崩溃循环
& $adb -s $serial shell 'chcon -R u:object_r:system_file:s0 /data/local/tmp/perms' 2>&1 | Out-Null
& $adb -s $serial shell 'mount --bind /data/local/tmp/perms /system/etc/permissions' 2>&1 | Out-Null
$n = ((& $adb -s $serial shell 'ls /system/etc/permissions/ | wc -l') -join '').Trim()
Ok "已挂载（$n 个文件）"

Step 4 '重启 framework 让特性生效'
& $adb -s $serial shell stop  2>&1 | Out-Null
Start-Sleep -Seconds 6
& $adb -s $serial shell start 2>&1 | Out-Null
$feat = $false
foreach ($i in 1..60) {
    Start-Sleep -Seconds 5
    if ((& $adb -s $serial shell 'pm list features' 2>&1) -match 'android\.hardware\.usb\.host') {
        $feat = $true
        break
    }
}
if ($feat) { Ok 'android.hardware.usb.host 已生效' } else { throw '特性没生效' }

# ---------------------------------------------------------- 5. 验证
Step 5 '验证透传设备 + 分辨率'
& $adb -s $serial shell "wm density $Density" 2>&1 | Out-Null
$id = ((& $adb -s $serial shell 'cat /sys/bus/usb/devices/1-1/idVendor; cat /sys/bus/usb/devices/1-1/idProduct' 2>&1) -join ':').Trim()
Ok "客户机 USB 设备 1-1 = $id"

if ((& $adb -s $serial shell 'dumpsys usb' 2>&1) -match 'host_manager') {
    Ok 'UsbHostManager 在运行'
} else {
    Warn 'UsbHostManager 没看到，应用可能仍扫不到设备'
}

Write-Host @"

全部就绪。接下来：

  cd android
  .\gradlew.bat assembleDebug
  adb -s $serial install -r app\build\outputs\apk\debug\app-debug.apk
  adb -s $serial shell am start -n com.hanwenjiang.uhfrfid/.MainActivity

App 里点「连接」——第一次会弹 USB 权限，点允许。
（权限的 PendingIntent 必须是 FLAG_MUTABLE，否则弹窗一闪而过且收不到回调。）

想还原：关掉模拟器重开即可，system 镜像没有被修改（bind mount 只在内存里）。
"@ -ForegroundColor Gray
