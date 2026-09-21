package com.hanwenjiang.uhfrfid;

import java.util.Arrays;
import java.util.List;

/**
 * TagWrite 的自测 —— 纯 JVM，不需要 Android。
 *
 * <pre>
 *   java -cp out com.hanwenjiang.uhfrfid.TagWriteSelfTest
 * </pre>
 *
 * <p>补全用的数据就是云端那 6 颗真实标签。
 */
public class TagWriteSelfTest {

    /** 云端现有的 6 颗 EPC。 */
    static final List<String> KNOWN = Arrays.asList(
            "E28069150000402008B83072",   // 周-教职
            "E28069150000502008B25C75",   // 外-后
            "E28069150000502008B3A45C",   // 外-社区
            "E28069150000502008B4485F",   // 外-委
            "E28069150000502008B13090",   // 外-其他
            "E28069150000502008B79490");  // 外-其他

    static int pass = 0, fail = 0;

    static void eq(String what, String got, String want) {
        boolean ok = got.equals(want);
        if (ok) pass++; else fail++;
        System.out.printf("  [%s] %-34s -> %s%n", ok ? "OK" : "FAIL", what, got);
        if (!ok) System.out.println("        期望: " + want);
    }

    static void yes(String what, boolean got) {
        if (got) pass++; else fail++;
        System.out.printf("  [%s] %s%n", got ? "OK" : "FAIL", what);
    }

    /** 打字（变长）。 */
    static String type(String s) {
        return TagWrite.completeFrom(s, KNOWN, true);
    }

    public static void main(String[] args) {
        System.out.println("=== 产品要求的三条，逐条验 ===");
        eq("输入 E", type("E"), "E28069150000");
        eq("接着输入 4", type("E280691500004"), "E28069150000402008B");
        eq("输入 5 而不是 4", type("E280691500005"), "E28069150000502008B");

        System.out.println("=== 按真人敲法走到底（补全停止后自己敲序号）===");
        // 注意不能"逐字敲目标串"：第一个字符就会触发补全，后面接的字符位置全错位，
        // 那是现实里敲不出来的序列。真人敲的是 E、4，然后接着敲序号 83072。
        String v = "";
        for (String c : new String[]{"E", "4", "8", "3", "0", "7", "2"}) {
            v = type(v + c);
        }
        eq("敲完 E 4 8 3 0 7 2", v, "E28069150000402008B83072");
        eq("敲到第 2 下就补到 19 位", type("E280691500004"), "E28069150000402008B");
        yes("补到 19 位后就不再多补（序号留给自己）",
                type("E28069150000402008B8").length() == 20);

        System.out.println("=== 退格不能被补回来 ===");
        eq("退格（不增长）不补", TagWrite.completeFrom("E2806915000040200", KNOWN, false),
                "E2806915000040200");
        eq("退到 13 位也不补", TagWrite.completeFrom("E280691500004", KNOWN, false),
                "E280691500004");
        eq("删到只剩 E 也不补", TagWrite.completeFrom("E", KNOWN, false), "E");
        eq("完全空了", TagWrite.completeFrom("", KNOWN, false), "");

        System.out.println("=== 直接敲序号（尾巴）===");
        eq("敲 25C75", type("25C75"), "E28069150000502008B25C75");
        eq("敲 3A45C", type("3A45C"), "E28069150000502008B3A45C");
        eq("敲 83072", type("83072"), "E28069150000402008B83072");

        System.out.println("=== 敲一位不能被整个吞掉（要求 >= MIN_TAIL_MATCH）===");
        eq("敲 2 不补（只配到尾巴的一位）", type("2"), "2");
        eq("敲 83 不补", type("83"), "83");
        eq("敲 8307（4 位且唯一）才补", type("8307"), "E28069150000402008B83072");

        System.out.println("=== 满了之后不再变长 ===");
        eq("24 位后再敲一位，截断到 24",
                type("E28069150000502008B25C75" + "A"), "E28069150000502008B25C75");
        eq("超过 24 位直接截断",
                type("E28069150000502008B25C75ABC"), "E28069150000502008B25C75");

        System.out.println("=== 补不出来的情况要老实返回原样 ===");
        eq("序号不唯一时前缀 0（5 颗都以 0 结尾？不，查唯一）",
                type("0000"), "0000");
        eq("不相干的字符", type("FFFF"), "FFFF");
        eq("没有已知标签时不补", TagWrite.completeFrom("E", Arrays.asList(), true), "E");

        System.out.println("=== 完整值原样返回，不会被截断 ===");
        eq("已经打满 24 位", type("E28069150000502008B25C75"),
                "E28069150000502008B25C75");

        System.out.println("=== 大小写和分隔符 ===");
        eq("小写", type("e"), "E28069150000");
        eq("带空格", type("E2 80"), "E28069150000");

        System.out.println("=== 公共前缀 ===");
        eq("全 6 颗", TagWrite.longestCommonPrefix(KNOWN), "E28069150000");
        eq("单颗", TagWrite.longestCommonPrefix(Arrays.asList("ABCD")), "ABCD");
        eq("空表", TagWrite.longestCommonPrefix(Arrays.asList()), "");

        System.out.println("=== EPC -> TID ===");
        eq("完整 EPC", TagWrite.tidFromEpc("E28069150000502008B3A45C"),
                "E28069152000502008B3A45C");
        eq("不足 9 位", TagWrite.tidFromEpc("E2806915"), "");

        System.out.println("=== 配对判断 ===");
        yes("合格的配对",
                TagWrite.paired("E28069150000502008B25C75", "E28069152000502008B25C75"));
        yes("尾巴不一致 -> 不配对",
                !TagWrite.paired("E28069150000502008B25C75", "E28069152000502008B3A45C"));
        yes("EPC 第 9 位不是 0 -> 不配对",
                !TagWrite.paired("E28069159000502008B25C75", "E28069152000502008B25C75"));

        System.out.printf("%n结果: %d 通过, %d 失败%n", pass, fail);
        if (fail > 0) System.exit(1);
    }
}
