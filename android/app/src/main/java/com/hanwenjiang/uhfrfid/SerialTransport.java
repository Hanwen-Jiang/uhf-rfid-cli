package com.hanwenjiang.uhfrfid;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 串口传输层：把 {@link RfidFrame} 拼好的帧发出去、把回来的字节收起来。
 *
 * <p>跑在手机 USB Host（OTG）上，走 usb-serial-for-android，支持
 * CH340/CH341、CP210x、FTDI、PL2303、CDC-ACM —— 和电脑上用的适配器是同一类。
 * 参数固定 115200 8N1，和原厂 GUI 一致。
 */
public class SerialTransport {

    public static final int BAUD = 115200;

    private UsbSerialPort port;
    private final StringBuilder log = new StringBuilder();

    /** 已连接的设备描述，未连接为 null。 */
    public String deviceName;

    public boolean isOpen() {
        return port != null && port.isOpen();
    }

    /** 列出认得出的串口设备（没权限的也在内，连接时再申请）。 */
    public static List<UsbSerialDriver> listDrivers(UsbManager manager) {
        return UsbSerialProber.getDefaultProber().findAllDrivers(manager);
    }

    /**
     * 打开第一个可用设备。
     *
     * @param manager   UsbManager
     * @param granted   已获得权限的设备集合（调用方先申请权限）
     * @throws IOException 没有设备 / 没权限 / 打开失败
     */
    public void open(UsbManager manager, java.util.Set<UsbDevice> granted) throws IOException {
        List<UsbSerialDriver> drivers = listDrivers(manager);
        if (drivers.isEmpty()) {
            throw new IOException("没找到 USB 串口设备。检查 OTG 线是否插好、"
                    + "适配器是不是 CH340/CP210x/FTDI 这类。");
        }
        UsbSerialDriver driver = null;
        for (UsbSerialDriver d : drivers) {
            if (granted == null || granted.contains(d.getDevice())) { driver = d; break; }
        }
        if (driver == null) {
            driver = drivers.get(0);
            throw new IOException("还没有 USB 权限，请在弹窗里点「允许」。");
        }
        port = driver.getPorts().get(0);
        port.open(manager.openDevice(driver.getDevice()));
        port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        port.setDTR(true);
        port.setRTS(true);
        deviceName = describe(driver.getDevice());
        log.setLength(0);
    }

    public void close() {
        if (port != null) {
            try { port.close(); } catch (IOException ignored) { }
            port = null;
        }
    }

    /** 把收到的残留字节丢掉。 */
    public void purge() {
        if (port == null) return;
        byte[] buf = new byte[512];
        try {
            long end = System.currentTimeMillis() + 120;
            while (System.currentTimeMillis() < end) {
                int n = port.read(buf, 50);
                if (n <= 0) break;
            }
        } catch (IOException ignored) { }
    }

    /** 发一帧。 */
    public void write(byte[] frame) throws IOException {
        if (port == null) throw new IOException("串口没打开");
        port.write(frame, 2000);
        log.append("TX  ").append(RfidFrame.hexSpaced(frame)).append('\n');
    }

    /**
     * 发一帧并收应答。
     *
     * <p>返回累积到的全部帧；超时没有应答就返回空列表。注意要按「累积后整体解析」
     * 处理 —— 一次应答经常分几次到达（实测 96 字节有时一整块、有时拆成 6+90）。
     */
    public List<RfidFrame.Frame> transact(byte[] frame, int timeoutMs) throws IOException {
        purge();
        write(frame);
        return readFrames(timeoutMs);
    }

    /** 只收，不发。 */
    public List<RfidFrame.Frame> readFrames(int timeoutMs) throws IOException {
        if (port == null) throw new IOException("串口没打开");
        List<RfidFrame.Frame> frames = new ArrayList<>();
        byte[] buf = new byte[4096];
        byte[] acc = new byte[0];
        long end = System.currentTimeMillis() + timeoutMs;
        long quietSince = System.currentTimeMillis();
        while (System.currentTimeMillis() < end) {
            int n;
            try {
                n = port.read(buf, 100);
            } catch (IOException e) {
                break;
            }
            if (n > 0) {
                acc = RfidFrame.concat(acc, RfidFrame.slice(buf, 0, n));
                quietSince = System.currentTimeMillis();
                frames = RfidFrame.parse(acc);
                // 已经解出完整帧，再等一小会儿看有没有后续
                if (!frames.isEmpty()) {
                    byte[] more = new byte[4096];
                    int m = port.read(more, 60);
                    if (m > 0) acc = RfidFrame.concat(acc, RfidFrame.slice(more, 0, m));
                }
                if (!frames.isEmpty() && System.currentTimeMillis() - quietSince > 60) break;
            } else if (!frames.isEmpty() && System.currentTimeMillis() - quietSince > 120) {
                break;
            }
        }
        if (acc.length > 0) log.append("RX  ").append(RfidFrame.hexSpaced(acc)).append('\n');
        return RfidFrame.parse(acc);
    }

    public String takeLog() {
        String s = log.toString();
        log.setLength(0);
        return s;
    }

    /** "CH340 (1a86:7523)" 这样，便于在界面上确认连的是哪个。 */
    public static String describe(UsbDevice d) {
        String name = d.getProductName();
        if (name == null || name.isEmpty()) name = "USB 串口";
        return String.format("%s (%04x:%04x)", name, d.getVendorId(), d.getProductId());
    }
}
