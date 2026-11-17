package io.github.wxmyyds.discordautotranslate.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import io.github.wxmyyds.discordautotranslate.R;
import io.github.wxmyyds.discordautotranslate.core.TranslationScope;

/** Edits an isolated draft; only Apply delivers it to the activity's existing save flow. */
final class TranslationScopeEditor {
    private static final int MAX_RULES = 256;
    private static final int MAX_LABEL_LENGTH = 80;

    private TranslationScopeEditor() {
    }

    /** The owner must dismiss the returned dialog in Activity.onStop(). */
    static Dialog show(Activity activity, TranslationScope initial,
                       Consumer<TranslationScope> onApply) {
        ScopeDialog dialog = new ScopeDialog(activity, initial, onApply);
        dialog.show();
        Window window = Objects.requireNonNull(dialog.getWindow());
        window.getDecorView().setPadding(0, 0, 0, 0);
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        return dialog;
    }

    static Bundle saveState(Dialog dialog) {
        ScopeDialog editor = (ScopeDialog) dialog;
        Bundle state = new Bundle();
        state.putString("scope", new TranslationScope(editor.defaultTranslate, editor.servers, editor.channels).toJson());
        state.putString("query", editor.query);
        if (editor.ruleForm != null) {
            state.putBoolean("rule_open", true);
            state.putBoolean("server", editor.editingServer);
            state.putString("original_id", editor.editingOriginal == null ? null : editor.editingOriginal.id);
            state.putString("id", editor.idField.getText().toString());
            state.putString("label", editor.labelField.getText().toString());
            state.putBoolean("translate", editor.actionField.getCheckedRadioButtonId() == editor.translateChoice);
        }
        return state;
    }

    static void restoreState(Dialog dialog, Bundle state) {
        ScopeDialog editor = (ScopeDialog) dialog;
        editor.query = state.getString("query", "");
        editor.searchField.setText(editor.query);
        if (state.getBoolean("rule_open")) {
            boolean server = state.getBoolean("server");
            TranslationScope.Rule original = null;
            for (TranslationScope.Rule rule : server ? editor.servers : editor.channels)
                if (rule.id.equals(state.getString("original_id"))) original = rule;
            editor.editRule(server, original, state.getString("id", ""));
            editor.labelField.setText(state.getString("label", ""));
            editor.actionField.check(state.getBoolean("translate") ? editor.translateChoice : editor.otherChoice);
        }
    }

    private static List<TranslationScope.Rule> copyRules(List<TranslationScope.Rule> source) {
        List<TranslationScope.Rule> result = new ArrayList<>(source.size());
        for (TranslationScope.Rule rule : source) {
            result.add(new TranslationScope.Rule(rule.id, rule.label, rule.translate));
        }
        return result;
    }

    private static final class ScopeDialog extends Dialog {
        private final Activity activity;
        private final Consumer<TranslationScope> onApply;
        private final List<TranslationScope.Rule> servers;
        private final List<TranslationScope.Rule> channels;
        private final Set<Dialog> children = new LinkedHashSet<>();
        private final ColorStateList primary;
        private final ColorStateList secondary;
        private final int background;
        private final LinearLayout serverCards;
        private final LinearLayout channelCards;
        private final TextView serverTitle;
        private final TextView channelTitle;
        private boolean defaultTranslate;
        private String query = "";
        private EditText searchField;
        private Dialog ruleForm;
        private boolean editingServer;
        private TranslationScope.Rule editingOriginal;
        private EditText idField;
        private EditText labelField;
        private RadioGroup actionField;
        private int translateChoice;
        private int otherChoice;

