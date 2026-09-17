package com.hanwenjiang.uhfrfid;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 单页界面：连接 → 读 EPC/TID → 可选写 EPC/TID。
 *
 * <p>连接状态放在 {@link RfidSession} 单例里，所以 Activity 被系统重建
 * （转屏、插拔 USB、改系统设置）之后能接回原来的连接，不会看着像"自己重启了"。
 */
public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION =
            "com.hanwenjiang.uhfrfid.USB_PERMISSION";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Set<UsbDevice> granted = new HashSet<>();

    private RfidSession session;
    private UsbManager usbManager;

    private TextView statusText, epcText, tidText, logText;
    private Button connectBtn, readBtn, writeBtn;
    private EditText antennaEdit, powerEdit, epcEdit, tidEdit;
    private CheckBox restartCheck, muteCheck;

    private final BroadcastReceiver permReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean ok = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (ok && dev != null) {
                granted.add(dev);
                doConnect();
            } else {
                setStatus("USB 权限被拒绝。没有权限就打不开串口。");
            }
        }
    };

    /** USB 被拔掉时立刻把连接收掉，别让界面显示"已连接"但实际发不出数据。 */
    private final BroadcastReceiver detachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                if (session.transport instanceof SerialTransport) {
                    session.onTransportLost("USB 设备被拔掉了");
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        session = RfidSession.get();
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        statusText = findViewById(R.id.statusText);
        epcText = findViewById(R.id.epcText);
        tidText = findViewById(R.id.tidText);
        logText = findViewById(R.id.logText);
        logText.setMovementMethod(new ScrollingMovementMethod());

        connectBtn = findViewById(R.id.connectBtn);
        readBtn = findViewById(R.id.readBtn);
        writeBtn = findViewById(R.id.writeBtn);

        antennaEdit = findViewById(R.id.antennaEdit);
        powerEdit = findViewById(R.id.powerEdit);
        epcEdit = findViewById(R.id.epcEdit);
        tidEdit = findViewById(R.id.tidEdit);

        restartCheck = findViewById(R.id.restartCheck);
        muteCheck = findViewById(R.id.muteCheck);

        connectBtn.setOnClickListener(v -> connectOrDisconnect());
        readBtn.setOnClickListener(v -> readOnce());
        writeBtn.setOnClickListener(v -> writeOnce());
        findViewById(R.id.scanUsbBtn).setOnClickListener(
                v -> setStatus(usbDiagnostics()));
        // 长按 = 芯片寄存器探针（排查 CH34x 初始化失败用）
        findViewById(R.id.scanUsbBtn).setOnLongClickListener(v -> {
            setStatus(chipProbe());
            return true;
        });

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(permReceiver, filter);
        }

        session.listener = reason -> ui.post(() -> {
            reflectConnectionState();
            setStatus("连接已断开：" + reason);
        });

        // Activity 被重建后把上次的状态恢复回来。
        // 顺序很重要：先让 reflectConnectionState() 按连接状态刷按钮，
        // 再用 lastStatus 覆盖文字 —— 反过来的话读取/写入的报告会被
        // 「已连接 xxx」冲掉。
        epcText.setText(session.lastEpc);
        tidText.setText(session.lastTid);
        reflectConnectionState();
        if (session.lastStatus != null && !session.lastStatus.isEmpty()) {
            setStatus(session.lastStatus);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        session.listener = null;
        try {
            unregisterReceiver(permReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        // 注意：这里**不**关闭连接 —— 转屏、切后台都会走到这里，
        // 关掉的话就成了「用着用着就断了」。真正断开的时机是用户点「断开」。
        worker.shutdown();
    }

    /** 按 session 的实际状态刷新按钮和文字。 */
    private void reflectConnectionState() {
        boolean connected = session.isConnected();
        readBtn.setEnabled(connected);
        writeBtn.setEnabled(connected);
        connectBtn.setText(connected ? R.string.disconnect : R.string.connect);
        if (connected && session.transport != null) {
            setStatus("已连接 " + session.transport.describe());
        }
    }

    /**
     * 扫描 USB 总线，把看到的设备全列出来 —— 不管驱动认不认识它。
     *
     * <p>这是排查「插上没反应」最有用的东西：如果这里能看到 1a86:5523（CH341），
     * 说明 USB 这一层通了，问题在权限或驱动匹配；如果一个设备都没有，
     * 那就是 OTG 线/手机不支持 USB Host/系统 OTG 开关没开。
     */
    private String usbDiagnostics() {
        StringBuilder sb = new StringBuilder();
        java.util.HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        sb.append("手机上看到的 USB 设备：").append(devices.size()).append(" 个\n");
        if (devices.isEmpty()) {
            sb.append("  一个都没有。\n"
                    + "  → 检查 OTG 线（要能传数据）、手机是否支持 USB Host、\n"
                    + "     小米/红米要在「设置 → 更多设置 → OTG」里打开。");
            return sb.toString();
        }
        java.util.Set<String> probed = new HashSet<>();
        for (com.hoho.android.usbserial.driver.UsbSerialDriver d
                : SerialTransport.listDrivers(usbManager)) {
            probed.add(String.format("%04x:%04x", d.getDevice().getVendorId(),
                    d.getDevice().getProductId()));
        }
        for (UsbDevice d : devices.values()) {
            String vidpid = String.format("%04x:%04x", d.getVendorId(), d.getProductId());
            sb.append("  ").append(vidpid)
              .append("  ").append(SerialTransport.describe(d))
              .append("\n      接口数=").append(d.getInterfaceCount())
              .append("  权限=").append(usbManager.hasPermission(d) ? "有" : "无")
              .append("  串口驱动=").append(probed.contains(vidpid) ? "认识 ✓" : "不认识 ✗")
              .append("\n");
        }
        if (probed.isEmpty()) {
            sb.append("  没有设备被串口驱动认出来。\n"
                    + "  → CH340/CH341 是 1a86:5523 或 1a86:7523，"
                    + "如果你看到它但显示「不认识」，把这个结果发我。");
        }
        return sb.toString();
    }

    /**
     * 直接对芯片发一次控制传输，读版本寄存器 0x5F 的两个字节。
     *
     * <p>usb-serial-for-android 的 CH34x 初始化第 1 步就是读这个寄存器，并且要求
     * <b>第 2 个字节等于 0x00</b>，否则报 "Expected 0x0 byte, but get 0xXX [init #1]"。
     * 把两个字节都打出来，就能判断是芯片本身这么回、还是中间传输层（比如模拟器的
     * USB 透传）把数据搞坏了。
     */
    private String chipProbe() {
        java.util.List<com.hoho.android.usbserial.driver.UsbSerialDriver> drivers =
                SerialTransport.listDrivers(usbManager);
        if (drivers.isEmpty()) return "没有可探测的串口设备。";
        UsbDevice dev = drivers.get(0).getDevice();
        if (!usbManager.hasPermission(dev)) {
            return "还没有 USB 权限，先点一次「连接」把权限给了再探。";
        }
        android.hardware.usb.UsbDeviceConnection conn =
                usbManager.openDevice(dev);
        if (conn == null) return "openDevice 返回 null。";
        try {
            StringBuilder sb = new StringBuilder();
            // 0xC0 = 设备到主机 | 厂商请求 | 设备接收者
            int[] reqs = {0x5F, 0x5F, 0x95};
            for (int req : reqs) {
                byte[] buf = new byte[2];
                int n = conn.controlTransfer(0xC0, req, 0, 0, buf, buf.length, 500);
                if (n < 0) {
                    sb.append(String.format("  控制传输 0x%02X -> 失败 (n=%d)%n", req, n));
                } else {
                    sb.append(String.format("  控制传输 0x%02X -> %d 字节: %s%n",
                            req, n, RfidFrame.hexSpaced(RfidFrame.slice(buf, 0, n))));
                }
            }
            return "CH34x 芯片寄存器探测（期望 0x5F 返回的第 2 字节 = 00）：\n" + sb;
        } finally {
            conn.close();
        }
    }

    // ------------------------------------------------------------ 连接

    private void connectOrDisconnect() {
        if (session.isConnected()) {
            session.disconnect();
            session.lastStatus = "已断开。";
            reflectConnectionState();
            setStatus(session.lastStatus);
            return;
        }
        requestPermissionThenConnect();
    }

    private void requestPermissionThenConnect() {
        java.util.List<com.hoho.android.usbserial.driver.UsbSerialDriver> drivers =
                SerialTransport.listDrivers(usbManager);
        if (drivers.isEmpty()) {
            setStatus("没找到 USB 串口设备。\n"
                    + "① OTG 线插好（要能传数据，不是只能充电的）\n"
                    + "② 手机支持 USB Host\n"
                    + "③ 小米/红米：「设置 → 更多设置 → OTG」要打开，默认 10 分钟自动关\n"
                    + "④ 模拟器透传不了 USB 串口，要真机才能测 USB 模式");
            return;
        }
        for (com.hoho.android.usbserial.driver.UsbSerialDriver d : drivers) {
            if (usbManager.hasPermission(d.getDevice())) granted.add(d.getDevice());
        }
        if (!granted.isEmpty()) {
            doConnect();
            return;
        }
        UsbDevice dev = drivers.get(0).getDevice();
        setStatus("请求 USB 权限：" + SerialTransport.describe(dev) + " …");
        // 这个 PendingIntent 必须是 MUTABLE：USB 权限对话框由系统弹出，系统会往
        // 这个 Intent 里补 EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED；用 IMMUTABLE
        // 系统塞不进 extras，回调收不到 —— 表现是「弹窗一闪而过，然后毫无反应」。
        //
        // 但 targetSdk 34+ 又规定：FLAG_MUTABLE **不能配隐式 Intent**，否则直接抛
        //   IllegalArgumentException: Targeting U+ (version 34 and above) disallows
        //   creating ... a PendingIntent with FLAG_MUTABLE, an implicit Intent ...
        // 所以要先 setPackage() 把它变成显式 Intent，两者才能同时满足。
        Intent perm = new Intent(ACTION_USB_PERMISSION);
        perm.setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, perm, flags);
        usbManager.requestPermission(dev, pi);
    }

    private void doConnect() {
        final int ant = parseInt(antennaEdit, 1);
        final int pw = parseInt(powerEdit, 26);
        final boolean doReset = restartCheck.isChecked();
        final boolean mute = muteCheck.isChecked();

        setStatus("正在连接…");
        worker.execute(() -> {
            try {
                // 打开串口本身也重试：CH34x 的初始化握手（读版本寄存器 0x5F）
                // 在中间传输层不稳时会偶发失败，报
                //   Expected 0x0 byte, but get 0xec [init #1]
                // （实测：模拟器的 USB 透传下 4 次里失败 2 次；真机上少见但也会有）
                Transport t = null;
                IOException lastErr = null;
                for (int attempt = 1; attempt <= 3 && t == null; attempt++) {
                    Transport cand = new SerialTransport(usbManager, granted);
                    try {
                        cand.open();
                        t = cand;
                    } catch (IOException e) {
                        lastErr = e;
                        cand.close();
                        if (attempt < 3) {
                            appendLog("打开串口失败（第 " + attempt + " 次）："
                                    + e.getMessage() + "，重试…");
                            Thread.sleep(400);
                        }
                    }
                }
                if (t == null) throw lastErr;

                RfidReader reader = session.use(t);

                if (doReset) reader.restart();
                reader.clearEpcMatch();
                if (mute) reader.setBeep(false);
                reader.setAntenna(ant);
                reader.setPower(pw);
                byte[] ver = reader.readVersion();

                appendLog(t.takeLog());
                String msg = "已连接 " + t.describe()
                        + (ver != null ? "\n固件 " + RfidFrame.hexSpaced(ver) : "")
                        + "\n天线 " + ant + "  功率 " + pw
                        + (mute ? "  蜂鸣器已关" : "");
                session.lastStatus = msg;
                ui.post(() -> {
                    reflectConnectionState();
                    setStatus(msg);
                });
            } catch (Exception e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                session.lastStatus = "连接失败：" + why;
                ui.post(() -> {
                    reflectConnectionState();
                    setStatus(session.lastStatus);
                });
            }
        });
    }

    // ------------------------------------------------------------ 读

    private void readOnce() {
        setStatus("查询中…");
        worker.execute(() -> {
            try {
                long t0 = System.currentTimeMillis();
                RfidFrame.TagId id = session.reader.readEpcTid();
                long dt = System.currentTimeMillis() - t0;
                String raw = session.transport.takeLog();
                if (id.ok()) {
                    session.lastEpc = id.epc;
                    session.lastTid = id.tid == null ? "(应答里没有)" : id.tid;
                    session.lastStatus = "读到标签（" + dt + " ms）";
                } else {
                    session.lastEpc = "—";
                    session.lastTid = "—";
                    session.lastStatus = "没读到标签。\n把标签贴近天线，"
                            + "或换天线口/功率再试（蜂鸣器每读到一次会响一声）。";
                }
                final String s = session.lastStatus;
                ui.post(() -> {
                    epcText.setText(session.lastEpc);
                    tidText.setText(session.lastTid);
                    setStatus(s);
                    appendLog(raw);
                });
            } catch (Exception e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                session.lastStatus = "查询失败：" + why;
                ui.post(() -> {
                    setStatus(session.lastStatus);
                    reflectConnectionState();
                });
            }
        });
    }

    // ------------------------------------------------------------ 写

    private void writeOnce() {
        final String epcHex = epcEdit.getText().toString().trim();
        final String tidHex = tidEdit.getText().toString().trim();
        if (epcHex.isEmpty() && tidHex.isEmpty()) {
            setStatus("先在 EPC / TID 输入框里填要写的 Hex 值。");
            return;
        }
        setStatus("写入中…（不要拔线）");
        worker.execute(() -> {
            try {
                byte[] epc = RfidFrame.unhex(epcHex);
                byte[] tid = RfidFrame.unhex(tidHex);
                String report = session.reader.writeEpcAndTid(epc, 2, tid, 0);
                String raw = session.transport.takeLog();
                session.lastStatus = report;
                ui.post(() -> {
                    setStatus(report);
                    appendLog(raw);
                    // 注意：这里**不要**顺手 readOnce()。
                    // 它会把刚写出来的报告立刻覆盖成「读到标签」，
                    // 于是写入到底成没成功根本看不见。
                });
            } catch (IllegalArgumentException e) {
                session.lastStatus = "Hex 格式不对：" + e.getMessage();
                ui.post(() -> setStatus(session.lastStatus));
            } catch (Exception e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                session.lastStatus = "写入失败：" + why;
                ui.post(() -> {
                    setStatus(session.lastStatus);
                    reflectConnectionState();
                });
            }
        });
    }

    // ------------------------------------------------------------ 小工具

    private static int parseInt(EditText e, int fallback) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void setStatus(String s) {
        statusText.setText(s);
    }

    private void appendLog(String s) {
        if (s == null || s.isEmpty()) return;
        logText.append(s);
        View v = findViewById(R.id.logText);
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if (tv.getLayout() != null) {
                int scroll = tv.getLayout().getLineTop(tv.getLineCount()) - tv.getHeight();
                if (scroll > 0) tv.scrollTo(0, scroll);
            }
        }
    }
}
