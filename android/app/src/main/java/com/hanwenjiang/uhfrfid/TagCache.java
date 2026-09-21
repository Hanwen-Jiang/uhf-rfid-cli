package com.hanwenjiang.uhfrfid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 已知标签的内存缓存 —— 给写标签时的自动补全用。
 *
 * <p>补全的数据来自这里，<b>不是每敲一个字就查一次网络</b>。刷新时机就三个
 * （和产品要求一致）：
 * <ol>
 *   <li>打开 App 时</li>
 *   <li>读完标签上传之后（刚传上去的那颗立刻能补出来）</li>
 *   <li>打开云端标签库之后</li>
 * </ol>
 *
 * <p>是进程级单例，所以转屏、切界面都不会丢。
 */
public final class TagCache {

    private static final List<String> EPCS = new ArrayList<>();
    private static final List<String> TIDS = new ArrayList<>();
    private static volatile long updatedAt;
    private static volatile String lastError;

    private TagCache() {
    }

    /** 用刚从云端拉到的标签刷新缓存。 */
    public static synchronized void put(List<CloudClient.Tag> tags) {
        EPCS.clear();
        TIDS.clear();
        if (tags != null) {
            for (CloudClient.Tag t : tags) {
                if (t == null || t.epc == null || t.epc.isEmpty()) continue;
                String e = TagWrite.norm(t.epc);
                if (!EPCS.contains(e)) EPCS.add(e);
                String tid = t.tid == null || t.tid.isEmpty()
                        ? TagWrite.tidFromEpc(e) : TagWrite.norm(t.tid);
                if (!tid.isEmpty() && !TIDS.contains(tid)) TIDS.add(tid);
            }
        }
        Collections.sort(EPCS);
        Collections.sort(TIDS);
        updatedAt = System.currentTimeMillis();
        lastError = null;
    }

    /** 拉取失败时记一下，界面上可以提示"补全数据是旧的"。 */
    public static void putError(String msg) {
        lastError = msg;
    }

    public static String lastError() {
        return lastError;
    }

    public static synchronized List<String> epcs() {
        return new ArrayList<>(EPCS);
    }

    public static synchronized List<String> tids() {
        return new ArrayList<>(TIDS);
    }

    public static synchronized boolean isEmpty() {
        return EPCS.isEmpty();
    }

    public static synchronized int size() {
        return EPCS.size();
    }

    public static long updatedAt() {
        return updatedAt;
    }
}
