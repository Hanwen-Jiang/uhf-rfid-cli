package com.hanwenjiang.uhfrfid;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 读写器的线路协议编解码 —— {@code re/rfidframe.py} 的 Java 移植版。
 *
 * <p>帧格式（见 re/PROTOCOL.md）：
 * <pre>
 *   A0  LEN  ADDR  CMD  payload(LEN-3)  CHECK       线路总长 = LEN + 2
 *   LEN = payload 长度 + 3
 *   CHECK = (-(所有线路字节的 int8 之和)) &amp; 0xFF
 * </pre>
 *
 * <p>最容易踩的坑：0x81/0x82 里的字地址和字数都是 <b>大端</b>。写反了报文照样
 * 合法、校验也对，读写器会收下然后一声不吭 —— 和「场上没标签」完全一样。
 */
public final class RfidFrame {

    // ---- 命令码（只列本 App 用到的，全表见 re/protocol_dump.txt）----
    public static final int CMD_RESTART          = 0x70;
    public static final int CMD_VERSION          = 0x72;
    public static final int CMD_SET_WORK_ANT     = 0x74;
    public static final int CMD_GET_WORK_ANT     = 0x75;
    public static final int CMD_SET_ANT_POWER    = 0x76;
    public static final int CMD_GET_ANT_POWER    = 0x77;
    public static final int CMD_SET_BEEP         = 0x7A;
    public static final int CMD_READ             = 0x81;
    public static final int CMD_WRITE            = 0x82;
    public static final int CMD_SET_EPC_MATCH    = 0x85;
    public static final int CMD_CUSTOM_INVENTORY = 0x8A;
    public static final int CMD_53               = 0x53;
    public static final int CMD_GET_TAG_BUFFER   = 0x91;
    public static final int CMD_GET_TAG_COUNT    = 0x92;
    public static final int CMD_CLEAR_TAG_BUFFER = 0x93;

    private RfidFrame() {}

    // ------------------------------------------------------------------ 编码

    /** 组装一个完整帧。 */
    public static byte[] build(int cmd, byte[] payload, int addr) {
        if (payload == null) payload = new byte[0];
        int len = payload.length + 3;
        byte[] out = new byte[len + 2];
        out[0] = (byte) 0xA0;
        out[1] = (byte) len;
        out[2] = (byte) addr;
        out[3] = (byte) cmd;
        System.arraycopy(payload, 0, out, 4, payload.length);
        out[len + 1] = (byte) checkSum(out, len + 1);
        return out;
    }

    public static byte[] build(int cmd, byte[] payload) {
        return build(cmd, payload, 0);
    }

    public static byte[] build(int cmd) {
        return build(cmd, new byte[0], 0);
    }

