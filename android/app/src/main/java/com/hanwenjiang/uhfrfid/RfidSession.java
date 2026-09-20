package com.hanwenjiang.uhfrfid;

import java.util.List;

/**
 * 全局单例，持有当前的传输和读写器对象。
 *
 * <p>**为什么需要它**：Android 在转屏、插拔 USB、改字体/深色模式等时候会销毁并
 * 重建 Activity。原来把 {@code SerialTransport} 放在 Activity 的字段里，一重建
 * 连接就没了 —— 表现就是「用着用着 App 自己重启了一下，然后显示未连接」。
 * 放到单例里，Activity 重建后能接回原来那条连接。
 */
public final class RfidSession {

    private static RfidSession instance;

    public static synchronized RfidSession get() {
        if (instance == null) instance = new RfidSession();
        return instance;
    }

    /** 当前传输；没连接时为 null。 */
    public Transport transport;
    public RfidReader reader;

    /** 上次读到的结果，Activity 重建后用来把界面恢复回去。 */
    public String lastEpc = "—";
    public String lastTid = "—";
    public String lastStatus = "";

    /** 上一次读取的完整性校验结果。 */
    public TagValidator.Result verdict;

    /** 不合格但已确认两次读数一致时置位，允许破例上传。 */
    public boolean forceAnomaly;

    /** USB 被拔掉时由 Activity 回调，用来通知界面。 */
    public interface DisconnectListener {
        void onTransportLost(String reason);
    }

    public DisconnectListener listener;

    private RfidSession() {
    }

    public boolean isConnected() {
        return transport != null && transport.isOpen();
    }

    /** 换一个传输（USB / TCP）。会先把旧的关掉。 */
    public RfidReader use(Transport t) {
        disconnect();
        transport = t;
        reader = new RfidReader(t, null);
        return reader;
    }

    public void disconnect() {
        if (transport != null) {
            transport.close();
        }
        transport = null;
        reader = null;
    }

    /** USB 被拔掉：关掉连接并通知界面。 */
    public void onTransportLost(String reason) {
        disconnect();
        if (listener != null) listener.onTransportLost(reason);
    }

    /** 把收发记录拼成一段文字给界面显示。 */
    public static String render(List<RfidFrame.Frame> frames) {
        StringBuilder sb = new StringBuilder();
        for (RfidFrame.Frame f : frames) {
            sb.append(String.format("  cmd=0x%02X %s  len=%d  %s%n",
                    f.cmd, f.ok ? "ok" : "校验错", f.payload.length,
                    RfidFrame.hexSpaced(f.payload)));
        }
        return sb.toString();
    }
}
