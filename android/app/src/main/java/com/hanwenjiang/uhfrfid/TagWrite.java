package com.hanwenjiang.uhfrfid;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * 补全时<b>最后几位留给用户自己敲</b>。
     *
     * <p>最后 5 位是每颗标签的序号（{@code 25C75}、{@code 3A45C} …），是"这颗是哪颗"
     * 的关键信息。替你填掉反而容易把标签写错，所以只补前面固定的部分。
     */
    public static final int TAIL_KEEP = 5;

    /**
     * 尾巴匹配 / 包含匹配至少要敲这么多位才认。
     *
     * <p>不设下限的话，敲一个 {@code 2} 就已经唯一匹配到 {@code ...83072}、一下填满 24 位，
     * 后面几下敲击全落在末尾变成垃圾。
     */
    public static final int MIN_TAIL_MATCH = 4;

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
     * 从<b>已知标签列表</b>里补全（数据来自内存缓存，不查网络）。
     *
     * <p>规则（用库里那 6 颗标签验证过，逐条对上）：
     * <pre>
     *   输入 E              → 6 个候选 → 公共前缀 E28069150000
     *   输入 E280691500004  → 1 个候选 → E28069150000402008B
     *   输入 E280691500005  → 5 个候选 → E28069150000502008B
     * </pre>
     *
     * <p>也就是：<b>补到所有候选的公共前缀，但最后 {@link #TAIL_KEEP} 位留给自己敲</b>
     * —— 最后几位是每颗标签的序号，是"这颗是哪颗"的关键信息，替你填掉反而容易写错。
     *
     * <p>另外还认<b>尾巴</b>：直接敲 {@code 25C75} 这种序号时，如果只有一颗标签以它
     * 结尾，就补成完整的那一颗。
     *
     * <p>只在"变长了"的时候补（{@code grew=true}）。否则用户一退格就被补回来，
     * 又变成删不掉的输入框了。
     *
     * @param known    已知的完整值（EPC 用 EPC 列表，TID 用 TID 列表）
     * @param grew     这次改动是不是在增加字符（打字），退格/删除时为 false
     * @return 补全后的值；补不出来就原样返回
     */
    public static String completeFrom(String raw, List<String> known, boolean grew) {
        String s = norm(raw);
        if (s.isEmpty() || known == null || known.isEmpty() || !isHex(s)) return s;
        // 封顶：满了之后再敲不该继续变长，否则多出来的字符会让写入变成非法值
        if (s.length() > FULL) return s.substring(0, FULL);
        if (s.length() >= FULL) return s;
        // 退格时不补 —— 否则删到补全长度以下又会被补回来，永远删不掉
        if (!grew) return s;

        // 1) 前缀匹配：补到候选的公共前缀（最后 TAIL_KEEP 位除外）
        List<String> hits = new ArrayList<>();
        for (String k : known) {
            if (k.startsWith(s)) hits.add(k);
        }
        if (!hits.isEmpty()) {
            String lcp = longestCommonPrefix(hits);
            int cap = Integer.MAX_VALUE;
            for (String k : hits) cap = Math.min(cap, k.length() - TAIL_KEEP);
            if (lcp.length() > cap) lcp = lcp.substring(0, Math.max(cap, 0));
            if (lcp.length() > s.length()) return lcp;
            return s;
        }

        // 2) 尾巴匹配 / 3) 包含匹配，都要求够长才认。
        //    不设下限的话，敲一个 "2" 就已经唯一匹配到 "...83072"、一下子填满 24 位，
        //    后面几下敲击全落在末尾变成垃圾。要求 ≥ MIN_TAIL_MATCH 位，
        //    相当于"用户确实在敲那串序号"，而不是碰巧一个字撞上了。
        if (s.length() >= MIN_TAIL_MATCH) {
            String uniq = uniqueMatch(known, s, true);
            if (uniq == null) uniq = uniqueMatch(known, s, false);
            if (uniq != null && uniq.length() > s.length()) return uniq;
        }
        return s;
    }

    /** 唯一匹配才返回，多个或没有都返回 null。 */
    private static String uniqueMatch(List<String> known, String s, boolean suffixOnly) {
        String found = null;
        for (String k : known) {
            boolean hit = suffixOnly ? k.endsWith(s) : k.contains(s);
            if (!hit) continue;
            if (found != null) return null;      // 不止一个，不猜
            found = k;
        }
        return found;
    }

    /** 一组字符串的公共前缀。 */
    public static String longestCommonPrefix(List<String> xs) {
        if (xs == null || xs.isEmpty()) return "";
        String s = xs.get(0);
        for (String x : xs) {
            int i = 0;
            while (i < s.length() && i < x.length() && s.charAt(i) == x.charAt(i)) i++;
            s = s.substring(0, i);
            if (s.isEmpty()) break;
        }
        return s;
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
