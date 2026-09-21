package com.hanwenjiang.uhfrfid;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 云端标签库：列出、命名、一键写入。
 *
 * <p>写入直接用 {@link RfidSession} 里那条已经连好的读写器，所以要先在主页点「连接」。
 */
public class CloudActivity extends AppCompatActivity {

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<CloudClient.Tag> tags = new ArrayList<>();

    private ListView list;
    private TextView status;
    private CheckBox onlyUnnamed;
    private TagAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cloud);
        setTitle(R.string.cloud_title);

        list = findViewById(R.id.tagList);
        status = findViewById(R.id.cloudStatus);
        onlyUnnamed = findViewById(R.id.onlyUnnamed);
        adapter = new TagAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.refreshBtn).setOnClickListener(v -> load());
        findViewById(R.id.settingsBtn).setOnClickListener(v -> showSettings());
        onlyUnnamed.setOnCheckedChangeListener((v, checked) -> load());
        list.setOnItemClickListener((parent, view, position, id) -> showRenameDialog(tags.get(position)));

        if (!Prefs.isConfigured(this)) {
            status.setText(R.string.cloud_need_key);
            showSettings();
        } else {
            load();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
    }

    // ------------------------------------------------------------------ 加载

    private void load() {
        status.setText(R.string.cloud_loading);
        final boolean unnamed = onlyUnnamed.isChecked();
        worker.execute(() -> {
            try {
                CloudClient client = Prefs.client(this);
                List<CloudClient.Tag> got = client.list(unnamed);
                // 时机 3：打开云端标签库后，把写标签补全用的缓存一并刷新。
                // 列表可能只筛了"没命名的"，所以缓存要单独拉一次全量。
                try {
                    TagCache.put(unnamed ? client.list(false) : got);
                } catch (Exception e) {
                    TagCache.putError(msg(e));
                }
                ui.post(() -> {
                    tags.clear();
                    tags.addAll(got);
                    adapter.notifyDataSetChanged();
                    int noName = 0;
                    for (CloudClient.Tag t : got) if (t.name == null || t.name.isEmpty()) noName++;
                    status.setText(getString(R.string.cloud_count, got.size(), noName));
                });
            } catch (Exception e) {
                ui.post(() -> status.setText(getString(R.string.cloud_error, msg(e))));
            }
        });
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    // ------------------------------------------------------------------ 写入

    private void writeTag(CloudClient.Tag t) {
        RfidSession s = RfidSession.get();
        if (!s.isConnected() || s.reader == null) {
            Toast.makeText(this, R.string.cloud_connect_first, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.cloud_write_title)
                .setMessage(getString(R.string.cloud_write_confirm,
                        t.name == null || t.name.isEmpty() ? "(未命名)" : t.name, t.epc))
                .setPositiveButton(R.string.cloud_write, (d, w) -> {
                    status.setText(R.string.cloud_writing);
                    worker.execute(() -> {
                        try {
                            byte[] epc = RfidFrame.unhex(t.epc);
                            byte[] tid = t.tid == null ? new byte[0] : RfidFrame.unhex(t.tid);
                            RfidReader.WriteReport rep = s.reader.writeEpcAndTid(epc, 2, tid, 0);
                            // 两个区都写成功才算一次，向云端记数
                            if (rep.ok) {
                                try {
                                    Prefs.client(this).markWritten(t.epc);
                                } catch (Exception ignored) {
                                    // 计数而已，失败不影响写入结果
                                }
                            }
                            ui.post(() -> {
                                status.setText(rep.text);
                                Toast.makeText(this,
                                        rep.ok ? R.string.cloud_write_done
                                               : R.string.cloud_write_failed,
                                        Toast.LENGTH_SHORT).show();
                                if (rep.ok) load();      // 刷新列表，让"写过几次"更新
                            });
                        } catch (Exception e) {
                            ui.post(() -> status.setText(getString(R.string.cloud_error, msg(e))));
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ 命名

    private void showRenameDialog(CloudClient.Tag t) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(t.name == null ? "" : t.name);
        input.setHint(R.string.cloud_name_hint);

        LinearLayout box = new LinearLayout(this);
        box.setPadding(48, 16, 48, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(t.epc)
                .setMessage(getString(R.string.cloud_row_hint, t.suffix, t.status))
                .setView(box)
                .setPositiveButton(R.string.cloud_save, (d, w) -> {
                    String name = input.getText().toString().trim();
                    worker.execute(() -> {
                        try {
                            Prefs.client(this).rename(t.id, name);
                            ui.post(this::load);
                        } catch (Exception e) {
                            ui.post(() -> status.setText(getString(R.string.cloud_error, msg(e))));
                        }
                    });
                })
                .setNeutralButton(R.string.cloud_delete, (d, w) ->
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.cloud_delete)
                                .setMessage(t.epc)
                                .setPositiveButton(android.R.string.ok, (d2, w2) ->
                                        worker.execute(() -> {
                                            try {
                                                Prefs.client(this).delete(t.id);
                                                ui.post(this::load);
                                            } catch (Exception e) {
                                                ui.post(() -> status.setText(
                                                        getString(R.string.cloud_error, msg(e))));
                                            }
                                        }))
                                .setNegativeButton(android.R.string.cancel, null)
                                .show())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ 设置

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 16, 48, 0);

        CheckBox auto = new CheckBox(this);
        auto.setText(R.string.cloud_auto_upload);
        auto.setChecked(Prefs.autoUpload(this));
        box.addView(auto);

        EditText base = new EditText(this);
        base.setHint(R.string.cloud_base_hint);
        base.setText(Prefs.base(this));
        box.addView(base);

        EditText key = new EditText(this);
        key.setHint(R.string.cloud_key_hint);
        key.setText(Prefs.apiKey(this));
        box.addView(key);

        EditText who = new EditText(this);
        who.setHint(R.string.cloud_operator_hint);
        // 没自定义过就显示手机名（也就是实际会用的值），让人知道传上去的是什么
        who.setText(Prefs.operatorIsCustom(this) ? Prefs.operator(this) : "");
        who.setHint(getString(R.string.cloud_operator_default, Prefs.deviceName(this)));
        box.addView(who);

        TextView tip = new TextView(this);
        tip.setText(R.string.cloud_setup_tip);
        tip.setTextSize(12);
        box.addView(tip);

        new AlertDialog.Builder(this)
                .setTitle(R.string.cloud_settings)
                .setView(box)
                .setPositiveButton(R.string.cloud_save, (d, w) -> {
                    Prefs.setAutoUpload(this, auto.isChecked());
                    // 地址/key 清空 = 回到编译时的默认值，不是"没有"
                    Prefs.setBase(this, base.getText().toString().trim()
                            .equals(Prefs.base(this)) ? "" : base.getText().toString());
                    Prefs.setApiKey(this, key.getText().toString().trim()
                            .equals(Prefs.apiKey(this)) ? "" : key.getText().toString());
                    Prefs.setOperator(this, who.getText().toString());
                    load();
                })
                .setNeutralButton(R.string.cloud_reset, (d, w) -> {
                    Prefs.setBase(this, "");
                    Prefs.setApiKey(this, "");
                    Prefs.setOperator(this, "");
                    Prefs.setAutoUpload(this, true);
                    load();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ 适配器

    private class TagAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return tags.size();
        }

        @Override
        public Object getItem(int i) {
            return tags.get(i);
        }

        @Override
        public long getItemId(int i) {
            return tags.get(i).id;
        }

        @Override
        public View getView(int i, View reuse, ViewGroup parent) {
            View v = reuse != null ? reuse
                    : getLayoutInflater().inflate(R.layout.row_tag, parent, false);
            CloudClient.Tag t = tags.get(i);

            TextView title = v.findViewById(R.id.rowTitle);
            TextView sub = v.findViewById(R.id.rowSub);
            Button write = v.findViewById(R.id.rowWrite);

            boolean named = t.name != null && !t.name.isEmpty();
            title.setText(named ? t.name : getString(R.string.cloud_unnamed));
            if (!named) title.setTextColor(0xFFB26A00);

            StringBuilder sb = new StringBuilder();
            sb.append(getString(R.string.cloud_suffix, t.suffix));
            if ("anomaly".equals(t.status)) sb.append("  ·  ").append(getString(R.string.cloud_anomaly));
            if (t.reads > 1) sb.append("  ·  读过 ").append(t.reads).append(" 次");
            if (t.operator != null && !t.operator.isEmpty()) sb.append("  ·  ").append(t.operator);
            sb.append('\n').append(t.epc);
            sub.setText(sb);

            write.setOnClickListener(x -> writeTag(t));
            return v;
        }
    }

    /** 主页读完之后可以直接跳过来。 */
    public static void open(Context c) {
        c.startActivity(new Intent(c, CloudActivity.class));
    }
}