    /**
     * 校验字节 = -(前面所有字节按 int8 求和) &amp; 0xFF。
     * 注意是<b>有符号</b>相加：原程序反汇编里是 movsx，不是无符号累加。
     */
    public static int checkSum(byte[] buf, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += buf[i];            // Java 的 byte 本身就是有符号，正好
        }
        return (-sum) & 0xFF;
    }

    // ------------------------------------------------------------ 载荷构造

    /** 大端写入。地址/字数用这个。 */
    public static byte[] be(long value, int nbytes) {
        byte[] out = new byte[nbytes];
        for (int i = nbytes - 1; i >= 0; i--) {
            out[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return out;
    }

    /** 0x81 读标签内存：bank, 字地址(BE32), 字数(BE16), 密码(BE32)。 */
    public static byte[] payloadRead(int bank, long wordAddr, int wordCount, long password) {
        byte[] out = new byte[11];
        out[0] = (byte) bank;
        System.arraycopy(be(wordAddr, 4), 0, out, 1, 4);
        System.arraycopy(be(wordCount, 2), 0, out, 5, 2);
        System.arraycopy(be(password, 4), 0, out, 7, 4);
        return out;
    }

    /** 0x82 写标签内存：00, 密码(BE24), bank, 字地址(BE32), 字数(BE16), 数据。 */
    public static byte[] payloadWrite(int bank, long wordAddr, byte[] data, long password) {
        int wordCount = data.length / 2;
        byte[] out = new byte[11 + data.length];
        out[0] = 0;
        System.arraycopy(be(password, 3), 0, out, 1, 3);
        out[4] = (byte) bank;
        System.arraycopy(be(wordAddr, 4), 0, out, 5, 4);
        System.arraycopy(be(wordCount, 2), 0, out, 9, 2);
        System.arraycopy(data, 0, out, 11, data.length);
        return out;
    }

    /** 0x85 设置 EPC 匹配：[enable][len][epc]。enable=0,len=0 即清除。 */
    public static byte[] payloadEpcMatch(boolean enable, byte[] epc) {
        byte[] out = new byte[2 + epc.length];
        out[0] = (byte) (enable ? 1 : 0);
        out[1] = (byte) epc.length;
        System.arraycopy(epc, 0, out, 2, epc.length);
        return out;
    }

    // ------------------------------------------------------------------ 解码

    /** 一个解析出来的帧。 */
    public static final class Frame {
        public final int cmd;
        public final int addr;
        public final byte[] payload;
        public final boolean ok;        // 校验是否通过

        Frame(int cmd, int addr, byte[] payload, boolean ok) {
            this.cmd = cmd;
            this.addr = addr;
            this.payload = payload;
            this.ok = ok;
        }

        public String hex() {
            return RfidFrame.hex(payload);
        }
    }

    /**
     * 从字节流里扫出所有帧。串口读取是流式的，可能半包、也可能粘包，
     * 所以要把累积到的缓冲区整体丢进来扫。
     */
    public static List<Frame> parse(byte[] buf) {
        List<Frame> out = new ArrayList<>();
        int i = 0;
        while (i + 4 <= buf.length) {
            if ((buf[i] & 0xFF) != 0xA0) { i++; continue; }
            int len = buf[i + 1] & 0xFF;
            int total = len + 2;
            if (len < 3 || i + total > buf.length) { i++; continue; }
            byte[] frame = new byte[total];
            System.arraycopy(buf, i, frame, 0, total);
            boolean ok = (checkSum(frame, total - 1) & 0xFF) == (frame[total - 1] & 0xFF);
            byte[] payload = new byte[len - 3];
            System.arraycopy(frame, 4, payload, 0, len - 3);
            out.add(new Frame(frame[3] & 0xFF, frame[2] & 0xFF, payload, ok));
            i += total;
        }
        return out;
    }

    // -------------------------------------------------- 0x8A/0x53 专有解析

    /** 一次查询拿到的 EPC + TID。 */
    public static final class TagId {
        public final String epc;      // 大写 hex，失败为 null
        public final String tid;
        TagId(String epc, String tid) { this.epc = epc; this.tid = tid; }
        public boolean ok() { return epc != null; }
    }

    private static final int EPC_OFF = 3, EPC_LEN = 12;
    private static final int TID_OFF = 18, TID_LEN = 12;

    /**
     * 从 0x53 载荷里取 EPC 和 TID。
     *
     * <p>载荷布局（真机抓包定位，见 README 的「0x8A 应答有 4 个帧」一节）：
     * <pre>
     *   [0]=0x01 [1]=0x30 [2]=0x00
     *   [3:15]  EPC (12 字节)
     *   [15:17] EPC CRC
     *   [17]    0x0C = TID 长度
     *   [18:30] TID (12 字节)
     *   [30:]   尾部，每次读数都变，忽略
     * </pre>
     *
     * <p>注意：0x8A 的应答里有 1~2 个这样的数据帧，后面还跟着一个<b>结束帧</b>
     * {@code A0 04 00 53 12 F7}（载荷只有 1 字节 0x12）。取「最后一个 0x53」
     * 就会取到结束帧，于是明明读到了却报「没读到」。所以必须逐个试，
     * 挑「载荷够长、能解出 EPC」的那个。
     */
    public static TagId parse53(byte[] payload) {
        if (payload == null || payload.length < TID_OFF + TID_LEN) {
            return new TagId(null, null);
        }
        byte[] epc = slice(payload, EPC_OFF, EPC_LEN);
        byte[] tid = slice(payload, TID_OFF, TID_LEN);
        boolean epcZero = true;
        for (byte b : epc) if (b != 0) { epcZero = false; break; }
        String epcHex = epcZero ? null : hex(epc);
        String tidHex = hex(tid);
        return new TagId(epcHex, tidHex);
    }

    /** 在解析出的帧列表里找第一个能解出 EPC 的 0x53。 */
    public static TagId findTag(List<Frame> frames) {
        for (Frame f : frames) {
            if (!f.ok || f.cmd != CMD_53) continue;
            TagId id = parse53(f.payload);
            if (id.ok()) return id;
        }
        return new TagId(null, null);
    }

    // ------------------------------------------------------------------ 工具

    public static byte[] slice(byte[] src, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, off, out, 0, len);
        return out;
    }

    public static String hex(byte[] b) {
        if (b == null || b.length == 0) return "";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    public static String hexSpaced(byte[] b) {
        if (b == null || b.length == 0) return "";
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", b[i]));
        }
        return sb.toString();
    }

    /** 把 "E2 80 69 …" / "E2806915…" 解析成字节；非法抛 IllegalArgumentException。 */
    public static byte[] unhex(String s) {
        String t = s.replaceAll("[\\s:,-]", "").toUpperCase();
        if (t.isEmpty()) return new byte[0];
        if ((t.length() & 1) != 0) throw new IllegalArgumentException("Hex 字符个数必须是偶数");
        if (!t.matches("[0-9A-F]+")) throw new IllegalArgumentException("只能包含 0-9 A-F");
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(t.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static byte[] concat(byte[] a, byte[] b) {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write(a, 0, a.length);
        os.write(b, 0, b.length);
        return os.toByteArray();
    }
}
