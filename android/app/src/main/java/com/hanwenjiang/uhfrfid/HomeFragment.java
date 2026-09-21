package com.hanwenjiang.uhfrfid;

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

import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

/**
 * 主页：读标签（顺带自动上传）+ 写标签（从库里挑一颗直接写）。
 *
 * <p>「读标签」和「上传到云端」并排放，因为自动上传有可能失败（网络、key 不对），
 * 那时候要能马上手点重传，而不是跑去别的页面找。
 */
public class HomeFragment extends Fragment implements MainActivity.StatusHost {

    private TextView statusText, epcText, tidText, checkText;
    private TextView hitLabel, hitName, hitSub, writeTargetText;
    private MaterialCardView hitCard;
    private com.google.android.material.button.MaterialButton uploadBtn;

    /** 用户挑好、准备写进去的那颗云端标签。 */
    private CloudClient.Tag target;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, Bundle b) {
        return inf.inflate(R.layout.fragment_home, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        statusText = v.findViewById(R.id.statusText);
        epcText = v.findViewById(R.id.epcText);
        tidText = v.findViewById(R.id.tidText);
        checkText = v.findViewById(R.id.checkText);
        hitCard = v.findViewById(R.id.hitCard);
        hitLabel = v.findViewById(R.id.hitLabel);
        hitName = v.findViewById(R.id.hitName);
        hitSub = v.findViewById(R.id.hitSub);
        writeTargetText = v.findViewById(R.id.writeTargetText);
        uploadBtn = v.findViewById(R.id.uploadBtn);

        v.findViewById(R.id.readBtn).setOnClickListener(x -> readOnce(false));
        uploadBtn.setOnClickListener(x -> uploadCurrent());
        v.findViewById(R.id.pickWriteBtn).setOnClickListener(x -> pickTag());

        // 从上次读到的状态恢复（切页面回来时不该是空的）
        epcText.setText(RfidSession.get().lastEpc);
        tidText.setText(RfidSession.get().lastTid);
        if (RfidSession.get().lastStatus != null) setStatus(RfidSession.get().lastStatus);
        updateUploadBtn();
        updateTargetText();
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
                    hideCards();
                    epcText.setText(epc);
                    tidText.setText(tid);
                    checkText.setText(verdict.complete
                            ? getString(R.string.check_ok)
                            : getString(R.string.check_bad,
                                    verdict.detail == null ? verdict.reason : verdict.detail));
                    checkText.setTextColor(verdict.complete ? 0xFF1B5E20 : 0xFFB71C1C);
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

                // 合格 + 开了自动上传 -> 直接传；否则等用户点「上传到云端」
                if (!forUpload && Prefs.autoUpload(requireContext())) {
                    doUpload();
                }
            } catch (Exception e) {
                final String why = e.getMessage() == null ? e.toString() : e.getMessage();
                ui(() -> setStatus(getString(R.string.query_failed, why)));
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
        final String note = s.forceAnomaly
                ? getString(R.string.anomaly_note) : null;
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
                        showHitCard(hit, false);
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
                    if (res.tag != null) showHitCard(res.tag, true);
                    askName(res.tag);
                });
                // 刚传上去的立刻能作为补全数据
                try {
                    TagCache.put(client.list(false));
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                final String why = e.getMessage() == null ? e.toString() : e.getMessage();
                // 自动上传失败时，用户手点「上传到云端」就能重来
                ui(() -> setStatus(getString(R.string.cloud_error, why)));
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
                .setMessage(getString(R.string.cloud_name_msg, tag.epc, tag.suffix))
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

    // ------------------------------------------------------------ 写（从库里挑）

    private void pickTag() {
        if (TagCache.size() == 0) {
            // 缓存空的时候现拉一次，别让用户白跑一趟
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
                tag -> writeTag(tag));
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

    private AlertDialog dialog;

    // ------------------------------------------------------------ 写

    /** 挑好一颗之后：记下来，并问要不要马上写。 */
    private void writeTag(CloudClient.Tag tag) {
        target = tag;
        updateTargetText();
        if (dialog != null) dialog.dismiss();
        if (!RfidSession.get().isConnected()) {
            setStatus(getString(R.string.need_connect_first));
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.cloud_write_title))
                .setMessage(getString(R.string.cloud_write_msg,
                        tag.name == null ? getString(R.string.cloud_unnamed) : tag.name,
                        tag.epc))
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
                byte[] tid = RfidFrame.unhex(
                        tag.tid == null || tag.tid.isEmpty()
                                ? TagWrite.tidFromEpc(tag.epc) : tag.tid);
                RfidReader.WriteReport rep = s.reader.writeEpcAndTid(epc, 2, tid, 0);
                if (rep.ok) {
                    // 两个区都写成功才向云端记一次
                    try {
                        Prefs.client(requireContext()).markWritten(tag.epc);
                    } catch (Exception ignored) {
                    }
                }
                ui(() -> {
                    setStatus(rep.ok ? getString(R.string.write_ok_short)
                            : getString(R.string.write_fail_short));
                    showWriteResult(rep.ok, rep.text);
                });
            } catch (Exception e) {
                ui(() -> setStatus(getString(R.string.write_failed, msg(e))));
            }
        });
    }

    private void updateTargetText() {
        if (writeTargetText == null) return;
        writeTargetText.setText(target == null
                ? getString(R.string.write_target_none)
                : getString(R.string.write_target,
                        target.name == null ? getString(R.string.cloud_unnamed) : target.name,
                        target.suffix));
    }

    // ------------------------------------------------------------ 卡片和提示

    private void ui(Runnable r) {
        if (isAdded()) requireActivity().runOnUiThread(r);
    }

    private void setStatus(String s) {
        showStatus(s);
    }

    /** 宿主（连接状态之类）也会往这一页推状态文字。 */
    @Override
    public void showStatus(String s) {
        if (statusText != null && s != null) statusText.setText(s);
    }

    private void updateUploadBtn() {
        RfidSession s = RfidSession.get();
        boolean has = s.lastEpc != null && !s.lastEpc.startsWith("—");
        uploadBtn.setEnabled(has);
    }

    private void hideCards() {
        hitCard.setVisibility(View.GONE);
    }

    static String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    static String fmtTime(String s) {
        if (s == null || s.length() < 16) return "-";
        return s.substring(5, 16);
    }

    private void showHitCard(CloudClient.Tag t, boolean justUploaded) {
        boolean unnamed = t.name == null || t.name.isEmpty();
        hitLabel.setText(justUploaded ? R.string.hit_label_new
                : (unnamed ? R.string.hit_label_unnamed : R.string.hit_label));
        hitName.setText(unnamed ? getString(R.string.cloud_unnamed) : t.name);
        hitName.setTextColor(unnamed ? 0xFFB26A00 : 0xFF0D47A1);

        StringBuilder sb = new StringBuilder(getString(R.string.hit_sub,
                t.suffix, t.seenCount, fmtTime(t.lastSeen)));
        if (t.writeCount > 0) {
            sb.append("　写过 ").append(t.writeCount).append(" 次");
            if (t.lastWritten != null && t.lastWritten.length() >= 16) {
                sb.append("（最后 ").append(fmtTime(t.lastWritten)).append("）");
            }
        }
        hitSub.setText(sb.toString());
        hitCard.setCardBackgroundColor(justUploaded ? 0xFFFFF8E1 : 0xFFE3F2FD);
        hitCard.setStrokeColor(justUploaded ? 0xFFF9A825 : 0xFF1E88E5);
        popIn(hitCard);
    }

    /** 写入结果：成功绿卡 + 强调动画，失败红卡。 */
    private void showWriteResult(boolean ok, String report) {
        hitCard.setVisibility(View.GONE);
        // 结果直接放进状态区，这里只保留"弹一下"的效果提示
        statusText.setTextColor(ok ? 0xFF1B5E20 : 0xFFB71C1C);
        popIn(statusText);
    }

    static void popIn(View card) {
        card.setVisibility(View.VISIBLE);
        card.setAlpha(0f);
        card.setScaleX(0.88f);
        card.setScaleY(0.88f);
        card.animate().alpha(1f).scaleX(1.04f).scaleY(1.04f).setDuration(200)
                .withEndAction(() -> card.animate().scaleX(1f).scaleY(1f)
                        .setDuration(120).start())
                .start();
    }
}
