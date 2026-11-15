package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import android.graphics.drawable.ColorDrawable;
import android.text.Spanned;
import android.view.View;
import android.widget.TextView;

import io.github.wxmyyds.discordautotranslate.core.StateStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Checks native restoration through captured methods and arguments, with real registry ownership. */
public class RerenderRegistryTest {
    private final List<IconHost> iconHosts = new ArrayList<>();

    @Before public void setUp() {
        assertTrue(HookRuntime.suspendAndAwait(0L));
        RerenderRegistry.clear();
        HookBridge.get().reset();
    }

    @After public void tearDown() {
        HookRuntime.suspend();
        for (IconHost host : iconHosts) {
            host.text = "native";
            InlineIconSpan.finishBinding(host);
            assertFalse(InlineIconSpan.ownsHost(host));
        }
        RerenderRegistry.clear();
        HookBridge.get().reset();
    }

    private static void register(String messageId, View host, NativeBinding receiver,
                                 Object content, Object options) throws Exception {
        Method method = NativeBinding.class.getMethod("bind", View.class, Object.class, Object.class);
        RerenderRegistry.register(messageId, host, receiver, method,
                new Object[]{host, content, options});
    }

    private static void restore(View host) {
        RerenderRegistry.restoreNativeHosts(Collections.singleton(host));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<RerenderRegistry.Target>> targets() throws Exception {
        java.lang.reflect.Field field = RerenderRegistry.class.getDeclaredField("TARGETS");
        field.setAccessible(true);
        return (Map<String, List<RerenderRegistry.Target>>) field.get(null);
    }

    private static RerenderRegistry.Target target(String key) throws Exception {
        return targets().get(key).get(0);
    }

    private static int capacity() throws Exception {
        java.lang.reflect.Field field = RerenderRegistry.class.getDeclaredField("MAX_MESSAGES");
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static Map<String, IconHost> fillRegistry(NativeBinding receiver) throws Exception {
        Map<String, IconHost> hosts = new HashMap<>();
        for (int i = 0; i < capacity(); i++) {
            String key = "ordinary-" + i;
            IconHost host = new IconHost();
            hosts.put(key, host);
            register(key, host, receiver, "content-" + i, "options-" + i);
        }
        return hosts;
    }

    private void ownIcon(IconHost host, StateStore.Status status) {
        iconHosts.add(host);
        InlineIconSpan span = InlineIconSpan.create(new ColorDrawable(), 1f, status);
        assertNotNull(span);
        span.bindHost(host);
        host.text = new IconText(span);
        InlineIconSpan.finishBinding(host);
        assertTrue(InlineIconSpan.ownsHost(host));
    }

    private static void clearReference(RerenderRegistry.Target target, String name) throws Exception {
        java.lang.reflect.Field field = RerenderRegistry.Target.class.getDeclaredField(name);
        field.setAccessible(true);
        ((WeakReference<?>) field.get(target)).clear();
    }

    private static boolean current(String key, RerenderRegistry.Target target) throws Exception {
        Method method = RerenderRegistry.class.getDeclaredMethod("isCurrentRegistration",
                String.class, RerenderRegistry.Target.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, key, target);
    }

    @Test public void refreshingAtCapacityKeepsEveryOtherKeyAndInvalidatesOldQueuedTarget()
            throws Exception {
        NativeBinding receiver = new NativeBinding();
        Map<String, IconHost> hosts = fillRegistry(receiver);
        String key = targets().keySet().iterator().next();
        RerenderRegistry.Target previous = target(key);
        java.util.Set<String> originalKeys = new java.util.HashSet<>(targets().keySet());
        register(key, hosts.get(key), receiver, "new content", "new options");
        assertEquals(originalKeys, targets().keySet());
        assertFalse(current(key, previous));
        HookBridge.get().bindView(hosts.get(key), key);
        restore(hosts.get(key));
        assertEquals("new content", receiver.content);
        assertEquals("new options", receiver.options);
    }

    @Test public void capacityPressurePreservesLatestTranslatingAndNonspinningNativeBindings()
            throws Exception {
        NativeBinding ordinary = new NativeBinding();
        Map<String, IconHost> hosts = fillRegistry(ordinary);
        // Protect the exact keys that arbitrary map-order eviction would discard first.
        List<String> keys = new ArrayList<>(targets().keySet());
        String translatingKey = keys.get(0);
        String translatedKey = keys.get(1);
        String olderSegment = keys.get(2);
        IconHost translating = hosts.get(translatingKey);
        IconHost translated = hosts.get(translatedKey);
        NativeBinding older = new NativeBinding();
        NativeBinding latestTranslating = new NativeBinding();
        NativeBinding latestTranslated = new NativeBinding();
        register(olderSegment, translating, older, "older card args", "older options");
        Object latestContent = new Object();
        Object latestOptions = new Object();
        register(translatingKey, translating, latestTranslating, latestContent, latestOptions);
        register(translatedKey, translated, latestTranslated, "translated args", "translated options");
        HookBridge.get().bindView(translating, translatingKey);
        HookBridge.get().bindView(translated, translatedKey);
        ownIcon(translating, StateStore.Status.TRANSLATING);
        ownIcon(translated, StateStore.Status.TRANSLATED);
        RerenderRegistry.Target translatingTarget = target(translatingKey);
        RerenderRegistry.Target translatedTarget = target(translatedKey);

        for (int i = 0; i < capacity() * 2; i++) {
            View ordinaryHost = new View(null);
            String key = "scroll-" + i;
            HookBridge.get().bindView(ordinaryHost, key);
            register(key, ordinaryHost, ordinary, "ordinary content", "ordinary options");
            assertEquals(capacity(), targets().size());
            assertTrue(current(translatingKey, translatingTarget));
            assertTrue(current(translatedKey, translatedTarget));
            if (i == 0) {
                assertFalse("Older keys for an owned host need no separate protection",
                        targets().containsKey(olderSegment));
            }
        }
        assertNull("Scrolling may evict the bounded identity mapping",
                HookBridge.get().directMessageIdOf(translating));
        assertNull(HookBridge.get().directMessageIdOf(translated));
        assertTrue(RerenderRegistry.hasNativeBinding(translating));
        assertTrue(RerenderRegistry.hasNativeBinding(translated));
        restore(translating);
        restore(translated);
        assertEquals(0, older.calls);
        assertEquals(1, latestTranslating.calls);
        assertSame(latestContent, latestTranslating.content);
        assertSame(latestOptions, latestTranslating.options);
        assertEquals(1, latestTranslated.calls);
        assertEquals("translated args", latestTranslated.content);
        assertEquals("native", translating.text);
        assertEquals("native", translated.text);
    }

    @Test public void capacityPruningReclaimsAllDeadViewsBeforeAnyLiveKey()
            throws Exception {
        NativeBinding receiver = new NativeBinding();
        Map<String, IconHost> hosts = fillRegistry(receiver);
        List<String> keys = new ArrayList<>(targets().keySet());
        String firstLiveKey = keys.get(0);
        RerenderRegistry.Target firstLive = target(firstLiveKey);
        String deadViewKey = keys.get(keys.size() - 1);
        String otherDeadViewKey = keys.get(keys.size() - 2);
        clearReference(target(deadViewKey), "contentView");
        clearReference(target(otherDeadViewKey), "contentView");
        register("new-key", new View(null), receiver, "new content", "new options");
        assertEquals(capacity() - 1, targets().size());
        assertFalse(targets().containsKey(deadViewKey));
        assertFalse(targets().containsKey(otherDeadViewKey));
        assertTrue(current(firstLiveKey, firstLive));
        HookBridge.get().bindView(hosts.get(firstLiveKey), firstLiveKey);
        restore(hosts.get(firstLiveKey));
        assertSame(hosts.get(firstLiveKey), receiver.host);
    }

    @Test public void capacityPruningCannotPromoteOlderSnapshotWhenLatestOwnedReceiverIsGone()
            throws Exception {
        NativeBinding receiver = new NativeBinding();
        Map<String, IconHost> hosts = fillRegistry(receiver);
        List<String> keys = new ArrayList<>(targets().keySet());
        String latestKey = keys.get(0);
        String olderKey = keys.get(1);
        IconHost host = hosts.get(latestKey);
        register(olderKey, host, receiver, "older arguments", "older options");
        register(latestKey, host, receiver, "latest arguments", "latest options");
        HookBridge.get().bindView(host, latestKey);
        ownIcon(host, StateStore.Status.TRANSLATED);
        RerenderRegistry.Target latest = target(latestKey);
        clearReference(latest, "invocationTarget");
        register("capacity-pressure", new View(null), receiver, "ordinary", "options");
        RerenderRegistry.rerender(latestKey);
        assertTrue(current(latestKey, latest));
        assertFalse(RerenderRegistry.hasNativeBinding(host));
        assertThrows(IllegalStateException.class, () -> restore(host));
        assertEquals(0, receiver.calls);
    }

    @Test public void nativeBindingAdmissionUsesExactHostAndLatestReceiver() throws Exception {
        View host = new View(null);
        View other = new View(null);
        NativeBinding receiver = new NativeBinding();
        assertFalse(RerenderRegistry.hasNativeBinding(null));
        assertFalse(RerenderRegistry.hasNativeBinding(host));
        register("message", host, receiver, "old", "options");
        HookBridge.get().bindView(host, "message");
        HookBridge.get().bindView(other, "message");
        assertTrue(RerenderRegistry.hasNativeBinding(host));
        assertFalse(RerenderRegistry.hasNativeBinding(other));
        register("message", host, receiver, "new", "options");
        clearReference(target("message"), "invocationTarget");
        assertFalse(RerenderRegistry.hasNativeBinding(host));
        assertThrows(IllegalStateException.class, () -> restore(host));
        assertEquals(0, receiver.calls);
    }

    @Test public void bodyRebindWithoutValidIdentityReleasesOldNativeArguments() throws Exception {
        View host = new View(null);
        NativeBinding receiver = new NativeBinding();
        register("old-message", host, receiver, "old content", "old options");
        HookBridge.get().bindView(host, "old-message");
        RerenderRegistry.Target previous = target("old-message");
        // The actual body-bind hook does these before inspecting the incoming ID.
        RerenderRegistry.unregister(host);
        HookBridge.get().unbindView(host);
        assertTrue(targets().isEmpty());
        assertFalse(current("old-message", previous));
        assertNull(HookBridge.get().directMessageIdOf(host));
        assertFalse(RerenderRegistry.hasNativeBinding(host));
        assertThrows(IllegalStateException.class, () -> restore(host));
        assertEquals(0, receiver.calls);
    }

    @Test public void queuedRegistrationsAreInvalidatedEvenWhenRebindKeepsSameKey() throws Exception {
        View host = new View(null);
        NativeBinding receiver = new NativeBinding();
        String primary = "123456789012345678\u0000embed\u0000card\u0000first";
        String secondary = "123456789012345678\u0000embed\u0000card\u0000second";
        register(primary, host, receiver, "old-primary", "old-options");
        register(secondary, host, receiver, "old-secondary", "old-options");
        RerenderRegistry.Target oldPrimary = target(primary);
        RerenderRegistry.Target oldSecondary = target(secondary);
        assertTrue(current(primary, oldPrimary));
        assertTrue(current(secondary, oldSecondary));
        RerenderRegistry.unregister(host);
        register(primary, host, receiver, "new-primary", "new-options");
        assertFalse(current(primary, oldPrimary));
        assertFalse(current(secondary, oldSecondary));
        assertTrue(current(primary, target(primary)));
        RerenderRegistry.unregister(host);
        assertFalse(current(primary, oldPrimary));
    }

    @Test public void queuedReplyParametersAreInvalidatedForSameReference() throws Exception {
        View host = new View(null);
        NativeBinding receiver = new NativeBinding();
        String key = "123456789012345678";
        register(key, host, receiver, "old-containing-message", "options");
        RerenderRegistry.Target previous = target(key);
        RerenderRegistry.unregister(host);
        register(key, host, receiver, "new-containing-message", "options");
        assertFalse(current(key, previous));
        assertTrue(current(key, target(key)));
    }

    @Test public void latestNativeTargetWinsOverOlderMappingsForSameHost() throws Exception {
        View host = new View(null);
        NativeBinding oldest = new NativeBinding();
        NativeBinding previous = new NativeBinding();
        NativeBinding current = new NativeBinding();
        register("old-message", host, oldest, new Object(), new Object());
        register("previous-message", host, previous, new Object(), new Object());
        Object originalContent = new Object();
        Object nativeOptions = new Object();
        register("current-message", host, current, originalContent, nativeOptions);
        HookBridge.get().bindView(host, "current-message");

        restore(host);
        assertEquals(0, oldest.calls);
        assertEquals(0, previous.calls);
        assertEquals(1, current.calls);
        assertSame(host, current.host);
        assertSame(originalContent, current.content);
        assertSame(nativeOptions, current.options);
        assertFalse("Original binding must run with module admission suspended", current.runtimeActive);
    }

    @Test public void latestCardSegmentWinsEvenWhenEveryOlderSegmentStillMatchesRow()
            throws Exception {
        View host = new View(null);
        String title = "123456789012345678\u0000embed\u0000title";
        String description = "123456789012345678\u0000embed\u0000description";
        NativeBinding titleBinding = new NativeBinding();
        NativeBinding descriptionBinding = new NativeBinding();
        register(title, host, titleBinding, "old title args", "old options");
        register(description, host, descriptionBinding, "current whole-card args", "current options");
        HookBridge.get().bindView(host, title);
        restore(host);
        assertEquals(0, titleBinding.calls);
        assertEquals(1, descriptionBinding.calls);
        assertEquals("current whole-card args", descriptionBinding.content);

        // Refreshing the first key must advance its registration, independent of map iteration.
        register(title, host, titleBinding, "newest whole-card args", "newest options");
        restore(host);
        assertEquals(1, titleBinding.calls);
        assertEquals(1, descriptionBinding.calls);
        assertEquals("newest whole-card args", titleBinding.content);
        assertEquals("newest options", titleBinding.options);
    }

    @Test public void sameMessageRefreshUsesLatestReceiverAndArgumentSnapshot() throws Exception {
        View host = new View(null);
        NativeBinding old = new NativeBinding();
        NativeBinding current = new NativeBinding();
        register("message", host, old, "old content", "old options");
        Object originalContent = new Object();
        Object nativeOptions = new Object();
        Object[] args = {host, originalContent, nativeOptions};
        RerenderRegistry.register("message", host, current,
                NativeBinding.class.getMethod("bind", View.class, Object.class, Object.class), args);
        args[1] = "mutated caller array";
        args[2] = "mutated options";
        HookBridge.get().bindView(host, "message");

        restore(host);
        assertEquals(0, old.calls);
        assertEquals(1, current.calls);
        assertSame(originalContent, current.content);
        assertSame(nativeOptions, current.options);
    }

    @Test public void mismatchedLatestRegistrationRejectsInsteadOfReplayingOlderMatchingTarget()
            throws Exception {
        View host = new View(null);
        NativeBinding olderMatching = new NativeBinding();
        NativeBinding latest = new NativeBinding();
        register("actual-message", host, olderMatching, new Object(), new Object());
        register("stale-message", host, latest, new Object(), new Object());
        HookBridge.get().bindView(host, "actual-message");
        assertFalse(RerenderRegistry.hasNativeBinding(host));
        assertThrows(IllegalStateException.class, () -> restore(host));
        assertEquals(0, olderMatching.calls);
        assertEquals(0, latest.calls);
    }

    @Test public void missingNativeTargetRejectsWithoutInvokingAnotherHostsBinding() throws Exception {
        View requested = new View(null);
        View other = new View(null);
        NativeBinding receiver = new NativeBinding();
        register("message", other, receiver, new Object(), new Object());
        HookBridge.get().bindView(requested, "message");
        assertThrows(IllegalStateException.class, () -> restore(requested));
        assertEquals(0, receiver.calls);
    }

    @Test public void failingNativeInvocationRejectsAndRetainsRegistrationForRetry() throws Exception {
        View host = new View(null);
        NativeBinding receiver = new NativeBinding();
        receiver.fail = true;
        Object originalContent = new Object();
        Object nativeOptions = new Object();
        register("message", host, receiver, originalContent, nativeOptions);
        HookBridge.get().bindView(host, "message");
        assertThrows(IllegalStateException.class, () -> restore(host));
        assertEquals(1, receiver.calls);
        receiver.fail = false;
        restore(host);
        assertEquals(2, receiver.calls);
        assertSame(originalContent, receiver.content);
        assertSame(nativeOptions, receiver.options);
    }

    @Test public void activeRuntimeRejectsNativeRestoreBeforeInvocation() throws Exception {
        View host = new View(null);
        NativeBinding receiver = new NativeBinding();
        register("message", host, receiver, new Object(), new Object());
        HookBridge.get().bindView(host, "message");
        HookRuntime.activate();
        assertThrows(IllegalStateException.class, () -> restore(host));
        assertEquals(0, receiver.calls);
        HookRuntime.suspend();
        restore(host);
        assertEquals(1, receiver.calls);
    }

    @Test public void staticNativeBindingUsesCapturedArgumentsWithoutReceiver() throws Exception {
        StaticHost host = new StaticHost();
        Object original = new Object();
        RerenderRegistry.register("message", host, null,
                RerenderRegistryTest.class.getMethod("staticNativeBind", StaticHost.class, Object.class),
                new Object[]{host, original});
        HookBridge.get().bindView(host, "message");
        assertTrue(RerenderRegistry.hasNativeBinding(host));
        restore(host);
        assertEquals(1, host.calls);
        assertSame(original, host.content);
    }

    @Test public void embedRebindUnregistersEveryOldSegmentEvenForSameRow() throws Exception {
        View view = new View(null);
        NativeBinding oldTitle = new NativeBinding();
        NativeBinding oldDescription = new NativeBinding();
        NativeBinding currentTitle = new NativeBinding();
        String title = "123456789012345678\u0000embed\u0000title";
        String description = "123456789012345678\u0000embed\u0000description";
        register(title, view, oldTitle, "old title", "old options");
        register(description, view, oldDescription, "old description", "old options");
        RerenderRegistry.unregister(view);
        // Reusing the title key must still use the newly captured original native parameters.
        register(title, view, currentTitle, "edited title", "new options");
        HookBridge.get().bindView(view, title);
        restore(view);
        assertEquals(0, oldTitle.calls);
        assertEquals(0, oldDescription.calls);
        assertEquals(1, currentTitle.calls);
        assertEquals("edited title", currentTitle.content);
        assertEquals("new options", currentTitle.options);
        // The next image-only card must have no native text registration left to replay.
        RerenderRegistry.unregister(view);
        assertThrows(IllegalStateException.class, () -> restore(view));
        assertEquals(1, currentTitle.calls);
    }

    @Test public void replyRebindingSameReferenceUsesLatestContainingMessageParameters() throws Exception {
        View preview = new View(null);
        NativeBinding receiver = new NativeBinding();
        String reference = "123456789012345678";
        register(reference, preview, receiver, "old-containing-message", "old options");
        RerenderRegistry.unregister(preview);
        register(reference, preview, receiver, "new-containing-message", "new options");
        HookBridge.get().bindView(preview, reference);
        restore(preview);
        assertEquals(1, receiver.calls);
        assertEquals("new-containing-message", receiver.content);
        assertEquals("new options", receiver.options);
        RerenderRegistry.unregister(preview);
        assertThrows(IllegalStateException.class, () -> restore(preview));
        assertEquals(1, receiver.calls);
    }

    @Test public void unknownBindingIsNotTreatedAsReuse() {
        assertTrue(RerenderRegistry.stillBound("123456789012345678", null));
    }

    @Test public void sameBindingIsStillValid() {
        assertTrue(RerenderRegistry.stillBound("123456789012345678", "123456789012345678"));
    }

    @Test public void reboundToAnotherMessageIsRejected() {
        assertFalse(RerenderRegistry.stillBound("123456789012345678", "987654321098765432"));
    }

    @Test public void missingExpectationSkipsValidation() {
        assertTrue(RerenderRegistry.stillBound(null, "987654321098765432"));
    }

    public static final class NativeBinding {
        int calls;
        boolean fail;
        boolean runtimeActive;
        View host;
        Object content;
        Object options;
        public void bind(View host, Object content, Object options) {
            calls++;
            runtimeActive = HookRuntime.isActive();
            this.host = host;
            this.content = content;
            this.options = options;
            if (fail) throw new IllegalStateException("native invocation failed");
            if (host instanceof IconHost) ((IconHost) host).text = "native";
        }
    }

    /** Detached translating hosts exercise BOUND ownership without Android frame handlers. */
    private static final class IconHost extends TextView {
        CharSequence text = "native";
        IconHost() { super(null); }
        @Override public CharSequence getText() { return text; }
        @Override public boolean isAttachedToWindow() { return false; }
        @Override public void addOnAttachStateChangeListener(OnAttachStateChangeListener listener) {}
        @Override public void removeOnAttachStateChangeListener(OnAttachStateChangeListener listener) {}
    }

    private static final class IconText implements Spanned {
        private final InlineIconSpan span;
        IconText(InlineIconSpan span) { this.span = span; }
        @Override public int length() { return 1; }
        @Override public char charAt(int index) { return '\uFFFC'; }
        @Override public CharSequence subSequence(int start, int end) {
            return "\uFFFC".subSequence(start, end);
        }
        @Override public <T> T[] getSpans(int start, int end, Class<T> type) {
            boolean matches = type.isInstance(span) && start <= 0 && end >= 1;
            @SuppressWarnings("unchecked")
            T[] result = (T[]) java.lang.reflect.Array.newInstance(type, matches ? 1 : 0);
            if (matches) result[0] = type.cast(span);
            return result;
        }
        @Override public int getSpanStart(Object tag) { return tag == span ? 0 : -1; }
        @Override public int getSpanEnd(Object tag) { return tag == span ? 1 : -1; }
        @Override public int getSpanFlags(Object tag) {
            return tag == span ? SPAN_EXCLUSIVE_EXCLUSIVE : 0;
        }
        @Override public int nextSpanTransition(int start, int limit, Class type) { return limit; }
    }

    public static final class StaticHost extends View {
        int calls;
        Object content;
        StaticHost() { super(null); }
    }

    public static void staticNativeBind(StaticHost host, Object content) {
        host.calls++;
        host.content = content;
    }
}
