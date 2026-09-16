package com.hanwenjiang.uhfrfid;

import java.io.IOException;
import java.util.List;

/**
 * 读写器高层操作：复位、射频参数、蜂鸣器、读 EPC/TID、写 EPC/TID。
 * 对应电脑端的 {@code read_epc_tid.py} 和 {@code tag_write_epc_tid.py}。
 */
public class RfidReader {

    /** 0x8A 应答里的 0x53 数据帧载荷长度（37）。 */
    private static final int PAYLOAD_53_LEN = 37;

    private final SerialTransport io;
    private final LogSink log;

    public interface LogSink {
        void line(String s);
    }

    public RfidReader(SerialTransport io, LogSink log) {
        this.io = io;
        this.log = log;
    }

    private void say(String s) {
        if (log != null) log.line(s);
    }

    // ------------------------------------------------------------ 基础命令

    /**
     * 0x70 复位。原 GUI 连接时就是「打开串口 → 复位 → 等 300ms → 校验链路」。
     * 不发这一步，读写器可能处于未知状态，后面的空中命令会不应答。
     */
    public boolean restart() throws IOException {
        say("复位读写器 (0x70)…");
        io.write(RfidFrame.build(RfidFrame.CMD_RESTART));
        try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        // 复位没有应答，用 0x72 探测，最多试 5 次
        for (int i = 0; i < 5; i++) {
            try { Thread.sleep(150); } catch (InterruptedException ignored) { }
            byte[] ver = readVersion();
            if (ver != null && ver.length > 0) {
                say("  复位完成，固件 " + RfidFrame.hexSpaced(ver));
                return true;
            }
        }
        say("  复位后没探测到应答（继续尝试）");
        return false;
    }

    /** 0x72 读固件版本。 */
    public byte[] readVersion() throws IOException {
        List<RfidFrame.Frame> fs = io.transact(
                RfidFrame.build(RfidFrame.CMD_VERSION), 600);
        for (RfidFrame.Frame f : fs) {
            if (f.ok && f.cmd == RfidFrame.CMD_VERSION) return f.payload;
        }
        return null;
    }

    public boolean setAntenna(int antenna) throws IOException {
        boolean ok = !io.transact(RfidFrame.build(RfidFrame.CMD_SET_WORK_ANT,
                new byte[]{(byte) antenna}), 800).isEmpty();
        say("  工作天线 = " + antenna + (ok ? " ✓" : " （无应答）"));
        return ok;
    }

    public boolean setPower(int power) throws IOException {
        boolean ok = !io.transact(RfidFrame.build(RfidFrame.CMD_SET_ANT_POWER,
                new byte[]{(byte) power}), 800).isEmpty();
        say("  天线功率 = " + power + (ok ? " ✓" : " （无应答）"));
        return ok;
    }

    /** 0x7A 蜂鸣器。读写器每盘存到一个标签就响一声，连读时很吵。 */
    public boolean setBeep(boolean on) throws IOException {
        boolean ok = !io.transact(RfidFrame.build(RfidFrame.CMD_SET_BEEP,
                new byte[]{(byte) (on ? 1 : 0)}), 800).isEmpty();
        say("  蜂鸣器 = " + (on ? "开" : "关") + (ok ? " ✓" : " （无应答）"));
        return ok;
    }

    /** 0x85 清 EPC 匹配。留着旧的过滤器可能把标签挡掉。 */
    public void clearEpcMatch() throws IOException {
        io.transact(RfidFrame.build(RfidFrame.CMD_SET_EPC_MATCH,
                RfidFrame.payloadEpcMatch(false, new byte[0])), 800);
    }

    public boolean armEpcMatch(byte[] epc) throws IOException {
        boolean ok = !io.transact(RfidFrame.build(RfidFrame.CMD_SET_EPC_MATCH,
                RfidFrame.payloadEpcMatch(true, epc)), 1000).isEmpty();
        say("  EPC 匹配 " + RfidFrame.hex(epc) + (ok ? " 已设置 ✓" : " 无应答"));
        return ok;
    }

    // ------------------------------------------------------- 读 EPC + TID

    /**
     * 一次自定义盘存 (0x8A) 拿到 EPC 和 TID。
     *
     * <p>载荷 {@code 01 01 01 06} 和原 GUI 完全一致。一次查询会回来 4 个帧：
     * 0x8A 的 ACK、1~2 个带 EPC/TID 的 0x53 数据帧、以及一个结束帧
     * {@code A0 04 00 53 12 F7}。所以要挑能解出 EPC 的那个，不能取最后一个。
     */
    public RfidFrame.TagId readEpcTid() throws IOException {
        byte[] req = RfidFrame.build(RfidFrame.CMD_CUSTOM_INVENTORY,
                new byte[]{1, 1, 1, 6});
        List<RfidFrame.Frame> fs = io.transact(req, 2000);
        RfidFrame.TagId id = RfidFrame.findTag(fs);
        if (!id.ok() && fs.isEmpty()) {
            // 再试一次：偶尔第一发会被吞掉
            fs = io.transact(req, 2000);
            id = RfidFrame.findTag(fs);
        }
        return id;
    }