        ScopeDialog(Activity activity, TranslationScope initial,
                    Consumer<TranslationScope> onApply) {
            super(activity);
            this.activity = Objects.requireNonNull(activity);
            this.onApply = Objects.requireNonNull(onApply);
            Objects.requireNonNull(initial);
            servers = copyRules(initial.servers);
            channels = copyRules(initial.channels);
            defaultTranslate = initial.defaultTranslate;
            setOwnerActivity(activity);
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            setCanceledOnTouchOutside(false);

            TypedArray colors = activity.obtainStyledAttributes(new int[]{
                    android.R.attr.textColorPrimary,
                    android.R.attr.textColorSecondary,
                    android.R.attr.colorBackground
            });
            try {
                primary = Objects.requireNonNull(colors.getColorStateList(0));
                secondary = Objects.requireNonNull(colors.getColorStateList(1));
                background = Objects.requireNonNull(colors.getColorStateList(2)).getDefaultColor();
            } finally {
                colors.recycle();
            }

            LinearLayout root = column();
            root.setPadding(dp(16), dp(12), dp(16), dp(12));
            root.setBackgroundColor(background);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                view.setPadding(dp(16) + insets.getSystemWindowInsetLeft(),
                        dp(12) + insets.getSystemWindowInsetTop(),
                        dp(16) + insets.getSystemWindowInsetRight(),
                        dp(12) + insets.getSystemWindowInsetBottom());
                return insets.consumeSystemWindowInsets();
            });

            LinearLayout header = row();
            TextView title = text(R.string.scope_title, false);
            title.setTextSize(22);
            header.addView(title, weighted());
            Button close = button(R.string.scope_close);
            close.setOnClickListener(view -> dismiss());
            header.addView(close);
            root.addView(header);

            ScrollView scroll = new ScrollView(activity);
            scroll.setFillViewport(true);
            LinearLayout content = column();
            scroll.addView(content, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

            content.addView(text(R.string.scope_default_policy, false));
            RadioGroup defaultGroup = new RadioGroup(activity);
            defaultGroup.setOrientation(RadioGroup.VERTICAL);
            RadioButton translate = radio(R.string.scope_default_translate);
            RadioButton doNotTranslate = radio(R.string.scope_default_do_not_translate);
            defaultGroup.addView(translate);
            defaultGroup.addView(doNotTranslate);
            defaultGroup.check(defaultTranslate ? translate.getId() : doNotTranslate.getId());
            defaultGroup.setOnCheckedChangeListener((group, checkedId) ->
                    defaultTranslate = checkedId == translate.getId());
            content.addView(defaultGroup);

            EditText search = new EditText(activity);
            searchField = search;
            search.setSingleLine(true);
            search.setHint(R.string.scope_search_hint);
            search.setContentDescription(activity.getString(R.string.scope_search_hint));
            search.setInputType(InputType.TYPE_CLASS_TEXT);
            search.setTextColor(primary);
            search.setHintTextColor(secondary);
            search.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    query = s.toString().trim().toLowerCase(Locale.ROOT);
                    renderRules();
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
            content.addView(search);

            serverTitle = addSectionHeader(content, true);
            serverCards = column();
            content.addView(serverCards);
            channelTitle = addSectionHeader(content, false);
            channelCards = column();
            content.addView(channelCards);

            TextView explanation = text(R.string.scope_explanation, true);
            explanation.setPadding(0, dp(20), 0, dp(12));
            content.addView(explanation);

            LinearLayout actions = row();
            Button cancel = button(R.string.scope_cancel);
            cancel.setOnClickListener(view -> dismiss());
            actions.addView(cancel, weighted());
            Button apply = button(R.string.scope_apply);
            apply.setOnClickListener(view -> {
                TranslationScope draft = new TranslationScope(defaultTranslate,
                        copyRules(servers), copyRules(channels));
                // This callback marks the activity draft dirty; it must not persist here.
                onApply.accept(draft);
                dismiss();
            });
            actions.addView(apply, weighted());
            root.addView(actions);

            setContentView(root);
            Window window = Objects.requireNonNull(getWindow());
            window.setBackgroundDrawable(new ColorDrawable(background));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            renderRules();
        }

        @Override
        public void dismiss() {
            // Also handles back, cancel, owner onStop, and externally requested dismissal.
            for (Dialog child : new ArrayList<>(children)) {
                child.dismiss();
            }
            children.clear();
            super.dismiss();
        }

        private void showChild(AlertDialog dialog) {
            dialog.setOwnerActivity(activity);
            dialog.setOnDismissListener(ignored -> {
                children.remove(dialog);
                if (ruleForm == dialog) ruleForm = null;
            });
            children.add(dialog);
            dialog.show();
        }

