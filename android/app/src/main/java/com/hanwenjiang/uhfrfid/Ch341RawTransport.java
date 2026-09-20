package com.hanwenjiang.uhfrfid;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;

/**
 * 直接自己驱动 CH341，<b>绕开 usb-serial-for-android 的版本校验</b>。
 *
 * <h3>为什么需要这个</h3>
 *
 * 库的 {@code Ch340SerialPort.initialize()} 是"校验 + 配置"交错执行的：
 *
 * <pre>
 *   checkState("init #1", 读 0x5F, 要求第2字节 == 0x00)   ← 这一步就抛异常
 *   controlOut(0xA1, 0, 0)                                 ← 复位，执行不到
 *   setBaudRate(9600)                                      ← 执行不到
 *   controlOut(0x9A, 0x2518, 0x00C3)                       ← 执行不到
 *   controlOut(0xA1, 0x501F, 0xD90A)
 *   setBaudRate(115200)
 * </pre>
 *
 * 实测这颗 CH341A 读 {@code 0x5F} 返回 {@code DF EC}（第 2 字节是 EC）或 {@code FF FF}，
 * 于是第一步就抛 {@code Expected 0x0 byte, but get 0x?? [init #1]}，
 * <b>后面那些配置一条都没发出去</b> —— 芯片其实压根没被初始化过。
 *
 * <p>但那个版本校验只是库的保守检查，不是芯片的工作前提：芯片本身能收能发。
 * 所以这里把同一套寄存器序列自己发一遍、跳过校验，看它到底能不能通。
 *
 * <h3>用到的寄存器（取自库 3.11.0 的字节码和 logcat）</h3>
 * <pre>
 *   0xA1 写 0x0000 = 复位
 *   0x9A 写 0x1312 = 波特率因子   9600 → 0xB282    115200 → 0xCC83
 *   0x9A 写 0x0F2C = 波特率分频   9600 → 0x000C    115200 → 0x0008
 *   0x9A 写 0x2518 = LCR（8N1）   0x00C3
 *   0xA1 写 0x501F = 0xD90A
 * </pre>
 */
public class Ch341RawTransport extends Transport {

    private static final int REQ_OUT = 0x40;   // 主机→设备 | 厂商 | 设备
    private static final int REQ_IN = 0xC0;    // 设备→主机 | 厂商 | 设备

    private final UsbManager manager;
    private final UsbDevice device;

    private UsbDeviceConnection conn;
    private UsbEndpoint epIn;
    private UsbEndpoint epOut;
    private UsbInterface iface;
    private int baud = 115200;
    private String name;

    public Ch341RawTransport(UsbManager manager, UsbDevice device) {
        this.manager = manager;
        this.device = device;
    }

    /** 记录初始化过程中每一步的结果，失败时能看出卡在哪。 */
    private final StringBuilder trace = new StringBuilder();

    @Override
    public void open() throws Exception {
        conn = manager.openDevice(device);
        if (conn == null) throw new IOException("打不开 USB 设备（没有权限？）");

        iface = device.getInterface(0);
        if (!conn.claimInterface(iface, true)) {
            throw new IOException("claimInterface 失败（是不是别的程序占着？）");
        }
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint ep = iface.getEndpoint(i);
            if (ep.getType() != android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (ep.getDirection() == android.hardware.usb.UsbConstants.USB_DIR_IN) epIn = ep;
            else epOut = ep;
        }
        if (epIn == null || epOut == null) {
            throw new IOException("没找到批量端点（in=" + epIn + " out=" + epOut + "）");
        }

        // ---- 库那套序列，去掉所有 checkState ----
        out(0xA1, 0x0000, 0x0000, "复位");
        setBaud(9600);
        out(0x9A, 0x2518, 0x00C3, "LCR 8N1");
        out(0xA1, 0x501F, 0xD90A, "收尾");
        setBaud(baud);

        // 芯片到底回什么，记下来（不影响后续）
        byte[] v = new byte[2];
        int n = conn.controlTransfer(REQ_IN, 0x5F, 0, 0, v, v.length, 500);
        trace.append("  读 0x5F -> ")
                .append(n > 0 ? RfidFrame.hexSpaced(RfidFrame.slice(v, 0, n)) : ("n=" + n))
                .append('\n');

        log.append("CH341 自驱初始化（跳过库的版本校验）:\n").append(trace);
        name = "CH341 自驱 " + String.format("%04X:%04X", device.getVendorId(), device.getProductId());
    }

    private void out(int request, int value, int index, String what) {
        int r = conn.controlTransfer(REQ_OUT, request, value, index, null, 0, 500);
        trace.append(String.format("  0x%02X <- 0x%04X/0x%04X  %s  %s%n",
                request, value, index, what, r >= 0 ? "ok" : ("n=" + r)));
    }

    /** 波特率：只用得到 9600 和 115200，直接给库里那两组常量。 */
    private void setBaud(int bps) {
        int factor, divisor;
        if (bps == 9600) {
            factor = 0xB282;
            divisor = 0x000C;
        } else {
            factor = 0xCC83;
            divisor = 0x0008;
        }
        out(0x9A, 0x1312, factor, "波特率因子 " + bps);
        out(0x9A, 0x0F2C, divisor, "波特率分频 " + bps);
    }

    @Override
    public void close() {
        try {
            if (conn != null && iface != null) conn.releaseInterface(iface);
        } catch (Exception ignored) {
        }
        try {
            if (conn != null) conn.close();
        } catch (Exception ignored) {
        }
        conn = null;
    }

    @Override
    public boolean isOpen() {
        return conn != null;
    }

    @Override
    public String describe() {
        return name == null ? "CH341 自驱" : name;
    }

    @Override
    public void write(byte[] frame) throws IOException {
        if (conn == null) throw new IOException("串口没打开");
        int sent = conn.bulkTransfer(epOut, frame, frame.length, 2000);
        log.append("TX  ").append(RfidFrame.hexSpaced(frame)).append('\n');
        if (sent != frame.length) {
            throw new IOException("发送不完整：" + sent + "/" + frame.length);
        }
    }

    @Override
    protected int read(byte[] buf, int timeoutMs) throws IOException {
        if (conn == null) throw new IOException("串口没打开");
        return conn.bulkTransfer(epIn, buf, buf.length, timeoutMs);
    }
}