    // ---------------------------------------------------------- 写 EPC/TID

    /** 读某个 bank 的字。 */
    public byte[] readBank(int bank, int wordAddr, int wordCount) throws IOException {
        List<RfidFrame.Frame> fs = io.transact(RfidFrame.build(RfidFrame.CMD_READ,
                RfidFrame.payloadRead(bank, wordAddr, wordCount, 0)), 1500);
        for (RfidFrame.Frame f : fs) {
            if (f.ok && f.cmd == RfidFrame.CMD_READ && f.payload.length >= wordCount * 2) {
                return f.payload;
            }
        }
        return null;
    }

    /**
     * 写某个 bank。
     *
     * @return 读写器是否应答（应答里第 1 个字节是状态，0x10 表示接受）
     */
    public boolean writeBank(int bank, int wordAddr, byte[] data) throws IOException {
        List<RfidFrame.Frame> fs = io.transact(RfidFrame.build(RfidFrame.CMD_WRITE,
                RfidFrame.payloadWrite(bank, wordAddr, data, 0)), 4000);
        for (RfidFrame.Frame f : fs) {
            if (f.ok && f.cmd == RfidFrame.CMD_WRITE) {
                boolean accepted = f.payload.length > 0 && (f.payload[0] & 0xFF) == 0x10;
                say("  写应答: " + RfidFrame.hexSpaced(f.payload)
                        + (accepted ? "   (接受)" : "   (未接受)"));
                return accepted;
            }
        }
        say("  写命令没有应答 —— 多半是场上没标签，或 EPC 匹配没设对");
        return false;
    }

    /**
     * 完整写一次：清匹配 → 盘存 → 设匹配 → 写 EPC → 回读 → 写 TID → 回读。
     * 对应电脑端的 {@code tag_write_epc_tid.py}。
     */
    public String writeEpcAndTid(byte[] epc, int epcStart, byte[] tid, int tidStart)
            throws IOException {
        StringBuilder r = new StringBuilder();

        // 1) 先盘存，确认场上有标签，并拿到它的 EPC 用于设匹配
        RfidFrame.TagId cur = readEpcTid();
        if (!cur.ok()) {
            return "场上没读到标签。把标签贴近天线，或换个天线口/功率再试。";
        }
        r.append("当前标签  EPC=").append(cur.epc).append("  TID=").append(cur.tid).append('\n');

        // 2) 设匹配锁定目标（写命令本身不带目标 EPC，不设匹配读写器可能不回应答）
        armEpcMatch(RfidFrame.unhex(cur.epc));

        // 3) 写 EPC
        say("写 EPC 区：起始字 " + epcStart + "，" + (epc.length / 2) + " 字");
        boolean w1 = writeBank(1, epcStart, epc);
        byte[] back = readBank(1, epcStart, epc.length / 2);
        String readEpc = back == null ? null
                : RfidFrame.hex(RfidFrame.slice(back, 5, epc.length));
        r.append("写 EPC    ").append(w1 ? "已接受" : "无应答")
                .append("   回读=").append(readEpc == null ? "(无)" : readEpc)
                .append(readEpc != null && readEpc.equalsIgnoreCase(RfidFrame.hex(epc))
                        ? "  一致 ✓" : "  ⚠ 不一致").append('\n');

        // 4) 写 TID（多数 Gen2 标签的 TID 只读，写了不会变）
        say("写 TID 区：起始字 " + tidStart + "，" + (tid.length / 2) + " 字");
        boolean w2 = writeBank(2, tidStart, tid);
        byte[] back2 = readBank(2, tidStart, tid.length / 2);
        String readTid = back2 == null ? null
                : RfidFrame.hex(RfidFrame.slice(back2, 5, tid.length));
        r.append("写 TID    ").append(w2 ? "已接受" : "无应答")
                .append("   回读=").append(readTid == null ? "(无)" : readTid)
                .append(readTid != null && readTid.equalsIgnoreCase(RfidFrame.hex(tid))
                        ? "  一致 ✓" : "  ⚠ 不一致（Gen2 的 TID 通常只读）").append('\n');

        return r.toString();
    }
}