        private TextView addSectionHeader(LinearLayout content, boolean server) {
            LinearLayout header = row();
            header.setPadding(0, dp(16), 0, dp(4));
            TextView title = text(server ? R.string.scope_servers_title
                    : R.string.scope_channels_title, false);
            title.setTextSize(18);
            header.addView(title, weighted());
            Button add = button(R.string.scope_add);
            add.setOnClickListener(view -> editRule(server, null, ""));
            header.addView(add);
            content.addView(header);
            return title;
        }

        private void renderRules() {
            serverTitle.setText(activity.getString(R.string.scope_servers_title,
                    servers.size(), MAX_RULES));
            channelTitle.setText(activity.getString(R.string.scope_channels_title,
                    channels.size(), MAX_RULES));
            renderSection(serverCards, servers, true);
            renderSection(channelCards, channels, false);
        }

        private void renderSection(LinearLayout container, List<TranslationScope.Rule> rules,
                                   boolean server) {
            container.removeAllViews();
            int displayed = 0;
            for (TranslationScope.Rule rule : rules) {
                String label = rule.label;
                if (!rule.id.toLowerCase(Locale.ROOT).contains(query)
                        && !label.toLowerCase(Locale.ROOT).contains(query)) {
                    continue;
                }
                displayed++;
                String title = label.isEmpty() ? rule.id : label;
                String action = activity.getString(rule.translate
                        ? R.string.scope_translate : R.string.scope_do_not_translate);
                LinearLayout card = column();
                card.setPadding(dp(14), dp(10), dp(14), dp(10));
                GradientDrawable shape = new GradientDrawable();
                shape.setColor(background);
                shape.setCornerRadius(dp(8));
                shape.setStroke(dp(1), borderColor());
                card.setBackground(shape);
                TextView heading = text(title, false);
                heading.setTextSize(17);
                card.addView(heading);
                card.addView(text(activity.getString(R.string.scope_rule_id, rule.id), true));
                card.addView(text(action, false));
                card.addView(text(R.string.scope_edit_hint, true));
                card.setFocusable(true);
                card.setContentDescription(activity.getString(R.string.scope_rule_summary,
                        title, rule.id, action));
                card.setOnClickListener(view -> editRule(server, rule, rule.id));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.bottomMargin = dp(8);
                container.addView(card, params);
            }
            if (displayed == 0) {
                TextView empty = text(rules.isEmpty()
                        ? R.string.scope_no_rules : R.string.scope_no_matches, true);
                empty.setPadding(dp(8), dp(12), dp(8), dp(12));
                container.addView(empty);
            }
        }

