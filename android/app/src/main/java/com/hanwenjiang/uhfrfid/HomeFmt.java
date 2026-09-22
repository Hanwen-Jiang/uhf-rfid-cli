package com.hanwenjiang.uhfrfid;

/** 界面用的小工具。留在 Java 这边是因为 {@link TagOps} 要用。 */
public final class HomeFmt {

    private HomeFmt() {
    }

    /** 云端给的是 UTC 的 {@code YYYY-MM-DD HH:MM:SS}，显示到分钟就够。 */
    public static String time(String s) {
        if (s == null || s.length() < 16) return "-";
        return s.substring(5, 16);
    }
}
