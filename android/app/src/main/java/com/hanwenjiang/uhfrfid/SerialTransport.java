package com.hanwenjiang.uhfrfid;

import android.hardware.usb.UsbDevice;
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
        port = driver.getPorts().get(0);
        port.open(manager.openDevice(driver.getDevice()));
        port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        port.setDTR(true);
        port.setRTS(true);
        name = describe(driver.getDevice());
        log.setLength(0);
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