        private void editRule(boolean server, TranslationScope.Rule original, String seedId) {
            List<TranslationScope.Rule> rules = server ? servers : channels;
            LinearLayout form = column();
            form.setPadding(dp(20), dp(8), dp(20), dp(8));
            form.addView(text(R.string.scope_id_label, false));
            EditText idInput = new EditText(activity);
            idInput.setSingleLine(true);
            idInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            idInput.setHint(R.string.scope_id_hint);
            idInput.setText(seedId);
            idInput.setTextColor(primary);
            idInput.setHintTextColor(secondary);
            form.addView(idInput);

            form.addView(text(R.string.scope_label_label, false));
            EditText labelInput = new EditText(activity);
            labelInput.setSingleLine(true);
            labelInput.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            labelInput.setHint(R.string.scope_label_hint);
            labelInput.setText(original == null ? "" : original.label);
            labelInput.setTextColor(primary);
            labelInput.setHintTextColor(secondary);
            form.addView(labelInput);
            form.addView(text(activity.getString(R.string.scope_label_limit,
                    MAX_LABEL_LENGTH), true));

            form.addView(text(R.string.scope_action_label, false));
            RadioGroup actionGroup = new RadioGroup(activity);
            actionGroup.setOrientation(RadioGroup.VERTICAL);
            RadioButton translate = radio(R.string.scope_translate);
            RadioButton doNotTranslate = radio(R.string.scope_do_not_translate);
            actionGroup.addView(translate);
            actionGroup.addView(doNotTranslate);
            boolean initialAction = original == null ? defaultTranslate : original.translate;
            actionGroup.check(initialAction ? translate.getId() : doNotTranslate.getId());
            form.addView(actionGroup);
            TextView error = text("", true);
            error.setVisibility(View.GONE);
            error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            form.addView(error);

            ScrollView scroll = new ScrollView(activity);
            scroll.addView(form);
            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setTitle(original == null
                            ? (server ? R.string.scope_add_server : R.string.scope_add_channel)
                            : (server ? R.string.scope_edit_server : R.string.scope_edit_channel))
                    .setView(scroll)
                    .setPositiveButton(R.string.scope_save_rule, null)
                    .setNegativeButton(R.string.scope_cancel, null);
            if (original != null) {
                builder.setNeutralButton(R.string.scope_delete, (dialog, which) -> {
                    rules.remove(original);
                    renderRules();
                });
            }
            AlertDialog editor = builder.create();
            editingServer = server;
            editingOriginal = original;
            idField = idInput;
            labelField = labelInput;
            actionField = actionGroup;
            translateChoice = translate.getId();
            otherChoice = doNotTranslate.getId();
            ruleForm = editor;
            showChild(editor);
            Objects.requireNonNull(editor.getWindow()).setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            // Install after show: Android's default positive listener dismisses invalid forms.
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                idInput.setError(null);
                labelInput.setError(null);
                error.setVisibility(View.GONE);
                String id = idInput.getText().toString().trim();
                String label = labelInput.getText().toString().trim();
                if (!TranslationScope.isValidId(id)) {
                    idInput.setError(activity.getString(R.string.scope_error_id));
                    idInput.requestFocus();
                    return;
                }
                if (label.length() > MAX_LABEL_LENGTH) {
                    labelInput.setError(activity.getString(R.string.scope_error_label,
                            MAX_LABEL_LENGTH));
                    labelInput.requestFocus();
                    return;
                }
                for (TranslationScope.Rule rule : rules) {
                    if (rule != original && rule.id.equals(id)) {
                        idInput.setError(activity.getString(R.string.scope_error_duplicate));
                        idInput.requestFocus();
                        return;
                    }
                }
                if (original == null && rules.size() >= MAX_RULES) {
                    error.setText(activity.getString(R.string.scope_error_limit, MAX_RULES));
                    error.setVisibility(View.VISIBLE);
                    return;
                }
                int checkedId = actionGroup.getCheckedRadioButtonId();
                if (checkedId != translate.getId() && checkedId != doNotTranslate.getId()) {
                    error.setText(R.string.scope_error_action);
                    error.setVisibility(View.VISIBLE);
                    return;
                }
                TranslationScope.Rule updated = new TranslationScope.Rule(id, label,
                        checkedId == translate.getId());
                if (original == null) {
                    rules.add(updated);
                } else {
                    rules.set(rules.indexOf(original), updated);
                }
                renderRules();
                editor.dismiss();
            });
        }

        private LinearLayout column() {
            LinearLayout result = new LinearLayout(activity);
            result.setOrientation(LinearLayout.VERTICAL);
            return result;
        }

        private LinearLayout row() {
            LinearLayout result = new LinearLayout(activity);
            result.setOrientation(LinearLayout.HORIZONTAL);
            result.setGravity(android.view.Gravity.CENTER_VERTICAL);
            return result;
        }

        private TextView text(int resource, boolean subdued) {
            return text(activity.getString(resource), subdued);
        }

        private TextView text(CharSequence value, boolean subdued) {
            TextView result = new TextView(activity);
            result.setText(value);
            result.setTextSize(14);
            result.setTextColor(subdued ? secondary : primary);
            return result;
        }

        private Button button(int resource) {
            Button result = new Button(activity);
            result.setText(resource);
            result.setAllCaps(false);
            result.setTextColor(primary);
            return result;
        }

        private RadioButton radio(int resource) {
            RadioButton result = new RadioButton(activity);
            result.setId(View.generateViewId());
            result.setText(resource);
            result.setTextColor(primary);
            return result;
        }

        private LinearLayout.LayoutParams weighted() {
            return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        }

        private int dp(int value) {
            return Math.round(value * activity.getResources().getDisplayMetrics().density);
        }

        private int borderColor() {
            int foreground = secondary.getDefaultColor();
            float amount = 0.35f * Color.alpha(foreground) / 255f;
            return Color.rgb(
                    Math.round(Color.red(background) * (1 - amount) + Color.red(foreground) * amount),
                    Math.round(Color.green(background) * (1 - amount) + Color.green(foreground) * amount),
                    Math.round(Color.blue(background) * (1 - amount) + Color.blue(foreground) * amount));
        }
    }
}
