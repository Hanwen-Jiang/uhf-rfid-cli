package com.hanwenjiang.uhfrfid;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
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

    /** 自动连接只试一次（用户主动断开后别再给他连上）。 */
    private boolean autoTried;
    private boolean attached;

    private static final String ACTION_USB_PERMISSION =
            "com.hanwenjiang.uhfrfid.USB_PERMISSION";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Set<UsbDevice> granted = new HashSet<>();

    private RfidSession session;
    private UsbManager usbManager;

    private TextView statusText, epcText, tidText, logText, checkText;
    private Button uploadBtn, cloudBtn;
    private LinearLayout hitCard, resultCard;
    private TextView hitLabel, hitName, hitSub, resultTitle, resultText;
    private Button readBtn, writeBtn;
    private MenuItem connectItem, scanItem;
    private EditText epcEdit, tidEdit;

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
        checkText = findViewById(R.id.checkText);
        uploadBtn = findViewById(R.id.uploadBtn);
        cloudBtn = findViewById(R.id.cloudBtn);
        hitCard = findViewById(R.id.hitCard);
        resultCard = findViewById(R.id.resultCard);
        hitLabel = findViewById(R.id.hitLabel);
        hitName = findViewById(R.id.hitName);
        hitSub = findViewById(R.id.hitSub);
        resultTitle = findViewById(R.id.resultTitle);
        resultText = findViewById(R.id.resultText);
        logText.setMovementMethod(new ScrollingMovementMethod());

        readBtn = findViewById(R.id.readBtn);
        writeBtn = findViewById(R.id.writeBtn);

        epcEdit = findViewById(R.id.epcEdit);
        tidEdit = findViewById(R.id.tidEdit);

        // 连接后复位 / 关蜂鸣器 都挪到设置页了，这里不用再绑定控件


        readBtn.setOnClickListener(v -> readOnce());
        writeBtn.setOnClickListener(v -> writeOnce());
        uploadBtn.setOnClickListener(v -> uploadCurrent());
        // 长按标题预览卡片动画 —— 不连读写器、不读标签，所以蜂鸣器不会响。
        // 挂标题上而不是「上传到云端」按钮：那个按钮没读到标签时是 disabled，
        // 禁用的控件收不到长按事件（踩过一次）。
        findViewById(R.id.titleText).setOnLongClickListener(v -> {
            previewCards();
            return true;
        });
        cloudBtn.setOnClickListener(v -> CloudActivity.open(this));
        // 「扫描」挪到顶栏菜单了（action_scan），对应的处理在 onOptionsItemSelected。
        setupActionBar();

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
    protected void onResume() {
        super.onResume();
        // 开屏（以及从后台回来）自动连 —— 正常流程里不用再点「连接」。
        autoConnect();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 插上读写器时系统会走到这里（清单里声明了 ATTACHED）。
        // singleTop + 已注册的接收者，所以这里是"同一个界面收到通知"，不会重建。
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            attached = true;
            autoConnect();
        }
    }

    /**
     * 自动连接：能连就连，连不上就退回「点按钮」。
     *
     * <p>注意 Android 的 USB 权限<b>必须由用户点一次弹窗</b>，这是安全模型，
     * 任何 App 都绕不过去。这里能做的是<b>把弹窗自动弹出来</b>，而不是等用户先点
     * 「连接」。清单里声明了 USB 设备之后，系统弹窗会带「总是允许」的勾选框，
     * 勾一次以后就不再问了。
     *
     * <p>只自动试一次：用户主动断开之后不该马上又给他连上。
     */
    private void autoConnect() {
        if (session.isConnected() || autoTried) return;
        autoTried = true;

        java.util.List<com.hoho.android.usbserial.driver.UsbSerialDriver> drivers =
                SerialTransport.listDrivers(usbManager);
        if (drivers.isEmpty()) {
            // 没插读写器 —— 什么都不做，别拿提示烦人
            setStatus(getString(R.string.no_device_hint));
            return;
        }
        boolean anyGranted = false;
        for (com.hoho.android.usbserial.driver.UsbSerialDriver d : drivers) {
            if (usbManager.hasPermission(d.getDevice())) anyGranted = true;
        }
        if (anyGranted) {
            // 必须先把已授权的设备放进 granted —— doConnect() 是从这个集合里挑设备的，
            // 不填的话它会说"还没有 USB 权限"（踩过：自动连接因此永远失败）
            for (com.hoho.android.usbserial.driver.UsbSerialDriver d : drivers) {
                if (usbManager.hasPermission(d.getDevice())) granted.add(d.getDevice());
            }
            doConnect();
        } else {
            // 自动弹权限窗（用户点「允许」，勾上"总是"以后就不再弹）
            requestUsbPermission(drivers.get(0).getDevice());
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
        // 顶栏那个「连接/断开」的文字要跟着状态变
        invalidateOptionsMenu();
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
            autoTried = false;      // 主动断开后，下次插拔/回前台可以再自动连
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
        requestUsbPermission(dev);
    }

    /**
     * 弹系统 USB 权限窗。
     *
     * <p>Android 的 USB 权限<b>必须由用户点一次</b>，这是安全模型，绕不过去；
     * 能做的是把它自动弹出来，别让用户先点「连接」才弹。
     * 清单里声明了 USB 设备之后，这个弹窗会带「总是允许」的勾选框 —— 勾一次就不再问。
     */
    private void requestUsbPermission(UsbDevice dev) {
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
        final int ant = Prefs.antenna(this);
        final int pw = Prefs.power(this);
        final boolean doReset = Prefs.restartOnConnect(this);
        // 蜂鸣器：默认静音（读写器每读到一次就响一声，很吵）。勾选框状态会记住。
        final boolean mute = Prefs.muteBeep(this);

        setStatus("正在连接…");
        worker.execute(() -> {
            try {
                // 打开串口本身也重试：CH34x 的初始化握手（读版本寄存器 0x5F）
                // 在中间传输层不稳时会偶发失败，报
                //   Expected 0x0 byte, but get 0xec [init #1]
                // （实测：模拟器的 USB 透传下 4 次里失败 2 次；真机上少见但也会有）

                // CH34x 初始化（读 0x5F 校验版本）有时会返回 0xff/0xec 而失败，
                // 重连几次通常能过。所以这里多试几次、退避递增。
                Transport t = null;
                IOException lastErr = null;
                final int tries = 6;
                for (int attempt = 1; attempt <= tries && t == null; attempt++) {
                    Transport cand = new SerialTransport(usbManager, granted);
                    try {
                        cand.open();
                        t = cand;
                    } catch (IOException e) {
                        lastErr = e;
                        cand.close();
                        if (attempt < tries) {
                            appendLog("打开串口失败（第 " + attempt + "/" + tries + " 次）："
                                    + e.getMessage() + "，重试…");
                            Thread.sleep(250L * attempt);
                        }
                    }
                }
                if (t == null) {
                    // 库的 CH34x 初始化被版本校验挡死（这颗芯片读 0x5F 回的字节不是 0x00），
                    // 那就自己驱动 —— 同一套寄存器序列，跳过那次校验。
                    // 自驱只要一个 UsbDevice，直接在已授权的里面拿。
                    if (granted.isEmpty()) throw lastErr;
                    UsbDevice rawDev = granted.iterator().next();
                    appendLog("库的初始化失败，改用自驱 CH341 再试…");
                    Ch341RawTransport raw = new Ch341RawTransport(usbManager, rawDev);
                    try {
                        raw.open();
                        t = raw;
                    } catch (Exception e2) {
                        raw.close();
                        appendLog("自驱也失败：" + e2.getMessage());
                        throw lastErr;
                    }
                }

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
        readOnce(false);
    }

    /**
     * 读一次标签，并做完整性校验。
     *
     * @param forUpload 这次读是为了上传（会决定是否自动重读一次来确认）
     */
    private void readOnce(boolean forUpload) {
        setStatus("查询中…");
        worker.execute(() -> {
            try {
                long t0 = System.currentTimeMillis();
                RfidFrame.TagId id = session.reader.readEpcTid();
                long dt = System.currentTimeMillis() - t0;
                String raw = session.transport.takeLog();
                if (!id.ok()) {
                    session.lastEpc = "—";
                    session.lastTid = "—";
                    session.verdict = null;
                    session.lastStatus = "没读到标签。\n把标签贴近天线，"
                            + "或换天线口/功率再试（蜂鸣器每读到一次会响一声）。";
                    ui.post(() -> {
                        epcText.setText(session.lastEpc);
                        tidText.setText(session.lastTid);
                        checkText.setText("");
                        setStatus(session.lastStatus);
                        appendLog(raw);
                    });
                    return;
                }

                TagValidator.Result v = TagValidator.check(id.epc, id.tid);
                ui.post(this::hideCards);
                session.lastEpc = id.epc;
                session.lastTid = id.tid == null ? "(应答里没有)" : id.tid;
                session.verdict = v;
                session.lastStatus = "读到标签（" + dt + " ms）";

                ui.post(() -> {
                    epcText.setText(session.lastEpc);
                    tidText.setText(session.lastTid);
                    checkText.setText(v.complete
                            ? getString(R.string.check_ok)
                            : getString(R.string.check_bad, v.detail == null ? v.reason : v.detail));
                    checkText.setTextColor(v.complete ? 0xFF1B5E20 : 0xFFB71C1C);
                    setStatus(session.lastStatus);
                    appendLog(raw);
                    updateCloudButtons();
                });

                if (forUpload && !v.complete) {
                    // 不合格：再读一次，两次读数相同才允许破例上传
                    ui.post(() -> setStatus(getString(R.string.check_retry)));
                    Thread.sleep(250);
                    RfidFrame.TagId again = session.reader.readEpcTid();
                    if (again.ok()) {
                        boolean same = id.epc.equalsIgnoreCase(again.epc)
                                && id.tid != null && id.tid.equalsIgnoreCase(again.tid);
                        if (same) {
                            session.lastStatus = getString(R.string.cloud_bad_twice);
                            session.forceAnomaly = true;
                            ui.post(() -> {
                                setStatus(session.lastStatus);
                                doUpload();
                            });
                            return;
                        }
                    }
                    session.forceAnomaly = false;
                    ui.post(() -> setStatus(getString(R.string.cloud_bad_once,
                            v.detail == null ? v.reason : v.detail)));
                    return;
                }

                // 读完自动上传：合格就直接传；不合格走上面的"重读确认"路径，
                // 所以这里再调一次带 forUpload 的读（它内部会判断）。
                if (!forUpload && Prefs.autoUpload(this)) {
                    if (v.complete) {
                        doUpload();
                    } else if (Prefs.isConfigured(this)) {
                        readOnce(true);
                    }
                }
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

    // ------------------------------------------------------------ 云端

    /** 上传当前读到的标签。不合格的会先重读确认，两次一致就按异常破例上传。 */
    private void uploadCurrent() {
        if (session.lastEpc == null || session.lastEpc.startsWith("—")) {
            setStatus(getString(R.string.cloud_read_first));
            return;
        }
        // 没配 key 就直接跳到云端界面 —— 那边会自动弹出设置窗。
        // （主页本身没有设置入口，只提示"点设置"会让人找不到。）
        if (!Prefs.isConfigured(this)) {
            setStatus(getString(R.string.cloud_need_key));
            CloudActivity.open(this);
            return;
        }
        if (session.verdict != null && !session.verdict.complete && !session.forceAnomaly) {
            readOnce(true);          // 不合格 -> 重读确认后再上传
            return;
        }
        doUpload();
    }

    private void doUpload() {
        final String epc = session.lastEpc;
        final String tid = session.lastTid;
        final String note = session.forceAnomaly
                ? "连续两次读数相同但第 9 位不符（EPC 应为 0 / TID 应为 2）"
                : null;
        session.forceAnomaly = false;
        setStatus("正在查云端…");
        worker.execute(() -> {
            try {
                CloudClient client = Prefs.client(this);

                // 先查库：已经在库里就不再传一遍，只告诉用户命中了哪一条。
                // touch=true 表示这次确实读到了，云端会把「读到次数」+1 并记时间。
                CloudClient.Tag hit = client.findByEpc(epc, true);
                if (hit != null) {
                    ui.post(() -> {
                        setStatus(hit.name == null || hit.name.isEmpty()
                                ? getString(R.string.cloud_hit_unnamed, hit.suffix,
                                            hit.seenCount, fmtTime(hit.lastSeen))
                                : getString(R.string.cloud_hit, hit.name, hit.suffix,
                                            hit.seenCount, fmtTime(hit.lastSeen)));
                        showHitCard(hit, false);
                        // 命中但还没命名 —— 顺手让它命名，省得再去列表里找
                        if (hit.name == null || hit.name.isEmpty()) askName(hit);
                        updateCloudButtons();
                    });
                    return;
                }

                ui.post(() -> setStatus("正在上传…"));
                CloudClient.UploadResult r = client.upload(epc, tid, note);
                String shown = r.tag == null ? epc : r.tag.epc;
                ui.post(() -> {
                    setStatus(note == null
                            ? getString(R.string.cloud_upload_ok, shown)
                            : getString(R.string.cloud_upload_anomaly, shown));
                    askName(r.tag);
                    if (r.tag != null) showHitCard(r.tag, true);
                    updateCloudButtons();
                });
            } catch (Exception e) {
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                ui.post(() -> setStatus(getString(R.string.cloud_error, why)));
            }
        });
    }

    /** 上传完立刻问要不要命名。 */
    private void askName(CloudClient.Tag tag) {
        if (tag == null) return;
        final EditText input = new EditText(this);
        input.setHint(R.string.cloud_name_hint);
        input.setSingleLine(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(48, 16, 48, 0);
        box.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(R.string.cloud_uploaded)
                .setMessage(tag.epc + "\n" + getString(R.string.cloud_suffix, tag.suffix))
                .setView(box)
                .setPositiveButton(R.string.cloud_save, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    worker.execute(() -> {
                        try {
                            Prefs.client(this).rename(tag.id, name);
                            ui.post(() -> setStatus(getString(R.string.cloud_upload_ok, tag.epc)
                                    + "  → " + name));
                        } catch (Exception e) {
                            String why = e.getMessage() == null ? e.toString() : e.getMessage();
                            ui.post(() -> setStatus(getString(R.string.cloud_error, why)));
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateCloudButtons() {
        boolean has = session.lastEpc != null && !session.lastEpc.startsWith("—");
        uploadBtn.setEnabled(has);
    }

    // ------------------------------------------------------------ 顶栏 / 设置

    /**
     * 顶栏排布：<b>左上角设置，右上角连接 + 扫描</b>。
     *
     * <p>为什么设置挂在 home 图标上而不是做成菜单项：ActionBar 的菜单项只会往右边排，
     * 想放左边就只能借用 home。
     */
    private void setupActionBar() {
        androidx.appcompat.app.ActionBar bar = getSupportActionBar();
        if (bar == null) return;
        bar.setDisplayHomeAsUpEnabled(true);
        bar.setHomeAsUpIndicator(R.drawable.ic_settings);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        connectItem = menu.findItem(R.id.action_connect);
        scanItem = menu.findItem(R.id.action_scan);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // 连接/断开的文字要随状态变，所以每次显示前刷一遍
        if (connectItem != null) {
            connectItem.setTitle(session.isConnected()
                    ? R.string.disconnect : R.string.connect);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            showSettings();
            return true;
        }
        if (id == R.id.action_connect) {
            connectOrDisconnect();
            return true;
        }
        if (id == R.id.action_scan) {
            // 长按这个菜单项做不了，所以把「芯片寄存器探针」并进诊断输出里 —— 
            // 反正它本来就是排查用的，一起打出来更省事。
            setStatus(usbDiagnostics() + "\n\n" + chipProbe());
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 读写器设置（右上角溢出菜单里进）。
     *
     * <p>「连接后复位」和「关蜂鸣器」原来占着主页一整行，但它们是一设就不动的开关，
     * 没必要天天摆在眼前，挪到这里。顺带把「读完自动上传」也放进来，凑齐所有开关。
     */
    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 8, 48, 0);

        // 射频参数：原来占着主页一行、还是两个光秃秃的数字，现在挪进来。
        // 注意 hint 只在输入框为空时显示，已经填了值就看不见了 —— 所以得配真正的标签。
        LinearLayout rf = new LinearLayout(this);
        rf.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout colA = new LinearLayout(this);
        colA.setOrientation(LinearLayout.VERTICAL);
        TextView labelA = new TextView(this);
        labelA.setText(R.string.antenna_label);
        labelA.setTextSize(12);
        colA.addView(labelA);
        EditText ant = new EditText(this);
        ant.setInputType(InputType.TYPE_CLASS_NUMBER);
        ant.setText(String.valueOf(Prefs.antenna(this)));
        colA.addView(ant);
        rf.addView(colA, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout colP = new LinearLayout(this);
        colP.setOrientation(LinearLayout.VERTICAL);
        TextView labelP = new TextView(this);
        labelP.setText(R.string.power_label);
        labelP.setTextSize(12);
        colP.addView(labelP);
        EditText pw = new EditText(this);
        pw.setInputType(InputType.TYPE_CLASS_NUMBER);
        pw.setText(String.valueOf(Prefs.power(this)));
        colP.addView(pw);
        LinearLayout.LayoutParams lpp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpp.setMarginStart(24);
        rf.addView(colP, lpp);
        box.addView(rf);

        CheckBox restart = new CheckBox(this);
        restart.setText(R.string.restart);
        restart.setChecked(Prefs.restartOnConnect(this));
        box.addView(restart);

        CheckBox mute = new CheckBox(this);
        mute.setText(R.string.mute);
        mute.setChecked(Prefs.muteBeep(this));
        box.addView(mute);

        CheckBox auto = new CheckBox(this);
        auto.setText(R.string.cloud_auto_upload);
        auto.setChecked(Prefs.autoUpload(this));
        box.addView(auto);

        TextView tip = new TextView(this);
        tip.setText(R.string.settings_tip);
        tip.setTextSize(12);
        box.addView(tip);

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings)
                .setView(box)
                .setPositiveButton(R.string.cloud_save, (d, w) -> {
                    Prefs.setAntenna(this, parseInt(ant, 1));
                    Prefs.setPower(this, parseInt(pw, 26));
                    Prefs.setRestartOnConnect(this, restart.isChecked());
                    Prefs.setMuteBeep(this, mute.isChecked());
                    Prefs.setAutoUpload(this, auto.isChecked());
                    setStatus("设置已保存：天线 " + Prefs.antenna(this)
                            + "  功率 " + Prefs.power(this));
                })
                .setNeutralButton(R.string.cloud_open, (d, w) -> CloudActivity.open(this))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 云端给的是 UTC 的 {@code YYYY-MM-DD HH:MM:SS}，显示到分钟就够。 */
    private static String fmtTime(String s) {
        if (s == null || s.length() < 16) return "-";
        return s.substring(5, 16);
    }

    // ------------------------------------------------------------ 结果卡片

    /**
     * 把一张卡片弹出来：淡入 + 轻微放大，落到 1.0 时再回弹一下。
     *
     * <p>没用第三方动画库 —— 就这点效果，ViewPropertyAnimator 足够，
     * 而且不引依赖。
     */
    private static void popIn(View card) {
        card.setVisibility(View.VISIBLE);
        card.setAlpha(0f);
        card.setScaleX(0.85f);
        card.setScaleY(0.85f);
        card.animate()
                .alpha(1f).scaleX(1.06f).scaleY(1.06f)
                .setDuration(220)
                .withEndAction(() -> card.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(120)
                        .start())
                .start();
    }

    /**
     * 写成功时的强调动画：先猛地放大一下再弹回，同时闪一下背景色。
     *
     * <p>刻意做得比"弹出一张卡"更重 —— 写入是用户最关心结果的动作。
     */
    private void celebrate(View card) {
        // 必须先设成可见 —— GONE 的视图动画照跑但什么都看不见
        // （这个坑踩过一次：只改了 alpha/scale，卡片一直不出现）
        card.setVisibility(View.VISIBLE);
        card.setScaleX(0.6f);
        card.setScaleY(0.6f);
        card.setAlpha(0f);
        card.animate()
                .alpha(1f).scaleX(1.12f).scaleY(1.12f)
                .setDuration(260)
                .withEndAction(() -> card.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(160)
                        .start())
                .start();
        // 背景闪一下，让余光也能看到
        card.setBackgroundResource(R.drawable.bg_card_ok);
        card.postDelayed(() -> card.setBackgroundResource(R.drawable.bg_card_ok), 400);
    }

    /** 显示"命中云端"或"刚上传"的卡片。 */
    private void showHitCard(CloudClient.Tag t, boolean justUploaded) {
        boolean unnamed = t.name == null || t.name.isEmpty();
        hitLabel.setText(justUploaded ? R.string.hit_label_new
                : (unnamed ? R.string.hit_label_unnamed : R.string.hit_label));
        hitName.setText(unnamed ? getString(R.string.cloud_unnamed) : t.name);
        hitName.setTextColor(unnamed ? 0xFFB26A00 : 0xFF0D47A1);

        StringBuilder sb = new StringBuilder(getString(R.string.hit_sub,
                t.suffix, t.seenCount, fmtTime(t.lastSeen)));
        if (t.writeCount > 0) {
            sb.append("　写过 ").append(t.writeCount).append(" 次");
            if (t.lastWritten != null && t.lastWritten.length() >= 16) {
                sb.append("（最后 ").append(fmtTime(t.lastWritten)).append("）");
            }
        }
        hitSub.setText(sb.toString());

        hitCard.setBackgroundResource(justUploaded
                ? R.drawable.bg_card_new : R.drawable.bg_card_hit);
        popIn(hitCard);
    }

    private void hideCards() {
        hitCard.setVisibility(View.GONE);
        resultCard.setVisibility(View.GONE);
    }

    /**
     * 预览卡片动画 —— <b>不碰读写器，不会响蜂鸣器</b>。
     *
     * <p>为什么需要它：想看动画效果就得读一次标签，而读写器每读到一次标签就响一声，
     * 调 UI 的时候很烦。所以单独做一个只用假数据的预览。
     *
     * <p>触发方式：长按主页的「上传到云端」按钮。
     */
    private void previewCards() {
        CloudClient.Tag demo = new CloudClient.Tag();
        demo.epc = "E28069150000402008B83072";
        demo.suffix = "83072";
        demo.name = "周-教职";
        demo.status = "ok";
        demo.seenCount = 12;
        demo.lastSeen = "2026-09-21 08:46:10";
        demo.writeCount = 3;
        demo.lastWritten = "2026-09-21 08:40:02";

        hideCards();
        setStatus("预览：命中云端卡片（假数据，没有读写标签）");
        showHitCard(demo, false);

        // 2.2 秒后换成"写入成功"卡片，把强调动画也演一遍
        hitCard.postDelayed(() -> {
            hitCard.setVisibility(View.GONE);
            setStatus("预览：写入成功卡片（假数据）");
            showWriteResult(true,
                    "当前标签  EPC=E28069150000402008B83072\n"
                            + "写 EPC 区  成功 ✓（1 轮）   回读=E28069150000402008B83072\n"
                            + "写 TID 区  成功 ✓（1 轮）   回读=E28069152000402008B83072\n");
        }, 2200);
    }

    /** 显示写入结果：成功用绿卡 + 强调动画，失败用红卡。 */
    private void showWriteResult(boolean ok, String report) {
        resultTitle.setText(ok ? R.string.result_ok : R.string.result_fail);
        resultTitle.setTextColor(ok ? 0xFF1B5E20 : 0xFFB71C1C);
        resultText.setText(report);
        resultText.setTextColor(ok ? 0xFF1B5E20 : 0xFFB71C1C);
        resultCard.setBackgroundResource(ok ? R.drawable.bg_card_ok : R.drawable.bg_card_bad);
        if (ok) {
            celebrate(resultCard);
        } else {
            popIn(resultCard);
        }
    }

    /**
     * 写成功之后向云端报一次「写过一次」。
     *
     * <p><b>只有 EPC 和 TID 两个区都写成功才调</b>，所以判断放在调用处。
     * 上报失败不影响本地结果 —— 只是云端计数少一次，不值得打断用户。
     */
    private void reportWritten(String epc) {
        if (epc == null || epc.isEmpty() || !Prefs.isConfigured(this)) return;
        worker.execute(() -> {
            try {
                Prefs.client(this).markWritten(epc);
            } catch (Exception ignored) {
                // 计数而已，失败就算了
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
                RfidReader.WriteReport rep = session.reader.writeEpcAndTid(epc, 2, tid, 0);
                String raw = session.transport.takeLog();
                session.lastStatus = rep.text;
                ui.post(() -> {
                    setStatus(rep.ok ? "写入完成" : "写入未完成");
                    hitCard.setVisibility(View.GONE);
                    showWriteResult(rep.ok, rep.text);
                    appendLog(raw);
                    // 注意：这里**不要**顺手 readOnce()。
                    // 它会把刚写出来的报告立刻覆盖成「读到标签」，
                    // 于是写入到底成没成功根本看不见。
                });
                // 两个区都写成功才向云端记一次 —— 写失败不算
                if (rep.ok) reportWritten(epcHex);
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
