package com.hanwenjiang.uhfrfid;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;

/**
 * 古法写入：自己敲 EPC / TID 的十六进制值。
 *
 * <p>正常写法在主页（从库里挑一颗直接写）。这一页留给需要手工指定值的情况。
 * 边敲边按云端已有的标签补全，数据来自 {@link TagCache} 内存缓存，不查网络。
 */
public class ManualFragment extends Fragment implements MainActivity.StatusHost {

    private TextInputEditText epcEdit, tidEdit;
    private TextInputLayout epcBox, tidBox;
    private TextView warnText, statusText, logText;

    /** 用户是不是自己动过 TID。动过就不再由 EPC 自动覆盖。 */
    private boolean tidManual;
    /** 程序正在改输入框，避免自己的改动又触发监听器绕成死循环。 */
    private boolean syncing;
    /** 上一次的文本，用来判断这次是不是"越打越长"。 */
    private String lastEpc = "", lastTid = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, Bundle b) {
        return inf.inflate(R.layout.fragment_manual, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        epcEdit = v.findViewById(R.id.epcEdit);
        tidEdit = v.findViewById(R.id.tidEdit);
        epcBox = v.findViewById(R.id.epcBox);
        tidBox = v.findViewById(R.id.tidBox);
        warnText = v.findViewById(R.id.warnText);
        statusText = v.findViewById(R.id.statusText);
        logText = v.findViewById(R.id.logText);

        epcEdit.addTextChangedListener(new TextWatcher() {
            public void afterTextChanged(Editable s) {
                onEpcChanged();
            }

            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }
        });
        tidEdit.addTextChangedListener(new TextWatcher() {
            public void afterTextChanged(Editable s) {
                if (syncing) return;
                tidManual = true;
                String typed = text(tidEdit);
                boolean grew = typed.length() > lastTid.length();
                lastTid = typed;
                String done = TagWrite.completeFrom(typed, TagCache.tids(), grew);
                if (!done.equals(typed)) {
                    syncing = true;
                    tidEdit.setText(done);
                    tidEdit.setSelection(done.length());
                    syncing = false;
                    lastTid = done;
                }
                updateWarn();
            }

            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }
        });

        v.findViewById(R.id.writeBtn).setOnClickListener(x -> writeOnce());
        updateWarn();
    }

    // ------------------------------------------------------------ 补全

    private static String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString();
    }

    /** EPC 变了：补全 -> 写回 -> 推出 TID -> 刷警告。 */
    private void onEpcChanged() {
        if (syncing) return;
        String typed = text(epcEdit);
        // 只在变长时补，否则一退格就被补回来，输入框会变得删不掉
        boolean grew = typed.length() > lastEpc.length();
        lastEpc = typed;
        String done = TagWrite.completeFrom(typed, TagCache.epcs(), grew);

        if (!done.equals(typed)) {
            syncing = true;
            epcEdit.setText(done);
            epcEdit.setSelection(done.length());
            syncing = false;
            lastEpc = done;
        }

        if (!tidManual) {
            syncing = true;
            String tid = TagWrite.tidFromEpc(done);
            tidEdit.setText(tid);
            if (!tid.isEmpty()) tidEdit.setSelection(tid.length());
            syncing = false;
            lastTid = tid;
        }
        updateWarn();
    }

    /**
     * 正常情况下这里什么都不显示 —— 输入框自己会补全，不需要再重复一遍。
     * 只在真有问题时才冒出来：值没输满，或者 EPC 和 TID 的尾巴对不上。
     */
    private void updateWarn() {
        String epc = TagWrite.norm(text(epcEdit));
        String tid = TagWrite.norm(text(tidEdit));
        String warn = null;
        if (!epc.isEmpty() && !tid.isEmpty()) {
            if (epc.length() != TagWrite.FULL || tid.length() != TagWrite.FULL) {
                warn = getString(R.string.manual_short,
                        TagWrite.FULL, epc.length(), tid.length());
            } else if (!epc.substring(TagWrite.DIFF + 1)
                    .equals(tid.substring(TagWrite.DIFF + 1))) {
                warn = getString(R.string.manual_mismatch);
            }
        }
        if (warn == null) {
            warnText.setVisibility(View.GONE);
        } else {
            warnText.setText("⚠ " + warn);
            warnText.setVisibility(View.VISIBLE);
        }
    }

    // ------------------------------------------------------------ 写

    private void writeOnce() {
        final String epcHex = TagWrite.norm(text(epcEdit));
        final String tidHex = TagWrite.norm(text(tidEdit));
        if (epcHex.isEmpty() && tidHex.isEmpty()) {
            statusText.setText(R.string.manual_empty);
            return;
        }
        if (!RfidSession.get().isConnected()) {
            statusText.setText(R.string.need_connect_first);
            return;
        }
        statusText.setText(R.string.cloud_writing);
        Bg.run(() -> {
            try {
                RfidSession s = RfidSession.get();
                RfidReader.WriteReport rep = s.reader.writeEpcAndTid(
                        RfidFrame.unhex(epcHex), 2, RfidFrame.unhex(tidHex), 0);
                String raw = s.transport.takeLog();
                if (rep.ok) {
                    try {
                        Prefs.client(requireContext()).markWritten(epcHex);
                    } catch (Exception ignored) {
                    }
                }
                ui(() -> {
                    statusText.setText(rep.ok ? getString(R.string.write_ok_short)
                            : getString(R.string.write_fail_short));
                    statusText.append("\n" + rep.text);
                    logText.setText(raw);
                    HomeFragment.fadeIn(statusText);
                });
            } catch (IllegalArgumentException e) {
                ui(() -> statusText.setText(getString(R.string.manual_bad_hex, e.getMessage())));
            } catch (Exception e) {
                ui(() -> statusText.setText(getString(R.string.write_failed, msg(e))));
            }
        });
    }

    /** 宿主（连接状态之类）也会往这一页推状态文字。 */
    @Override
    public void showStatus(String s) {
        if (statusText != null && s != null) statusText.setText(s);
    }

    private void ui(Runnable r) {
        if (isAdded()) requireActivity().runOnUiThread(r);
    }

    static String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
