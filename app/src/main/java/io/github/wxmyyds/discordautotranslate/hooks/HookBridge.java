package io.github.wxmyyds.discordautotranslate.hooks;

import android.view.View;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.EnglishDetector;
import io.github.wxmyyds.discordautotranslate.core.Scheduler;
import io.github.wxmyyds.discordautotranslate.core.StateStore;
import io.github.wxmyyds.discordautotranslate.core.TextExtractor;
import io.github.wxmyyds.discordautotranslate.core.TranslationCache;
import io.github.wxmyyds.discordautotranslate.core.TranslationException;
import io.github.wxmyyds.discordautotranslate.core.TranslationTaskId;
import io.github.wxmyyds.discordautotranslate.translate.JsonApiEngine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 渲染层与翻译状态之间的桥。
 *
 * <p>职责：
 * <ol>
 *   <li>维护「正文视图 → 消息 ID」与「内容对象 → 消息 ID」两张映射：
 *       RecyclerView 会复用 {@code MessageContentView} 实例，
 *       译文状态绝不能存在 View 上（需求 §风险 2）。</li>
 *   <li>协调唯一翻译生产入口；原生重放由 RenderDispatcher 与订阅表负责。</li>
 *   <li>提供“原文/译文”切换与失败重试入口，供图标点击调用。</li>
 * </ol>
 */
public final class HookBridge {

    /** Discord 消息 ID 是雪花 ID：纯数字，长度约 17–20。 */
    private static final int SNOWFLAKE_MIN = 15;
    private static final int SNOWFLAKE_MAX = 21;

    private static final HookBridge INSTANCE = new HookBridge();

    public static HookBridge get() {
        return INSTANCE;
    }

    /** 正文视图 → 消息 ID（有界，避免列表回收后无限增长）。 */
    private final Map<View, String> viewToMessageId = boundedMap(512);

    /** Only native row bindings own edit metadata; source snapshots live with state. */
    private final Map<String, MessageRevision> messageRevisions = boundedMap(512);

    private static final class MessageRevision {
        final String editedTimestamp;

        MessageRevision(@Nullable String editedTimestamp) {
            this.editedTimestamp = editedTimestamp;
        }
    }

    static final class ReferencedTranslation {
        final StateStore.Entry state;
        final Object originalContent;
        final TextExtractor.Result extracted;

        ReferencedTranslation(StateStore.Entry state, Object originalContent, TextExtractor.Result extracted) {
            this.state = state;
            this.originalContent = originalContent;
            this.extracted = extracted;
        }
    }

    /** Only the containing message's own bind may record its edit revision. */
    void rememberMessageRevision(String messageId, @Nullable String editedTimestamp) {
        if (messageId != null) {
            messageRevisions.put(messageId, new MessageRevision(editedTimestamp));
        }
    }

    void rememberCanonicalContent(String messageId, Object content) {
        MessageRevision revision = messageRevisions.get(messageId);
        StateStore.get().rememberSource(messageId, new StateStore.Source(content,
                revision != null, revision == null ? null : revision.editedTimestamp));
    }

    /**
     * A reply is a projection, not a second body. Select the canonical source by
     * message identity and edit revision, then verify that source against state.
     * Never hash the preview text or mutate/create state from it.
     */
    @Nullable
    ReferencedTranslation referencedTranslation(String messageId, @Nullable String editedTimestamp) {
        if (messageId == null) {
            return null;
        }
        StateStore.Source original = StateStore.get().source(messageId);
        MessageRevision current = messageRevisions.get(messageId);
        if (original == null || !original.revisionKnown || current == null
                || !java.util.Objects.equals(original.editedTimestamp, editedTimestamp)
                || !java.util.Objects.equals(current.editedTimestamp, editedTimestamp)) {
            return null;
        }
        return currentTranslation(messageId, original.content);
    }

    /** 按引用身份反查消息 ID；Discord StructurableText 的 equals() 是按内容比较。 */
    private final IdentityMessageMap contentIdentityToMessageId = new IdentityMessageMap();

    private volatile Config.Snapshot config = Config.read(null);
    private volatile Scheduler.Engine engine = new JsonApiEngine(config);

    private HookBridge() {
    }

    // ---------------------------------------------------------------- 配置与引擎

