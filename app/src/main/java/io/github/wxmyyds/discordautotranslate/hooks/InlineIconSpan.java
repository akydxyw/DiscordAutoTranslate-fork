package io.github.wxmyyds.discordautotranslate.hooks;

import io.github.wxmyyds.discordautotranslate.core.ProjectLog;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.style.ImageSpan;
import android.view.View;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.StateStore;
import io.github.wxmyyds.discordautotranslate.core.Scheduler;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 消息正文末尾的行内翻译图标。
 *
 * <p>继承 {@link ImageSpan}（其父类为 {@code android.text.style.ReplacementSpan}），
 * 因此图标是文本流的一部分：它紧跟正文最后一个字符，最后一行剩余空间不足时
 * 由 {@code Layout} 自然换到下一行，不使用任何屏幕绝对坐标或负边距。</p>
 *
 * <h3>为何自绘而不依赖 ImageSpan 默认实现</h3>
 * <p>宿主 {@code MessageContentView} 是 {@code SimpleDraweeSpanTextView}，其
 * {@code DraweeSpanStringBuilder} 自带一套 drawee 自绘 span 体系。自绘可以绕开
 * 「标准 ImageSpan 绘制是否被兼容」这一不确定点。已通过真机实验确认：自定义
 * ReplacementSpan 能被正常绘制、能被点击命中。</p>
 *
 * <h3>为何不写 FontMetricsInt</h3>
 * <p>{@link #getSize} 刻意不修改 {@code fm}，因此图标不会撑高行距，
 * 满足「不得挤压、遮挡或截断正文」的要求。</p>
 *
 * <h3>旋转动画如何可靠重绘</h3>
 * <p>{@code ReplacementSpan} 本身不会触发重绘，必须由外部驱动。行内 span 拿不到
 * 宿主 {@code TextView}，因此注入时通过 {@link #bindHost} 记下宿主 View（弱引用），
 * 翻译中状态用 {@code View.postOnAnimation} 自驱动重绘。宿主是 Embed 卡片
 * （ViewGroup）时，每帧除宿主外还需逐个失效其内部的 TextView：父视图的
 * invalidate 不会重录子视图的显示列表，见 {@link #spinTargets}。</p>
 *
 * <p>「翻译中图标卡死不动」有四条已知成因，本类逐一防御：</p>
 * <ol>
 *   <li>瞬时 detach 不能终止动画。列表布局/复用过程中条目视图会被 detach
 *       再 attach；若 detach 时直接终止，动画就再也起不来。因此用两个状态区分：
 *       {@link #active}（动画应存在）与 {@link #running}（正在排帧）。detach 只把
 *       {@code running} 置假，{@link #active} 保持不变；重新附着时由
 *       {@link View.OnAttachStateChangeListener} 恢复排帧。</li>
 *   <li>过期链条必须能自行作废。每次「重新启动」都新建 ticker 实例并登记为
 *       当前链条（同一链条内重复投递自己的实例，每帧不产生新对象），旧实例醒来时
 *       发现已不是当前链条就直接退出，避免同一帧被重复 invalidate
 *       （{@code View.removeCallbacks} 无法撤销 {@code postOnAnimation}
 *       已投递到 Choreographer 的回调）。</li>
 *   <li>视角按时间计算：每帧固定增量会让转速随刷新率变化，
 *       暂停恢复后还会跳变。</li>
 *   <li>不旋转的新 span 也必须接管旧动画。翻译完成时新 span 不再旋转，
 *       若不停止旧 span，旧 ticker 会持续 invalidate 该视图。</li>
 * </ol>
 *
 * <p>线程约定：{@link #bindHost}、排帧与视图监听器相关操作都在主线程；
 * {@link #resetAll} 可能在热重载线程调用，内部会自行切主线程处理视图。</p>
 */
public final class InlineIconSpan extends ImageSpan {

    /** 图标视觉尺寸（dp）。需求要求 18–20dp。 */
    private static final int ICON_DP = 18;
    /** 图标前间距（dp）。需求要求与文字间距约 3–5dp。 */
    private static final int LEADING_GAP_DP = 3;
    /**
     * 点击区域最小宽度（dp）。需求要求 32–40dp。
     *
     * <p>字符命中是水平的（{@code getOffsetForHorizontal}），因此把 span 宽度
     * 作为点击宽度：图标仍为 18dp，多出的部分作为尾部留白，既扩大点击区，
     * 又不改变图标与文字的视觉间距。</p>
     */
    private static final int MIN_TAP_DP = 32;

    private static final int COLOR_TRANSLATING = 0xFFB9BBBE;
    private static final int COLOR_TRANSLATED = 0xFF5865F2;
    private static final int COLOR_ORIGINAL = 0xFF72767D;
    private static final int COLOR_FAILED = 0xFFED4245;

    /** 转一圈用时（毫秒）。视角按此换算，与刷新率解耦。 */
    private static final long SPIN_DURATION_MS = 900L;

    /** 收集失效目标的深度与数量上限，防御异常视图树。 */
    private static final int SPIN_TARGET_MAX_DEPTH = 16;
    private static final int SPIN_TARGET_MAX_COUNT = 512;
    private static final int HOST_VIEW_MAX_COUNT = 2048;

    /** Weak hosts own their current injected span; all states participate in confirmed teardown. */
    private static final Map<View, InlineIconSpan> BOUND = new WeakHashMap<>();
    private static final Object BOUND_LOCK = new Object();

    private final int iconPx;
    private final int leadingGapPx;
    private final int trailingGapPx;

    /** 是否处于翻译中（决定要不要转动）。构造后不再变化：状态变化会重建 span。 */
    private final boolean spinning;

    /** 宿主视图。弱引用：既不固定视图，也避免与 {@link #BOUND} 形成强引用环。 */
    @Nullable
    private WeakReference<View> host;

    /**
     * 每帧需要失效的视图集合。
     *
     * <p>正文路径的宿主就是持有图标的 TextView，失效宿主即可重画。Embed 路径的
     * 宿主是整张卡片（ViewGroup）：父视图 invalidate 不会重录子 TextView 的
     * 显示列表（Android 显示列表按视图各自的脏标记重录），图标所在的具体
     * TextView 不失效、角度变化就不可见。因此宿主为非 TextView 时，
     * 绑定时把卡片内全部 TextView 收集进来，逐个失效。</p>
     */
    private final List<WeakReference<android.widget.TextView>> spinTargets = new ArrayList<>();
    private WeakReference<android.widget.TextView> shadowTarget;
    private Object clickSpan;
    private WeakReference<android.text.Spannable> injectedText;
    private boolean targetsComplete = true;
    private int visitedViews;

    /** 当前视角（度）。 */
    private volatile float angle;
    /** 动画是否应当存在（翻译中即为真）。detach 不会改变它。 */
    private volatile boolean active;
    /** 是否正在排帧。detach 时置假，重新附着时恢复。 */
    private volatile boolean running;
    /** 本轮排帧的起始时间，用于按时间换算视角。 */
    private volatile long spinStartMs;
    /** 当前有效的 ticker 链条；旧链条醒来时据此判断自己已过期。 */
    @Nullable
    private volatile Runnable ticker;

    /** detach 只暂停、attach 恢复，避免列表复用造成的瞬时 detach 永久停掉动画。 */
    private final View.OnAttachStateChangeListener attachListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    if (active) {
                        startSpin();
                    }
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    running = false;
                }
            };

    private InlineIconSpan(Drawable drawable, float density, StateStore.Status status) {
        super(drawable);
        this.iconPx = InlineIconGeometry.dpToPx(ICON_DP, density);
        this.leadingGapPx = InlineIconGeometry.dpToPxAllowZero(LEADING_GAP_DP, density);
        // 尾部留白 = 让 span 总宽达到最小点击宽度；至少保留 1dp 避免紧贴。
        this.trailingGapPx = InlineIconGeometry.trailingGapForTapTarget(
                leadingGapPx, iconPx,
                InlineIconGeometry.dpToPx(MIN_TAP_DP, density),
                InlineIconGeometry.dpToPxAllowZero(1, density));
        this.spinning = status == StateStore.Status.TRANSLATING;
    }

    /**
     * 创建 span。Drawable 或状态无效时返回 null（调用方跳过注入）。
     *
     * @param icon      已按状态着色、且已独立复制的 Drawable
     * @param density   屏幕密度
     * @param status    当前翻译状态（配色已体现在 icon 上）
     */
    @Nullable
    static InlineIconSpan create(@Nullable Drawable icon, float density,
                                StateStore.Status status) {
        if (icon == null || status == null) {
            return null;
        }
        return new InlineIconSpan(icon, density, status);
    }

    /** 按状态给 Drawable 着色，并返回独立副本（模块缓存的 Drawable 会被多处复用）。 */
    @Nullable
    static Drawable tintForState(@Nullable Drawable source, StateStore.Status status,
                                 boolean showingOriginal) {
        if (source == null) {
            return null;
        }
        Drawable copy;
        try {
            Drawable.ConstantState cs = source.getConstantState();
            if (cs == null) {
                ProjectLog.warnOnce("icon.constantState.missing",
                        "icon duplication rejected: ConstantState missing");
                return null;
            }
            copy = cs.newDrawable().mutate();
        } catch (Throwable t) {
            ProjectLog.warnOnce("icon.copy.failed", "icon duplication failed error="
                    + t.getClass().getSimpleName());
            return null;
        }
        int color;
        switch (status) {
            case TRANSLATING:
                color = COLOR_TRANSLATING;
                break;
            case FAILED:
                color = COLOR_FAILED;
                break;
            case TRANSLATED:
            default:
                color = showingOriginal ? COLOR_ORIGINAL : COLOR_TRANSLATED;
                break;
        }
        try {
            copy.setTint(color);
        } catch (Throwable t) {
            ProjectLog.warnOnce("icon.tint.failed", "icon tint failed error="
                    + t.getClass().getSimpleName());
            return null;
        }
        return copy;
    }

    /**
     * 绑定宿主 View。
     *
     * <p>无论本 span 是否需要旋转，都先停掉该视图上原有的动画
     * span，否则翻译完成（新 span 不旋转）时旧 ticker 会持续 invalidate 该视图。</p>
     */
    void bindHost(@Nullable View view) {
        if (view == null) {
            return;
        }
        InlineIconSpan previous;
        synchronized (BOUND_LOCK) {
            previous = BOUND.put(view, this);
        }
        if (previous != null && previous != this) {
            previous.terminate();
            previous.clearShadowSpans();
        }

        this.host = new WeakReference<>(view);
        collectSpinTargets(view);
        if (!spinning) {
            return;
        }
        this.active = true;
        // 同一实例重复添加会被 View 去重（addOnAttachStateChangeListener）。
        view.addOnAttachStateChangeListener(attachListener);
        startSpin();
    }

    void ownText(android.text.Spannable text, Object clickSpan) {
        this.clickSpan = clickSpan;
        this.injectedText = new WeakReference<>(text);
    }

    static boolean ownsHost(View view) {
        synchronized (BOUND_LOCK) { return BOUND.containsKey(view); }
    }

    /** Collect native text owners, including the body renderer's private truncation shadow. */
    private void collectSpinTargets(View hostView) {
        spinTargets.clear();
        targetsComplete = true;
        visitedViews = 0;
        shadowTarget = null;
        if (hostView instanceof android.widget.TextView) {
            spinTargets.add(new WeakReference<>((android.widget.TextView) hostView));
            collectShadow(hostView);
            return;
        }
        collectTextViews(hostView, 0);
    }

    private void collectShadow(View view) {
        io.github.wxmyyds.discordautotranslate.core.HookConfiguration config =
                io.github.wxmyyds.discordautotranslate.core.HookConfiguration.activeConfiguration();
        org.json.JSONObject render = config == null ? null : config.hook("message.render");
        org.json.JSONObject target = render == null ? null : render.optJSONObject("target");
        if (target == null || !view.getClass().getName().equals(target.optString("class"))) return;
        try {
            String getter = render.getString("shadowViewGetter");
            java.lang.reflect.Method method = view.getClass().getDeclaredMethod(getter);
            method.setAccessible(true);
            Object shadow = method.invoke(view);
            if (!(shadow instanceof android.widget.TextView))
                throw new IllegalStateException("Native shadow text owner unavailable");
            shadowTarget = new WeakReference<>((android.widget.TextView) shadow);
            spinTargets.add(shadowTarget);
        } catch (Exception failure) {
            targetsComplete = false;
            ProjectLog.warnOnce("icon.shadow.failed", "native shadow capture failed error="
                    + failure.getClass().getSimpleName());
        }
    }

    /** Remove only module-owned spans from the private measurement buffer; keep its native text. */
    private void clearShadowSpans() {
        clearInjectedBuffer();
        android.widget.TextView shadow = shadowTarget == null ? null : shadowTarget.get();
        if (shadow == null) return;
        CharSequence text = shadow.getText();
        if (!(text instanceof android.text.Spanned)) return;
        InlineIconSpan[] icons = ((android.text.Spanned) text).getSpans(0, text.length(), InlineIconSpan.class);
        if (icons.length == 0) return;
        if (!(text instanceof android.text.Spannable))
            throw new IllegalStateException("Native shadow spans are immutable");
        android.text.Spannable mutable = (android.text.Spannable) text;
        for (InlineIconSpan icon : icons) {
            icon.clearInjectedBuffer();
            if (icon.clickSpan != null) mutable.removeSpan(icon.clickSpan);
            mutable.removeSpan(icon);
        }
    }

    private void clearInjectedBuffer() {
        android.text.Spannable text = injectedText == null ? null : injectedText.get();
        if (text == null) return;
        if (clickSpan != null) text.removeSpan(clickSpan);
        text.removeSpan(this);
    }

    /** 深度与数量有界，防御异常深的视图树。 */
    private void collectTextViews(View view, int depth) {
        if (!targetsComplete) return;
        if (depth > SPIN_TARGET_MAX_DEPTH || ++visitedViews > HOST_VIEW_MAX_COUNT
                || spinTargets.size() >= SPIN_TARGET_MAX_COUNT) {
            targetsComplete = false;
            return;
        }
        if (!(view instanceof android.view.ViewGroup)) {
            return;
        }
        android.view.ViewGroup group = (android.view.ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof android.widget.TextView) {
                spinTargets.add(new WeakReference<>((android.widget.TextView) child));
            }
            collectTextViews(child, depth + 1);
        }
    }

    /** 失效全部目标：已回收的引用顺手清理。 */
    private void invalidateSpinTargets() {
        for (java.util.Iterator<WeakReference<android.widget.TextView>> it =
                spinTargets.iterator(); it.hasNext(); ) {
            android.widget.TextView target = it.next().get();
            if (target == null) {
                it.remove();
                continue;
            }
            target.invalidate();
        }
    }

    /** 启动（或继续）排帧。需在主线程调用。 */
    private void startSpin() {
        if (running || !active) {
            return;
        }
        View v = host == null ? null : host.get();
        if (v == null || !v.isAttachedToWindow()) {
            // 尚未附着：不排帧，等 attach 监听器回调时再来。
            // 这里若强行把 running 置真，attach 回调会因 running 已为真而直接返回，
            // 动画就永远不会启动。
            return;
        }
        running = true;
        // 之前若暂停过，按已转过的角度回推起点，恢复时不跳变。
        long elapsed = (long) (angle / 360f * SPIN_DURATION_MS);
        spinStartMs = SystemClock.uptimeMillis() - elapsed;

        // 每次「重新启动」才新建一条链条；同一链条内重复投递自己的实例，
        // 因此每帧不产生新对象。旧链条醒来时据 ticker 判定自己已过期而自行退出。
        final Runnable chain = new Runnable() {
            @Override
            public void run() {
                onTick(this);
            }
        };
        ticker = chain;
        v.postOnAnimation(chain);
    }

    private void onTick(Runnable self) {
        // 已被新链条取代（stop 后又 start，或已被新 span 接管）：本链条就此终止。
        if (ticker != self) {
            return;
        }
        View v = host == null ? null : host.get();
        if (!active || v == null) {
            running = false;
            return;
        }
        if (!v.isAttachedToWindow()) {
            // 瞬时 detach（列表布局/复用）：只暂停排帧，保持 active，
            // 由 attach 监听器恢复。这里若终止动画，就会永久卡死。
            running = false;
            return;
        }
        angle = InlineIconGeometry.angleForElapsed(
                SystemClock.uptimeMillis() - spinStartMs, SPIN_DURATION_MS);
        invalidateSpinTargets();
        v.postOnAnimation(self);
    }

    /**
     * 终止动画并解除本 span 在宿主上的登记。需在主线程调用。
     */
    private void terminate() {
        active = false;
        running = false;
        Runnable previousTicker = ticker;
        ticker = null;
        View v = host == null ? null : host.get();
        host = null;
        if (v != null) {
            if (previousTicker != null) v.removeCallbacks(previousTicker);
            v.removeOnAttachStateChangeListener(attachListener);
        }
    }

    /** A real native bind relinquishes the old animation even when no new icon will be injected. */
    public static void stopForHost(View view) {
        InlineIconSpan previous;
        synchronized (BOUND_LOCK) { previous = BOUND.get(view); }
        if (previous != null) {
            previous.terminate();
            previous.clearShadowSpans();
        }
    }

    static void finishBinding(View view) {
        InlineIconSpan span;
        synchronized (BOUND_LOCK) { span = BOUND.get(view); }
        if (span == null) return;
        span.collectSpinTargets(view);
        if (span.targetsComplete && !span.hasLiveIcon()) {
            span.terminate();
            synchronized (BOUND_LOCK) { BOUND.remove(view, span); }
        }
    }

    private boolean hasLiveIcon() {
        for (WeakReference<android.widget.TextView> reference : spinTargets) {
            android.widget.TextView view = reference.get();
            if (view == null) continue;
            CharSequence text = view.getText();
            if (text instanceof android.text.Spanned
                    && ((android.text.Spanned) text).getSpans(0, text.length(),
                            InlineIconSpan.class).length != 0) return true;
        }
        return false;
    }

    /** Called only after runtime admission has closed and admitted business calls have drained. */
    public static boolean resetAll(long timeoutMs) {
        synchronized (BOUND_LOCK) {
            if (BOUND.isEmpty() && !PollIconTouch.hasAttachments()) return true;
        }
        return Scheduler.MainThread.runAndAwait(() -> {
            Map<View, InlineIconSpan> bindings;
            synchronized (BOUND_LOCK) { bindings = new java.util.IdentityHashMap<>(BOUND); }
            java.util.Set<View> restore = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<>());
            for (Map.Entry<View, InlineIconSpan> entry : bindings.entrySet()) {
                InlineIconSpan span = entry.getValue();
                span.terminate();
                if (!span.targetsComplete || span.hasLiveIcon()) restore.add(entry.getKey());
                span.clearShadowSpans();
            }
            PollIconTouch.detachAll();
            RerenderRegistry.restoreNativeHosts(restore);
            for (Map.Entry<View, InlineIconSpan> entry : bindings.entrySet()) {
                InlineIconSpan span = entry.getValue();
                span.collectSpinTargets(entry.getKey());
                if (!span.targetsComplete || span.hasLiveIcon()) {
                    throw new IllegalStateException("Native icon cleanup incomplete");
                }
            }
            synchronized (BOUND_LOCK) { BOUND.clear(); }
        }, timeoutMs);
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end,
                       Paint.FontMetricsInt fm) {
        // 刻意不写 fm：保持宿主原有行高，避免图标撑大行距。
        return InlineIconGeometry.spanWidth(leadingGapPx, iconPx, trailingGapPx);
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end,
                     float x, int top, int y, int bottom, Paint paint) {
        Drawable d = getDrawable();
        if (d == null) {
            return;
        }
        Paint.FontMetricsInt fm = paint.getFontMetricsInt();
        int centerY = InlineIconGeometry.verticalCenter(y, fm.ascent, fm.descent);
        int left = InlineIconGeometry.iconLeft(Math.round(x), leadingGapPx);
        int iconTop = InlineIconGeometry.iconTop(centerY, iconPx);
        d.setBounds(left, iconTop, left + iconPx, iconTop + iconPx);

        boolean rotated = spinning && angle != 0f;
        if (rotated) {
            canvas.save();
            canvas.rotate(angle, left + iconPx / 2f, iconTop + iconPx / 2f);
        }
        d.draw(canvas);
        if (rotated) {
            canvas.restore();
        }
    }

}
