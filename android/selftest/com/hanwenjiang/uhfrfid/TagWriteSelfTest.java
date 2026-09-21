package com.hanwenjiang.uhfrfid;

/**
 * TagWrite 的自测 —— 纯 JVM，不需要 Android。
 *
 * <pre>
 *   java -cp out com.hanwenjiang.uhfrfid.TagWriteSelfTest
 * </pre>
 *
 * <p>这个测试存在的原因：补全逻辑原来写在 Activity 里没法测，
 * 「TID 少一位时被重复补前缀」的 bug 就那样溜进去了。
 */
public class TagWriteSelfTest {

    static final String EPC_PREFIX = "E28069150000502008B";
    static final String TID_PREFIX = "E28069152000502008B";

    static int pass = 0, fail = 0;

    static void eq(String what, String got, String want) {
        boolean ok = got.equals(want);
        if (ok) pass++; else fail++;
        System.out.printf("  [%s] %-40s -> %s%n", ok ? "OK" : "FAIL", what, got);
        if (!ok) System.out.println("        期望: " + want);
    }

    static void yes(String what, boolean got) {
        boolean ok = got;
        if (ok) pass++; else fail++;
        System.out.printf("  [%s] %s%n", ok ? "OK" : "FAIL", what);
    }

    public static void main(String[] args) {
        System.out.println("=== TID 前缀必须和 EPC 前缀分开 ===");
        eq("tidPrefix(EPC前缀)", TagWrite.tidPrefix(EPC_PREFIX), TID_PREFIX);
        // 这个就是线上那个 bug：TID 用 EPC 前缀去补，会叠成两段
        eq("TID 23 位用 TID 前缀补",
                TagWrite.complete("E28069152000502008B3A45", TID_PREFIX),
                "E28069152000502008B3A45");
        yes("TID 23 位若用 EPC 前缀补就会出错（反证）",
                !TagWrite.complete("E28069152000502008B3A45", EPC_PREFIX)
                        .equals("E28069152000502008B3A45"));

        System.out.println("=== 逐字输入 3A45C（EPC）===");
        eq("3", TagWrite.complete("3", EPC_PREFIX), EPC_PREFIX + "3");
        eq("3A", TagWrite.complete("3A", EPC_PREFIX), EPC_PREFIX + "3A");
        eq("3A45", TagWrite.complete("3A45", EPC_PREFIX), EPC_PREFIX + "3A45");
        eq("3A45C", TagWrite.complete("3A45C", EPC_PREFIX), EPC_PREFIX + "3A45C");

        System.out.println("=== 少一位 / 退格：不能再叠前缀 ===");
        eq("补满后原样", TagWrite.complete(EPC_PREFIX + "3A45C", EPC_PREFIX),
                EPC_PREFIX + "3A45C");
        eq("退一位后原样", TagWrite.complete(EPC_PREFIX + "3A45", EPC_PREFIX),
                EPC_PREFIX + "3A45");
        eq("超长截断", TagWrite.complete(EPC_PREFIX + "3A45C" + "D", EPC_PREFIX),
                EPC_PREFIX + "3A45C");

        System.out.println("=== 往前删前缀（必须删得掉）===");
        eq("删到 E", TagWrite.complete("E", EPC_PREFIX), "E");
        eq("删到 E2806915", TagWrite.complete("E2806915", EPC_PREFIX), "E2806915");
        eq("删到 18 位", TagWrite.complete("E2806915000050200", EPC_PREFIX),
                "E2806915000050200");

        System.out.println("=== 非法字符 / 空 / 分隔符 ===");
        eq("有非法字符原样", TagWrite.complete("3A45X", EPC_PREFIX), "3A45X");
        eq("空", TagWrite.complete("", EPC_PREFIX), "");
        eq("小写转大写", TagWrite.complete("3a45c", EPC_PREFIX), EPC_PREFIX + "3A45C");
        eq("带空格", TagWrite.complete("E2 80 69 15", EPC_PREFIX), "E2806915");

        System.out.println("=== EPC -> TID ===");
        eq("完整 EPC", TagWrite.tidFromEpc("E28069150000502008B3A45C"),
                "E28069152000502008B3A45C");
        eq("23 位 EPC", TagWrite.tidFromEpc("E28069150000502008B3A45"),
                "E28069152000502008B3A45");
        eq("不足 9 位", TagWrite.tidFromEpc("E2806915"), "");

        System.out.println("=== 配对判断 ===");
        yes("合格的配对",
                TagWrite.paired("E28069150000502008B25C75", "E28069152000502008B25C75"));
        yes("尾巴不一致 -> 不配对",
                !TagWrite.paired("E28069150000502008B25C75", "E28069152000502008B3A45C"));
        yes("EPC 第 9 位不是 0 -> 不配对",
                !TagWrite.paired("E28069159000502008B25C75", "E28069152000502008B25C75"));
        yes("长度不足 -> 不配对",
                !TagWrite.paired("E28069150000502008B3A45", "E28069152000502008B3A45"));

        System.out.printf("%n结果: %d 通过, %d 失败%n", pass, fail);
        if (fail > 0) System.exit(1);
    }
}
