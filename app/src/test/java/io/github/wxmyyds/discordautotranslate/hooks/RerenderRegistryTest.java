package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Collections;

/** Checks native restoration through captured methods and arguments, with real registry ownership. */
public class RerenderRegistryTest {
    @Before public void setUp() {
        assertTrue(HookRuntime.suspendAndAwait(0L));
        RerenderRegistry.clear();
        HookBridge.get().reset();
    }

    @After public void tearDown() {
        HookRuntime.suspend();
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
    private static RerenderRegistry.Target target(String key) throws Exception {
        java.lang.reflect.Field field = RerenderRegistry.class.getDeclaredField("TARGETS");
        field.setAccessible(true);
        java.util.Map<String, java.util.List<RerenderRegistry.Target>> targets =
                (java.util.Map<String, java.util.List<RerenderRegistry.Target>>) field.get(null);
        return targets.get(key).get(0);
    }

    private static boolean current(String key, RerenderRegistry.Target target) throws Exception {
        Method method = RerenderRegistry.class.getDeclaredMethod("isCurrentRegistration",
                String.class, RerenderRegistry.Target.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, key, target);
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
        }
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
