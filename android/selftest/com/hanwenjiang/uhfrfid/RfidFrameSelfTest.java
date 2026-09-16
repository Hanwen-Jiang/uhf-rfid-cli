package com.hanwenjiang.uhfrfid;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * RfidFrame 的自测：不需要 Android，纯 JVM 就能跑。
 *
 * <p>期望值来自已经用真机验证过的 Python 实现（{@code re/rfidframe.py} 的 20 个
 * 帧往返自测、{@code re/final_compare.py} 对 GUI 抓包的逐字节断言），
 * 以及 {@code re/capture53.hex} 里那份真机原始应答。Java 侧算出来的必须一模一样，
 * 否则说明移植错了。
 *
 * <p>编译运行（不需要 Android SDK）：
 * <pre>
 *   javac -d out android/app/src/main/java/com/hanwenjiang/uhfrfid/RfidFrame.java \
 *         android/app/src/test/java/com/hanwenjiang/uhfrfid/RfidFrameSelfTest.java
 *   java -cp out com.hanwenjiang.uhfrfid.RfidFrameSelfTest
 * </pre>
 */
public class RfidFrameSelfTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== RfidFrame 移植自测（对 Python 实现逐字节比对）===");

        // ---- 1. 各命令的帧，期望值来自 re/rfidframe.py 的自测输出 ----
        frame("restart(0x70)", RfidFrame.CMD_RESTART, new byte[0], "A0 03 00 70 ED");
        frame("version(0x72)", RfidFrame.CMD_VERSION, new byte[0], "A0 03 00 72 EB");
        frame("set_work_ant(0x74)=1", RfidFrame.CMD_SET_WORK_ANT,
                new byte[]{1}, "A0 04 00 74 01 E7");
        frame("set_ant_power(0x76)=26", RfidFrame.CMD_SET_ANT_POWER,
                new byte[]{26}, "A0 04 00 76 1A CC");
        frame("set_beep(0x7A)=0", RfidFrame.CMD_SET_BEEP,
                new byte[]{0}, "A0 04 00 7A 00 E2");
        frame("set_beep(0x7A)=1", RfidFrame.CMD_SET_BEEP,
                new byte[]{1}, "A0 04 00 7A 01 E1");
        frame("clear_epc_match(0x85) [0,0]", RfidFrame.CMD_SET_EPC_MATCH,
                new byte[]{0, 0}, "A0 05 00 85 00 00 D6");
        frame("custom_inventory(0x8A) 01 01 01 06", RfidFrame.CMD_CUSTOM_INVENTORY,
                new byte[]{1, 1, 1, 6}, "A0 07 00 8A 01 01 01 06 C6");

        // ---- 2. 真机验证过的 6C 读写帧（re/final_compare.py 断言过）----
        byte[] epc = RfidFrame.unhex("E28069150000502008B25C75");
        frame("写 EPC 区 bank=1 addr=2 6字", RfidFrame.CMD_WRITE,
                RfidFrame.payloadWrite(1, 2, epc, 0),
                "A0 1A 00 82 00 00 00 00 01 00 00 00 02 00 06 "
                        + "E2 80 69 15 00 00 50 20 08 B2 5C 75 E0");
        byte[] tid = RfidFrame.unhex("E28069152000502008B25C75");
        frame("写 TID 区 bank=2 addr=0 6字", RfidFrame.CMD_WRITE,
                RfidFrame.payloadWrite(2, 0, tid, 0),
                "A0 1A 00 82 00 00 00 00 02 00 00 00 00 00 06 "
                        + "E2 80 69 15 20 00 50 20 08 B2 5C 75 C1");
        frame("读 EPC 区 bank=1 addr=2 6字", RfidFrame.CMD_READ,
                RfidFrame.payloadRead(1, 2, 6, 0),
                "A0 0E 00 81 01 00 00 00 02 00 06 00 00 00 00 C8");
        frame("读 TID 区 bank=2 addr=0 6字", RfidFrame.CMD_READ,
                RfidFrame.payloadRead(2, 0, 6, 0),
                "A0 0E 00 81 02 00 00 00 00 00 06 00 00 00 00 C9");

        // ---- 3. 大端陷阱：地址必须是大端 ----
        byte[] p = RfidFrame.payloadRead(1, 2, 6, 0);
        check("addr 是大端 (字节 1..4 = 00 00 00 02)",
                p[1] == 0 && p[2] == 0 && p[3] == 0 && p[4] == 2);
        check("wordCount 是大端 (字节 5..6 = 00 06)",
                p[5] == 0 && p[6] == 6);

        // ---- 4. 校验和：有符号累加，不是无符号 ----
        // 0x70 帧里 0xA0 是负数，如果按无符号累加会算成 0x9D 而不是 0xED
        check("checkSum 按 int8 累加 (A0 03 00 70 -> ED)",
                RfidFrame.checkSum(RfidFrame.unhex("A0030070"), 4) == 0xED);

        // ---- 5. 解析真机抓包，含「结束帧」那个坑 ----
        String capPath = args.length > 0 ? args[0] : "re/capture53.hex";
        if (Files.exists(Paths.get(capPath))) {
            String txt = new String(Files.readAllBytes(Paths.get(capPath)), "US-ASCII");
            byte[] cap = RfidFrame.unhex(txt);
            check("抓包 96 字节", cap.length == 96);
            List<RfidFrame.Frame> fs = RfidFrame.parse(cap);
            check("解出 4 个帧", fs.size() == 4);
            check("帧 1 是 0x8A 的 ACK", fs.get(0).cmd == 0x8A && fs.get(0).ok);
            check("帧 2 是 0x53 数据帧 (载荷 37)", fs.get(1).cmd == 0x53
                    && fs.get(1).payload.length == 37 && fs.get(1).ok);
            check("帧 4 是结束帧 (载荷 1 字节 = 0x12)",
                    fs.get(3).cmd == 0x53 && fs.get(3).payload.length == 1
                            && (fs.get(3).payload[0] & 0xFF) == 0x12);
            check("最后一个 0x53 解不出 EPC（这就是那个坑）",
                    !RfidFrame.parse53(fs.get(3).payload).ok());
            RfidFrame.TagId id = RfidFrame.findTag(fs);
            check("findTag 挑对了数据帧", id.ok());
            check("EPC = E28069150000402008B83072",
                    "E28069150000402008B83072".equals(id.epc));
            check("TID = E28069152000402008B83072",
                    "E28069152000402008B83072".equals(id.tid));
        } else {
            System.out.println("  (跳过抓包测试：找不到 " + capPath + ")");
        }

        // ---- 6. 半包/粘包：必须累积后整体解析 ----
        byte[] frame = RfidFrame.build(RfidFrame.CMD_CUSTOM_INVENTORY,
                new byte[]{1, 1, 1, 6});
        check("半包解析不出东西", RfidFrame.parse(
                RfidFrame.slice(frame, 0, 4)).isEmpty());
        check("完整包能解析", RfidFrame.parse(frame).size() == 1);

        System.out.println();
        System.out.printf("结果: %d 通过, %d 失败%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    private static void frame(String name, int cmd, byte[] payload, String want) {
        byte[] got = RfidFrame.build(cmd, payload);
        String hex = RfidFrame.hexSpaced(got);
        boolean ok = hex.equals(want);
        report(name, ok, ok ? hex : hex + "  期望 " + want);
    }

    private static void check(String name, boolean ok) {
        report(name, ok, null);
    }

    private static void report(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.printf("  [OK]   %s%n", name);
        } else {
            fail++;
            System.out.printf("  [FAIL] %s%s%n", name,
                    detail == null ? "" : "   -> " + detail);
        }
    }
}
