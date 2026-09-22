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
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.hoho.android.usbserial.driver.UsbSerialDriver;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * 宿主：只负责两件事 —— 读写器的 USB 连接，和底部标签页的切换。
 *
 * <p>具体功能都在各页里：
 * <ul>
 *   <li>{@link HomeFragment} 读标签 + 上传 + 从库里挑一颗来写</li>
 *   <li>{@link LibraryFragment} 云端标签库</li>
 *   <li>{@link ManualFragment} 古法写入（手敲 EPC/TID）</li>
 *   <li>{@link SettingsFragment} 设置</li>
 * </ul>
 */
public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION =
            "com.hanwenjiang.uhfrfid.USB_PERMISSION";

    private final RfidSession session = RfidSession.get();
    private final Set<UsbDevice> granted = new HashSet<>();
    private UsbManager usbManager;

    private MenuItem connectItem;
    /** 自动连接只试一次；用户主动断开后允许再试。 */
    private boolean autoTried;

    /**
     * 正在连接中。
     *
     * <p>连接要好几秒（库的版本校验会重试 6 次再切自驱）。期间再点那颗图标、
     * 或者 onResume 又触发一次自动连，就会起第二个连接去抢同一个串口 ——
     * 表现是「连接失败：串口没打开」。
     */
    private boolean connecting;
    /** 上一次 toast 过的状态，避免重复弹同样的提示。 */
    private String lastToast;

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        setSupportActionBar(findViewById(R.id.toolbar));

        BottomNavigationView nav = findViewById(R.id.bottomNav);
        nav.setOnItemSelectedListener(item -> {
            show(item.getItemId());
            return true;
        });
        if (savedInstanceState == null) {
            nav.setSelectedItemId(R.id.tab_home);
        }

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(permReceiver, filter);
        }

        session.listener = reason -> runOnUiThread(() -> {
            reflectConnectionState();
            setStatus(getString(R.string.disconnected, reason));
        });

        refreshTagCache();
    }

    @Override
    protected void onResume() {
        super.onResume();
        autoConnect();
        reflectConnectionState();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            onDeviceAttached();
        }
    }

    /** 插上读写器 = 明确的意图，允许再自动连一次（哪怕之前主动断开过）。 */
    private void onDeviceAttached() {
        autoTried = false;
        autoConnect();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        session.listener = null;
        try {
            unregisterReceiver(permReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        // 故意不关连接：转屏、切后台都会走到这里，关掉就成了"用着用着就断了"
    }

    // ------------------------------------------------------------ 标签页

    private void show(int itemId) {
        Fragment f;
        if (itemId == R.id.tab_library) {
            f = new LibraryFragment();
        } else if (itemId == R.id.tab_manual) {
            f = new ManualFragment();
        } else if (itemId == R.id.tab_settings) {
            f = new SettingsFragment();
        } else {
            f = new HomeFragment();
        }
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.content, f)
                .commitAllowingStateLoss();
    }

    // ------------------------------------------------------------ 顶栏

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        connectItem = menu.findItem(R.id.action_connect);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // 连接那颗图标随状态换：连上=绿色 USB，没连=灰色带斜杠。
        // 颜色写在 vector 文件里，不靠主题 tint（主题 tint 会把它刷成白色）。
        if (connectItem != null) {
            boolean on = session.isConnected();
            connectItem.setIcon(on ? R.drawable.ic_usb_on : R.drawable.ic_usb_off);
            connectItem.setTitle(on ? R.string.disconnect : R.string.connect);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_connect) {
            connectOrDisconnect();
            return true;
        }
        if (id == R.id.action_scan) {
            setStatus(usbDiagnostics() + "\n\n" + chipProbe());
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void reflectConnectionState() {
        invalidateOptionsMenu();
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.content);
        if (f instanceof StatusHost) ((StatusHost) f).onConnectionChanged();
    }

    /**
     * 把状态显示出来。
     *
     * <p>各页有自己的状态栏；这里统一先记进 {@link RfidSession}，再交给当前那一页。
     */
    private void setStatus(String s) {
        if (s == null) return;
        session.lastStatus = s;
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.content);
        if (f instanceof StatusHost) {
            ((StatusHost) f).showStatus(s);
        } else {
            android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** 页面想接收宿主的状态文字就实现它。 */
    public interface StatusHost {
        void showStatus(String s);

        /**
         * 读写器的连接状态变了。
         *
         * <p>页面里的按钮可用性是跟着"连没连上"走的，而连接是异步完成的 ——
         * 只在 onViewCreated 里算一次的话，按钮会一直停在"没连上"的灰态。
         */
        default void onConnectionChanged() {
        }
    }

    // ------------------------------------------------------------ 连接

    private final BroadcastReceiver permReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                session.onTransportLost(getString(R.string.device_unplugged));
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
                setStatus(getString(R.string.perm_denied));
            }
        }
    };

    private void connectOrDisconnect() {
        if (session.isConnected()) {
            session.disconnect();
            // 主动断开就保持断开：不再被 onResume 自动连回来。
            // 想重连可以点这颗图标，或者把线拔了重插。
            autoTried = true;
            setStatus(getString(R.string.disconnected_manual));
            reflectConnectionState();
            return;
        }
        requestPermissionThenConnect();
    }

    private void requestPermissionThenConnect() {
        java.util.List<UsbSerialDriver> drivers = SerialTransport.listDrivers(usbManager);
        if (drivers.isEmpty()) {
            setStatus(getString(R.string.no_device_found));
            return;
        }
        for (UsbSerialDriver d : drivers) {
            if (usbManager.hasPermission(d.getDevice())) granted.add(d.getDevice());
        }
        if (!granted.isEmpty()) {
            doConnect();
            return;
        }
        requestUsbPermission(drivers.get(0).getDevice());
    }

    /**
     * 弹系统 USB 权限窗。
     *
     * <p>Android 的 USB 权限必须由用户点一次，绕不过去；能做的是把它自动弹出来。
     * 清单里声明了 USB 设备，所以这个弹窗带「总是允许」的勾选框，勾一次就不再问。
     */
    private void requestUsbPermission(UsbDevice dev) {
        setStatus(getString(R.string.requesting_perm, SerialTransport.describe(dev)));
        // 必须 MUTABLE：系统要往这个 Intent 里补 EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED
        // 但 targetSdk 34+ 规定 FLAG_MUTABLE 不能配隐式 Intent，所以先 setPackage 变显式
        Intent perm = new Intent(ACTION_USB_PERMISSION);
        perm.setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        usbManager.requestPermission(dev,
                PendingIntent.getBroadcast(this, 0, perm, flags));
    }

    /**
     * 自动连接：能连就连，连不上就退回"点图标"。
     *
     * <p>每次启动/插拔只试一次 —— 用户主动断开之后不该马上又给他连上。
     */
    private void autoConnect() {
        if (session.isConnected() || autoTried) return;

        java.util.List<UsbSerialDriver> drivers = SerialTransport.listDrivers(usbManager);
        // 没插设备不算试过了 —— 否则先开 App 再插读写器时，
        // autoTried 已经置位，插上后会被守卫挡住、永远不自动连（踩过）
        if (drivers.isEmpty()) return;
        autoTried = true;

        boolean anyGranted = false;
        for (UsbSerialDriver d : drivers) {
            if (usbManager.hasPermission(d.getDevice())) {
                // 必须先把已授权的设备放进 granted —— doConnect() 是从这个集合里挑的，
                // 不填的话它会说"还没有 USB 权限"（踩过：自动连接因此永远失败）
                granted.add(d.getDevice());
                anyGranted = true;
            }
        }
        if (anyGranted) doConnect();
        else requestUsbPermission(drivers.get(0).getDevice());
    }

    private void doConnect() {
        if (connecting) return;      // 已经在连了，别起第二个抢串口
        connecting = true;

        final int ant = Prefs.antenna(this);
        final int pw = Prefs.power(this);
        final boolean doReset = Prefs.restartOnConnect(this);
        final boolean mute = Prefs.muteBeep(this);

        setStatus(getString(R.string.connecting));
        Bg.run(() -> {
            try {
                // CH34x 的初始化（读 0x5F 校验版本）有时会返回 0xff/0xec 而失败，
                // 重试几次通常能过。所以多试几次、退避递增。
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
                        if (attempt < tries) Thread.sleep(250L * attempt);
                    }
                }
                if (t == null) {
                    // 库被版本校验挡死（这颗芯片读 0x5F 回的字节不是 0x00），
                    // 那就自己驱动 —— 同一套寄存器序列，跳过那次校验。
                    if (granted.isEmpty()) throw lastErr;
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

                RfidReader reader = session.use(t);
                if (doReset) reader.restart();
                reader.clearEpcMatch();
                if (mute) reader.setBeep(false);
                reader.setAntenna(ant);
                reader.setPower(pw);
                byte[] ver = reader.readVersion();

                String msg = getString(R.string.connected, t.describe())
                        + (ver != null ? "\n" + getString(R.string.firmware_line,
                                RfidFrame.hexSpaced(ver)) : "")
                        + "\n" + getString(R.string.radio_line, ant, pw);
                runOnUiThread(() -> {
                    connecting = false;
                    reflectConnectionState();
                    setStatus(msg);
                });
            } catch (Exception e) {
                final String why = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> {
                    connecting = false;
                    reflectConnectionState();
                    setStatus(getString(R.string.connect_failed, why));
                });
            }
        });
    }

    /** 补全用的内存缓存：打开 App 时拉一次。 */
    private void refreshTagCache() {
        Bg.run(() -> {
            try {
                TagCache.put(Prefs.client(this).list(false));
            } catch (Exception e) {
                TagCache.putError(e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------ 诊断

    private String usbDiagnostics() {
        StringBuilder sb = new StringBuilder(getString(R.string.diag_header) + "\n");
        java.util.List<UsbSerialDriver> ds = SerialTransport.listDrivers(usbManager);
        sb.append(getString(R.string.diag_count, ds.size())).append('\n');
        for (UsbSerialDriver d : ds) {
            UsbDevice dev = d.getDevice();
            sb.append(String.format("%04X:%04X  %s  接口数=%d  权限=%s%n",
                    dev.getVendorId(), dev.getProductId(), SerialTransport.describe(dev),
                    dev.getInterfaceCount(),
                    usbManager.hasPermission(dev) ? getString(R.string.diag_yes)
                            : getString(R.string.diag_no)));
        }
        return sb.toString();
    }

    /** 直接读 CH34x 的版本寄存器，看芯片到底回什么（排查初始化失败用）。 */
    private String chipProbe() {
        java.util.List<UsbSerialDriver> ds = SerialTransport.listDrivers(usbManager);
        if (ds.isEmpty()) return getString(R.string.diag_no_device);
        UsbDevice dev = ds.get(0).getDevice();
        if (!usbManager.hasPermission(dev)) return getString(R.string.diag_no_perm);
        UsbDeviceConnection conn = usbManager.openDevice(dev);
        if (conn == null) return getString(R.string.diag_open_failed);
        try {
            StringBuilder sb = new StringBuilder(getString(R.string.diag_probe) + "\n");
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