    /**
     * 更新配置。会影响翻译结果的配置变化（URL/模型/目标语言/协议/提示词/凭据）
     * 发生后，递增状态代次使旧译文与在途任务失效（需求 §5.6、§5.7）。
     */
    public void updateConfig(Config.Snapshot c) {
        if (c == null) {
            return;
        }
        Config.Snapshot old = this.config;
        this.config = c;

        // 并发上限即时生效（需求 §5.7 普通配置变更尽量即时生效）。
        Scheduler.get().setMaxConcurrency(c.maxConcurrency);

        // version() 已包含密钥/额外请求头指纹：凭据变更同样会使缓存与在途结果失效。
        if (old == null || !old.version().equals(c.version())) {
            StateStore.get().onNewGeneration();
            TranslationCache.get().clear();
            PollCardState.clear();
        }
        // 引擎随配置重建，保证下一次请求使用最新配置。
        this.engine = new JsonApiEngine(c);
    }

    // ---------------------------------------------------------------- 视图映射

    /** 记录正文视图当前绑定的消息 ID。列表复用时会被覆盖，始终反映最新绑定。 */
    public void bindView(View contentView, String messageId) {
        if (contentView == null || messageId == null) {
            return;
        }
        viewToMessageId.put(contentView, messageId);
    }

    /** Remove a stale direct binding when a recycled preview has no resolvable message ID. */
    public void unbindView(@Nullable View contentView) {
        if (contentView == null) {
            return;
        }
        viewToMessageId.remove(contentView);
    }

    @Nullable
    public String directMessageIdOf(@Nullable View contentView) {
        return contentView == null ? null : viewToMessageId.get(contentView);
    }

