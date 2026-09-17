package com.hanwenjiang.uhfrfid;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * TCP 传输：读写器自带网口/WiFi 时直接连它；或者连电脑/树莓派上的桥接服务。
 *
 * <p>原程序本来就支持 TCP，默认端口 8080（见 re/PROTOCOL.md 的传输一节），
 * 帧格式和串口完全一样。有了它有两个好处：
 * <ul>
 *   <li>手机不用 OTG 线、不用驱动，同一 WiFi 下就能用；</li>
 *   <li>可以在模拟器里调试 —— 模拟器透传不了 USB 串口，但网络是通的。</li>
 * </ul>
 */
public class TcpTransport extends Transport {

    private final String host;
    private final int port;
    private Socket socket;
    private InputStream in;
    private OutputStream out;

    public TcpTransport(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public void open() throws Exception {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 4000);
        socket.setSoTimeout(100);            // read() 靠这个超时返回 0
        socket.setTcpNoDelay(true);          // 小帧不要攒着，立刻发
        in = socket.getInputStream();
        out = socket.getOutputStream();
        log.setLength(0);
    }

    @Override
    public void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        in = null;
        out = null;
    }

    @Override
    public boolean isOpen() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    @Override
    public String describe() {
        return host + ":" + port;
    }

    @Override
    public void write(byte[] frame) throws IOException {
        if (out == null) throw new IOException("连接没打开");
        out.write(frame);
        out.flush();
        log.append("TX  ").append(RfidFrame.hexSpaced(frame)).append('\n');
    }

    @Override
    protected int read(byte[] buf, int timeoutMs) throws IOException {
        if (in == null) throw new IOException("连接没打开");
        try {
            int n = in.read(buf);
            return n < 0 ? 0 : n;
        } catch (SocketTimeoutException e) {
            return 0;                        // 超时 = 这次没数据，交给上层判断
        }
    }
}
