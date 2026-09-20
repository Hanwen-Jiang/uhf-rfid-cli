package com.hanwenjiang.uhfrfid;

/**
 * 标签完整性校验（{完整}）—— 和 Cloudflare Worker 里的 {@code checkComplete()} 保持一致。
 *
 * <p>这颗标签的 EPC 和 TID <b>只差第 9 个 hex 位</b>：EPC 那一位是 {@code 0}，
 * TID 那一位是 {@code 2}，其余完全相同：
 * <pre>
 *   EPC  E2806915 0 000502008B25C75
 *   TID  E2806915 2 000502008B25C75
 *               ↑ 第 9 位
 * </pre>
 *
 * <p>注意这一位是 **hex 字符位**（从 1 开始数），不是字节位。
 * 两处实现必须同步改，否则客户端说合格、服务端判不合格。
 */
public final class TagValidator {

    /** 第 9 个 hex 位，0 基下标是 8。 */
    public static final int DIFF_INDEX = 8;
    public static final char EPC_AT_DIFF = '0';
    public static final char TID_AT_DIFF = '2';

    public static final class Result {
        public final boolean complete;
        public final String reason;      // ok / ninth_digit / other_digit / length_mismatch / too_short
        public final String detail;

        Result(boolean complete, String reason, String detail) {
            this.complete = complete;
            this.reason = reason;
            this.detail = detail;
        }
    }

    private TagValidator() {
    }

    /** EPC 和 TID 共同的后缀（除了第 9 位以外它们一样）。 */
    public static String commonSuffix(String epc, String tid, int len) {
        if (epc == null || tid == null) return "";
        int n = Math.min(Math.min(epc.length(), tid.length()), len);
        if (n <= 0) return "";
        return epc.substring(epc.length() - n);
    }

    public static Result check(String epc, String tid) {
        if (epc == null || tid == null) {
            return new Result(false, "missing", "EPC 或 TID 为空");
        }
        if (epc.length() != tid.length()) {
            return new Result(false, "length_mismatch",
                    "EPC " + epc.length() + " 字符，TID " + tid.length() + " 字符");
        }
        if (epc.length() <= DIFF_INDEX) {
            return new Result(false, "too_short", "只有 " + epc.length() + " 个字符");
        }
        char e = epc.charAt(DIFF_INDEX);
        char t = tid.charAt(DIFF_INDEX);
        if (e != EPC_AT_DIFF || t != TID_AT_DIFF) {
            return new Result(false, "ninth_digit",
                    String.format("第 %d 位应为 EPC='%c' / TID='%c'，实际 EPC='%c' / TID='%c'",
                            DIFF_INDEX + 1, EPC_AT_DIFF, TID_AT_DIFF, e, t));
        }
        for (int i = 0; i < epc.length(); i++) {
            if (i == DIFF_INDEX) continue;
            if (epc.charAt(i) != tid.charAt(i)) {
                return new Result(false, "other_digit",
                        String.format("第 %d 位就不同：EPC='%c' / TID='%c'",
                                i + 1, epc.charAt(i), tid.charAt(i)));
            }
        }
        return new Result(true, "ok", null);
    }
}
