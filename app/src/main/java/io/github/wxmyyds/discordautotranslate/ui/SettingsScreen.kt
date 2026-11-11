package io.github.wxmyyds.discordautotranslate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.wxmyyds.discordautotranslate.R
import io.github.wxmyyds.discordautotranslate.core.Config
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * miuix 风格的设置界面。
 *
 * <p>结构对应旧版 View 布局：状态行 → 开关/协议 → 连接参数 → 目标语言 → 翻译参数
 * → 测试文本 → 隐私说明 → 保存 → 三个测试按钮 → 结果。</p>
 */
@Composable
internal fun SettingsScreen(state: SettingsUiState, actions: SettingsActions) {
    SettingsTheme {
        Scaffold(
            topBar = { SmallTopAppBar(title = stringResource(R.string.module_name)) },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.status.isNotEmpty()) {
                    Text(
                        text = state.status,
                        modifier = Modifier.padding(horizontal = 4.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }

                SwitchCard(state = state, actions = actions)

                SettingTextField(
                    title = stringResource(R.string.label_api_url),
                    placeholder = stringResource(R.string.hint_api_url),
                    value = state.apiUrl,
                    keyboardType = KeyboardType.Uri,
                    onValueChange = actions::onApiUrlChange,
                )
                SettingTextField(
                    title = stringResource(R.string.label_api_key),
                    placeholder = "",
                    value = state.apiKey,
                    visualTransformation = PasswordVisualTransformation(),
                    onValueChange = actions::onApiKeyChange,
                )
                SettingTextField(
                    title = stringResource(R.string.label_model),
                    placeholder = Config.DEF_MODEL,
                    value = state.model,
                    onValueChange = actions::onModelChange,
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        OverlayDropdownPreference(
                            items = state.targetLanguageLabels,
                            selectedIndex = state.targetLanguageIndex,
                            title = stringResource(R.string.label_target_language),
                            onSelectedIndexChange = actions::onTargetLanguageChange,
                        )
                    }
                }

                SettingTextField(
                    title = stringResource(R.string.label_headers),
                    placeholder = "",
                    value = state.headers,
                    singleLine = false,
                    minLines = 2,
                    onValueChange = actions::onHeadersChange,
                )
                SettingTextField(
                    title = stringResource(R.string.label_system_prompt),
                    placeholder = stringResource(R.string.hint_system_prompt),
                    value = state.systemPrompt,
                    singleLine = false,
                    minLines = 3,
                    onValueChange = actions::onSystemPromptChange,
                )
                SettingTextField(
                    title = stringResource(R.string.label_timeout),
                    placeholder = Config.DEF_TIMEOUT_MS.toString(),
                    value = state.timeout,
                    keyboardType = KeyboardType.Number,
                    onValueChange = actions::onTimeoutChange,
                )
                SettingTextField(
                    title = stringResource(R.string.label_concurrency),
                    placeholder = Config.DEF_MAX_CONCURRENCY.toString(),
                    value = state.concurrency,
                    keyboardType = KeyboardType.Number,
                    onValueChange = actions::onConcurrencyChange,
                )
                SettingTextField(
                    title = stringResource(R.string.label_test_text),
                    placeholder = stringResource(R.string.hint_test_text),
                    value = state.testText,
                    singleLine = false,
                    minLines = 3,
                    onValueChange = actions::onTestTextChange,
                )

                Text(
                    text = stringResource(R.string.privacy_notice),
                    modifier = Modifier.padding(horizontal = 4.dp),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )

                Button(
                    onClick = actions::onSave,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.saveEnabled,
                ) {
                    Text(
                        text = stringResource(R.string.button_save),
                        style = MiuixTheme.textStyles.button,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TestButton(
                        text = stringResource(R.string.button_config_check),
                        enabled = state.testsEnabled,
                        onClick = actions::onSelfCheck,
                    )
                    TestButton(
                        text = stringResource(R.string.button_test_connection),
                        enabled = state.testsEnabled,
                        onClick = actions::onTestConnection,
                    )
                    TestButton(
                        text = stringResource(R.string.button_test_translation),
                        enabled = state.testsEnabled,
                        onClick = actions::onTestTranslation,
                    )
                }

                if (state.result.isNotEmpty()) {
                    val resultColor = when (state.resultOk) {
                        true -> MiuixTheme.colorScheme.primary
                        false -> MiuixTheme.colorScheme.error
                        null -> MiuixTheme.colorScheme.onBackgroundVariant
                    }
                    Text(
                        text = state.result,
                        modifier = Modifier.padding(horizontal = 4.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        color = resultColor,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/** 开关与下拉框分组：启用、供应商协议、流式、目标语言。 */
@Composable
private fun SwitchCard(state: SettingsUiState, actions: SettingsActions) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            SwitchPreference(
                checked = state.enabled,
                onCheckedChange = actions::onEnabledChange,
                title = stringResource(R.string.label_enabled),
            )
            OverlayDropdownPreference(
                items = SettingsActivity.PROTOCOL_ENTRIES.toList(),
                selectedIndex = state.protocolIndex,
                title = stringResource(R.string.label_protocol),
                summary = stringResource(R.string.hint_protocol),
                onSelectedIndexChange = actions::onProtocolChange,
            )
            SwitchPreference(
                checked = state.streaming,
                onCheckedChange = actions::onStreamingChange,
                title = stringResource(R.string.label_streaming),
                enabled = state.streamingAvailable,
            )
        }
    }
}

/** 三列并排的测试按钮，文本上下换行以适配窄屏。 */
@Composable
private fun RowScope.TestButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        enabled = enabled,
    ) {
        Text(
            text = text,
            maxLines = 2,
            textAlign = TextAlign.Center,
            style = MiuixTheme.textStyles.footnote1,
        )
    }
}

/** 标题 + miuix 输入框；默认值通过占位符展示（与旧版 hint 行为一致）。 */
@Composable
private fun SettingTextField(
    title: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 4.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = placeholder,
            useLabelAsPlaceholder = true,
            singleLine = singleLine,
            minLines = minLines,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = visualTransformation,
            textStyle = MiuixTheme.textStyles.body2,
        )
    }
}
