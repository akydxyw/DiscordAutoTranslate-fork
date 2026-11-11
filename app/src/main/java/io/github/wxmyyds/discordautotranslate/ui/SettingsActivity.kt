package io.github.wxmyyds.discordautotranslate.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.libxposed.service.XposedService
import io.github.wxmyyds.discordautotranslate.R
import io.github.wxmyyds.discordautotranslate.core.Config
import io.github.wxmyyds.discordautotranslate.core.MemoryPrefs
import io.github.wxmyyds.discordautotranslate.core.Protocols
import io.github.wxmyyds.discordautotranslate.translate.ApiTester
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

/**
 * 设置界面（需求 §5.7），界面用 miuix（Compose）实现。
 *
 * <p>读写逻辑与旧版 View 实现保持一一对应：本地 SharedPreferences 与 LSPosed
 * remote preferences 之间的初始化/同步、默认值用占位符展示、保存后重启 Discord 生效。</p>
 */
class SettingsActivity : ComponentActivity(), SettingsActions {

    companion object {
        private const val LOCAL_PREFS_NAME = "settings_before_activation"
        private const val KEY_LOCAL_INITIALIZED = "settings_initialized"
        private val CONFIG_KEYS = arrayOf(
            Config.KEY_ENABLED, Config.KEY_API_URL, Config.KEY_API_KEY, Config.KEY_MODEL,
            Config.KEY_TARGET_LANG, Config.KEY_EXTRA_HEADERS, Config.KEY_SYSTEM_PROMPT,
            Config.KEY_PROTOCOL, Config.KEY_STREAMING, Config.KEY_TIMEOUT_MS,
            Config.KEY_MAX_CONCURRENCY,
        )
        private val TARGET_LANGUAGE_VALUES = arrayOf(
            "简体中文", "繁體中文", "English", "日本語", "한국어", "Русский",
            "Українська", "Español", "Français", "Deutsch", "Português", "Italiano",
            "العربية", "فارسی", "हिन्दी", "বাংলা", "ไทย", "עברית", "Ελληνικά",
            "Tiếng Việt", "Türkçe", "Bahasa Indonesia",
        )
        private val TARGET_LANGUAGE_LABELS_EN = arrayOf(
            "Simplified Chinese", "Traditional Chinese", "English", "Japanese", "Korean",
            "Russian", "Ukrainian", "Spanish", "French", "German", "Portuguese", "Italian",
            "Arabic", "Persian", "Hindi", "Bengali", "Thai", "Hebrew", "Greek",
            "Vietnamese", "Turkish", "Indonesian",
        )
        private val TARGET_LANGUAGE_LABELS_ZH = arrayOf(
            "简体中文", "繁體中文", "英语", "日语", "韩语", "俄语", "乌克兰语", "西班牙语",
            "法语", "德语", "葡萄牙语", "意大利语", "阿拉伯语", "波斯语", "印地语",
            "孟加拉语", "泰语", "希伯来语", "希腊语", "越南语", "土耳其语", "印尼语",
        )

        /** 下拉框显示顺序：0=OpenAI-Responses，1=OpenAI-Compatible，2=Anthropic-Messages。 */
        val PROTOCOL_ENTRIES = arrayOf(
            "OpenAI-Responses", "OpenAI-Compatible", "Anthropic-Messages",
        )

        /** 下拉框位置 → 协议存储值；无效位置返回 null 供配置校验拒绝。 */
        private fun protocolAt(position: Int): String? = when (position) {
            0 -> Config.PROTOCOL_OPENAI_RESPONSES
            1 -> Config.PROTOCOL_OPENAI_COMPATIBLE
            2 -> Config.PROTOCOL_ANTHROPIC_MESSAGES
            else -> null
        }

        /** 协议存储值 → 下拉框位置；未知值回落到默认协议，避免下拉框无选中项。 */
        private fun positionOf(protocol: String?): Int = when (protocol) {
            Config.PROTOCOL_OPENAI_RESPONSES -> 0
            Config.PROTOCOL_OPENAI_COMPATIBLE -> 1
            Config.PROTOCOL_ANTHROPIC_MESSAGES -> 2
            else -> 1
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val testExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val saveExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val state = SettingsUiState()

    /**
     * 把进程级 service 回调转成 Activity 内部方法。
     *
     * <p>[XposedServiceConnectionHolder.Listener] 是包内可见的 Java 类型，
     * 公共的 Activity 不能直接实现它，因此用一个私有匿名实现转发。</p>
     */
    private val serviceListener = object : XposedServiceConnectionHolder.Listener {
        override fun onServiceConnected(service: XposedService) {
            handleServiceConnected(service)
        }

        override fun onServiceDied(service: XposedService) {
            handleServiceDied(service)
        }
    }

    private var service: XposedService? = null
    private var prefs: SharedPreferences? = null
    private var remotePrefs: SharedPreferences? = null
    private var testFuture: Future<*>? = null

    private var testGeneration = 0L
    private var serviceGeneration = 0L
    private var saveInProgress = false
    private var syncInProgress = false
    private var destroyed = false

    private var chineseUi = false
    private var loadingUi = false
    private var hasUnsavedChanges = false
    private var baselineProtocol: String? = null
    private var baselineTargetLanguage: String? = null
    private val targetLanguageValues = ArrayList<String>()
    private var targetLanguageLabels: MutableList<String> = ArrayList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        chineseUi = isChineseSystemLanguage()
        setTitle(R.string.module_name)
        prefs = getSharedPreferences(LOCAL_PREFS_NAME, MODE_PRIVATE)
        targetLanguageValues.clear()
        targetLanguageValues.addAll(TARGET_LANGUAGE_VALUES)
        targetLanguageLabels =
            ArrayList(if (chineseUi) TARGET_LANGUAGE_LABELS_ZH.toList() else TARGET_LANGUAGE_LABELS_EN.toList())
        state.targetLanguageLabels = targetLanguageLabels.toList()
        state.targetLanguageIndex = targetLanguagePosition(Config.DEF_TARGET_LANG)
        setContent {
            SettingsScreen(state = state, actions = this)
        }
        loadIntoUi()
        updateSaveButtonState()
        updateStatus(getString(R.string.status_local_ready))
        XposedServiceConnectionHolder.bind(serviceListener)
    }

    override fun onDestroy() {
        destroyed = true
        XposedServiceConnectionHolder.unbind(serviceListener)
        cancelPendingTest()
        testExecutor.shutdownNow()
        saveExecutor.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 服务连接

    private fun handleServiceConnected(service: XposedService) {
        if (destroyed || prefs == null) {
            return
        }
        this.service = service
        serviceGeneration++
        val generation = serviceGeneration
        saveInProgress = false
        syncInProgress = false
        val local = prefs ?: return
        val remote: SharedPreferences? = try {
            service.getRemotePreferences(Config.GROUP)
        } catch (t: Throwable) {
            remotePrefs = null
            updateStatus(getString(R.string.status_sync_pending))
            updateSaveButtonState()
            return
        }
        remotePrefs = remote
        if (remote == null) {
            updateStatus(getString(R.string.status_sync_pending))
            updateSaveButtonState()
            return
        }

        syncInProgress = true
        updateSaveButtonState()
        updateStatus(getString(R.string.status_syncing))
        try {
            saveExecutor.execute {
                var synced = false
                try {
                    synced = if (local.getBoolean(KEY_LOCAL_INITIALIZED, false)) {
                        copyConfigPreferences(local, remote, false)
                    } else {
                        copyConfigPreferences(remote, local, true)
                    }
                } catch (ignored: Throwable) {
                    synced = false
                }
                val syncResult = synced
                mainHandler.post {
                    if (destroyed || generation != serviceGeneration ||
                        prefs !== local || remotePrefs !== remote
                    ) {
                        return@post
                    }
                    syncInProgress = false
                    if (!hasUnsavedChanges) {
                        loadIntoUi()
                    }
                    updateSaveButtonState()
                    updateStatus(
                        if (syncResult) {
                            getString(
                                R.string.status_connected,
                                service.getFrameworkVersion(),
                                service.getApiVersion(),
                            )
                        } else {
                            getString(R.string.status_sync_failed)
                        },
                    )
                }
            }
        } catch (t: Throwable) {
            syncInProgress = false
            updateSaveButtonState()
            updateStatus(getString(R.string.status_sync_failed))
        }
    }

    private fun handleServiceDied(deadService: XposedService) {
        if (destroyed || service !== deadService) {
            return
        }
        service = null
        remotePrefs = null
        serviceGeneration++
        saveInProgress = false
        syncInProgress = false
        updateSaveButtonState()
        updateStatus(getString(R.string.status_local_ready))
    }

    // ------------------------------------------------------------ 用户操作

    override fun onEnabledChange(value: Boolean) {
        state.enabled = value
        markDirty()
    }

    override fun onProtocolChange(index: Int) {
        state.protocolIndex = index
        state.streamingAvailable = protocolAt(index)?.let { Protocols.supportsStreaming(it) } ?: false
        markSpinnerDirty()
    }

    override fun onStreamingChange(value: Boolean) {
        state.streaming = value
        markDirty()
    }

    override fun onApiUrlChange(value: String) {
        state.apiUrl = value
        markDirty()
    }

    override fun onApiKeyChange(value: String) {
        state.apiKey = value
        markDirty()
    }

    override fun onModelChange(value: String) {
        state.model = value
        markDirty()
    }

    override fun onHeadersChange(value: String) {
        state.headers = value
        markDirty()
    }

    override fun onSystemPromptChange(value: String) {
        state.systemPrompt = value
        markDirty()
    }

    override fun onTimeoutChange(value: String) {
        state.timeout = value
        markDirty()
    }

    override fun onConcurrencyChange(value: String) {
        state.concurrency = value
        markDirty()
    }

    override fun onTestTextChange(value: String) {
        state.testText = value
        markDirty()
    }

    override fun onTargetLanguageChange(index: Int) {
        state.targetLanguageIndex = index
        markSpinnerDirty()
    }

    override fun onSave() {
        save()
    }

    override fun onSelfCheck() {
        runSelfCheck()
    }

    override fun onTestConnection() {
        runTestTranslation(true)
    }

    override fun onTestTranslation() {
        runTestTranslation(false)
    }

    // ------------------------------------------------------------ 脏标记

    private fun markDirty() {
        if (!loadingUi) {
            hasUnsavedChanges = true
        }
    }

    private fun markSpinnerDirty() {
        if (loadingUi) {
            return
        }
        val protocol = protocolAt(state.protocolIndex)
        val targetLanguage = selectedTargetLanguage()
        if (!sameValue(protocol, baselineProtocol) ||
            !sameValue(targetLanguage, baselineTargetLanguage)
        ) {
            hasUnsavedChanges = true
        }
    }

    private fun sameValue(left: String?, right: String?): Boolean =
        if (left == null) right == null else left == right

    private fun ensureTargetLanguageAvailable(language: String?) {
        if (language == null || targetLanguageValues.contains(language)) {
            return
        }
        targetLanguageValues.add(language)
        targetLanguageLabels.add(getString(R.string.legacy_language))
        state.targetLanguageLabels = targetLanguageLabels.toList()
    }

    private fun targetLanguagePosition(language: String?): Int {
        val index = targetLanguageValues.indexOf(language)
        return if (index >= 0) index else 0
    }

    private fun selectedTargetLanguage(): String? {
        val position = state.targetLanguageIndex
        return if (position >= 0 && position < targetLanguageValues.size) {
            targetLanguageValues[position]
        } else {
            null
        }
    }

    private fun isChineseSystemLanguage(): Boolean {
        val locale: Locale = resources.configuration.locales[0]
        return "zh".equals(locale.language, ignoreCase = true)
    }

    // ------------------------------------------------------------ 读写

    private fun loadIntoUi() {
        val currentPrefs = prefs ?: return
        loadingUi = true
        try {
            val snapshot = Config.read(currentPrefs)
            if (snapshot.configError != null) {
                updateStatus(getString(R.string.status_invalid_config))
            }
            state.enabled = snapshot.enabled
            state.protocolIndex = positionOf(snapshot.protocol)
            state.streaming = snapshot.streaming
            state.streamingAvailable = protocolAt(state.protocolIndex)
                ?.let { Protocols.supportsStreaming(it) } ?: false
            state.apiUrl = placeholderOrValue(snapshot.apiUrl, Config.DEF_API_URL)
            state.apiKey = placeholderOrValue(snapshot.apiKey, Config.DEF_API_KEY)
            state.model = placeholderOrValue(snapshot.model, Config.DEF_MODEL)
            ensureTargetLanguageAvailable(snapshot.targetLang)
            state.targetLanguageIndex = targetLanguagePosition(snapshot.targetLang)
            state.headers = placeholderOrValue(snapshot.extraHeaders, Config.DEF_EXTRA_HEADERS)
            state.systemPrompt = placeholderOrValue(snapshot.systemPrompt, Config.DEF_SYSTEM_PROMPT)
            state.timeout = placeholderOrValue(
                snapshot.timeoutMs.toString(), Config.DEF_TIMEOUT_MS.toString(),
            )
            state.concurrency = placeholderOrValue(
                snapshot.maxConcurrency.toString(), Config.DEF_MAX_CONCURRENCY.toString(),
            )
            baselineProtocol = snapshot.protocol
            baselineTargetLanguage = snapshot.targetLang
            hasUnsavedChanges = false
        } finally {
            loadingUi = false
        }
    }

    private fun save() {
        if (destroyed || prefs == null) {
            updateStatus(getString(R.string.status_save_persist_failed))
            return
        }
        val snapshot = snapshotFromUi()
        if (snapshot.configError != null) {
            updateStatus(getString(R.string.status_invalid_config))
            return
        }
        commitSnapshot(snapshot)
    }

    private fun commitSnapshot(snapshot: Config.Snapshot) {
        val local = prefs
        val remote = remotePrefs
        if (destroyed || local == null) {
            updateStatus(getString(R.string.status_save_persist_failed))
            return
        }

        val editor: SharedPreferences.Editor = try {
            local.edit().also {
                writeSnapshot(it, snapshot)
                it.putBoolean(KEY_LOCAL_INITIALIZED, true)
            }
        } catch (t: Throwable) {
            updateStatus(getString(R.string.status_save_failed, t.javaClass.simpleName))
            return
        }

        saveInProgress = true
        updateSaveButtonState()
        updateStatus(getString(R.string.status_saving))
        try {
            saveExecutor.execute {
                var localCommitted = false
                var remoteCommitted = false
                var failure: Throwable? = null
                try {
                    localCommitted = editor.commit()
                    if (localCommitted && remote != null) {
                        remoteCommitted = copyConfigPreferences(local, remote, false)
                    }
                } catch (t: Throwable) {
                    failure = t
                }
                val localResult = localCommitted
                val remoteResult = remoteCommitted
                val saveFailure = failure
                mainHandler.post {
                    if (destroyed || prefs !== local) {
                        return@post
                    }
                    saveInProgress = false
                    updateSaveButtonState()
                    if (saveFailure != null && !localResult) {
                        updateStatus(
                            getString(R.string.status_save_failed, saveFailure.javaClass.simpleName),
                        )
                    } else if (!localResult) {
                        updateStatus(getString(R.string.status_save_persist_failed))
                    } else {
                        toast(getString(R.string.toast_saved))
                        updateStatus(
                            getString(
                                if (remoteResult) {
                                    R.string.status_saved_restart
                                } else {
                                    R.string.status_sync_pending
                                },
                            ),
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            saveInProgress = false
            updateSaveButtonState()
            updateStatus(getString(R.string.status_save_failed, t.javaClass.simpleName))
        }
    }

    private fun updateSaveButtonState() {
        state.saveEnabled = !destroyed && prefs != null && !saveInProgress && !syncInProgress
    }

    private fun parseIntInput(value: String, defaultValue: Int): Any {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            return defaultValue
        }
        return try {
            Integer.valueOf(trimmed)
        } catch (e: NumberFormatException) {
            trimmed
        }
    }

    private fun textOrDefault(value: String, defaultValue: String): String {
        val trimmed = value.trim()
        return if (trimmed.isEmpty()) defaultValue else trimmed
    }

    /** 值等于默认值时清空输入框，占位符展示默认值；自定义值才写入。 */
    private fun placeholderOrValue(value: String, def: String): String =
        if (def == value) "" else value

    private fun promptOrDefault(value: String, defaultValue: String): String =
        if (value.trim().isEmpty()) defaultValue else value

    private fun snapshotFromUi(): Config.Snapshot {
        val memoryPrefs = MemoryPrefs()
        memoryPrefs.put(Config.KEY_ENABLED, state.enabled)
        memoryPrefs.put(Config.KEY_PROTOCOL, protocolAt(state.protocolIndex))
        memoryPrefs.put(Config.KEY_STREAMING, state.streaming)
        memoryPrefs.put(Config.KEY_API_URL, textOrDefault(state.apiUrl, Config.DEF_API_URL))
        memoryPrefs.put(Config.KEY_API_KEY, state.apiKey.trim())
        memoryPrefs.put(Config.KEY_MODEL, textOrDefault(state.model, Config.DEF_MODEL))
        memoryPrefs.put(Config.KEY_TARGET_LANG, selectedTargetLanguage())
        memoryPrefs.put(Config.KEY_EXTRA_HEADERS, state.headers)
        memoryPrefs.put(
            Config.KEY_SYSTEM_PROMPT,
            promptOrDefault(state.systemPrompt, Config.DEF_SYSTEM_PROMPT),
        )
        memoryPrefs.put(Config.KEY_TIMEOUT_MS, parseIntInput(state.timeout, Config.DEF_TIMEOUT_MS))
        memoryPrefs.put(
            Config.KEY_MAX_CONCURRENCY,
            parseIntInput(state.concurrency, Config.DEF_MAX_CONCURRENCY),
        )
        return Config.read(memoryPrefs)
    }

    /** 离线配置检查：不发网络请求。 */
    private fun runSelfCheck() {
        showResult(ApiTester.selfCheck(snapshotFromUi()))
    }

    /**
     * 真实请求测试，使用本页面独立的单任务 executor。
     *
     * @param connectionTest true=使用固定测试文本，false=使用测试文本输入框
     */
    private fun runTestTranslation(connectionTest: Boolean) {
        if (destroyed || testFuture != null) {
            return
        }
        val snapshot = snapshotFromUi()
        val text = if (connectionTest) ApiTester.DEFAULT_TEST_TEXT else state.testText
        if (!connectionTest && text.trim().isEmpty()) {
            state.result = getString(R.string.status_enter_test_text)
            state.resultOk = null
            return
        }

        val generation = ++testGeneration
        state.result = getString(R.string.status_requesting)
        state.resultOk = null
        setTestButtonsEnabled(false)

        val task = object : FutureTask<ApiTester.Result>(
            Callable {
                if (connectionTest) {
                    ApiTester.testConnection(snapshot)
                } else {
                    ApiTester.testTranslation(snapshot, text)
                }
            },
        ) {
            override fun done() {
                val self: FutureTask<ApiTester.Result> = this
                val result: ApiTester.Result
                try {
                    result = get()
                } catch (e: CancellationException) {
                    return
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    mainHandler.post { finishTest(generation, self, null, e) }
                    return
                } catch (e: ExecutionException) {
                    mainHandler.post {
                        finishTest(generation, self, null, e.cause ?: e)
                    }
                    return
                }
                mainHandler.post { finishTest(generation, self, result, null) }
            }
        }
        testFuture = task
        try {
            testExecutor.execute(task)
        } catch (t: Throwable) {
            testFuture = null
            setTestButtonsEnabled(true)
            state.result = getString(R.string.status_test_not_started, t.javaClass.simpleName)
        }
    }

    private fun finishTest(
        generation: Long,
        task: Future<*>?,
        result: ApiTester.Result?,
        failure: Throwable?,
    ) {
        if (destroyed || generation != testGeneration || testFuture !== task) {
            return
        }
        testFuture = null
        setTestButtonsEnabled(true)
        if (result != null) {
            showResult(result)
        } else {
            state.result = getString(
                R.string.status_test_failed_detail,
                if (failure == null) getString(R.string.unknown_error) else failure.javaClass.simpleName,
            )
            updateStatus(getString(R.string.status_test_failed))
        }
    }

    private fun cancelPendingTest() {
        testGeneration++
        val pending = testFuture
        testFuture = null
        // Future 取消会发出中断请求；不假定网络实现一定能因此中断 HTTP I/O。
        pending?.cancel(true)
        setTestButtonsEnabled(true)
    }

    private fun setTestButtonsEnabled(enabled: Boolean) {
        state.testsEnabled = enabled
    }

    private fun showResult(result: ApiTester.Result) {
        val message = StringBuilder()
        if (result.ok) {
            message.append(getString(R.string.result_success, result.elapsedMs))
        } else {
            message.append(getString(R.string.result_failure))
        }
        if (result.translated != null) {
            message.append(getString(R.string.result_translation, result.translated))
        }
        state.result = message.toString()
        state.resultOk = result.ok
        updateStatus(
            getString(if (result.ok) R.string.status_test_passed else R.string.status_test_failed),
        )
    }

    private fun updateStatus(status: String) {
        if (!destroyed) {
            state.status = status
        }
    }

    private fun toast(message: String) {
        if (!destroyed) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------ SharedPreferences 复制

    private fun writeSnapshot(editor: SharedPreferences.Editor, snapshot: Config.Snapshot) {
        editor.putBoolean(Config.KEY_ENABLED, snapshot.enabled)
        editor.putString(Config.KEY_PROTOCOL, snapshot.protocol)
        editor.putBoolean(Config.KEY_STREAMING, snapshot.streaming)
        putUnlessDefault(editor, Config.KEY_API_URL, snapshot.apiUrl, Config.DEF_API_URL)
        putUnlessDefault(editor, Config.KEY_API_KEY, snapshot.apiKey, Config.DEF_API_KEY)
        putUnlessDefault(editor, Config.KEY_MODEL, snapshot.model, Config.DEF_MODEL)
        putUnlessDefault(editor, Config.KEY_TARGET_LANG, snapshot.targetLang, Config.DEF_TARGET_LANG)
        putUnlessDefault(
            editor, Config.KEY_EXTRA_HEADERS, snapshot.extraHeaders, Config.DEF_EXTRA_HEADERS,
        )
        putUnlessDefault(
            editor, Config.KEY_SYSTEM_PROMPT, snapshot.systemPrompt, Config.DEF_SYSTEM_PROMPT,
        )
        putUnlessDefault(editor, Config.KEY_TIMEOUT_MS, snapshot.timeoutMs, Config.DEF_TIMEOUT_MS)
        putUnlessDefault(
            editor, Config.KEY_MAX_CONCURRENCY, snapshot.maxConcurrency, Config.DEF_MAX_CONCURRENCY,
        )
    }

    private fun putUnlessDefault(
        editor: SharedPreferences.Editor,
        key: String,
        value: String,
        def: String,
    ) {
        if (def == value) {
            editor.remove(key)
        } else {
            editor.putString(key, value)
        }
    }

    private fun putUnlessDefault(
        editor: SharedPreferences.Editor,
        key: String,
        value: Int,
        def: Int,
    ) {
        if (value == def) {
            editor.remove(key)
        } else {
            editor.putInt(key, value)
        }
    }

    private fun copyConfigPreferences(
        source: SharedPreferences,
        target: SharedPreferences,
        markInitialized: Boolean,
    ): Boolean {
        val values = source.all
        val editor = target.edit()
        for (key in CONFIG_KEYS) {
            val value = values[key]
            if (!values.containsKey(key) || value == null) {
                editor.remove(key)
                continue
            }
            putPreferenceValue(editor, key, value)
        }
        if (markInitialized) {
            editor.putBoolean(KEY_LOCAL_INITIALIZED, true)
        }
        return editor.commit()
    }

    private fun putPreferenceValue(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            else -> throw IllegalArgumentException("Unsupported preference type")
        }
    }
}
