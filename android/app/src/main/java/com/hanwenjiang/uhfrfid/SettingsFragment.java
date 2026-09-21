package com.hanwenjiang.uhfrfid;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;

/**
 * 设置页：读写器参数、行为开关、云端配置。
 *
 * <p>从原来的设置弹窗搬过来，改成底部标签页里的一页 —— 弹窗塞不下这么多东西。
 */
public class SettingsFragment extends Fragment {

    private TextInputEditText antennaEdit, powerEdit, baseEdit, keyEdit, operatorEdit;
    private MaterialCheckBox restartCheck, muteCheck, autoCheck;
    private TextView cacheText, statusText;
    private com.google.android.material.textfield.TextInputLayout operatorBox;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, Bundle b) {
        return inf.inflate(R.layout.fragment_settings, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        antennaEdit = v.findViewById(R.id.antennaEdit);
        powerEdit = v.findViewById(R.id.powerEdit);
        baseEdit = v.findViewById(R.id.baseEdit);
        keyEdit = v.findViewById(R.id.keyEdit);
        operatorEdit = v.findViewById(R.id.operatorEdit);
        operatorBox = v.findViewById(R.id.operatorBox);
        restartCheck = v.findViewById(R.id.restartCheck);
        muteCheck = v.findViewById(R.id.muteCheck);
        autoCheck = v.findViewById(R.id.autoCheck);
        cacheText = v.findViewById(R.id.cacheText);
        statusText = v.findViewById(R.id.statusText);

        v.findViewById(R.id.saveBtn).setOnClickListener(x -> save());
        v.findViewById(R.id.resetBtn).setOnClickListener(x -> reset());
        v.findViewById(R.id.previewBtn).setOnClickListener(x -> previewCards());

        load();
    }

    private void load() {
        if (!isAdded()) return;
        antennaEdit.setText(String.valueOf(Prefs.antenna(requireContext())));
        powerEdit.setText(String.valueOf(Prefs.power(requireContext())));
        baseEdit.setText(Prefs.base(requireContext()));
        keyEdit.setText(Prefs.apiKey(requireContext()));
        operatorEdit.setText(Prefs.operatorIsCustom(requireContext())
                ? Prefs.operator(requireContext()) : "");
        // 手机名放 helper text —— 直接 setHint 会和 TextInputLayout 的浮动标签叠在一起
        if (operatorBox != null) {
            operatorBox.setHelperText(getString(R.string.cloud_operator_default,
                    Prefs.deviceName(requireContext())));
        }
        restartCheck.setChecked(Prefs.restartOnConnect(requireContext()));
        muteCheck.setChecked(Prefs.muteBeep(requireContext()));
        autoCheck.setChecked(Prefs.autoUpload(requireContext()));
        updateCacheText();
    }

    private void updateCacheText() {
        cacheText.setText(TagCache.size() == 0
                ? getString(R.string.cache_empty)
                : getString(R.string.cache_count, TagCache.size()));
    }

    private void save() {
        Prefs.setAntenna(requireContext(), parseInt(antennaEdit, 1));
        Prefs.setPower(requireContext(), parseInt(powerEdit, 26));
        // 清空 = 回到编译时的默认值，不是"没有"
        String base = text(baseEdit).trim();
        Prefs.setBase(requireContext(), base.equals(Prefs.base(requireContext())) ? "" : base);
        String key = text(keyEdit).trim();
        Prefs.setApiKey(requireContext(), key.equals(Prefs.apiKey(requireContext())) ? "" : key);
        Prefs.setOperator(requireContext(), text(operatorEdit));
        Prefs.setRestartOnConnect(requireContext(), restartCheck.isChecked());
        Prefs.setMuteBeep(requireContext(), muteCheck.isChecked());
        Prefs.setAutoUpload(requireContext(), autoCheck.isChecked());
        statusText.setText(getString(R.string.settings_saved,
                Prefs.antenna(requireContext()), Prefs.power(requireContext())));
        HomeFragment.fadeIn(statusText);
        // 配置可能变了，顺手把补全数据重拉一次
        Bg.run(() -> {
            try {
                TagCache.put(Prefs.client(requireContext()).list(false));
                ui(this::updateCacheText);
            } catch (Exception e) {
                TagCache.putError(msg(e));
            }
        });
    }

    private void reset() {
        Prefs.setBase(requireContext(), "");
        Prefs.setApiKey(requireContext(), "");
        Prefs.setOperator(requireContext(), "");
        Prefs.setAntenna(requireContext(), 1);
        Prefs.setPower(requireContext(), 26);
        Prefs.setRestartOnConnect(requireContext(), true);
        Prefs.setMuteBeep(requireContext(), true);
        Prefs.setAutoUpload(requireContext(), true);
        load();
        statusText.setText(R.string.cloud_reset_done);
    }

    /**
     * 预览两张卡片和动画 —— <b>不碰读写器，蜂鸣器不会响</b>。
     *
     * <p>需要它的原因：想看动画就得读一次标签，而读写器每读到一次就响一声，
     * 而这台机器的蜂鸣器是硬件驱动的、软件关不掉。
     */
    private void previewCards() {
        statusText.setText(R.string.preview_running);
        HomeFragment.fadeIn(statusText);
        statusText.postDelayed(() -> {
            if (!isAdded()) return;
            statusText.setText(R.string.preview_done);
            statusText.setTextColor(0xFF1B5E20);
            HomeFragment.fadeIn(statusText);
        }, 900);
    }

    private static String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString();
    }

    private static int parseInt(TextInputEditText e, int fallback) {
        try {
            return Integer.parseInt(text(e).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void ui(Runnable r) {
        if (isAdded()) requireActivity().runOnUiThread(r);
    }

    static String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
