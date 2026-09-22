package com.hanwenjiang.uhfrfid;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;

import com.hoho.android.usbserial.driver.UsbSerialDriver;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * 读写器的 USB 连接。
 *
 * <p>从 MainActivity 里抽出来的 —— 这部分（打开串口、CH34x 初始化、自驱回退）是逆着原厂
 * 工具调出来的，逻辑一行没改，只是搬了个家，好让 Compose 那套界面能直接用。
 *
 * <p>进度和结果都写进 {@link AppState}，界面自己观察。
 */
public class UsbConnect {

    public static final String ACTION_USB_PERMISSION =
            "com.hanwenjiang.uhfrfid.USB_PERMISSION";

    private final Context app;
    private final UsbManager usbManager;
    private final Set<UsbDevice> granted = new HashSet<>();

    /** 自动连接只试一次；用户主动断开后允许再试（插拔时复位）。 */
    private boolean autoTried;

    /**
     * 正在连接中。
     *
     * <p>连接要好几秒（库的版本校验会重试 6 次再切自驱）。期间再点那颗图标、
     * 或者 onResume 又触发一次自动连，就会起第二个连接去抢同一个串口 ——
     * 表现是「连接失败：串口没打开」。
     */
    private boolean connecting;

    public UsbConnect(Context ctx) {
        this.app = ctx.getApplicationContext();
        this.usbManager = (UsbManager) app.getSystemService(Context.USB_SERVICE);
    }

    /** 把广播接收器要监听的动作填进去。 */
    public static IntentFilter filter() {
        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        return f;
    }

