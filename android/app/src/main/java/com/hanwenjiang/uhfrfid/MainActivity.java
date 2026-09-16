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
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 单页界面：连接读写器 → 读 EPC/TID → 可选写 EPC/TID。
 *
 * <p>所有串口操作都放到后台线程，主线程只更新界面 —— Android 上主线程碰 I/O
 * 会直接 ANR。
 */
public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION =
            "com.hanwenjiang.uhfrfid.USB_PERMISSION";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Set<UsbDevice> granted = new HashSet<>();

    private SerialTransport io;
    private RfidReader reader;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        io = new SerialTransport();
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

        readBtn.setEnabled(false);
        writeBtn.setEnabled(false);

        connectBtn.setOnClickListener(v -> connectOrDisconnect());
        readBtn.setOnClickListener(v -> readOnce());
        writeBtn.setOnClickListener(v -> writeOnce());

        reader = new RfidReader(io, this::appendLog);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(permReceiver, filter);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(permReceiver); } catch (IllegalArgumentException ignored) { }
        io.close();
        worker.shutdownNow();
    }

    // ------------------------------------------------------------ 连接

    private void connectOrDisconnect() {
        if (io.isOpen()) {
            io.close();
            readBtn.setEnabled(false);
            writeBtn.setEnabled(false);
            connectBtn.setText(R.string.connect);
            setStatus("已断开。");
            return;
        }
        requestPermissionThenConnect();
    }

    private void requestPermissionThenConnect() {
        java.util.List<com.hoho.android.usbserial.driver.UsbSerialDriver> drivers =
                SerialTransport.listDrivers(usbManager);
        if (drivers.isEmpty()) {
            setStatus("没找到 USB 串口设备。\n"
                    + "检查：① OTG 线插好 ② 读写器电源开了 "
                    + "③ 适配器是 CH340/CP210x/FTDI 这类。");
            return;
        }
        // 先看有没有已经授权的
        for (com.hoho.android.usbserial.driver.UsbSerialDriver d : drivers) {
            if (usbManager.hasPermission(d.getDevice())) granted.add(d.getDevice());
        }
        if (!granted.isEmpty()) {
            doConnect();
            return;
        }
        UsbDevice dev = drivers.get(0).getDevice();
        setStatus("请求 USB 权限：" + SerialTransport.describe(dev) + " …");
        PendingIntent pi = PendingIntent.getBroadcast(
                this, 0, new Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        usbManager.requestPermission(dev, pi);
    }

    private void doConnect() {
        setStatus("正在打开串口…");
        worker.execute(() -> {
            try {
                io.open(usbManager, granted);
                int ant = parseInt(antennaEdit, 1);
                int pw = parseInt(powerEdit, 26);
                boolean doReset = restartCheck.isChecked();
                boolean mute = muteCheck.isChecked();

                if (doReset) reader.restart();
                reader.clearEpcMatch();
                if (mute) reader.setBeep(false);
                reader.setAntenna(ant);
                reader.setPower(pw);

                byte[] ver = reader.readVersion();
                final String msg = "已连接 " + io.deviceName
                        + (ver != null ? "\n固件 " + RfidFrame.hexSpaced(ver) : "")
                        + "\n天线 " + ant + "  功率 " + pw
                        + (mute ? "  蜂鸣器已关" : "");
                ui.post(() -> {
                    setStatus(msg);
                    connectBtn.setText(R.string.disconnect);
                    readBtn.setEnabled(true);
                    writeBtn.setEnabled(true);
                });
            } catch (Exception e) {
                ui.post(() -> setStatus("连接失败：" + e.getMessage()));
            }
        });
    }

    // ------------------------------------------------------------ 读

    private void readOnce() {
        setStatus("查询中…");
        worker.execute(() -> {
            try {
                long t0 = System.currentTimeMillis();
                RfidFrame.TagId id = reader.readEpcTid();
                long dt = System.currentTimeMillis() - t0;
                ui.post(() -> {
                    if (id.ok()) {
                        epcText.setText(id.epc);
                        tidText.setText(id.tid == null ? "(应答里没有)" : id.tid);
                        setStatus("读到标签（" + dt + " ms）");
                    } else {
                        epcText.setText("—");
                        tidText.setText("—");
                        setStatus("没读到标签。\n把标签贴近天线，"
                                + "或换天线口/功率再试（蜂鸣器每读到一次会响一声）。");
                    }
                });
            } catch (Exception e) {
                ui.post(() -> setStatus("查询失败：" + e.getMessage()));
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
                String report = reader.writeEpcAndTid(epc, 2, tid, 0);
                ui.post(() -> {
                    setStatus(report);
                    readOnce();
                });
            } catch (IllegalArgumentException e) {
                ui.post(() -> setStatus("Hex 格式不对：" + e.getMessage()));
            } catch (Exception e) {
                ui.post(() -> setStatus("写入失败：" + e.getMessage()));
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
        ui.post(() -> {
            logText.append(s + "\n");
            int scroll = logText.getLayout() == null ? 0
                    : logText.getLayout().getLineTop(logText.getLineCount())
                      - logText.getHeight();
            if (scroll > 0) logText.scrollTo(0, scroll);
        });
    }
}
