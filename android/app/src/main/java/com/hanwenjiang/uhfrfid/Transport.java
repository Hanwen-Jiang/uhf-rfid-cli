package com.hanwenjiang.uhfrfid;

import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 传输层基类。USB-OTG 串口和 TCP 都从这里派生，上层 {@link RfidReader} 不关心
 * 底下是哪种 —— 协议帧在两种传输上是完全一样的。
 */
public abstract class Transport {

    /** logcat 里过滤用：adb logcat -s RfidIo */
    public static final String TAG = "RfidIo";

    protected final StringBuilder log = new StringBuilder();

    public abstract void open() throws Exception;
    public abstract void close();
    public abstract boolean isOpen();

    /** 界面上显示的连接对象描述，例如 "CH340 (1a86:7523)" 或 "192.168.0.178:8080"。 */
    public abstract String describe();

    /** 发出一个完整帧。 */
    public abstract void write(byte[] frame) throws IOException;
    /**
     * 读一批字节。
     *
     * @return 读到的字节数；0 表示这次读超时、没有数据
     */
    protected abstract int read(byte[] buf, int timeoutMs) throws IOException;

    /** 把残留的字节丢掉（上一次没读完的应答）。 */
    public void purge() {
        if (!isOpen()) return;
        byte[] buf = new byte[512];
        long end = System.currentTimeMillis() + 150;
        while (System.currentTimeMillis() < end) {
            try {
                if (read(buf, 40) <= 0) break;
            } catch (IOException e) {
                break;
            }
        }
    }

    /**
     * 收一次应答，返回累积解析出的所有帧。
     *
     * <p>要点：串口/TCP 都是流，一次应答可能分几次到达（实测 0x8A 的 96 字节
     * 有时一整块、有时拆成 6+90），所以必须把缓冲区累积起来整体解析，
     * 不能对单次 read 的结果单独解析。
     *
     * <p>结束条件：收到帧之后再有 150 ms 没有新数据就认为说完了。太早退出会
     * 只拿到 ACK 而丢掉后面的数据帧（0x8A 就是这样：先回 ACK，再回数据）。
     */
    public List<RfidFrame.Frame> readFrames(int timeoutMs) throws IOException {
        byte[] acc = new byte[0];
        byte[] buf = new byte[4096];
        long deadline = System.currentTimeMillis() + timeoutMs;
        long lastData = System.currentTimeMillis();
        List<RfidFrame.Frame> frames = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            int n = read(buf, 100);
            if (n > 0) {
                acc = RfidFrame.concat(acc, RfidFrame.slice(buf, 0, n));
                lastData = System.currentTimeMillis();
                frames = RfidFrame.parse(acc);
            } else if (!frames.isEmpty()
                    && System.currentTimeMillis() - lastData > 150) {
                break;
            }
        }
        if (acc.length > 0) {
            log.append("RX  ").append(RfidFrame.hexSpaced(acc)).append('\n');
        }
        return RfidFrame.parse(acc);
    }

    /** 发一帧并收应答。 */
    public List<RfidFrame.Frame> transact(byte[] frame, int timeoutMs) throws IOException {
        purge();
        write(frame);
        return readFrames(timeoutMs);
    }

    /** 取走并清空收发记录（给界面的原始报文窗口用）。 */
    public String takeLog() {
        String s = log.toString();
        log.setLength(0);
        return s;
    }
}
