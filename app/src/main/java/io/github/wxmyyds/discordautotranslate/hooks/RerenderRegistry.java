package io.github.wxmyyds.discordautotranslate.hooks;

import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 视图重渲染登记表：把 messageId 映射到一组可重渲染目标。
 *
 * <p>翻译是异步完成的，完成时需要通过消息 ID 找回对应视图重新渲染。</p>
 *
 * <h3>为何每个消息登记一组目标</h3>
 * <p>同一条消息的正文可能同时出现在多个位置：消息自身所在的行，
 * 以及其他消息中引用它的回复预览（{@code MessageViewReplyPreview.replyText}）。
 * 若只记录一个视图，另一处就不会随译文更新。</p>
 *
 * <h3>为何记录调用目标、方法与实参</h3>
 * <p>正文重渲染方法由版本配置选择，可能是静态渲染入口；回复引用则重跑其绑定方法。</p>
 *
 * <h3>复用防护</h3>
 * <p>列表会复用视图。渲染前逐个校验“视图当前绑定的消息 ID 是否仍是目标消息”，
 * 不是则从登记表移除，避免把译文渲染到错误的消息上（需求 §风险 2）。
 * 视图与调用目标本身使用弱引用；原生实参可能强引用视图，真实重新绑定前必须
 * 注销旧登记，容量清理则必须保留仍拥有图标的宿主所需的最新原生实参。</p>
 */
public final class RerenderRegistry {

    /** 单个重渲染目标。 */
    public static final class Target {
        /** 用于校验“是否仍绑定目标消息”的正文视图。 */
        private final WeakReference<View> contentView;
        /** 实际调用 {@link #method} 的接收者（正文视图本身，或回复预览容器）。 */
        private final WeakReference<Object> invocationTarget;
        private final Method method;
        /** 原始实参快照；重渲染即用同一组实参再跑一次。 */
        private final Object[] args;
        private final long registration = REGISTRATION_SEQUENCE.incrementAndGet();

        Target(View contentView, Object invocationTarget, Method method, Object[] args) {
            this.contentView = new WeakReference<>(contentView);
            this.invocationTarget = new WeakReference<>(invocationTarget);
            this.method = method;
            this.args = args;
        }
    }

    /** messageId -> 承载它的视图集合。所有登记与容量变更由类锁串行化。 */
    private static final Map<String, List<Target>> TARGETS = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong REGISTRATION_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();

    /** 登记上限，避免快速滚动时无限增长。 */
    private static final int MAX_MESSAGES = 1024;

    private RerenderRegistry() {
    }

    /**
     * 登记一个重渲染目标。同一正文视图重复登记不会产生重复项，
     * 但会刷新保存的实参（消息可能被重新渲染为最新内容）。
     *
     * @param contentView      正文视图，用于复用校验
     * @param invocationTarget 调用 method 的接收者
     * @param method           重渲染方法
     * @param args             原始实参
     */
    public static synchronized void register(String messageId, View contentView, Object invocationTarget,
                                Method method, Object[] args) {
        if (messageId == null || contentView == null || method == null || args == null
                || (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                && invocationTarget == null)) {
            return;
        }
        // Refreshing an existing key needs no capacity and must not evict its cleanup metadata.
        if (!TARGETS.containsKey(messageId)) {
            pruneIfNeeded();
        }
        List<Target> list = TARGETS.computeIfAbsent(messageId, k -> new ArrayList<>(2));
        synchronized (list) {
            for (int i = 0; i < list.size(); i++) {
                Target existing = list.get(i);
                if (existing.contentView.get() == contentView) {
                    // 同一视图再次渲染：以最新实参替换已登记记录。
                    list.set(i, new Target(contentView, invocationTarget, method, args.clone()));
                    return;
                }
            }
            list.add(new Target(contentView, invocationTarget, method, args.clone()));
        }
    }