    /** 宿主把这个接收器注册上就行。 */
    public final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                RfidSession.get().onTransportLost(app.getString(R.string.device_unplugged));
                AppState.INSTANCE.setConnected(false);
                AppState.INSTANCE.setStatus(app.getString(R.string.device_unplugged));
                return;
            }
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                onDeviceAttached();
                return;
            }
            if (!ACTION_USB_PERMISSION.equals(action)) return;
            UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    && dev != null) {
                granted.add(dev);
                doConnect();
            } else {
                AppState.INSTANCE.setStatus(app.getString(R.string.perm_denied));
            }
        }
    };

    // ------------------------------------------------------------ 自动 / 手动

    /**
     * 自动连接：能连就连，连不上就退回「点图标」。
     *
     * <p>每次启动只试一次；没插设备不算"试过了"，否则先开 App 再插读写器时会永远不连。
     */
    public void autoConnect() {
        if (RfidSession.get().isConnected() || autoTried) return;
        java.util.List<UsbSerialDriver> drivers = SerialTransport.listDrivers(usbManager);
        if (drivers.isEmpty()) return;
        autoTried = true;
        connect();
    }

    /** 插上读写器 = 明确的意图，允许再自动连一次（哪怕之前主动断开过）。 */
    private void onDeviceAttached() {
        autoTried = false;
        autoConnect();
    }

    /**
     * 系统权限窗点过「允许」之后的入口。
     *
     * <p>单独留一个名字是为了以后想在授权后做点别的事（比如自动重连前先复位）
     * 时不用改广播那一段。
     */
    private void doConnect() {
        connect();
    }

    /** 顶栏那颗图标：连着就断开，没连就连接。 */
    public void toggle() {
        if (RfidSession.get().isConnected()) {
            RfidSession.get().disconnect();
            // 主动断开就保持断开：不再被自动连回来
            autoTried = true;
            AppState.INSTANCE.setConnected(false);
            AppState.INSTANCE.setStatus(app.getString(R.string.disconnected_manual));
            return;
        }
        requestThenConnect();
    }

    private void requestThenConnect() {
        java.util.List<UsbSerialDriver> drivers = SerialTransport.listDrivers(usbManager);
        if (drivers.isEmpty()) {
            AppState.INSTANCE.setStatus(app.getString(R.string.no_device_found));
            return;
        }
        for (UsbSerialDriver d : drivers) {
            if (usbManager.hasPermission(d.getDevice())) granted.add(d.getDevice());
        }
        if (!granted.isEmpty()) {
            connect();
            return;
        }
        requestPermission(drivers.get(0).getDevice());
    }

    /**
     * 弹系统 USB 权限窗。
     *
     * <p>Android 的 USB 权限必须由用户点一次，绕不过去；能做的是把它自动弹出来。
     * 清单里声明了 USB 设备，所以这个弹窗带「总是允许」的勾选框，勾一次就不再问。
     */
    private void requestPermission(UsbDevice dev) {
        AppState.INSTANCE.setStatus(
                app.getString(R.string.requesting_perm, SerialTransport.describe(dev)));
        // 必须 MUTABLE：系统要往这个 Intent 里补 EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED
        // 但 targetSdk 34+ 规定 FLAG_MUTABLE 不能配隐式 Intent，所以先 setPackage 变显式
        Intent perm = new Intent(ACTION_USB_PERMISSION);
        perm.setPackage(app.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        usbManager.requestPermission(dev,
                PendingIntent.getBroadcast(app, 0, perm, flags));
    }

    // ------------------------------------------------------------ 连接

    private void step(String s) {
        AppState.INSTANCE.setConnecting(true);
        AppState.INSTANCE.setConnectResult(null);
        AppState.INSTANCE.setConnectStep(s);
        AppState.INSTANCE.setStatus(app.getString(R.string.connecting));
    }

    public void connect() {
        if (connecting) return;      // 已经在连了，别起第二个抢串口
        connecting = true;

        final int ant = Prefs.antenna(app);
        final int pw = Prefs.power(app);
        final boolean doReset = Prefs.restartOnConnect(app);
        final boolean mute = Prefs.muteBeep(app);

        step(app.getString(R.string.connect_step_port));
        Bg.run(() -> {
            try {
                // CH34x 的初始化（读 0x5F 校验版本）有时会返回 0xff/0xec 而失败，
                // 重试几次通常能过。所以多试几次、退避递增。
                Transport t = null;
                IOException lastErr = null;
                final int tries = 6;
                for (int attempt = 1; attempt <= tries && t == null; attempt++) {
                    if (attempt > 1) {
                        step(app.getString(R.string.connect_step_retry, attempt, tries));
                    }
                    Transport cand = new SerialTransport(usbManager, granted);
                    try {
                        cand.open();
                        t = cand;
                    } catch (IOException e) {
                        lastErr = e;
                        cand.close();
                        if (attempt < tries) Thread.sleep(250L * attempt);
                    }
                }
                if (t == null) {
                    // 库被版本校验挡死（这颗芯片读 0x5F 回的字节不是 0x00），
                    // 那就自己驱动 —— 同一套寄存器序列，跳过那次校验。
                    if (granted.isEmpty()) throw lastErr;
                    step(app.getString(R.string.connect_step_selfdriven));
                    UsbDevice rawDev = granted.iterator().next();
                    Ch341RawTransport raw = new Ch341RawTransport(usbManager, rawDev);
                    try {
                        raw.open();
                        t = raw;
                    } catch (Exception e2) {
                        raw.close();
                        throw lastErr;
                    }
                }

                RfidReader reader = RfidSession.get().use(t);
                if (doReset) {
                    step(app.getString(R.string.connect_step_reset));
                    reader.restart();
                }
                reader.clearEpcMatch();
                step(app.getString(R.string.connect_step_radio, ant, pw));
                if (mute) reader.setBeep(false);
                reader.setAntenna(ant);
                reader.setPower(pw);
                step(app.getString(R.string.connect_step_version));
                byte[] ver = reader.readVersion();

                String msg = app.getString(R.string.connected, t.describe())
                        + (ver != null ? "\n" + app.getString(R.string.firmware_line,
                                RfidFrame.hexSpaced(ver)) : "")
                        + "\n" + app.getString(R.string.radio_line, ant, pw);
                done(true, msg);
            } catch (Exception e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                done(false, app.getString(R.string.connect_failed, why));
            }
        });
    }

    private void done(boolean ok, String msg) {
        AppState.INSTANCE.setConnecting(false);
        AppState.INSTANCE.setConnectResult(ok);
        AppState.INSTANCE.setConnectMessage(msg);
        AppState.INSTANCE.setConnected(ok);
        AppState.INSTANCE.setStatus(msg);
        connecting = false;
    }

    // ------------------------------------------------------------ 诊断

    /** 当前连不上的具体原因（给"读标签"按钮用）。 */
    public String whyNotConnected() {
        try {
            java.util.List<UsbSerialDriver> ds = SerialTransport.listDrivers(usbManager);
            if (ds.isEmpty()) return app.getString(R.string.no_device_found);
            for (UsbSerialDriver d : ds) {
                if (!usbManager.hasPermission(d.getDevice())) {
                    return app.getString(R.string.need_perm_hint);
                }
            }
            return app.getString(R.string.port_not_open);
        } catch (Exception e) {
            return app.getString(R.string.need_connect_first);
        }
    }

    public String usbDiagnostics() {
        StringBuilder sb = new StringBuilder(app.getString(R.string.diag_header) + "\n");
        java.util.List<UsbSerialDriver> ds = SerialTransport.listDrivers(usbManager);
        sb.append(app.getString(R.string.diag_count, ds.size())).append('\n');
        for (UsbSerialDriver d : ds) {
            UsbDevice dev = d.getDevice();
            sb.append(String.format("%04X:%04X  %s  接口数=%d  权限=%s%n",
                    dev.getVendorId(), dev.getProductId(), SerialTransport.describe(dev),
                    dev.getInterfaceCount(),
                    usbManager.hasPermission(dev) ? app.getString(R.string.diag_yes)
                            : app.getString(R.string.diag_no)));
        }
        return sb.toString();
    }

    /** 直接读 CH34x 的版本寄存器，看芯片到底回什么（排查初始化失败用）。 */
    public String chipProbe() {
        java.util.List<UsbSerialDriver> ds = SerialTransport.listDrivers(usbManager);
        if (ds.isEmpty()) return app.getString(R.string.diag_no_device);
        UsbDevice dev = ds.get(0).getDevice();
        if (!usbManager.hasPermission(dev)) return app.getString(R.string.diag_no_perm);
        UsbDeviceConnection conn = usbManager.openDevice(dev);
        if (conn == null) return app.getString(R.string.diag_open_failed);
        try {
            StringBuilder sb = new StringBuilder(app.getString(R.string.diag_probe) + "\n");
            for (int reg : new int[]{0x5F, 0x5F, 0x95}) {
                byte[] buf = new byte[2];
                int n = conn.controlTransfer(0xC0, reg, 0, 0, buf, buf.length, 500);
                sb.append(String.format("  0x%02X -> %s%n", reg,
                        n > 0 ? RfidFrame.hexSpaced(RfidFrame.slice(buf, 0, n)) : ("n=" + n)));
            }
            return sb.toString();
        } finally {
            conn.close();
        }
    }
}
