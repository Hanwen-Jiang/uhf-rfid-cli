package com.hanwenjiang.uhfrfid;

/**
 * 写标签时的取值和补全规则。
 *
 * <p>刻意做成<b>不依赖 Android 的纯函数</b>：这段逻辑原来写在 MainActivity 里，
 * 结果"TID 少一位时被重复补前缀"的 bug 溜了进去也没测出来。抽出来之后
 * {@code selftest/TagWriteSelfTest.java} 可以在纯 JVM 上跑。
 *
 * <h3>这批标签的规律</h3>
 * <pre>
 *   EPC  E2806915 0 000502008B25C75
 *   TID  E2806915 2 000502008B25C75
 *               ↑ 第 9 位：EPC=0，TID=2，其余完全相同
 * </pre>
 * 所以 EPC 前缀是 {@code E28069150000502008B}，TID 前缀是
 * {@code E28069152000502008B} —— <b>两者第 9 位不同，不能共用一个前缀</b>。
 */
public final class TagWrite {

    /** EPC / TID 的完整长度（hex 字符数）。 */
    public static final int FULL = 24;

    /** 第 9 位的下标（0 基）。 */
    public static final int DIFF = 8;

    private TagWrite() {
    }

    /** 去掉分隔符并转大写。 */
    public static String norm(String raw) {
        return raw == null ? "" : raw.replaceAll("[\\s:,-]", "").toUpperCase();
    }

    private static boolean isHex(String s) {
        return s.matches("[0-9A-F]+");
    }

    /**
     * 按给定前缀补全一个值。
     *
     * <p>补全是<b>边输边补</b>的、光标停在末尾 —— 前缀补在前面、尾巴在后面，所以
     * 继续往下敲会正确地接在尾巴后面。
     *
     * <p>但"无脑加前缀"会让输入框变成砖头：补完之后一退格，长度就不足 24 了，
     * 再加一次前缀就叠成 {@code E280...BE280...B}，而且再也删不掉。所以按顺序判断：
     * <ol>
     *   <li>空 → 空</li>
     *   <li>有非法字符 → 原样（交给 unhex 报错，别乱补）</li>
     *   <li>已经以完整前缀开头 → 原样（补过了 / 用户自己打全了）</li>
     *   <li>输入是前缀的开头一段 → 原样（用户正在往前删前缀，别跟他抢）</li>
     *   <li>够 24 位 → 取前 24 位</li>
     *   <li>其余 → 前缀 + 输入</li>
     * </ol>
     *
     * @param prefix EPC 用 {@code E28069150000502008B}，
     *               TID 用 {@code E28069152000502008B}，<b>不能混用</b>
     */
    public static String complete(String raw, String prefix) {
        String s = norm(raw);
        if (s.isEmpty()) return "";
        if (!isHex(s)) return s;
        if (s.startsWith(prefix)) return s.length() > FULL ? s.substring(0, FULL) : s;
        if (prefix.startsWith(s)) return s;
        if (s.length() >= FULL) return s.substring(0, FULL);
        return prefix + s;
    }

    /**
     * 由 EPC 推出 TID：只把第 9 位从 {@code 0} 换成 {@code 2}，其余照抄。
     *
     * <p>不足 9 位时返回空串（还不知道第 9 位是什么，推不出来）。
     */
    public static String tidFromEpc(String epc) {
        String s = norm(epc);
        if (s.length() < DIFF + 1) return "";
        return s.substring(0, DIFF) + "2" + s.substring(DIFF + 1);
    }

    /** TID 的补全前缀 = EPC 前缀把第 9 位换成 2。 */
    public static String tidPrefix(String epcPrefix) {
        return tidFromEpc(epcPrefix);
    }

    /**
     * 两者是不是"配对"的：长度相同、且除第 9 位外完全一致
     * （EPC 第 9 位 {@code 0}、TID 第 9 位 {@code 2}）。
     *
     * <p>用来在写入前提醒 —— 只改一个区会让标签变成不配对，之后每次读数都会被判不合格。
     */
    public static boolean paired(String epc, String tid) {
        String e = norm(epc);
        String t = norm(tid);
        if (e.length() != FULL || t.length() != FULL) return false;
        return t.equals(tidFromEpc(e)) && e.charAt(DIFF) == '0';
    }
}