    /** Resolve a content child through its enclosing message row when no direct mapping exists. */
    @Nullable
    public String messageIdOf(@Nullable View contentView) {
        View current = contentView;
        while (current != null) {
            String messageId = viewToMessageId.get(current);
            if (messageId != null) {
                return messageId;
            }
            android.view.ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /** 清空全部映射。热重载与配置重置时调用。 */
    public void reset() {
        viewToMessageId.clear();
        messageRevisions.clear();
        contentIdentityToMessageId.clear();
        PollCardState.clear();
    }

    private static <K, V> Map<K, V> boundedMap(int maxEntries) {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxEntries;
            }
        });
    }

    // ------------------------------------------------------- 内容 → 消息 ID

    /**
     * 记录“内容对象 → 消息 ID”。只能在能拿到权威消息 ID 的地方调用
     * （由版本配置选择的消息绑定与回复绑定 Hook）。
     */
    public void rememberContent(String messageId, @Nullable Object structurableText) {
        if (messageId == null || structurableText == null) {
            return;
        }
        contentIdentityToMessageId.put(structurableText, messageId);
    }

    /**
     * 反查某个 {@code StructurableText} 属于哪条消息。
     *
     * <p>只按对象身份映射反查。不得按正文哈希兜底：不同消息完全可能有相同正文，
     * 将哈希映射到单个 ID 会把译文错误地套用到另一条消息。</p>
     */
    @Nullable
    public String resolveMessageId(@Nullable Object structurableText) {
        if (structurableText == null) {
            return null;
        }
        return contentIdentityToMessageId.get(structurableText);
    }

    // ------------------------------------------------------- Embed 状态键

    /**
     * 为 Embed 文本生成独立的状态键。
     *
     * <p>格式：{@code 行消息ID + "\u0000embed\u0000" + 内容哈希}。它与正文共用
     * 同一套翻译管线（StateStore/Scheduler/缓存/重渲染/替换），但：
     * <ul>
     *   <li>与正文的状态条目、与同消息内不同 Embed 文本（标题/描述/字段）
     *       互不覆盖：每段文本按内容哈希各占一条；</li>
     *   <li>哈希与 {@link #onMessageBound} 完全同口径，保证键与条目一致；</li>
     *   <li>分隔符含 {@code U+0000}，真实雪花 ID 不可能包含，可安全区分
     *       Embed 键与正文键。</li>
     * </ul>
     *
     * @return 状态键；文本为空或类型未知时返回 null（调用方应跳过）
     */
    @Nullable
    public String embedStateKey(String rowMessageId, @Nullable Object structurable) {
        TextExtractor.Result ex = TextExtractor.extract(structurable);
        if (ex == null || ex.text.trim().isEmpty()) {
            return null;
        }
        return stateKey(rowMessageId, EMBED_KEY_PREFIX, contentHashOf(ex, config));
    }

    /** One current capture feeds identity verification, native reconstruction and icon eligibility. */
    @Nullable
    ReferencedTranslation currentTranslation(String key, @Nullable Object content) {
        TextExtractor.Result extracted = TextExtractor.extract(content);
        StateStore.Entry state = matchingState(key, extracted);
        return state == null ? null : new ReferencedTranslation(state, content, extracted);
    }

    @Nullable
    StateStore.Entry matchingState(String key, @Nullable TextExtractor.Result extracted) {
        StateStore.Entry entry = StateStore.get().peek(key);
        return entry != null && extracted != null
                && contentHashOf(extracted, config).equals(entry.contentHash) ? entry : null;
    }

    /** Poll question/answer slots share the same hash, scheduler and state pipeline. */
    @Nullable
    String pollStateKey(String rowMessageId, String slot, Object structurable) {
        if (!looksLikeSnowflake(rowMessageId) || slot == null || slot.isEmpty()
                || slot.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid Poll state identity");
        }
        TextExtractor.Result ex = TextExtractor.extract(structurable);
        return ex == null || ex.text.trim().isEmpty() ? null
                : stateKey(rowMessageId, POLL_KEY_PREFIX + slot + "\u0000",
                        contentHashOf(ex, config));
    }

    static boolean isPollKey(@Nullable String key) {
        return key != null && key.contains(POLL_KEY_PREFIX);
    }

    /** Card segment keys are isolated from canonical body IDs. */
    public static boolean isCardKey(@Nullable String key) {
        return key != null && (key.contains(EMBED_KEY_PREFIX) || key.contains(POLL_KEY_PREFIX));
    }

    /**
     * 取卡片段状态键中的行消息 ID 部分；正文键原样返回。
     *
     * <p>同一张卡片承载同一条消息的多个文本段，
     * 各段键不同但行 ID 相同。复用校验按“行未变即仍有效”放宽，
     * 行 ID 变化（视图被复用到其他消息）则仍然拒绝。</p>
     */
    public static String cardRowOf(@Nullable String key) {
        if (key == null) {
            return key;
        }
        int i = isCardKey(key) ? key.indexOf('\u0000') : -1;
        return i >= 0 ? key.substring(0, i) : key;
    }

    private static String stateKey(String rowMessageId, String namespace, String contentHash) {
        return rowMessageId + namespace + contentHash;
    }

    /** 与 {@link #onMessageBound} 同口径的内容哈希（含多片段缓存命名空间）。 */
    private static String contentHashOf(TextExtractor.Result ex, Config.Snapshot cfg) {
        String hashInput = ex.segmentCount >= 2 ? ex.text + SEGMENT_CACHE_SUFFIX : ex.text;
        return Scheduler.contentHash(hashInput, cfg.version());
    }

    /**
     * 多片段消息附加到缓存键的后缀。
     *
     * <p>含 {@code U+0000} 保证不会与任何真实正文混淆；只有存在 ≥2 个可翻译
     * 片段的消息会带上它，因此单片段消息的缓存与旧版本保持兼容。</p>
     *
     * <p>版本随回填协议的译文形态变化升级：seg2 对应「单链路无标记请求 +
     * 渲染层结构对齐回填」的版本，同时失效 seg1 时期（可能含损坏/被拒译文）
     * 的旧缓存。</p>
     */
    private static final String SEGMENT_CACHE_SUFFIX = "\u0000seg2";

    /** Embed 状态键的分隔符；真实消息 ID（雪花 ID）不可能包含 U+0000。 */
    private static final String EMBED_KEY_PREFIX = "\u0000embed\u0000";
    private static final String POLL_KEY_PREFIX = "\u0000poll\u0000";

    // ---------------------------------------------------------------- 翻译流程

    /**
     * 消息绑定后的处理入口：识别英文、查缓存、按需发起翻译。
     */
    public void onMessageBound(String messageId, @Nullable Object structurableText) {
        if (!HookRuntime.isActive() || messageId == null || structurableText == null) {
            return;
        }
        TextExtractor.Result ex = TextExtractor.extract(structurableText);
        String nodeTypes = TextExtractor.typeSummary(structurableText, ex);
        HookDiagnostics.infoOnce("translation.nodeTypes." + nodeTypes,
                "translation content node types=" + nodeTypes);
        if (ex == null || ex.text.trim().isEmpty()) {
            HookDiagnostics.warnOnce("translation.noText", "translation skipped: extracted text is empty"
                    + " structurableClass=" + structurableText.getClass().getName());
            return;
        }

        Config.Snapshot cfg = config;
        // 单一链路（用户决策）：始终发送无标记纯文本，不做分段标记请求。
        // 内容哈希沿用带分段后缀的键空间（仅作缓存命名空间，多段消息与
        // 旧缓存条目隔离）。
        String requestText = ex.text;
        String hash = contentHashOf(ex, cfg);
        StateStore.Entry entry = StateStore.get().obtain(messageId, hash);
        rememberCanonicalContent(messageId, structurableText);
        if (!cfg.enabled) {
            HookDiagnostics.warnOnce("translation.disabled", "translation skipped: auto-translate is disabled");
            // 总开关关闭：不发起新翻译，保留已有译文（需求 §2.7）。
            return;
        }
        if (entry.status == StateStore.Status.TRANSLATING
                || entry.status == StateStore.Status.TRANSLATED) {
            return; // 已有在途任务或已有译文，不重复翻译（需求 §5.4 去重）。
        }
        if (entry.status == StateStore.Status.FAILED) {
            // 失败后不自动重试：渲染漏斗会被频繁调用（每次重绘都会经过），
            // 若无条件重试会造成请求风暴。失败后统一由用户点图标重试（需求 §2.6）。
            return;
        }

        // 缓存命中：直接出结果，不发请求（需求 §5.6）。
        String cached = TranslationCache.get().get(hash);
        if (cached != null) {
            if (StateStore.get().useCached(entry, cached)) refreshTranslation(messageId);
            return;
        }

        if (!cfg.isUsable()) {
            HookDiagnostics.warnOnce("translation.config", "translation API configuration is unusable"
                    + " configErrorPresent=" + (cfg.configError != null));
            if (StateStore.get().failIdle(entry,
                    cfg.configError != null ? cfg.configError : "API 未配置"))
                refreshTranslation(messageId);
            return;
        }

        EnglishDetector.Verdict v = EnglishDetector.evaluate(
                ex.text, ex.textNodeCount, ex.opaqueNodeCount, ex.codeHeavy,
                cfg.targetLang);
        if (!v.shouldTranslate) {
            HookDiagnostics.infoOnce("translation.languageSkip", "translation skipped by language detector"
                    + " reason=" + v.reason.name() + " textNodes=" + ex.textNodeCount
                    + " opaqueNodes=" + ex.opaqueNodeCount);
            return;
        }

        HookDiagnostics.infoOnce("translation.started", "translation request started"
                + " textNodes=" + ex.textNodeCount + " segments=" + ex.segmentCount);
        TranslationTaskId task = StateStore.get().begin(entry);
        if (task == null) return;
        refreshTranslation(messageId);
        Scheduler.get().submit(task, requestText, engine,
                identity -> HookRuntime.isActive() && config.enabled && StateStore.get().owns(identity),
                this::onTranslateResult);
    }

    /** Complete task ownership is validated before every transition, including discarded results. */
    void onTranslateResult(TranslationTaskId task, boolean accepted,
                           @Nullable String translated, @Nullable Throwable error) {
        HookDiagnostics.info("translation result received accepted=" + accepted
                + " translatedPresent=" + (translated != null && !translated.isEmpty())
                + " errorClass=" + (error == null ? "none" : error.getClass().getSimpleName())
                + " errorKind=" + (error instanceof TranslationException
                        ? ((TranslationException) error).kind().name() : "none")
                + " causeClass=" + (error == null || error.getCause() == null
                        ? "none" : error.getCause().getClass().getSimpleName()));
        boolean success = accepted && error == null && translated != null && !translated.isEmpty();
        if (!StateStore.get().complete(task, accepted, translated,
                success ? null : accepted ? describe(error) : null)) return;
        if (success) TranslationCache.get().put(task.contentHash, translated);
        refreshTranslation(task.messageId);
    }

    /** A segment update also refreshes the Poll title's aggregate progress/error icon. */
    private static void refreshTranslation(String key) {
        RerenderRegistry.rerender(key);
        PollCardState.Card card = PollCardState.forKey(key);
        if (card != null && card.titleKey != null && !key.equals(card.titleKey))
            RerenderRegistry.rerender(card.titleKey);
    }

    /** 把异常转换为可读且不泄露密钥的描述。 */
    private static String describe(@Nullable Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        if (t instanceof TranslationException) {
            return ((TranslationException) t).userMessage();
        }
        return t.getClass().getSimpleName();
    }

    /** 富文本结构未能按严格规则重建时，将网络成功转换为可见翻译失败。 */
    public void onTranslationRenderRejected(String id, String translated) {
        StateStore.Entry entry = StateStore.get().peek(id);
        if (entry == null || entry.status != StateStore.Status.TRANSLATED
                || !translated.equals(entry.translatedText)) {
            return;
        }
        TranslationCache.get().remove(entry.contentHash);
        if (StateStore.get().rejectRender(id, translated)) {
            HookDiagnostics.info("translation render rejected: native structure reconstruction failed");
            refreshTranslation(id);
        }
    }

    /** 原文/译文切换：由图标点击触发，不触发新请求（需求 §2.4）。 */
    public void toggleShowingOriginal(String messageId) {
        if (isPollKey(messageId)) {
            PollCardState.Card card = PollCardState.toggle(messageId);
            if (card != null) for (String key : card.keys) RerenderRegistry.rerender(key);
            return;
        }
        if (StateStore.get().toggle(messageId)) RerenderRegistry.rerender(messageId);
    }

    /**
     * 行内图标点击的统一入口（需求：成功时切换原文/译文，失败时重试）。
     *
     * <p>由 {@code IconClickSpanFactory} 创建的 span 回调触发。
     * 状态可能因列表复用而变化，因此每次都重新读取当前状态。</p>
     */
    public void onIconClicked(String messageId) {
        if (messageId == null) {
            return;
        }
        if (isPollKey(messageId)) {
            PollCardState.Card card = PollCardState.forKey(messageId);
            if (card == null || !messageId.equals(card.titleKey)) return;
            toggleShowingOriginal(messageId);
            // Switching back to translation is the sole user retry action for failed slots.
            if (!card.showingOriginal()) for (String key : card.keys) {
                StateStore.Entry segment = StateStore.get().peek(key);
                if (segment != null && segment.status == StateStore.Status.FAILED) retry(key);
            }
            return;
        }
        StateStore.Entry e = StateStore.get().peek(messageId);
        if (e == null) {
            return;
        }
        switch (e.status) {
            case TRANSLATED:
                if (e.translatedText != null) {
                    toggleShowingOriginal(messageId);
                }
                break;
            case FAILED:
                retry(messageId);
                break;
            default:
                // 翻译中或未开始：忽略，避免重复触发。
                break;
        }
    }

    /** 失败后重试：由图标点击触发（需求 §2.6）。 */
    public void retry(String messageId) {
        if (!HookRuntime.isActive()) return;
        StateStore.Entry e = StateStore.get().peek(messageId);
        if (e == null) {
            return;
        }
        StateStore.Source original = StateStore.get().source(messageId);
        MessageRevision current = messageRevisions.get(messageId);
        boolean sameRevision = original != null && (!original.revisionKnown
                ? current == null
                : current != null && java.util.Objects.equals(
                        original.editedTimestamp, current.editedTimestamp));
        if (!sameRevision || matchingState(messageId, TextExtractor.extract(original.content)) != e) {
            // A new model may have bound before its body renderer. Do not relabel
            // the previous body's retry with the latest model revision or reset state.
            return;
        }
        Object structurable = original.content;

        if (!StateStore.get().prepareRetry(e)) return;
        refreshTranslation(messageId);

        onMessageBound(messageId, structurable);
    }

    // ---------------------------------------------------------------- 工具

    /** 判断字符串是否符合 Discord 雪花 ID 形态。 */
    public static boolean looksLikeSnowflake(@Nullable String s) {
        if (s == null) {
            return false;
        }
        int n = s.length();
        if (n < SNOWFLAKE_MIN || n > SNOWFLAKE_MAX) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
