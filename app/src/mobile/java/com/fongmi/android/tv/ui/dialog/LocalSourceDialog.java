package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;

import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogLocalSourceBinding;
import com.fongmi.android.tv.theme.WebHtvAlertDialogBuilder;
import com.fongmi.android.tv.utils.LocalSource;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * 本地源启用弹窗，对应 MuseTV 的 au1（dialog_local_source）。
 * 启用开关 + 自动开关 + 已选文件夹 + 状态；保存时如果开了启用但没选文件夹，
 * 提示“需要先设置本地源文件夹”并不关闭。
 */
public class LocalSourceDialog extends BaseAlertDialog {

    private DialogLocalSourceBinding binding;
    private Runnable onSaved;

    public static LocalSourceDialog create() {
        return new LocalSourceDialog();
    }

    public LocalSourceDialog onSaved(Runnable onSaved) {
        this.onSaved = onSaved;
        return this;
    }

    public void show(Fragment fragment) {
        show(fragment.getParentFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogLocalSourceBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new WebHtvAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_Dialog).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        binding.enable.setChecked(LocalSource.isEnabled());
        binding.auto.setChecked(LocalSource.isAuto());
        setDirsText();
        setStatusText();
    }

    @Override
    protected void initEvent() {
        binding.negative.setOnClickListener(v -> dismiss());
        binding.positive.setOnClickListener(v -> onSave());
        binding.settings.setOnClickListener(v -> LocalSourceSettingsDialog.create()
                .onSaved(this::refresh).show(this));
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.92f);
    }

    private void refresh() {
        if (binding == null) return;
        setDirsText();
        setStatusText();
        if (onSaved != null) onSaved.run();
    }

    private void setDirsText() {
        List<String> dirs = LocalSource.getDirs();
        if (dirs.isEmpty()) {
            binding.dirs.setText(R.string.local_source_no_dir);
        } else {
            binding.dirs.setText(TextUtils.join("\n", dirs));
        }
    }

    private void setStatusText() {
        String status = LocalSource.summary();
        String output = LocalSource.outputPath();
        if (!TextUtils.isEmpty(output)) status += "\n" + output;
        binding.status.setText(status);
    }

    private void onSave() {
        boolean enabled = binding.enable.isChecked();
        if (enabled && LocalSource.getDirs().isEmpty()) {
            Notify.show(R.string.local_source_dir_required);
            return;
        }
        LocalSource.setEnabled(enabled);
        LocalSource.setAuto(binding.auto.isChecked());
        PermissionUtil.requestFile(requireActivity(), granted -> {
            if (!Boolean.TRUE.equals(granted)) {
                Notify.show(R.string.local_source_permission_required);
                return;
            }
            Notify.show(R.string.local_source_scanning);
            LocalSource.generateAndLoad(requireActivity(), onSaved);
            dismiss();
        });
    }
}
