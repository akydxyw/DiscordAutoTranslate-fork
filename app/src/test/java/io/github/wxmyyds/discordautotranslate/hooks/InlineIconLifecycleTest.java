package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import android.graphics.drawable.ColorDrawable;
import android.text.Spanned;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import io.github.wxmyyds.discordautotranslate.core.Scheduler;
import io.github.wxmyyds.discordautotranslate.core.StateStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Exercises real icon ownership and teardown with observable hosts, not Android text stubs. */
public class InlineIconLifecycleTest {
    private final List<FakeTextView> textViews = new ArrayList<>();
    private final List<View> owners = new ArrayList<>();

    @Before public void setUp() throws Exception {
        assertTrue(HookRuntime.suspendAndAwait(0L));
        RerenderRegistry.clear();
        HookBridge.get().reset();
        Scheduler.MainThread.clear();
        installDispatcher();
        assertTrue(InlineIconSpan.resetAll(0L));
    }

    @After public void tearDown() throws Exception {
        HookRuntime.suspend();
        installDispatcher();
        // Use the public lifecycle to release even ownership intentionally retained by failure tests.
        for (FakeTextView view : textViews) view.text = "native";
        for (View owner : owners) {
            if (owner instanceof FakeTree) ((FakeTree) owner).child = textViews.get(0);
            InlineIconSpan.finishBinding(owner);
        }
        try {
            assertTrue(InlineIconSpan.resetAll(0L));
        } finally {
            RerenderRegistry.clear();
            HookBridge.get().reset();
            Scheduler.MainThread.clear();
        }
    }

    private static void installDispatcher() throws Exception {
        // Reuse the existing core test seam; no new production bypass or Android Handler is needed.
        Method install = Scheduler.MainThread.class.getDeclaredMethod("setDirectDispatcherForTests");
        install.setAccessible(true);
        install.invoke(null);
    }

    private FakeTextView host() {
        FakeTextView view = new FakeTextView();
        textViews.add(view);
        owners.add(view);
        return view;
    }

    private static InlineIconSpan icon(StateStore.Status status) {
        InlineIconSpan span = InlineIconSpan.create(new ColorDrawable(), 1f, status);
        assertNotNull(span);
        return span;
    }

    private static void show(FakeTextView host, InlineIconSpan span) {
        // Injection binds the span before the native text write completes.
        span.bindHost(host);
        host.text = new IconText("translated \uFFFC", span);
        InlineIconSpan.finishBinding(host);
    }

    private static void register(FakeTextView host, NativeBinding receiver,
                                 Object content, Object options) throws Exception {
        HookBridge.get().bindView(host, "current-message");
        RerenderRegistry.register("current-message", host, receiver,
                NativeBinding.class.getMethod("bind", FakeTextView.class, Object.class,
                        Object.class), new Object[]{host, content, options});
    }

    @Test public void recycledHostWithoutIconStopsImmediatelyAndCannotRestartOnReattach() {
        FakeTextView host = host();
        show(host, icon(StateStore.Status.TRANSLATING));
        assertEquals(1, host.attachListeners.size());
        assertEquals(1, host.frames.size());
        Runnable staleFrame = host.frames.get(0);
        List<View.OnAttachStateChangeListener> staleListeners =
                new ArrayList<>(host.attachListeners);
        // Prove the captured callback really drives production animation before recycling it.
        staleFrame.run();
        assertEquals(1, host.invalidations);
        assertEquals(2, host.frames.size());

        InlineIconSpan.stopForHost(host);
        assertTrue(host.removedCallbacks.contains(staleFrame));
        assertTrue(host.attachListeners.isEmpty());
        int posted = host.frames.size();
        int invalidations = host.invalidations;
        // Choreographer may still deliver a callback already posted before removeCallbacks.
        staleFrame.run();
        host.reattach(staleListeners);
        assertEquals(posted, host.frames.size());
        assertEquals(invalidations, host.invalidations);

        host.text = "next message, no icon";
        InlineIconSpan.finishBinding(host);
        Scheduler.MainThread.clear();
        assertTrue("Clean native bind must relinquish ownership", InlineIconSpan.resetAll(0L));
    }

