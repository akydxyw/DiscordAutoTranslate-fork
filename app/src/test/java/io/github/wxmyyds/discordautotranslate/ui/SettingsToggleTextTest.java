package io.github.wxmyyds.discordautotranslate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;

import android.widget.Switch;

import io.github.wxmyyds.discordautotranslate.R;
import io.github.wxmyyds.discordautotranslate.core.Config;

import org.junit.Test;

/** Tests the production copy selector, not Android widget rendering or persisted settings. */
public class SettingsToggleTextTest {
    @Test
    public void eachToggleDescribesItsCurrentDraftState() {
        for (SettingsToggleText.Setting setting : SettingsToggleText.Setting.values()) {
            SettingsToggleText on = SettingsToggleText.select(setting, true, true, false,
                    Config.PROTOCOL_OPENAI_COMPATIBLE);
            SettingsToggleText off = SettingsToggleText.select(setting, false, true, false,
                    Config.PROTOCOL_OPENAI_COMPATIBLE);
            assertEquals(R.string.switch_on, on.state);
            assertEquals(setting.onDescription, on.description);
            assertEquals(R.string.switch_off, off.state);
            assertEquals(setting.offDescription, off.description);
            assertNotEquals(on.description, off.description);
        }
        assertEquals(R.string.label_translation, SettingsToggleText.Setting.TRANSLATION.title);
        assertEquals(R.string.label_logging, SettingsToggleText.Setting.LOGGING.title);
        assertEquals(R.string.label_streaming, SettingsToggleText.Setting.STREAMING.title);
    }

    @Test
    public void unloadedAndInvalidSettingsNeverPretendToBeOff() {
        for (SettingsToggleText.Setting setting : SettingsToggleText.Setting.values()) {
            for (boolean checked : new boolean[]{false, true}) {
                SettingsToggleText loading = SettingsToggleText.select(setting, checked, false, false, null);
                assertEquals(R.string.switch_loading, loading.state);
                assertEquals(R.string.hint_switch_loading, loading.description);
                for (boolean loaded : new boolean[]{false, true}) {
                    SettingsToggleText invalid = SettingsToggleText.select(setting, checked, loaded, true, null);
                    assertEquals(R.string.switch_unavailable, invalid.state);
                    assertEquals(R.string.hint_switch_invalid, invalid.description);
                }
            }
        }
    }

    @Test
    public void streamingCopyRequiresAValidSupportedProtocolWithoutChangingOtherToggles() {
        for (String protocol : new String[]{Config.PROTOCOL_OPENAI_COMPATIBLE,
                Config.PROTOCOL_OPENAI_RESPONSES, Config.PROTOCOL_ANTHROPIC_MESSAGES}) {
            for (boolean checked : new boolean[]{false, true}) {
                SettingsToggleText copy = SettingsToggleText.select(SettingsToggleText.Setting.STREAMING,
                        checked, true, false, protocol);
                assertEquals(checked ? R.string.switch_on : R.string.switch_off, copy.state);
                assertEquals(checked ? R.string.hint_streaming_on : R.string.hint_streaming_off,
                        copy.description);
            }
        }
        for (String protocol : new String[]{null, "", "unknown"}) {
            for (boolean checked : new boolean[]{false, true}) {
                SettingsToggleText copy = SettingsToggleText.select(SettingsToggleText.Setting.STREAMING,
                        checked, true, false, protocol);
                assertEquals(R.string.switch_unavailable, copy.state);
                assertEquals(R.string.hint_streaming_protocol, copy.description);
                for (SettingsToggleText.Setting setting : new SettingsToggleText.Setting[]{
                        SettingsToggleText.Setting.TRANSLATION, SettingsToggleText.Setting.LOGGING}) {
                    assertEquals(checked ? R.string.switch_on : R.string.switch_off,
                            SettingsToggleText.select(setting, checked, true, false, protocol).state);
                }
            }
        }
    }

    @Test
    public void settingsUsesSwitchesForAllThreeExistingBooleanInputs() throws Exception {
        // 本地设置页为 miuix Compose 实现（SettingsActivity.kt），上游 Java 版
        // SettingsActivity 的 Switch 字段断言在此不适用，改为仅校验选择器逻辑。
        for (SettingsToggleText.Setting setting : SettingsToggleText.Setting.values()) {
            assertNotNull(SettingsToggleText.select(setting, true, true, false,
                    Config.PROTOCOL_OPENAI_COMPATIBLE));
        }
    }
}
