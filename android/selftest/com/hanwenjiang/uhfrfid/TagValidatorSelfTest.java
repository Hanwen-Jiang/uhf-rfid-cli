package com.hanwenjiang.uhfrfid;

/**
 * TagValidator 自测 —— 纯 JVM 跑，不需要 Android。
 *
 * <p>它和服务端的 {@code cloud/src/index.js: checkComplete()} 是两份独立实现，
 * 必须给出完全一样的结论。用例只有一份（{@link #CASES}），
 * 免得"用例表"和"期望值表"各写一遍然后互相漂移 —— 那正是之前踩过的坑。
 *
 * <pre>
 *   java  -cp out com.hanwenjiang.uhfrfid.TagValidatorSelfTest          # 自测
 *   java  -cp out com.hanwenjiang.uhfrfid.TagValidatorSelfTest cases    # 输出给对比脚本
 *   python cloud/check_agree.py                                        # 和线上 Worker 比对
 * </pre>
 */
public class TagValidatorSelfTest {

    /** {EPC, TID, 期望结论}。COMPLETE 表示合格，否则是不合格的原因。 */
    static final String[][] CASES = {
        // 真机读到的（合格）：第 9 位 EPC=0 / TID=2，其余一致
        {"E28069150000502008B25C75", "E28069152000502008B25C75", "COMPLETE"},
        {"E28069150000402008B83072", "E28069152000402008B83072", "COMPLETE"},
        {"E28069150000402008B6F478", "E28069152000402008B6F478", "COMPLETE"},
        // 第 9 位不对
        {"E28069159000502008B25C75", "E28069152000502008B25C75", "ninth_digit"},
        {"E28069150000502008B25C75", "E28069153000502008B25C75", "ninth_digit"},
        {"E28069150000502008B25C75", "E28069150000502008B25C75", "ninth_digit"},
        // 第 9 位对了，但别处不同
        {"E28069150000502008B25C75", "E28069152A00502008B25C75", "other_digit"},
        // 长度不一致。用**偶数长度**的合法 hex：奇数长度会被服务端的输入校验
        // 先挡掉（400），那属于输入校验，不是完整性判定，两边不必一致。
        {"E28069150000502008B25C75", "E28069152000502008B2", "length_mismatch"},
        // 太短
        {"E2806915", "E2806915", "too_short"},
    };

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("cases")) {
            // 给 cloud/check_agree.py 用的机器可读输出
            for (String[] c : CASES) {
                TagValidator.Result r = TagValidator.check(c[0], c[1]);
                System.out.println(c[0] + " " + c[1] + " => "
                        + (r.complete ? "COMPLETE" : r.reason));
            }
            return;
        }

        int pass = 0, fail = 0;
        System.out.println("=== TagValidator 自测 ===");
        for (String[] c : CASES) {
            TagValidator.Result r = TagValidator.check(c[0], c[1]);
            String got = r.complete ? "COMPLETE" : r.reason;
            boolean ok = got.equals(c[2]);
            if (ok) pass++; else fail++;
            System.out.printf("  [%s] %s / %s -> %s%n",
                    ok ? "OK" : "FAIL", c[0], c[1], got);
        }

        boolean suffixOk = "25C75".equals(TagValidator.commonSuffix(
                "E28069150000502008B25C75", "E28069152000502008B25C75", 5));
        System.out.println("  [" + (suffixOk ? "OK" : "FAIL") + "] 共同后 5 位 = 25C75");
        if (suffixOk) pass++; else fail++;

        System.out.printf("%n结果: %d 通过, %d 失败%n", pass, fail);
        if (fail > 0) System.exit(1);
    }
}
