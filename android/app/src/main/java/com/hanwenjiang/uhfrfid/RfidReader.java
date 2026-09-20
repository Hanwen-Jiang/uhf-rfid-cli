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

    private final Transport io;
    private final LogSink log;

    public interface LogSink {
        void line(String s);
    }

    public RfidReader(Transport io, LogSink log) {
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
     * 读 EPC 区的当前内容，取出 EPC 本身（12 字节）。
     *
     * <p><b>写标签前必须用这个，不能用 {@link #readEpcTid()}。</b>
     * 实测：0x8A 自定义盘存之后紧接着写，读写器会稳定回状态码 <b>0x41</b>
     * 并且写不进去（补发 0x8C 停止盘存也没用）；改成先做一次 0x81 读内存就没问题。
     * 对照实验（同一个标签、同样的写入报文、Python 侧复刻）：
     * <pre>
     *   0x8A 流程：3/6 失败，回读没变
     *   0x81 流程：6/6 成功，回读跟着变
     * </pre>
     *
     * <p>读应答是 {@code 00 01 <len> <PC 2字节> <数据>}，所以 EPC 在偏移 5。
     */
    public byte[] readCurrentEpc() throws IOException {
        // EPC 区字 2 起才是 EPC 本身（字 0-1 是 CRC + PC）
        for (int attempt = 1; attempt <= 2; attempt++) {
            byte[] p = readBank(1, 2, 6);
            if (p != null && p.length >= 5 + 12) {
                byte[] epc = RfidFrame.slice(p, 5, 12);
                for (byte b : epc) {
                    if (b != 0) return epc;
                }
            }
            if (attempt == 1) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return null;
    }

    /**
     * 重写轮数：写一次 + 回读校验为一轮，不成功就整轮重来。
     *
     * <p><b>只在这里重试，{@link #writeBank} 本身不重试。</b>
     * 之前两层都写了重试（8 轮 × 8 次 = 最坏 64 次写入，每次 4 秒超时），
     * 一次失败的写入能拖好几分钟。重试只该有一层。
     */
    public static final int WRITE_ATTEMPTS = 8;

    /** 单次写入的结果。 */
    public static final class WriteOnce {
        public boolean ok;
        public boolean replied;    // 有没有收到应答（false = 超时，通常意味着场上没标签）
        public int code = -1;      // 状态码：长应答取第 3 字节，短应答就是它本身
    }

    /**
     * 写某个 bank，**只写一次**。
     *
     * <p>成功时读应答形如 {@code 00 <字数2> 10 <PC> <数据> <CRC> 10 01}，状态码在偏移 2；
     * 失败时只有一个字节，就是错误码本身（例如 0x41）。
     */
    public WriteOnce writeBankOnce(int bank, int wordAddr, byte[] data) throws IOException {
        WriteOnce r = new WriteOnce();
        List<RfidFrame.Frame> fs = io.transact(RfidFrame.build(RfidFrame.CMD_WRITE,
                RfidFrame.payloadWrite(bank, wordAddr, data, 0)), 4000);
        for (RfidFrame.Frame f : fs) {
            if (f.ok && f.cmd == RfidFrame.CMD_WRITE) {
                r.replied = true;
                r.code = f.payload.length >= 3
                        ? (f.payload[2] & 0xFF)
                        : (f.payload.length == 1 ? (f.payload[0] & 0xFF) : -1);
                r.ok = r.code == 0x10;
                say("  写应答: " + RfidFrame.hexSpaced(f.payload)
                        + (r.ok ? "   (接受)" : String.format("   (失败 0x%02X)", r.code)));
                return r;
            }
        }
        say("  写命令没有应答（超时）");
        return r;
    }

    /** 写入一个 bank 的结果。 */
    public static final class WriteResult {
        public boolean ok;
        public int attempts;         // 实际跑了几轮
        public int lastCode = -1;    // 最后一轮的状态码，方便看出到底为什么失败
        public boolean replied;      // 最后一轮有没有收到应答
        public String readBack;      // 回读到的值（大写 hex），读不到为 null
        public String error;         // 失败原因，成功时为 null
    }

    /**
     * 写入 + 回读校验，不成功就整轮重来（最多 {@link #WRITE_ATTEMPTS} 轮）。
     *
     * <p>语义：**写不成就再写一遍，直到回读一致为止**。
     * 只看写入应答还不够 —— 有时应答说成功但内容没落地，所以以回读为准。
     *
     * <p>重试**只有这一层**：{@link #writeBankOnce} 每次只写一次。两层都重试的话
     * 最坏会变成 8×8=64 次写入，一次失败要拖好几分钟。
     *
     * <p>校验方式是在读应答里**查找**写入的数据，而不是按固定偏移切：读应答是一条
     * 合并记录 {@code 00 01 <len> <PC2> <EPC12> <CRC2> <TID12>}，EPC 在偏移 5、
     * TID 在偏移 19。按偏移 5 取 TID 会取到 EPC，于是永远"不一致"。
     */
    public WriteResult writeBankVerified(int bank, int wordAddr, byte[] data, String label)
            throws IOException {
        WriteResult res = new WriteResult();
        String want = RfidFrame.hex(data);
        for (int round = 1; round <= WRITE_ATTEMPTS; round++) {
            res.attempts = round;
            if (round > 1) say("  第 " + round + "/" + WRITE_ATTEMPTS + " 轮重写 " + label + "…");
            WriteOnce w = writeBankOnce(bank, wordAddr, data);
            res.lastCode = w.code;
            res.replied = w.replied;

            byte[] back = readBank(bank, wordAddr, data.length / 2);
            res.readBack = back == null ? null : extract(back, data, bank);
            if (back != null && contains(back, data)) {
                res.ok = true;
                return res;
            }
            // 一直没应答 = 场上没标签，重写也变不出来，两轮就收手
            if (!w.replied && round >= 2) {
                res.error = String.format(
                        "连续 %d 轮没有任何应答 —— 多半是场上没有标签（回读=%s）",
                        round, res.readBack == null ? "读不到" : res.readBack);
                return res;
            }
            if (round < WRITE_ATTEMPTS) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        res.error = String.format("重写 %d 轮后回读仍是 %s，未变成 %s（最后状态码 0x%02X）",
                res.attempts, res.readBack == null ? "(读不到)" : res.readBack, want,
                res.lastCode < 0 ? 0 : res.lastCode);
        return res;
    }

    /**
     * 从读应答里把目标 bank 的数据取出来给界面显示。
     *
     * <p>合并记录的偏移：EPC 区数据在 5，TID 区数据在 19（5 + EPC 12 + CRC 2）。
     * 其它 bank 先按 5 试，取不到就退回整条应答。
     */
    private static String extract(byte[] reply, byte[] want, int bank) {
        int off = bank == 2 ? 19 : 5;
        if (reply.length >= off + want.length) {
            return RfidFrame.hex(RfidFrame.slice(reply, off, want.length));
        }
        return RfidFrame.hex(reply);
    }

    /** data 是否作为连续子串出现在 reply 里。 */
    private static boolean contains(byte[] reply, byte[] data) {
        if (data.length == 0 || reply.length < data.length) return false;
        outer:
        for (int i = 0; i + data.length <= reply.length; i++) {
            for (int j = 0; j < data.length; j++) {
                if (reply[i + j] != data[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    /**
     * 完整写一次：读当前 EPC → 设匹配 → 写 EPC → 回读 → 写 TID → 回读。
     * 对应电脑端的 {@code tag_write_epc_tid.py}。
     */
    public String writeEpcAndTid(byte[] epc, int epcStart, byte[] tid, int tidStart)
            throws IOException {
        StringBuilder r = new StringBuilder();

        // 1) 拿当前 EPC 用于设匹配。
        //    注意这里用 0x81 读内存，**不能用 0x8A 盘存** —— 0x8A 之后的写入会一直失败。
        byte[] cur = readCurrentEpc();
        if (cur == null) {
            return "场上没读到标签（0x81 读 EPC 区无数据）。\n"
                    + "把标签贴近天线，或换个天线口/功率再试。";
        }
        r.append("当前标签  EPC=").append(RfidFrame.hex(cur)).append('\n');

        // 2) 设匹配锁定目标（写命令本身不带目标 EPC，不设匹配读写器可能不回应答）
        armEpcMatch(cur);

        // 3) 写 EPC —— 不成功就整轮重写，以回读为准
        say("写 EPC 区：起始字 " + epcStart + "，" + (epc.length / 2) + " 字");
        WriteResult w1 = writeBankVerified(1, epcStart, epc, "EPC 区");
        r.append("写 EPC 区  ").append(w1.ok ? "成功 ✓" : "失败 ✗")
                .append("（").append(w1.attempts).append(" 轮）")
                .append("   回读=").append(w1.readBack == null ? "(无)" : w1.readBack)
                .append('\n');
        if (!w1.ok) {
            r.append("  ⚠ ").append(w1.error).append('\n');
            // **EPC 没写进去就不要接着写 TID。**
            // 这类标签的 EPC/TID 是配对的（只差第 9 位），只把 TID 改掉会让它
            // 变成不配对，之后每次读数都会被判「不合格」。宁可什么都不改。
            //
            // 注意：EPC 写失败**不代表 EPC 被锁**。实测这颗标签失败几次后
            // 重试就能写进去（写入本身是偶发的），所以这里是在重试全部用尽之后
            // 才走到，属于"确实写不进去"的情况。
            r.append("  → 已跳过 TID 写入：EPC 没改成的话，只写 TID 会让这颗标签的 "
                    + "EPC/TID 不配对（第 9 位不再是 0/2），之后每次读都会被判不合格。\n");
            r.append("     换一颗新标签，或先确认这颗标签的 EPC 区是不是被锁了。\n");
            return r.toString();
        }

        // 4) EPC 变了，匹配得跟着更新 —— 否则后面写 TID 时读写器锁定的还是旧 EPC。
        //    电脑端的 tag_write_epc_tid.py 也做这一步（"EPC 已变，更新匹配"）。
        if (w1.ok) {
            byte[] now = readCurrentEpc();
            if (now != null && !RfidFrame.hex(now).equalsIgnoreCase(RfidFrame.hex(cur))) {
                say("EPC 已变，更新匹配为 " + RfidFrame.hex(now));
                armEpcMatch(now);
            }
        }

        // 5) 写 TID —— 实测这颗标签的 TID 是可写的，所以同样重写到成功
        say("写 TID 区：起始字 " + tidStart + "，" + (tid.length / 2) + " 字");
        WriteResult w2 = writeBankVerified(2, tidStart, tid, "TID 区");
        r.append("写 TID 区  ").append(w2.ok ? "成功 ✓" : "失败 ✗")
                .append("（").append(w2.attempts).append(" 轮）")
                .append("   回读=").append(w2.readBack == null ? "(无)" : w2.readBack)
                .append('\n');
        if (!w2.ok) {
            r.append("  ⚠ ").append(w2.error).append('\n');
            r.append("  （标准 Gen2 的 TID 是出厂只读的，写不进去属正常；"
                    + "要写可改的存储区可以用 USER 区 bank 3）\n");
        }

        return r.toString();
    }
}
