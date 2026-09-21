package com.hanwenjiang.uhfrfid;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 后台任务用的<b>单线程</b>执行器。
 *
 * <p>为什么是单线程：读标签、写标签、查云端都要碰同一个串口 / 同一个会话，
 * 两个页面同时操作会互相踩。串行执行从根上避免这件事 —— 代价只是操作要排队，
 * 而用户本来也是一次做一件事。
 *
 * <p>进程级的，所以切页面、转屏都不会把排队中的任务丢掉。
 */
public final class Bg {

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rfid-bg");
        t.setDaemon(true);
        return t;
    });

    private Bg() {
    }

    public static void run(Runnable task) {
        POOL.execute(task);
    }
}
