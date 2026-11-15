package io.github.wxmyyds.discordautotranslate.hooks;

import io.github.wxmyyds.discordautotranslate.core.ProjectLog;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import androidx.annotation.Nullable;
import java.lang.reflect.Method;

/** Native replay dispatcher: owns nested replay scope, epoch and layout handoff. */
public final class RenderDispatcher {
    private static final ThreadLocal<Boolean> RERENDERING = new ThreadLocal<>();
    private static Handler handler;
    private static long epoch;
    private RenderDispatcher() {}

    public static synchronized void install() {
        handler = new Handler(Looper.getMainLooper());
        epoch++;
    }

    public static synchronized void reset() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        handler = null;
        epoch++;
        RowRefresher.reset();
    }

    /** Delayed replay uses the owned handler, never a detached View action queue. */
    static synchronized boolean post(Runnable action) {
        if (handler == null) return false;
        long scheduledEpoch = epoch;
        return handler.post(() -> {
            synchronized (RenderDispatcher.class) {
                if (scheduledEpoch != epoch || handler == null) return;
            }
            if (HookRuntime.isActive()) action.run();
        });
    }

    /** 当前是否处于“重渲染”调用中。 */
    static boolean isRerendering() {
        return Boolean.TRUE.equals(RERENDERING.get());
    }

    /**
     * 投递重渲染：必须离开当前宿主布局过程再执行。
     *
     * <p>调用方（{@code onMessageBound}、点击回调）可能正处于 RecyclerView 的
     * measure/layout 中，此时直接重渲染会让新的布局请求被本轮遍历丢掉，
     * 表现为“文字变了但行高/空白行要等滚动才更新”。投递到视图消息队列后，
     * 下一次遍历再测量，结果立即生效。</p>
     *
     * <p>视图是否已被复用的判定见 {@link RerenderRegistry}；投递执行时由
     * 文本转换 Hook 按当前状态重新决定替换与否，因此不会把译文写错消息。</p>
     *
     * <p>排队期间视图可能已被 RecyclerView 复用到其他消息，因此执行前必须
     * 再校验一次：绑定已变成其他消息则跳过，不能把旧译文渲染到新消息上。
     * 绑定未知（{@code null}，通常是映射表 LRU 淘汰）不视为复用。</p>
     *
     * @param contentView 用于校验复用的正文视图，可为 null（不校验）
     * @param messageId   期望绑定的消息 ID，可为 null（不校验）
     * @param invocationTarget 调用 {@code method} 的接收者（正文视图或回复预览容器）
     */
    static void requestRerender(@Nullable View contentView, @Nullable String messageId,
                                @Nullable Object invocationTarget, @Nullable Method method,
                                @Nullable Object[] args,
                                java.util.function.BooleanSupplier registrationCurrent) {
        if (method == null || args == null
                || (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                && invocationTarget == null)) {
            return;
        }
        String bound = HookBridge.get().messageIdOf(contentView);
        ProjectLog.info("rerender queued targetValid="
                + (contentView == null || messageId == null
                || RerenderRegistry.stillBound(messageId, bound))
                + " viewAttached=" + (contentView != null && contentView.isAttachedToWindow()));
        if (contentView != null && messageId != null && !RerenderRegistry.stillBound(messageId, bound)) {
            return;
        }
        post(() -> {
            // A new bind replaces/removes the registry Target even when the new row
            // references the same message. Reject old queued whole-row parameters.
            if (!HookRuntime.isActive() || !registrationCurrent.getAsBoolean()) {
                return;
            }
            String currentBound = HookBridge.get().messageIdOf(contentView);
            if (contentView != null && messageId != null
                    && !RerenderRegistry.stillBound(messageId, currentBound)) {
                return;
            }
            ProjectLog.info("rerender dispatched viewAttached="
                    + (contentView != null && contentView.isAttachedToWindow())
                    + " viewClass=" + (contentView == null
                    ? "null" : contentView.getClass().getName()));
            invokeRerender(invocationTarget, contentView, messageId, method, args);
        });
    }

    private static void invokeRerender(@Nullable Object invocationTarget, @Nullable View contentView,
                                @Nullable String messageId, Method method, Object[] args) {
        if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                && invocationTarget == null) {
            return;
        }
        View view = contentView != null
                ? contentView
                : (invocationTarget instanceof View ? (View) invocationTarget : null);
        Boolean previous = RERENDERING.get();
        RERENDERING.set(Boolean.TRUE);
        try {
            method.invoke(invocationTarget, args);
        } catch (Throwable t) {
            ProjectLog.warnOnce("rerender.invoke.failed", "rerender invocation failed error="
                    + t.getClass().getSimpleName());
            return;
        } finally {
            if (previous == null) RERENDERING.remove(); else RERENDERING.set(previous);
        }
        ProjectLog.info("rerender method completed viewAttached="
                + (view != null && view.isAttachedToWindow()));
        // 回复引用的调用目标是容器，真正需要重新绑定的是内部正文视图。
        RowRefresher.postInvalidateRow(HookBridge.get()::messageIdOf, messageId, view);
    }

}