    /** Remove every stale registration for a view before it is rebound to another message. */
    public static synchronized void unregister(View contentView) {
        if (contentView == null) {
            return;
        }
        for (Map.Entry<String, List<Target>> entry : TARGETS.entrySet()) {
            List<Target> list = entry.getValue();
            synchronized (list) {
                for (Iterator<Target> it = list.iterator(); it.hasNext(); ) {
                    if (it.next().contentView.get() == contentView) {
                        it.remove();
                    }
                }
                if (list.isEmpty()) {
                    TARGETS.remove(entry.getKey(), list);
                }
            }
        }
    }

    /** The newest target for this exact view is the sole source of native cleanup arguments. */
    @Nullable
    private static Map.Entry<String, Target> latestRegistration(View host) {
        Map.Entry<String, Target> latest = null;
        for (Map.Entry<String, List<Target>> entry : TARGETS.entrySet()) {
            synchronized (entry.getValue()) {
                for (Target target : entry.getValue()) {
                    if (target.contentView.get() == host
                            && (latest == null
                            || target.registration > latest.getValue().registration)) {
                        latest = new java.util.AbstractMap.SimpleImmutableEntry<>(entry.getKey(), target);
                    }
                }
            }
        }
        return latest;
    }

    /** Whether injection can rely on a current native binding for this exact host. */
    static synchronized boolean hasNativeBinding(View host) {
        if (host == null) return false;
        Map.Entry<String, Target> latest = latestRegistration(host);
        return latest != null
                && stillBound(latest.getKey(), HookBridge.get().messageIdOf(host))
                && (java.lang.reflect.Modifier.isStatic(latest.getValue().method.getModifiers())
                || latest.getValue().invocationTarget.get() != null);
    }

    /** Restore only icon-owning hosts through their latest recorded native binding, on the UI thread. */
    static synchronized void restoreNativeHosts(java.util.Set<View> hosts) {
        if (HookRuntime.isActive()) throw new IllegalStateException("Runtime must be suspended");
        for (View host : hosts) {
            Map.Entry<String, Target> binding = latestRegistration(host);
            if (binding == null || !stillBound(binding.getKey(), HookBridge.get().messageIdOf(host))) {
                throw new IllegalStateException("Current native icon host binding unavailable");
            }
            Target latest = binding.getValue();
            Object receiver = latest.invocationTarget.get();
            if (!java.lang.reflect.Modifier.isStatic(latest.method.getModifiers()) && receiver == null) {
                throw new IllegalStateException("Native icon host receiver unavailable");
            }
            try {
                latest.method.invoke(receiver, latest.args);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native icon host restoration failed", failure);
            }
        }
    }

    public static synchronized void clear() {
        TARGETS.clear();
    }

    /**
     * 视图是否仍可承载该消息的渲染。
     *
     * <p>绑定未知（{@code null}）不等于已复用：映射表是容量上限 512 的 LRU，
     * 长列表滚动后旧条目会被淘汰。只有确认绑定了其他消息时才必须跳过。</p>
     *
     * <p>Embed 文本键：一个 Embed 视图按段（标题/描述/字段）登记了多个键，
     * 视图绑定只保留最后一个键。同一条消息（行 ID 相同）的键之间互视为有效；
     * 行 ID 变化说明视图已复用到其他消息，仍然拒绝。</p>
     */
    static boolean stillBound(@Nullable String expected, @Nullable String actual) {
        if (expected == null || actual == null || expected.equals(actual)) {
            return true;
        }
        if (HookBridge.isCardKey(expected) && HookBridge.isCardKey(actual)) {
            return HookBridge.cardRowOf(expected).equals(HookBridge.cardRowOf(actual));
        }
        return false;
    }

