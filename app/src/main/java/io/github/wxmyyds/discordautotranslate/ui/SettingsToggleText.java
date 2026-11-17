package io.github.wxmyyds.discordautotranslate.ui;

import io.github.wxmyyds.discordautotranslate.R;
import io.github.wxmyyds.discordautotranslate.core.Protocols;

/** Selects switch copy from the current draft; never changes configuration or the saved log gate. */
final class SettingsToggleText {
    enum Setting {
        TRANSLATION(R.string.label_translation, R.string.hint_translation_on, R.string.hint_translation_off),
        LOGGING(R.string.label_logging, R.string.hint_logging_on, R.string.hint_logging_off),
        STREAMING(R.string.label_streaming, R.string.hint_streaming_on, R.string.hint_streaming_off);

        final int title;
        final int onDescription;
        final int offDescription;

        Setting(int title, int onDescription, int offDescription) {
            this.title = title;
            this.onDescription = onDescription;
            this.offDescription = offDescription;
        }
    }

    final int state;
    final int description;

    private SettingsToggleText(int state, int description) {
        this.state = state;
        this.description = description;
    }

    static SettingsToggleText select(Setting setting, boolean checked, boolean loaded,
                                     boolean invalid, String protocol) {
        if (invalid) {
            return new SettingsToggleText(R.string.switch_unavailable, R.string.hint_switch_invalid);
        }
        if (!loaded) {
            return new SettingsToggleText(R.string.switch_loading, R.string.hint_switch_loading);
        }
        if (setting == Setting.STREAMING && !Protocols.supportsStreaming(protocol)) {
            return new SettingsToggleText(R.string.switch_unavailable, R.string.hint_streaming_protocol);
        }
        return new SettingsToggleText(checked ? R.string.switch_on : R.string.switch_off,
                checked ? setting.onDescription : setting.offDescription);
    }
}