    @Test public void stoppedHostRetainsCleanupOwnershipUntilNativeWriteIsConfirmed()
            throws Exception {
        FakeTextView host = host();
        show(host, icon(StateStore.Status.TRANSLATING));
        InlineIconSpan.stopForHost(host);
        assertTrue(host.attachListeners.isEmpty());
        assertTrue(host.text instanceof Spanned);
        NativeBinding binding = new NativeBinding();
        register(host, binding, new Object(), new Object());

        assertTrue(InlineIconSpan.resetAll(0L));
        assertEquals("Stopping the ticker must not forget an icon still in native text", 1,
                binding.calls);
        assertEquals("native text", host.text);
    }

    @Test public void nonspinningReplacementStopsOldAnimationAndStillOwnsNativeCleanup()
            throws Exception {
        FakeTextView host = host();
        show(host, icon(StateStore.Status.TRANSLATING));
        Runnable staleFrame = host.frames.get(0);
        show(host, icon(StateStore.Status.TRANSLATED));
        assertTrue(host.attachListeners.isEmpty());
        assertTrue(host.removedCallbacks.contains(staleFrame));
        int posted = host.frames.size();
        staleFrame.run();
        assertEquals(posted, host.frames.size());

        NativeBinding nativeBinding = new NativeBinding();
        Object original = new Object();
        Object options = new Object();
        register(host, nativeBinding, original, options);
        assertTrue(InlineIconSpan.resetAll(0L));
        assertEquals(1, nativeBinding.calls);
        assertSame(original, nativeBinding.content);
        assertSame(options, nativeBinding.options);
        assertEquals("native text", host.getText());
    }

    @Test public void spinningReplacementOwnsOnlyOneLiveTicker() throws Exception {
        FakeTextView host = host();
        show(host, icon(StateStore.Status.TRANSLATING));
        Runnable oldFrame = host.frames.get(0);
        show(host, icon(StateStore.Status.TRANSLATING));
        assertEquals(1, host.attachListeners.size());
        assertTrue(host.removedCallbacks.contains(oldFrame));
        assertEquals(2, host.frames.size());
        Runnable currentFrame = host.frames.get(1);
        assertNotSame(oldFrame, currentFrame);
        oldFrame.run();
        assertEquals(2, host.frames.size());
        assertEquals(0, host.invalidations);
        currentFrame.run();
        assertEquals(3, host.frames.size());
        assertSame(currentFrame, host.frames.get(2));
        assertEquals(1, host.invalidations);
        NativeBinding binding = new NativeBinding();
        register(host, binding, new Object(), new Object());
        assertTrue(InlineIconSpan.resetAll(0L));
        assertEquals(1, binding.calls);
        assertTrue(host.attachListeners.isEmpty());
        currentFrame.run();
        assertEquals(3, host.frames.size());
    }

    @Test public void failedStatusAlsoRestoresThroughCapturedNativeArguments() throws Exception {
        FakeTextView host = host();
        show(host, icon(StateStore.Status.FAILED));
        assertTrue(host.frames.isEmpty());
        NativeBinding oldBinding = new NativeBinding();
        RerenderRegistry.register("old-message", host, oldBinding,
                NativeBinding.class.getMethod("bind", FakeTextView.class, Object.class,
                        Object.class), new Object[]{host, "old content", "old options"});
        NativeBinding nativeBinding = new NativeBinding();
        Object original = new Object();
        Object options = new Object();
        register(host, nativeBinding, original, options);
        assertTrue(InlineIconSpan.resetAll(0L));
        assertEquals(0, oldBinding.calls);
        assertEquals(1, nativeBinding.calls);
        assertSame(host, nativeBinding.host);
        assertSame(original, nativeBinding.content);
        assertSame(options, nativeBinding.options);
        assertFalse(nativeBinding.runtimeActive);
        Scheduler.MainThread.clear();
        assertTrue("Successful teardown must clear ownership", InlineIconSpan.resetAll(0L));
        assertEquals(1, nativeBinding.calls);
    }

