package com.hanwenjiang.uhfrfid;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

/**
 * 主页：读标签（顺带自动上传）+ 从库里挑一颗写进去。
 *
 * <p>上半部分是一块<b>固定高度</b>的控制面板：状态、命中信息、EPC/TID、校验结果都在里面，
 * 所以内容怎么变版面都不跳。反馈靠<b>边框动画</b> —— 不缩放，也就不会把周围挤开。
 */
public class HomeFragment extends Fragment implements MainActivity.StatusHost {

    private static final int BORDER_IDLE = 0xFFB0BEC5;   // 灰：待机
    private static final int BORDER_HIT = 0xFF1E88E5;    // 蓝：命中云端
    private static final int BORDER_OK = 0xFF43A047;     // 绿：成功
    private static final int BORDER_BAD = 0xFFE53935;    // 红：失败 / 不合格

    private MaterialCardView panel;
    private TextView statusText, epcText, tidText, checkText, hitName, hitSub;
    private MaterialButton uploadBtn, writeBtn, pickWriteBtn;

    /** 用户挑好、准备写进去的那颗云端标签。 */
    private CloudClient.Tag target;
    private ValueAnimator borderAnim;
    private AlertDialog dialog;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, Bundle b) {
        return inf.inflate(R.layout.fragment_home, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        panel = v.findViewById(R.id.panel);
        statusText = v.findViewById(R.id.statusText);
        epcText = v.findViewById(R.id.epcText);
        tidText = v.findViewById(R.id.tidText);
        checkText = v.findViewById(R.id.checkText);
        hitName = v.findViewById(R.id.hitName);
        hitSub = v.findViewById(R.id.hitSub);
        uploadBtn = v.findViewById(R.id.uploadBtn);
        writeBtn = v.findViewById(R.id.writeBtn);
        pickWriteBtn = v.findViewById(R.id.pickWriteBtn);

        v.findViewById(R.id.readBtn).setOnClickListener(x -> readOnce(false));
        uploadBtn.setOnClickListener(x -> uploadCurrent());
        // 左边只挑（选中而已，不动标签）
        pickWriteBtn.setOnClickListener(x -> pickTag());
        // 右边才真写
        writeBtn.setOnClickListener(x -> {
            if (target == null) {
                setStatus(getString(R.string.write_target_none));
                return;
            }
            confirmWrite(target);
        });

        RfidSession s = RfidSession.get();
        epcText.setText(s.lastEpc);
        tidText.setText(s.lastTid);
        if (s.lastStatus != null) setStatus(s.lastStatus);
        updateUploadBtn();
        updatePickButton();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (borderAnim != null) borderAnim.cancel();
    }

    // ------------------------------------------------------------ 反馈动画

    /**
     * 边框走一段颜色过渡。
     *
     * <p>刻意<b>不缩放</b>卡片：缩放会画到原尺寸之外，把上下挤开、还会和邻居重叠。
     * 边框画在卡片里侧，怎么变都不影响布局。
     */
    private void borderTo(int color, boolean pulse) {
        if (panel == null) return;
        if (borderAnim != null) borderAnim.cancel();
        if (!pulse) {
            panel.setStrokeColor(color);
            return;
        }
        borderAnim = ValueAnimator.ofObject(new ArgbEvaluator(),
                BORDER_IDLE, color, BORDER_IDLE, color);
        borderAnim.setDuration(900);
        borderAnim.addUpdateListener(
                a -> panel.setStrokeColor((int) a.getAnimatedValue()));
        borderAnim.start();
    }

    /** 淡入。不放大 —— 放大会撑空间，这里只动透明度。 */
    static void fadeIn(View view) {
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(220).start();
    }

    // ------------------------------------------------------------ 读

    private void readOnce(boolean forUpload) {
        final RfidSession s = RfidSession.get();
        if (!s.isConnected()) {
            setStatus(getString(R.string.need_connect_first));
            return;
        }
        setStatus(getString(R.string.reading));
        Bg.run(() -> {
            try {
                long t0 = System.currentTimeMillis();
                RfidFrame.TagId id = s.reader.readEpcTid();
                long dt = System.currentTimeMillis() - t0;
                if (!id.ok()) {
                    s.lastEpc = "—";
                    s.lastTid = "—";
                    s.verdict = null;
                    s.lastStatus = getString(R.string.read_none);
                    ui(() -> {
                        epcText.setText(s.lastEpc);
                        tidText.setText(s.lastTid);
                        checkText.setText("");
                        hideHit();
                        borderTo(BORDER_IDLE, false);
                        setStatus(s.lastStatus);
                        updateUploadBtn();
                    });
                    return;
                }

                TagValidator.Result verdict = TagValidator.check(id.epc, id.tid);
                final String epc = id.epc;
                final String tid = id.tid == null ? "(应答里没有)" : id.tid;
                s.lastEpc = epc;
                s.lastTid = tid;
                s.verdict = verdict;
                s.lastStatus = getString(R.string.read_ok, dt);
                final String shown = s.lastStatus;

                ui(() -> {
                    epcText.setText(epc);
                    tidText.setText(tid);
                    checkText.setText(verdict.complete
                            ? getString(R.string.check_ok)
                            : getString(R.string.check_bad,
                                    verdict.detail == null ? verdict.reason : verdict.detail));
                    checkText.setTextColor(verdict.complete ? BORDER_OK : BORDER_BAD);
                    borderTo(verdict.complete ? BORDER_OK : BORDER_BAD, true);
                    setStatus(shown);
                    updateUploadBtn();
                });

                if (!verdict.complete) {
                    // 不合格：再读一次，两次读数完全相同才破例上传
                    ui(() -> setStatus(getString(R.string.check_retry)));
                    Thread.sleep(250);
                    RfidFrame.TagId again = s.reader.readEpcTid();
                    if (again.ok()
                            && epc.equalsIgnoreCase(again.epc)
                            && tid.equalsIgnoreCase(again.tid)) {
                        s.forceAnomaly = true;
                        s.lastStatus = getString(R.string.cloud_bad_twice);
                        ui(() -> {
                            setStatus(s.lastStatus);
                            doUpload();
                        });
                    } else {
                        s.forceAnomaly = false;
                        ui(() -> setStatus(getString(R.string.cloud_bad_once,
                                verdict.detail == null ? verdict.reason : verdict.detail)));
                    }
                    return;
                }

                if (!forUpload && Prefs.autoUpload(requireContext())) {
                    doUpload();
                }
            } catch (Exception e) {
                final String why = msg(e);
                ui(() -> {
                    borderTo(BORDER_BAD, false);
                    setStatus(getString(R.string.query_failed, why));
                });
            }
        });
    }

    // ------------------------------------------------------------ 上传

    private void uploadCurrent() {
        RfidSession s = RfidSession.get();
        if (s.lastEpc == null || s.lastEpc.startsWith("—")) {
            setStatus(getString(R.string.cloud_read_first));
            return;
        }
        if (!Prefs.isConfigured(requireContext())) {
            setStatus(getString(R.string.cloud_need_key));
            return;
        }
        doUpload();
    }

    private void doUpload() {
        final RfidSession s = RfidSession.get();
        final String epc = s.lastEpc;
        final String tid = s.lastTid;
        final String note = s.forceAnomaly ? getString(R.string.anomaly_note) : null;
        s.forceAnomaly = false;

        ui(() -> setStatus(getString(R.string.cloud_checking)));
        Bg.run(() -> {
            try {
                CloudClient client = Prefs.client(requireContext());

                // 已经在库里就不重复上传，只告诉用户命中了哪一条
                CloudClient.Tag hit = client.findByEpc(epc, true);
                if (hit != null) {
                    ui(() -> {
                        setStatus(hit.name == null || hit.name.isEmpty()
                                ? getString(R.string.cloud_hit_unnamed, hit.suffix,
                                        hit.seenCount, fmtTime(hit.lastSeen))
                                : getString(R.string.cloud_hit, hit.name, hit.suffix,
                                        hit.seenCount, fmtTime(hit.lastSeen)));
                        showHit(hit, false);
                        if (hit.name == null || hit.name.isEmpty()) askName(hit);
                    });
                    return;
                }

                ui(() -> setStatus(getString(R.string.cloud_uploading)));
                CloudClient.UploadResult res = client.upload(epc, tid, note);
                ui(() -> {
                    setStatus(note == null
                            ? getString(R.string.cloud_upload_ok, epc)
                            : getString(R.string.cloud_upload_anomaly, epc));
                    if (res.tag != null) showHit(res.tag, true);
                    askName(res.tag);
                    updatePickButton();
                });
                try {
                    TagCache.put(client.list(false));
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                final String why = msg(e);
                ui(() -> {
                    borderTo(BORDER_BAD, false);
                    setStatus(getString(R.string.cloud_error, why));
                });
            }
        });
    }

    private void askName(CloudClient.Tag tag) {
        if (tag == null || !isAdded()) return;
        if (tag.name != null && !tag.name.isEmpty()) return;

        EditText input = new EditText(requireContext());
        input.setHint(R.string.cloud_name_hint);
        LinearLayout box = new LinearLayout(requireContext());
        box.setPadding(48, 8, 48, 0);
        box.addView(input);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.cloud_name_title)
                .setView(box)
                .setPositiveButton(R.string.cloud_save, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    Bg.run(() -> {
                        try {
                            Prefs.client(requireContext()).rename(tag.id, name);
                            try {
                                TagCache.put(Prefs.client(requireContext()).list(false));
                            } catch (Exception ignored) {
                            }
                            ui(() -> setStatus(getString(R.string.cloud_renamed, name)));
                        } catch (Exception e) {
                            ui(() -> setStatus(getString(R.string.cloud_error, msg(e))));
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------ 挑标签

    private void pickTag() {
        if (TagCache.size() == 0) {
            setStatus(getString(R.string.cloud_checking));
            Bg.run(() -> {
                try {
                    TagCache.put(Prefs.client(requireContext()).list(false));
                    ui(this::showPicker);
                } catch (Exception e) {
                    ui(() -> setStatus(getString(R.string.pick_need_cache)));
                }
            });
            return;
        }
        showPicker();
    }

    private void showPicker() {
        if (!isAdded()) return;
        List<CloudClient.Tag> all = TagCache.tags();
        if (all.isEmpty()) {
            setStatus(getString(R.string.pick_empty));
            return;
        }

        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_pick_tag, null, false);
        android.widget.ListView list = view.findViewById(R.id.pickList);
        EditText filter = view.findViewById(R.id.filterEdit);
        TextView empty = view.findViewById(R.id.emptyText);

        List<CloudClient.Tag> shown = new ArrayList<>(all);
        TagRowAdapter adapter = new TagRowAdapter(requireContext(), shown,
                this::selectTag, R.string.pick_select);
        list.setAdapter(adapter);

        filter.addTextChangedListener(new android.text.TextWatcher() {
            public void afterTextChanged(android.text.Editable e) {
                String q = e.toString().trim().toUpperCase();
                shown.clear();
                for (CloudClient.Tag t : all) {
                    if (q.isEmpty()
                            || (t.name != null && t.name.toUpperCase().contains(q))
                            || t.suffix.toUpperCase().contains(q)
                            || t.epc.toUpperCase().contains(q)) {
                        shown.add(t);
                    }
                }
                adapter.notifyDataSetChanged();
                empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
            }

            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }
        });
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);

        dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.pick_title)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
    }

    /** 挑一颗：只记下来，不动标签。 */
    private void selectTag(CloudClient.Tag tag) {
        target = tag;
        if (dialog != null) dialog.dismiss();
        updatePickButton();
        setStatus(getString(R.string.write_target, displayName(tag), tag.suffix));
        borderTo(BORDER_HIT, false);
    }

    private static String displayName(CloudClient.Tag t) {
        return t.name == null || t.name.isEmpty() ? "(未命名)" : t.name;
    }

    /** 挑好的那颗直接写在按钮上，不用另外占一行。 */
    private void updatePickButton() {
        if (pickWriteBtn == null) return;
        // 选中的信息显示在「写入」主按钮上；右半边只是下拉三角，不显示文字
        writeBtn.setText(target == null
                ? getString(R.string.write_now)
                : getString(R.string.write_now_target,
                        displayName(target), target.suffix));
        if (writeBtn != null) writeBtn.setEnabled(target != null);
    }

    // ------------------------------------------------------------ 写

    private void confirmWrite(CloudClient.Tag tag) {
        if (!RfidSession.get().isConnected()) {
            setStatus(getString(R.string.need_connect_first));
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.cloud_write_title))
                .setMessage(getString(R.string.cloud_write_msg,
                        displayName(tag), tag.epc))
                .setPositiveButton(R.string.cloud_write, (d, w) -> doWrite(tag))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void doWrite(CloudClient.Tag tag) {
        setStatus(getString(R.string.cloud_writing));
        Bg.run(() -> {
            try {
                RfidSession s = RfidSession.get();
                byte[] epc = RfidFrame.unhex(tag.epc);
                byte[] tid = RfidFrame.unhex(tag.tid == null || tag.tid.isEmpty()
                        ? TagWrite.tidFromEpc(tag.epc) : tag.tid);
                RfidReader.WriteReport rep = s.reader.writeEpcAndTid(epc, 2, tid, 0);
                if (rep.ok) {
                    try {
                        Prefs.client(requireContext()).markWritten(tag.epc);
                    } catch (Exception ignored) {
                    }
                }
                ui(() -> {
                    setStatus(rep.ok ? getString(R.string.write_ok_short)
                            : getString(R.string.write_fail_short));
                    // 成功：边框脉冲；失败：常红。都不缩放。
                    borderTo(rep.ok ? BORDER_OK : BORDER_BAD, true);
                });
            } catch (Exception e) {
                ui(() -> {
                    borderTo(BORDER_BAD, false);
                    setStatus(getString(R.string.write_failed, msg(e)));
                });
            }
        });
    }

    // ------------------------------------------------------------ 杂项

    private void ui(Runnable r) {
        if (isAdded()) requireActivity().runOnUiThread(r);
    }

    @Override
    public void showStatus(String s) {
        if (statusText != null && s != null) statusText.setText(s);
    }

    private void setStatus(String s) {
        showStatus(s);
    }

    private void hideHit() {
        hitName.setVisibility(View.GONE);
        hitSub.setVisibility(View.GONE);
    }

    private void updateUploadBtn() {
        RfidSession s = RfidSession.get();
        uploadBtn.setEnabled(s.lastEpc != null && !s.lastEpc.startsWith("—"));
    }

    static String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    static String fmtTime(String s) {
        if (s == null || s.length() < 16) return "-";
        return s.substring(5, 16);
    }

    private void showHit(CloudClient.Tag t, boolean justUploaded) {
        boolean unnamed = t.name == null || t.name.isEmpty();
        hitName.setText(unnamed ? getString(R.string.cloud_unnamed) : t.name);
        hitName.setTextColor(unnamed ? 0xFFB26A00 : 0xFF0D47A1);

        StringBuilder sb = new StringBuilder(getString(R.string.hit_sub,
                t.suffix, t.seenCount, fmtTime(t.lastSeen)));
        if (t.writeCount > 0) {
            sb.append("　写过 ").append(t.writeCount).append(" 次");
        }
        hitSub.setText(sb.toString());
        fadeIn(hitName);
        fadeIn(hitSub);
        borderTo(justUploaded ? BORDER_OK : BORDER_HIT, justUploaded);
    }
}