    /**
     * 用最新状态重新渲染该消息的全部视图（消息行 + 所有引用它的回复预览）。
     *
     * <p>视图已回收或确认已被复用到其他消息时会跳过并清理。注意：
     * “绑定未知”不等于“已复用”：{@code viewToMessageId} 是容量上限 512 的 LRU，
     * 长列表滚动后旧条目会被淘汰。若把未知也当成已复用而删除登记项，
     * 该消息就再也不会因译文完成而刷新，只有重新 bind（滚动）才恢复。</p>
     */
    public static synchronized void rerender(String messageId) {
        if (messageId == null) {
            return;
        }
        List<Target> list = TARGETS.get(messageId);
        if (list == null) {
            return;
        }
        synchronized (list) {
            for (Iterator<Target> it = list.iterator(); it.hasNext(); ) {
                Target target = it.next();
                View contentView = target.contentView.get();
                if (contentView == null) {
                    it.remove();
                    continue;
                }
                String bound = HookBridge.get().messageIdOf(contentView);
                if (!stillBound(messageId, bound)) {
                    // Skip stale replay, but retain owned-host metadata until a real native rebind.
                    // Removing it here could expose older whole-row arguments as the latest bind.
                    if (!InlineIconSpan.ownsHost(contentView)) it.remove();
                    continue;
                }
                Object invocationTarget = target.invocationTarget.get();
                if (!java.lang.reflect.Modifier.isStatic(target.method.getModifiers())
                        && invocationTarget == null) {
                    if (!InlineIconSpan.ownsHost(contentView)) it.remove();
                    continue;
                }
                RenderDispatcher.requestRerender(contentView, messageId,
                        invocationTarget, target.method, target.args,
                        () -> isCurrentRegistration(messageId, target));
            }
            if (list.isEmpty()) {
                TARGETS.remove(messageId, list);
            }
        }
    }

    /** A queued callback must not replay parameters from an earlier bind of the same view. */
    private static synchronized boolean isCurrentRegistration(String messageId, Target expected) {
        List<Target> current = TARGETS.get(messageId);
        if (current == null) {
            return false;
        }
        synchronized (current) {
            return current.contains(expected);
        }
    }

    /** Make room for one new key without discarding any live icon host's latest native binding. */
    private static void pruneIfNeeded() {
        if (TARGETS.size() < MAX_MESSAGES) {
            return;
        }
        // Scan every list first: a dead entry must be reclaimed before any live key is evicted.
        for (Map.Entry<String, List<Target>> entry : TARGETS.entrySet()) {
            List<Target> list = entry.getValue();
            synchronized (list) {
                for (Iterator<Target> it = list.iterator(); it.hasNext(); ) {
                    Target target = it.next();
                    // A missing receiver must keep its registration identity: dropping it could
                    // make an older whole-row snapshot look current for a still-owned icon host.
                    if (target.contentView.get() == null) {
                        it.remove();
                    }
                }
                if (list.isEmpty()) {
                    TARGETS.remove(entry.getKey(), list);
                }
            }
        }
        if (TARGETS.size() < MAX_MESSAGES) {
            return;
        }

        // This is a transient selection from TARGETS, never a second owner of native snapshots.
        Map<View, Map.Entry<String, Target>> newestByHost = new IdentityHashMap<>();
        for (Map.Entry<String, List<Target>> entry : TARGETS.entrySet()) {
            synchronized (entry.getValue()) {
                for (Target target : entry.getValue()) {
                    View host = target.contentView.get();
                    if (host == null || !InlineIconSpan.ownsHost(host)) continue;
                    Map.Entry<String, Target> previous = newestByHost.get(host);
                    if (previous == null || target.registration > previous.getValue().registration) {
                        newestByHost.put(host,
                                new java.util.AbstractMap.SimpleImmutableEntry<>(entry.getKey(), target));
                    }
                }
            }
        }
        Set<String> protectedKeys = new HashSet<>();
        for (Map.Entry<String, Target> latest : newestByHost.values()) {
            protectedKeys.add(latest.getKey());
        }
        for (String key : TARGETS.keySet()) {
            if (!protectedKeys.contains(key)) {
                TARGETS.remove(key);
                if (TARGETS.size() < MAX_MESSAGES) return;
            }
        }
        throw new IllegalStateException("Native icon host bindings occupy rerender registry capacity");
    }
}
