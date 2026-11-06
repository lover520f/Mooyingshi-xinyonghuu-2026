package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogLocalSourceParsesBinding;
import com.fongmi.android.tv.theme.WebHtvAlertDialogBuilder;
import com.fongmi.android.tv.utils.LocalSource;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 解析接口编辑弹窗：每行一个解析（启用开关 + 名字 + 地址 + 移除）。
 * 保存时把 enabled 写回 parse_entries。
 */
public class LocalSourceParsesDialog extends BaseAlertDialog {

    private DialogLocalSourceParsesBinding binding;
    private final List<JsonObject> entries = new ArrayList<>();
    private Consumer<JsonArray> onSaved;

    public static LocalSourceParsesDialog create(JsonArray parses) {
        LocalSourceParsesDialog dialog = new LocalSourceParsesDialog();
        if (parses != null) {
            for (JsonElement item : parses) {
                if (item.isJsonObject()) dialog.entries.add(item.getAsJsonObject().deepCopy());
            }
        }
        return dialog;
    }

    public LocalSourceParsesDialog onSaved(Consumer<JsonArray> onSaved) {
        this.onSaved = onSaved;
        return this;
    }

    public void show(Fragment fragment) {
        show(fragment.getParentFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogLocalSourceParsesBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new WebHtvAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_Dialog).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        setList();
    }

    @Override
    protected void initEvent() {
        binding.negative.setOnClickListener(v -> dismiss());
        binding.positive.setOnClickListener(v -> onSave());
        binding.add.setOnClickListener(v -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", "");
            entry.addProperty("type", 0);
            entry.addProperty("url", "");
            entry.addProperty("enabled", true);
            entries.add(entry);
            setList();
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.92f);
    }

    private void setList() {
        binding.parseList.removeAllViews();
        for (JsonObject entry : entries) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            CheckBox check = new CheckBox(requireContext());
            check.setChecked(LocalSource.bool(entry, "enabled", true));
            check.setOnCheckedChangeListener((button, checked) -> entry.addProperty("enabled", checked));
            LinearLayout fields = new LinearLayout(requireContext());
            fields.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            fields.setLayoutParams(params);
            EditText name = new EditText(requireContext());
            name.setText(LocalSource.string(entry, "name"));
            name.setHint(R.string.local_source_parses_name);
            name.setTextSize(12);
            name.setSingleLine(true);
            EditText url = new EditText(requireContext());
            url.setText(LocalSource.string(entry, "url"));
            url.setHint(R.string.local_source_parses_url);
            url.setTextSize(12);
            url.setSingleLine(true);
            fields.addView(name);
            fields.addView(url);
            MaterialButton remove = new MaterialButton(requireContext(), null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            remove.setText(R.string.local_source_remove);
            remove.setOnClickListener(v -> {
                entries.remove(entry);
                setList();
            });
            row.addView(check);
            row.addView(fields);
            row.addView(remove);
            binding.parseList.addView(row);
            name.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) entry.addProperty("name", name.getText().toString().trim());
            });
            url.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) entry.addProperty("url", url.getText().toString().trim());
            });
        }
    }

    private void onSave() {
        for (int i = 0; i < binding.parseList.getChildCount(); i++) {
            if (!(binding.parseList.getChildAt(i) instanceof LinearLayout row)) continue;
            if (row.getChildCount() < 2 || !(row.getChildAt(1) instanceof LinearLayout fields)) continue;
            if (fields.getChildCount() < 2) continue;
            if (!(fields.getChildAt(0) instanceof EditText name)) continue;
            if (!(fields.getChildAt(1) instanceof EditText url)) continue;
            JsonObject entry = entries.get(i);
            entry.addProperty("name", name.getText().toString().trim());
            entry.addProperty("url", url.getText().toString().trim());
        }
        JsonArray result = new JsonArray();
        for (JsonObject entry : entries) {
            if (TextUtils.isEmpty(LocalSource.string(entry, "url"))) continue;
            result.add(entry);
        }
        if (onSaved != null) onSaved.accept(result);
        dismiss();
    }
}