    @Test public void nativeInvocationFailureRetainsOwnershipForRetryAndStopsInteractions()
            throws Exception {
        FakeTextView host = host();
        show(host, icon(StateStore.Status.TRANSLATING));
        Runnable staleFrame = host.frames.get(0);
        PollIconTouch.attach(host, (Spanned) host.text);
        assertNotNull(host.touchListener);
        NativeBinding nativeBinding = new NativeBinding();
        nativeBinding.fail = true;
        register(host, nativeBinding, new Object(), new Object());
        CharSequence injected = host.text;

        assertThrows(IllegalStateException.class, () -> InlineIconSpan.resetAll(0L));
        assertEquals(1, nativeBinding.calls);
        assertSame("Failure must not erase icons by manually replacing text", injected, host.text);
        assertTrue(host.attachListeners.isEmpty());
        assertTrue(host.removedCallbacks.contains(staleFrame));
        assertNull(host.touchListener);
        assertFalse(PollIconTouch.hasAttachments());
        int posted = host.frames.size();
        staleFrame.run();
        assertEquals(posted, host.frames.size());

        nativeBinding.fail = false;
        assertTrue(InlineIconSpan.resetAll(0L));
        assertEquals("Failed cleanup must retain its native owner for retry", 2, nativeBinding.calls);
        assertEquals("native text", host.text);
    }

    @Test public void nativeReturnWithIconStillPresentRejectsCleanupAndCanBeRetried()
            throws Exception {
        FakeTextView host = host();
        show(host, icon(StateStore.Status.TRANSLATED));
        NativeBinding nativeBinding = new NativeBinding();
        nativeBinding.leaveIcon = true;
        register(host, nativeBinding, new Object(), new Object());
        CharSequence injected = host.text;
        assertThrows(IllegalStateException.class, () -> InlineIconSpan.resetAll(0L));
        assertSame(injected, host.text);
        assertEquals(1, nativeBinding.calls);
        nativeBinding.leaveIcon = false;
        assertTrue(InlineIconSpan.resetAll(0L));
        assertEquals(2, nativeBinding.calls);
        assertEquals("native text", host.text);
    }

    @Test public void missingNativeBindingRejectsCleanupAndLaterRegistrationCanRecover()
            throws Exception {
        FakeTextView host = host();
        show(host, icon(StateStore.Status.TRANSLATED));
        CharSequence injected = host.text;
        assertThrows(IllegalStateException.class, () -> InlineIconSpan.resetAll(0L));
        assertSame(injected, host.text);
        NativeBinding nativeBinding = new NativeBinding();
        register(host, nativeBinding, new Object(), new Object());
        assertTrue(InlineIconSpan.resetAll(0L));
        assertEquals(1, nativeBinding.calls);
        assertEquals("native text", host.text);
    }

    @Test public void finishBindingRefreshesChildrenAfterNativeWrite() throws Exception {
        FakeTextView oldChild = host();
        FakeTextView newChild = host();
        FakeCard card = new FakeCard(oldChild);
        owners.add(card);
        InlineIconSpan span = icon(StateStore.Status.TRANSLATED);
        span.bindHost(card);
        card.child = newChild;
        newChild.text = new IconText("new child \uFFFC", span);
        InlineIconSpan.finishBinding(card);
        NativeCardBinding binding = new NativeCardBinding();
        HookBridge.get().bindView(card, "card-message");
        RerenderRegistry.register("card-message", card, binding,
                NativeCardBinding.class.getMethod("bind", FakeCard.class), new Object[]{card});

        assertTrue(InlineIconSpan.resetAll(0L));
        assertEquals("Newly written child must be observed and restored", 1, binding.calls);
        assertEquals("native card", newChild.text);
        assertEquals("", oldChild.text);
    }

