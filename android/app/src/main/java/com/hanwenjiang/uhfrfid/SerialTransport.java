package com.hanwenjiang.uhfrfid;

import android.hardware.usb.UsbDevice;
import android.util.Log;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * USB-OTG 串口传输：手机直接插线跟读写器通信。
 *
 * <p>走 usb-serial-for-android，**不需要任何驱动**：这个库在用户态用 Java 实现了
 * CH341/CP210x/FTDI/PL2303 的 USB 协议，通过 Android USB Host API 直接读写
 * 原始 USB 端点。和 macOS 上要装 kext 完全不是一回事。
 *
 * <p>参数固定 115200 8N1，和原厂 GUI 一致。
 */
public class SerialTransport extends Transport {

    public static final int BAUD = 115200;

    private final UsbManager manager;
    private final Set<UsbDevice> granted;
    private UsbSerialPort port;
    private String name = "USB 串口";

    public SerialTransport(UsbManager manager, Set<UsbDevice> granted) {
        this.manager = manager;
        this.granted = granted;
    }

    /** 列出认得出的串口设备。 */
    public static List<UsbSerialDriver> listDrivers(UsbManager manager) {
        return UsbSerialProber.getDefaultProber().findAllDrivers(manager);
    }

    @Override
    public void open() throws Exception {
        List<UsbSerialDriver> drivers = listDrivers(manager);
        if (drivers.isEmpty()) {
            throw new IOException("没找到 USB 串口设备。检查：① OTG 线插好 "
                    + "② 手机支持 USB Host ③ 小米/红米要打开「设置→更多设置→OTG」"
                    + "（默认 10 分钟自动关）④ 换根能传数据的 OTG 线。");
        }
        UsbSerialDriver driver = null;
        for (UsbSerialDriver d : drivers) {
            if (granted.contains(d.getDevice())) { driver = d; break; }
        }
        if (driver == null) {
            throw new IOException("还没有 USB 权限，请在弹窗里点「允许」。");
        }

        warmUp(driver.getDevice());

        port = driver.getPorts().get(0);
        port.open(manager.openDevice(driver.getDevice()));
        port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        port.setDTR(true);
        port.setRTS(true);
        name = describe(driver.getDevice());
        log.setLength(0);
    }

    /**
     * 让库去初始化之前，先自己给芯片发一次复位，再空读几次版本寄存器。
     *
     * <p>背景：库的 CH34x 初始化**第一步是读**寄存器 {@code 0x5F} 校验版本
     * （要求第 2 字节 == {@code 0x00}），不满足就报
     * {@code Expected 0x0 byte, but get 0x?? [init #1]}；
     * 它要等到**下一步**才发 {@code 0xA1} 复位。
     *
     * <p>也就是说 <b>复位在校验之后</b>。芯片上电后、或上一轮会话结束时如果停在某个
     * 状态，第一次读就会拿到 {@code 0xff} / {@code 0xec}，校验直接不过。所以这里把
     * 顺序倒过来 —— 先补一发 {@code 0xA1} 复位，再读。
     *
     * <p>读到的内容记进日志，方便判断芯片到底回什么。失败不影响后续。
     */
    private void warmUp(UsbDevice dev) {
        UsbDeviceConnection c = null;
        try {
            c = manager.openDevice(dev);
            if (c == null) return;

            // 0x40 = 主机到设备 | 厂商请求 | 设备接收者
            int n = c.controlTransfer(0x40, 0xA1, 0, 0, null, 0, 500);
            log.append("预热：0xA1 复位 -> ").append(n >= 0 ? "ok" : ("n=" + n)).append('\n');
            try {
                Thread.sleep(80);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                byte[] buf = new byte[2];
                int r = c.controlTransfer(0xC0, 0x5F, 0, 0, buf, buf.length, 500);
                sb.append(String.format("#%d %s  ", i + 1,
                        r > 0 ? RfidFrame.hexSpaced(RfidFrame.slice(buf, 0, r))
                              : ("n=" + r)));
                try {
                    Thread.sleep(60);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.append("预热读 0x5F: ").append(sb).append('\n');
        } catch (Exception e) {
            log.append("预热失败（忽略）: ").append(e.getMessage()).append('\n');
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public void close() {
        if (port != null) {
            try {
                port.close();
            } catch (IOException ignored) {
            }
            port = null;
        }
    }

    @Override
    public boolean isOpen() {
        return port != null && port.isOpen();
    }

    @Override
    public String describe() {
        return name;
    }

    @Override
    public void write(byte[] frame) throws IOException {
        if (port == null) throw new IOException("串口没打开");
        port.write(frame, 2000);
        log.append("TX  ").append(RfidFrame.hexSpaced(frame)).append('\n');
    }

    @Override
    protected int read(byte[] buf, int timeoutMs) throws IOException {
        if (port == null) throw new IOException("串口没打开");
        return port.read(buf, timeoutMs);
    }

    /** "CH340 (1a86:7523)" 这样，便于在界面上确认连的是哪个。 */
    public static String describe(UsbDevice d) {
        String product = d.getProductName();
        if (product == null || product.isEmpty()) product = "USB 串口";
        return String.format("%s (%04x:%04x)", product, d.getVendorId(), d.getProductId());
    }
}
