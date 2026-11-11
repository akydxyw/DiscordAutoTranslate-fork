package io.github.wxmyyds.discordautotranslate.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 设置页的可观察状态。
 *
 * <p>由 [SettingsActivity] 在写配置、同步 service 时写入，Compose 侧只读取；
 * 所有写入都发生在主线程（后台任务通过 Handler.post 回到主线程后再改）。</p>
 */
class SettingsUiState {
    var status by mutableStateOf("")
    var enabled by mutableStateOf(true)
    var protocolIndex by mutableIntStateOf(1)
    var streaming by mutableStateOf(true)
    var streamingAvailable by mutableStateOf(true)
    var apiUrl by mutableStateOf("")
    var apiKey by mutableStateOf("")
    var model by mutableStateOf("")
    var targetLanguageLabels by mutableStateOf<List<String>>(emptyList())
    var targetLanguageIndex by mutableIntStateOf(0)
    var headers by mutableStateOf("")
    var systemPrompt by mutableStateOf("")
    var timeout by mutableStateOf("")
    var concurrency by mutableStateOf("")
    var testText by mutableStateOf("")
    var result by mutableStateOf("")
    var resultOk by mutableStateOf<Boolean?>(null)
    var saveEnabled by mutableStateOf(false)
    var testsEnabled by mutableStateOf(true)
}

/** 设置页向宿主 Activity 上报的用户操作。 */
interface SettingsActions {
    fun onEnabledChange(value: Boolean)
    fun onProtocolChange(index: Int)
    fun onStreamingChange(value: Boolean)
    fun onApiUrlChange(value: String)
    fun onApiKeyChange(value: String)
    fun onModelChange(value: String)
    fun onHeadersChange(value: String)
    fun onSystemPromptChange(value: String)
    fun onTimeoutChange(value: String)
    fun onConcurrencyChange(value: String)
    fun onTestTextChange(value: String)
    fun onTargetLanguageChange(index: Int)
    fun onSave()
    fun onSelfCheck()
    fun onTestConnection()
    fun onTestTranslation()
}