    @Test public void incompleteTreeCaptureRejectsUnconfirmedCleanupAndRetainsOwner()
            throws Exception {
        FakeTextView deepChild = host();
        FakeTree root = new FakeTree(deepChild);
        for (int depth = 0; depth < 18; depth++) root = new FakeTree(root);
        owners.add(root);
        InlineIconSpan span = icon(StateStore.Status.TRANSLATED);
        span.bindHost(root);
        deepChild.text = new IconText("deep icon \uFFFC", span);
        InlineIconSpan.finishBinding(root);
        FakeTextView nativeChild = host();
        nativeChild.text = "native tree";
        NativeTreeBinding binding = new NativeTreeBinding(nativeChild);
        HookBridge.get().bindView(root, "deep-message");
        RerenderRegistry.register("deep-message", root, binding,
                NativeTreeBinding.class.getMethod("bind", FakeTree.class), new Object[]{root});

        assertThrows(IllegalStateException.class, () -> InlineIconSpan.resetAll(0L));
        assertEquals(1, binding.calls);
        assertTrue(deepChild.text instanceof Spanned);
        binding.flatten = true;
        assertTrue(InlineIconSpan.resetAll(0L));
        assertEquals("Unconfirmed cleanup must retain ownership for a verified retry", 2,
                binding.calls);
        assertSame(nativeChild, root.child);
        assertEquals("native tree", nativeChild.text);
    }

    @Test public void pollTouchIsRemovedOnNoIconRebindAndStandaloneTeardown() {
        FakeTextView host = host();
        Spanned withIcon = new IconText("poll \uFFFC", icon(StateStore.Status.TRANSLATED));
        PollIconTouch.attach(host, withIcon);
        View.OnTouchListener old = host.touchListener;
        assertNotNull(old);
        assertTrue(PollIconTouch.hasAttachments());
        assertFalse("Suspended callbacks must reject before reading an event", old.onTouch(host, null));
        PollIconTouch.attach(host, new IconText("native poll"));
        assertNull(host.touchListener);
        assertFalse(PollIconTouch.hasAttachments());

        PollIconTouch.attach(host, withIcon);
        assertNotNull(host.touchListener);
        assertTrue(InlineIconSpan.resetAll(0L));
        assertNull(host.touchListener);
        assertFalse(PollIconTouch.hasAttachments());
    }

    @Test public void emptyCleanupNeedsNoHandler() {
        Scheduler.MainThread.clear();
        assertTrue(InlineIconSpan.resetAll(0L));
    }

    @Test public void missingHandlerRetainsLiveOwnershipUntilDispatcherIsAvailable()
            throws Exception {
        FakeTextView host = host();
        show(host, icon(StateStore.Status.TRANSLATED));
        NativeBinding binding = new NativeBinding();
        register(host, binding, new Object(), new Object());
        Scheduler.MainThread.clear();
        assertFalse(InlineIconSpan.resetAll(0L));
        assertEquals(0, binding.calls);
        assertTrue(host.text instanceof Spanned);
        installDispatcher();
        assertTrue(InlineIconSpan.resetAll(0L));
        assertEquals(1, binding.calls);
        assertEquals("native text", host.text);
    }

    public static final class NativeBinding {
        int calls;
        boolean fail;
        boolean leaveIcon;
        boolean runtimeActive;
        FakeTextView host;
        Object content;
        Object options;

        public void bind(FakeTextView host, Object content, Object options) {
            calls++;
            runtimeActive = HookRuntime.isActive();
            this.host = host;
            this.content = content;
            this.options = options;
            if (fail) throw new IllegalStateException("native bind failed");
            if (!leaveIcon) host.text = "native text";
        }
    }

    public static final class NativeCardBinding {
        int calls;
        public void bind(FakeCard card) {
            calls++;
            card.child.text = "native card";
        }
    }

    public static final class NativeTreeBinding {
        final FakeTextView nativeChild;
        int calls;
        boolean flatten;
        NativeTreeBinding(FakeTextView nativeChild) { this.nativeChild = nativeChild; }
        public void bind(FakeTree root) {
            calls++;
            if (flatten) root.child = nativeChild;
        }
    }

