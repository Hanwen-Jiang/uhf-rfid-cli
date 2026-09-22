package com.hanwenjiang.uhfrfid;

import android.content.Context;

import java.util.List;

/**
 * 读标签、上传、写标签、标签库操作。
 *
 * <p>从 HomeFragment / CloudActivity 里抽出来的：原来这些逻辑和界面控件缠在一起
 * （直接 setText、弹 AlertDialog），换成 Compose 之后界面那边没法被 Java 直接操作，
 * 所以改成"只写状态"—— 所有结果都写进 {@link AppState}，界面自己观察、自己画。
 */
public class TagOps {

    private final Context app;

    public TagOps(Context ctx) {
        this.app = ctx.getApplicationContext();
    }

    private static void status(String s) {
        AppState.INSTANCE.setStatus(s);
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    // ------------------------------------------------------------ 读

    public void read() {
        read(false);
    }

    /**
     * 读一次标签。
     *
     * <p>合格就（按设置）自动上传；不合格会<b>再读一次</b>，两次读数完全相同才破例上传并记为异常。
     */
    public void read(boolean forUpload) {
        final RfidSession s = RfidSession.get();
        if (!s.isConnected()) {
            status(app.getString(R.string.need_connect_first));
            return;
        }
        status(app.getString(R.string.reading));
        Bg.run(() -> {
            try {
                long t0 = System.currentTimeMillis();
                RfidFrame.TagId id = s.reader.readEpcTid();
                long dt = System.currentTimeMillis() - t0;
                if (!id.ok()) {
                    s.lastEpc = "—";
                    s.lastTid = "—";
                    s.verdict = null;
                    s.lastStatus = app.getString(R.string.read_none);
                    AppState.INSTANCE.setRead("—", "—", null, "");
                    AppState.INSTANCE.clearHit();
                    status(s.lastStatus);
                    return;
                }

                TagValidator.Result v = TagValidator.check(id.epc, id.tid);
                final String epc = id.epc;
                final String tid = id.tid == null ? "(应答里没有)" : id.tid;
                s.lastEpc = epc;
                s.lastTid = tid;
                s.verdict = v;
                s.lastStatus = app.getString(R.string.read_ok, dt);

                AppState.INSTANCE.setRead(epc, tid, v.complete,
                        v.complete ? app.getString(R.string.check_ok)
                                : app.getString(R.string.check_bad,
                                        v.detail == null ? v.reason : v.detail));
                AppState.INSTANCE.clearHit();
                status(s.lastStatus);

                if (!v.complete) {
                    status(app.getString(R.string.check_retry));
                    Thread.sleep(250);
                    RfidFrame.TagId again = s.reader.readEpcTid();
                    if (again.ok()
                            && epc.equalsIgnoreCase(again.epc)
                            && tid.equalsIgnoreCase(again.tid)) {
                        s.forceAnomaly = true;
                        s.lastStatus = app.getString(R.string.cloud_bad_twice);
                        status(s.lastStatus);
                        doUpload();
                    } else {
                        s.forceAnomaly = false;
                        status(app.getString(R.string.cloud_bad_once,
                                v.detail == null ? v.reason : v.detail));
                    }
                    return;
                }

                if (!forUpload && Prefs.autoUpload(app)) doUpload();
            } catch (Exception e) {
                status(app.getString(R.string.query_failed, msg(e)));
            }
        });
    }

    // ------------------------------------------------------------ 上传

    public void upload() {
        RfidSession s = RfidSession.get();
        if (s.lastEpc == null || s.lastEpc.startsWith("—")) {
            status(app.getString(R.string.cloud_read_first));
            return;
        }
        if (!Prefs.isConfigured(app)) {
            status(app.getString(R.string.cloud_need_key));
            return;
        }
        doUpload();
    }

    private void doUpload() {
        final RfidSession s = RfidSession.get();
        final String epc = s.lastEpc;
        final String tid = s.lastTid;
        final String note = s.forceAnomaly ? app.getString(R.string.anomaly_note) : null;
        s.forceAnomaly = false;

        status(app.getString(R.string.cloud_checking));
        Bg.run(() -> {
            try {
                CloudClient client = Prefs.client(app);

                // 已经在库里就不重复上传，只告诉用户命中了哪一条
                CloudClient.Tag hit = client.findByEpc(epc, true);
                if (hit != null) {
                    status(hit.name == null || hit.name.isEmpty()
                            ? app.getString(R.string.cloud_hit_unnamed, hit.suffix,
                                    hit.seenCount, HomeFmt.time(hit.lastSeen))
                            : app.getString(R.string.cloud_hit, hit.name, hit.suffix,
                                    hit.seenCount, HomeFmt.time(hit.lastSeen)));
                    AppState.INSTANCE.setHit(
                            hit.name == null || hit.name.isEmpty() ? "(未命名)" : hit.name,
                            app.getString(R.string.hit_sub, hit.suffix, hit.seenCount,
                                    HomeFmt.time(hit.lastSeen)),
                            false);
                    if (hit.name == null || hit.name.isEmpty()) {
                        AppState.INSTANCE.setNameTag(hit);
                    }
                    return;
                }

                status(app.getString(R.string.cloud_uploading));
                CloudClient.UploadResult res = client.upload(epc, tid, note);
                status(note == null
                        ? app.getString(R.string.cloud_upload_ok, epc)
                        : app.getString(R.string.cloud_upload_anomaly, epc));
                if (res.tag != null) {
                    AppState.INSTANCE.setHit(
                            res.tag.name == null || res.tag.name.isEmpty()
                                    ? "(未命名)" : res.tag.name,
                            app.getString(R.string.hit_sub, res.tag.suffix,
                                    res.tag.seenCount, HomeFmt.time(res.tag.lastSeen)),
                            false);
                    if (res.tag.name == null || res.tag.name.isEmpty()) {
                        AppState.INSTANCE.setNameTag(res.tag);
                    }
                }
                refreshCache();
            } catch (Exception e) {
                status(app.getString(R.string.cloud_error, msg(e)));
            }
        });
    }

    // ------------------------------------------------------------ 写

    /** 写一颗云端标签到贴近天线的这颗标签上（两个区都写 + 回读校验）。 */
    public void write(CloudClient.Tag tag) {
        AppState.INSTANCE.setWriting(true);
        status(app.getString(R.string.cloud_writing));
        Bg.run(() -> {
            try {
                RfidSession s = RfidSession.get();
                byte[] epc = RfidFrame.unhex(tag.epc);
                final String tidHex = tag.tid == null || tag.tid.isEmpty()
                        ? TagWrite.tidFromEpc(tag.epc) : tag.tid;
                byte[] tid = RfidFrame.unhex(tidHex);
                RfidReader.WriteReport rep = s.reader.writeEpcAndTid(epc, 2, tid, 0);
                if (rep.ok) {
                    try {
                        Prefs.client(app).markWritten(tag.epc);
                    } catch (Exception ignored) {
                    }
                    // 面板刷成**刚写进去**的值。不用再读一次：writeEpcAndTid
                    // 每写一个区都回读校验过，这个值就是验证过的。
                    s.lastEpc = tag.epc;
                    s.lastTid = tidHex;
                    s.verdict = TagValidator.check(tag.epc, tidHex);
                    AppState.INSTANCE.setRead(tag.epc, tidHex, s.verdict.complete,
                            s.verdict.complete ? app.getString(R.string.check_ok)
                                    : app.getString(R.string.check_bad,
                                            s.verdict.detail == null ? s.verdict.reason
                                                    : s.verdict.detail));
                    AppState.INSTANCE.setHit(
                            tag.name == null || tag.name.isEmpty() ? "(未命名)" : tag.name,
                            app.getString(R.string.written_sub, tag.suffix), true);
                    status(app.getString(R.string.write_ok_target,
                            tag.name == null || tag.name.isEmpty() ? "(未命名)" : tag.name,
                            tag.suffix));
                } else {
                    status(app.getString(R.string.write_fail_short));
                    AppState.INSTANCE.setRawLog(rep.text);
                }
                AppState.INSTANCE.setWriteOk(rep.ok);
            } catch (Exception e) {
                status(app.getString(R.string.write_failed, msg(e)));
                AppState.INSTANCE.setWriteOk(false);
            } finally {
                AppState.INSTANCE.setWriting(false);
            }
        });
    }

    /** 古法写入：手敲的 EPC / TID。 */
    public void writeManual(String epcHex, String tidHex) {
        if (epcHex.isEmpty() && tidHex.isEmpty()) {
            AppState.INSTANCE.setManualStatus(app.getString(R.string.manual_empty));
            return;
        }
        if (!RfidSession.get().isConnected()) {
            AppState.INSTANCE.setManualStatus(app.getString(R.string.need_connect_first));
            return;
        }
        AppState.INSTANCE.setManualStatus(app.getString(R.string.cloud_writing));
        Bg.run(() -> {
            try {
                RfidSession s = RfidSession.get();
                RfidReader.WriteReport rep = s.reader.writeEpcAndTid(
                        RfidFrame.unhex(epcHex), 2, RfidFrame.unhex(tidHex), 0);
                if (rep.ok) {
                    try {
                        Prefs.client(app).markWritten(epcHex);
                    } catch (Exception ignored) {
                    }
                }
                AppState.INSTANCE.setRawLog(s.transport.takeLog());
                AppState.INSTANCE.setManualStatus(
                        (rep.ok ? app.getString(R.string.write_ok_short)
                                : app.getString(R.string.write_fail_short)) + "\n" + rep.text);
            } catch (IllegalArgumentException e) {
                AppState.INSTANCE.setManualStatus(
                        app.getString(R.string.manual_bad_hex, e.getMessage()));
            } catch (Exception e) {
                AppState.INSTANCE.setManualStatus(app.getString(R.string.write_failed, msg(e)));
            }
        });
    }

    // ------------------------------------------------------------ 标签库

    public void loadLibrary() {
        final boolean unnamed = AppState.INSTANCE.getOnlyUnnamed();
        AppState.INSTANCE.setTagsLoading(true);
        AppState.INSTANCE.setTagsStatus(app.getString(R.string.cloud_loading));
        Bg.run(() -> {
            try {
                CloudClient client = Prefs.client(app);
                List<CloudClient.Tag> got = client.list(unnamed);
                // 补全数据用全量（列表可能只筛了没命名的）
                try {
                    TagCache.put(unnamed ? client.list(false) : got);
                } catch (Exception e) {
                    TagCache.putError(msg(e));
                }
                int noName = 0;
                for (CloudClient.Tag t : got) {
                    if (t.name == null || t.name.isEmpty()) noName++;
                }
                AppState.INSTANCE.setTags(got);
                AppState.INSTANCE.setTagsStatus(
                        app.getString(R.string.cloud_count, got.size(), noName));
            } catch (Exception e) {
                AppState.INSTANCE.setTagsStatus(app.getString(R.string.cloud_error, msg(e)));
            } finally {
                AppState.INSTANCE.setTagsLoading(false);
            }
        });
    }

    public void rename(long id, String name) {
        if (name == null || name.trim().isEmpty()) return;
        final String n = name.trim();
        Bg.run(() -> {
            try {
                Prefs.client(app).rename(id, n);
                loadLibrary();
            } catch (Exception e) {
                AppState.INSTANCE.setTagsStatus(app.getString(R.string.cloud_error, msg(e)));
            }
        });
    }

    public void delete(long id) {
        Bg.run(() -> {
            try {
                Prefs.client(app).delete(id);
                loadLibrary();
            } catch (Exception e) {
                AppState.INSTANCE.setTagsStatus(app.getString(R.string.cloud_error, msg(e)));
            }
        });
    }

    /** 补全用的内存缓存 + 标签库列表。 */
    public void refreshCache() {
        try {
            TagCache.put(Prefs.client(app).list(false));
            AppState.INSTANCE.setTags(TagCache.tags());
        } catch (Exception e) {
            TagCache.putError(msg(e));
        }
    }

    public void refreshCacheAsync() {
        Bg.run(this::refreshCache);
    }
}
