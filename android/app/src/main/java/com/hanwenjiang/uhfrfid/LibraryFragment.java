package com.hanwenjiang.uhfrfid;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.checkbox.MaterialCheckBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 标签库页：云端那 6 条（或更多）标签的列表，能命名、删除、一键写入。
 *
 * <p>从原来的 CloudActivity 搬过来，改成底部标签页里的一页。
 */
public class LibraryFragment extends Fragment {

    private final List<CloudClient.Tag> tags = new ArrayList<>();
    private TagRowAdapter adapter;
    private TextView status;
    private MaterialCheckBox onlyUnnamed;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, Bundle b) {
        return inf.inflate(R.layout.fragment_library, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        status = v.findViewById(R.id.status);
        onlyUnnamed = v.findViewById(R.id.onlyUnnamed);
        ListView list = v.findViewById(R.id.list);

        adapter = new TagRowAdapter(requireContext(), tags, this::onWriteClicked);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, view, pos, id) -> onRowClicked(tags.get(pos)));

        v.findViewById(R.id.refreshBtn).setOnClickListener(x -> load());
        onlyUnnamed.setOnCheckedChangeListener((x, checked) -> load());
        load();
    }

    /** 每次回到这一页都刷新一下 —— 别让用户看到过期的列表。 */
    @Override
    public void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        if (!isAdded()) return;
        status.setText(R.string.cloud_loading);
        final boolean unnamed = onlyUnnamed.isChecked();
        final CloudClient client = Prefs.client(requireContext());
        Bg.run(() -> {
            try {
                List<CloudClient.Tag> got = client.list(unnamed);
                // 补全数据用全量（列表可能只筛了没命名的）
                try {
                    TagCache.put(unnamed ? client.list(false) : got);
                } catch (Exception e) {
                    TagCache.putError(msg(e));
                }
                ui(() -> {
                    tags.clear();
                    tags.addAll(got);
                    adapter.notifyDataSetChanged();
                    int noName = 0;
                    for (CloudClient.Tag t : got) {
                        if (t.name == null || t.name.isEmpty()) noName++;
                    }
                    status.setText(getString(R.string.cloud_count, got.size(), noName));
                });
            } catch (Exception e) {
                ui(() -> status.setText(getString(R.string.cloud_error, msg(e))));
            }
        });
    }

    /** 点一行：改名或删除。 */
    private void onRowClicked(CloudClient.Tag t) {
        EditText input = new EditText(requireContext());
        input.setHint(R.string.cloud_name_hint);
        input.setText(t.name == null ? "" : t.name);
        LinearLayout box = new LinearLayout(requireContext());
        box.setPadding(48, 8, 48, 0);
        box.addView(input);

        new AlertDialog.Builder(requireContext())
                .setTitle(t.epc)
                .setMessage(getString(R.string.cloud_row_msg, t.suffix))
                .setView(box)
                .setPositiveButton(R.string.cloud_save, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    Bg.run(() -> {
                        try {
                            Prefs.client(requireContext()).rename(t.id, name);
                            ui(this::load);
                        } catch (Exception e) {
                            ui(() -> status.setText(getString(R.string.cloud_error, msg(e))));
                        }
                    });
                })
                .setNeutralButton(R.string.cloud_delete, (d, w) ->
                        new AlertDialog.Builder(requireContext())
                                .setTitle(R.string.cloud_delete_title)
                                .setMessage(t.epc)
                                .setPositiveButton(R.string.cloud_delete, (d2, w2) ->
                                Bg.run(() -> {
                                    try {
                                        Prefs.client(requireContext()).delete(t.id);
                                        ui(this::load);
                                    } catch (Exception e) {
                                        ui(() -> status.setText(
                                                getString(R.string.cloud_error, msg(e))));
                                    }
                                }))
                                .setNegativeButton(android.R.string.cancel, null)
                                .show())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void onWriteClicked(CloudClient.Tag t) {
        if (!RfidSession.get().isConnected()) {
            status.setText(R.string.need_connect_first);
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.cloud_write_title)
                .setMessage(getString(R.string.cloud_write_msg,
                        t.name == null || t.name.isEmpty()
                                ? getString(R.string.cloud_unnamed) : t.name,
                        t.epc))
                .setPositiveButton(R.string.cloud_write, (d, w) -> {
                    status.setText(R.string.cloud_writing);
                    Bg.run(() -> {
                        try {
                            RfidSession s = RfidSession.get();
                            byte[] epc = RfidFrame.unhex(t.epc);
                            byte[] tid = RfidFrame.unhex(
                                    t.tid == null || t.tid.isEmpty()
                                            ? TagWrite.tidFromEpc(t.epc) : t.tid);
                            RfidReader.WriteReport rep =
                                    s.reader.writeEpcAndTid(epc, 2, tid, 0);
                            if (rep.ok) {
                                try {
                                    Prefs.client(requireContext()).markWritten(t.epc);
                                } catch (Exception ignored) {
                                }
                            }
                            ui(() -> {
                                status.setText(rep.text);
                                load();
                            });
                        } catch (Exception e) {
                            ui(() -> status.setText(getString(R.string.cloud_error, msg(e))));
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void ui(Runnable r) {
        if (isAdded()) requireActivity().runOnUiThread(r);
    }

    static String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