    public static final class FakeTree extends ViewGroup {
        View child;
        FakeTree(View child) { super(null); this.child = child; }
        @Override public int getChildCount() { return 1; }
        @Override public View getChildAt(int index) { return index == 0 ? child : null; }
        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {}
    }

    public static final class FakeTextView extends TextView {
        CharSequence text = "";
        boolean attached = true;
        int invalidations;
        final List<Runnable> frames = new ArrayList<>();
        final List<Runnable> removedCallbacks = new ArrayList<>();
        final List<View.OnAttachStateChangeListener> attachListeners = new ArrayList<>();
        View.OnTouchListener touchListener;

        FakeTextView() { super(null); }
        @Override public CharSequence getText() { return text; }
        @Override public boolean isAttachedToWindow() { return attached; }
        @Override public void postOnAnimation(Runnable callback) { frames.add(callback); }
        @Override public boolean removeCallbacks(Runnable callback) {
            removedCallbacks.add(callback);
            return true;
        }
        @Override public void invalidate() { invalidations++; }
        @Override public void addOnAttachStateChangeListener(View.OnAttachStateChangeListener listener) {
            if (!attachListeners.contains(listener)) attachListeners.add(listener);
        }
        @Override public void removeOnAttachStateChangeListener(View.OnAttachStateChangeListener listener) {
            attachListeners.remove(listener);
        }
        @Override public void setOnTouchListener(View.OnTouchListener listener) {
            touchListener = listener;
        }
        void reattach(List<View.OnAttachStateChangeListener> staleListeners) {
            attached = false;
            for (View.OnAttachStateChangeListener listener : staleListeners)
                listener.onViewDetachedFromWindow(this);
            attached = true;
            for (View.OnAttachStateChangeListener listener : staleListeners)
                listener.onViewAttachedToWindow(this);
        }
    }

    public static final class FakeCard extends ViewGroup {
        FakeTextView child;
        FakeCard(FakeTextView child) { super(null); this.child = child; }
        @Override public int getChildCount() { return 1; }
        @Override public View getChildAt(int index) { return index == 0 ? child : null; }
        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {}
    }

    /** Minimal real span lookup; SpannableStringBuilder itself is a no-op in local Android stubs. */
    private static final class IconText implements Spanned {
        private final String text;
        private final InlineIconSpan[] spans;
        IconText(String text, InlineIconSpan... spans) { this.text = text; this.spans = spans; }
        @Override public int length() { return text.length(); }
        @Override public char charAt(int index) { return text.charAt(index); }
        @Override public CharSequence subSequence(int start, int end) { return text.subSequence(start, end); }
        @Override public String toString() { return text; }
        @Override public <T> T[] getSpans(int start, int end, Class<T> type) {
            List<T> matches = new ArrayList<>();
            for (InlineIconSpan span : spans) {
                if (type.isInstance(span) && start <= text.length() - 1 && end >= text.length() - 1)
                    matches.add(type.cast(span));
            }
            @SuppressWarnings("unchecked")
            T[] result = (T[]) Array.newInstance(type, matches.size());
            return matches.toArray(result);
        }
        @Override public int getSpanStart(Object span) {
            for (InlineIconSpan candidate : spans) if (candidate == span) return text.length() - 1;
            return -1;
        }
        @Override public int getSpanEnd(Object span) { return getSpanStart(span) < 0 ? -1 : text.length(); }
        @Override public int getSpanFlags(Object span) { return getSpanStart(span) < 0 ? 0 : SPAN_EXCLUSIVE_EXCLUSIVE; }
        @Override public int nextSpanTransition(int start, int limit, Class type) {
            int next = limit;
            for (InlineIconSpan span : spans) {
                if (type != null && !type.isInstance(span)) continue;
                int spanStart = getSpanStart(span);
                if (spanStart > start && spanStart < next) next = spanStart;
                if (text.length() > start && text.length() < next) next = text.length();
            }
            return next;
        }
    }
}
