package com.fongmi.android.tv.ui.dialog;

import android.content.Intent;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogLocalSourceSettingsBinding;
import com.fongmi.android.tv.theme.WebHtvAlertDialogBuilder;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.LocalSource;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 本地源完整设置弹窗，对应 MuseTV 的 ku1（dialog_local_source_settings）。
 * 输出名、文件夹、扫描后缀、跳过文件夹、替换、蜘蛛 jar、直播地址、Base64、解析接口。
 * 保存后扫描生成本地源配置并当作点播配置加载。
 */
public class LocalSourceSettingsDialog extends BaseAlertDialog {

    private DialogLocalSourceSettingsBinding binding;
    private Runnable onSaved;
    private JsonObject json;
    private List<String> dirs;
    private List<String> enabledDirs;
    private List<String> blockedDirs;
    private List<String> extensions;
    private JsonArray parses;
    private int pickMode;
    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getData() == null) return;
                String path = FileChooser.getPersistentPathFromUri(result.getData().getData());
                if (TextUtils.isEmpty(path)) return;
                if (pickMode == 1) {
                    addDir(path);
                } else if (pickMode == 2) {
                    json.addProperty("spider_jar", path);
                    setSpiderText();
                }
            });

    public static LocalSourceSettingsDialog create() {
        return new LocalSourceSettingsDialog();
    }

    public LocalSourceSettingsDialog onSaved(Runnable onSaved) {
        this.onSaved = onSaved;
        return this;
    }

    public void show(Fragment fragment) {
        show(fragment.getParentFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogLocalSourceSettingsBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new WebHtvAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_Dialog).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        json = LocalSource.settings();
        dirs = LocalSource.stringList(json, "scan_dirs");
        enabledDirs = LocalSource.stringList(json, "scan_enabled_dirs");
        blockedDirs = LocalSource.stringList(json, "blocked_dirs");
        extensions = LocalSource.stringList(json, "scan_extensions");
        JsonElement entries = json.get("parse_entries");
        parses = entries != null && entries.isJsonArray()
                ? entries.getAsJsonArray().deepCopy() : LocalSource.defaultParses();
        binding.outName.setText(LocalSource.string(json, "out_name"));
        binding.replace.setChecked(LocalSource.bool(json, "replace_sites", false));
        binding.base64.setChecked(LocalSource.bool(json, "decode_base64", true));
        binding.liveUrl.setText(LocalSource.string(json, "live_url"));
        setSpiderText();
        setDirList();
        setBlockedList();
        setCandidatesText();
        loadExtensions();
    }

    @Override
    protected void initEvent() {
        binding.negative.setOnClickListener(v -> dismiss());
        binding.positive.setOnClickListener(v -> onSave());
        binding.dirAdd.setOnClickListener(v -> addDir(binding.dirInput.getText().toString()));
        binding.dirPick.setOnClickListener(v -> {
            pickMode = 1;
            FileChooser.from(launcher).showDirectory();
        });
        binding.extAll.setOnClickListener(v -> {
            extensions.clear();
            setExtList(new ArrayList<>());
        });
        binding.spiderPick.setOnClickListener(v -> {
            pickMode = 2;
            FileChooser.from(launcher).show();
        });
        binding.spiderClear.setOnClickListener(v -> {
            json.addProperty("spider_jar", "");
            setSpiderText();
        });
        binding.liveClear.setOnClickListener(v -> binding.liveUrl.setText(""));
        binding.parses.setOnClickListener(v -> LocalSourceParsesDialog.create(parses)
                .onSaved(updated -> {
                    parses = updated;
                    binding.parses.setText(getString(R.string.local_source_settings_parses_count, parses.size()));
                }).show(this));
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.92f);
    }

    private void setSpiderText() {
        String spider = LocalSource.string(json, "spider_jar");
        binding.spiderValue.setText(TextUtils.isEmpty(spider)
                ? getString(R.string.local_source_spider_auto) : spider);
    }

    private void addDir(String dir) {
        String path = LocalSource.normalize(dir);
        if (TextUtils.isEmpty(path)) return;
        if (!new File(path).isDirectory()) {
            Notify.show(R.string.local_source_dir_invalid);
            return;
        }
        if (!dirs.contains(path)) dirs.add(path);
        if (!enabledDirs.contains(path)) enabledDirs.add(path);
        binding.dirInput.setText("");
        setDirList();
        setCandidatesText();
    }

    private void setDirList() {
        binding.dirList.removeAllViews();
        for (String dir : dirs) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            CheckBox check = new CheckBox(requireContext());
            check.setChecked(enabledDirs.contains(dir));
            check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    if (!enabledDirs.contains(dir)) enabledDirs.add(dir);
                } else {
                    enabledDirs.remove(dir);
                }
            });
            TextView text = new TextView(requireContext());
            text.setText(dir);
            text.setTextSize(12);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            text.setLayoutParams(params);
            MaterialButton remove = new MaterialButton(requireContext(), null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            remove.setText(R.string.local_source_remove);
            remove.setOnClickListener(v -> {
                dirs.remove(dir);
                enabledDirs.remove(dir);
                setDirList();
                setCandidatesText();
            });
            row.addView(check);
            row.addView(text);
            row.addView(remove);
            binding.dirList.addView(row);
        }
    }

    private void setBlockedList() {
        binding.blockedList.removeAllViews();
        for (String name : blockedDirs) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView text = new TextView(requireContext());
            text.setText(name);
            text.setTextSize(12);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            text.setLayoutParams(params);
            MaterialButton remove = new MaterialButton(requireContext(), null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            remove.setText(R.string.local_source_remove);
            remove.setOnClickListener(v -> {
                blockedDirs.remove(name);
                setBlockedList();
            });
            row.addView(text);
            row.addView(remove);
            binding.blockedList.addView(row);
        }
    }

    /** 后台扫一遍启用的目录，收集实际出现过的后缀，再画勾选列表。 */
    private void loadExtensions() {
        new Thread(() -> {
            LocalSource.Candidates candidates = LocalSource.scanCandidates();
            List<String> all = new ArrayList<>(candidates.extensions);
            for (String ext : new String[]{"py", "js", "wv.js", "php", "html", "xbpq", "xyq", "json", "txt", "m3u", "db", "zip", "pkg"}) {
                if (!all.contains(ext)) all.add(ext);
            }
            List<String> found = all;
            com.fongmi.android.tv.App.post(() -> {
                if (binding == null) return;
                setExtList(found);
                setCandidatesText(candidates);
            });
        }, "local-source-extensions").start();
    }

    private void setExtList(List<String> all) {
        binding.extList.removeAllViews();
        for (String ext : all) {
            CheckBox check = new CheckBox(requireContext());
            check.setText(ext);
            check.setChecked(extensions.isEmpty() || extensions.contains(ext));
            check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    if (!extensions.contains(ext)) extensions.add(ext);
                } else {
                    extensions.remove(ext);
                }
            });
            binding.extList.addView(check);
        }
    }

    private void setCandidatesText() {
        setCandidatesText(null);
    }

    private void setCandidatesText(LocalSource.Candidates candidates) {
        if (binding == null) return;
        if (candidates == null) {
            binding.candidates.setText(R.string.local_source_candidates_scanning);
            return;
        }
        String text = getString(R.string.local_source_candidates_format, candidates.files);
        if (!candidates.samples.isEmpty()) {
            text += "\n" + TextUtils.join("\n", candidates.samples);
        }
        binding.candidates.setText(text);
    }

    private void onSave() {
        String outName = binding.outName.getText() == null ? "" : binding.outName.getText().toString().trim();
        json.addProperty("out_name", TextUtils.isEmpty(outName) ? "墨本地源" : outName);
        json.addProperty("replace_sites", binding.replace.isChecked());
        json.addProperty("decode_base64", binding.base64.isChecked());
        String liveUrl = binding.liveUrl.getText() == null ? "" : binding.liveUrl.getText().toString().trim();
        json.addProperty("live_url", liveUrl);
        LocalSource.putList(json, "scan_dirs", dirs);
        LocalSource.putList(json, "scan_enabled_dirs", enabledDirs);
        LocalSource.putList(json, "scan_extensions", extensions);
        LocalSource.putList(json, "blocked_dirs", blockedDirs);
        json.add("parse_entries", parses);
        LocalSource.save(json);
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
