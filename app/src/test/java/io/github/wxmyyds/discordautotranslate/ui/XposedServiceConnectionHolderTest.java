package io.github.wxmyyds.discordautotranslate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.List;

/** Only the nested pure subscription policy is initialized; no Android/service runtime. */
public class XposedServiceConnectionHolderTest {
    @Test
    public void bindingIsIdentityIdempotentAndIndependentAcrossOwners() {
        XposedServiceConnectionHolder.Subscriptions<Object> subscriptions =
                new XposedServiceConnectionHolder.Subscriptions<>();
        Object first = new String("equal");
        Object second = new String("equal");

        assertTrue(subscriptions.add(first)); // Newly subscribed: current replay is allowed.
        assertFalse(subscriptions.add(first)); // No duplicate subscription/current replay.
        assertTrue(subscriptions.add(second)); // Equal is not identical.
        assertEquals(2, subscriptions.snapshot().size());

        subscriptions.remove(first);
        subscriptions.remove(first);
        assertFalse(subscriptions.contains(first));
        assertTrue(subscriptions.contains(second));
        assertSame(second, subscriptions.snapshot().get(0));
        assertTrue(subscriptions.add(first)); // New lifecycle subscription permits replay.
        assertFalse(subscriptions.add(first));
    }

    @Test
    public void dispatchSnapshotSurvivesReentrantBindAndUnbind() {
        XposedServiceConnectionHolder.Subscriptions<Object> subscriptions =
                new XposedServiceConnectionHolder.Subscriptions<>();
        Object first = new Object();
        Object second = new Object();
        Object addedDuringDispatch = new Object();
        subscriptions.add(first);
        subscriptions.add(second);
        List<Object> snapshot = subscriptions.snapshot();

        subscriptions.remove(second);
        subscriptions.add(addedDuringDispatch);

        assertEquals(2, snapshot.size());
        assertSame(second, snapshot.get(1));
        assertFalse(subscriptions.contains(second)); // Dispatch's membership guard skips it.
        assertFalse(snapshot.contains(addedDuringDispatch)); // Not part of this event snapshot.
        assertTrue(subscriptions.contains(addedDuringDispatch));
        assertSame(addedDuringDispatch, subscriptions.snapshot().get(1));
    }

    @Test
    public void rebindDuringDispatchDoesNotDeliverOldEventToNewSubscription() {
        XposedServiceConnectionHolder.Subscriptions<Object> subscriptions =
                new XposedServiceConnectionHolder.Subscriptions<>();
        Object listener = new Object();
        subscriptions.add(listener);
        WeakReference<Object> oldSubscription = subscriptions.dispatchSnapshot().get(0);

        subscriptions.remove(listener);
        assertTrue(subscriptions.add(listener)); // Receives current replay as a new subscription.

        assertTrue(subscriptions.contains(listener));
        assertFalse(subscriptions.isBound(oldSubscription));
        assertTrue(subscriptions.isBound(subscriptions.dispatchSnapshot().get(0)));
    }

    @Test
    public void clearedWeakSubscriptionsArePrunedWithoutDependingOnGcTiming() throws Exception {
        XposedServiceConnectionHolder.Subscriptions<Object> subscriptions =
                new XposedServiceConnectionHolder.Subscriptions<>();
        Object released = new Object();
        Object retained = new Object();
        subscriptions.add(released);
        subscriptions.add(retained);
        Field field = subscriptions.getClass().getDeclaredField("listeners");
        field.setAccessible(true);
        List<?> references = (List<?>) field.get(subscriptions);
        ((WeakReference<?>) references.get(0)).clear();

        assertEquals(1, subscriptions.snapshot().size());
        assertSame(retained, subscriptions.snapshot().get(0));
        assertEquals(1, references.size());
        assertTrue(subscriptions.add(released));
        subscriptions.remove(retained);
        assertSame(released, subscriptions.snapshot().get(0));
    }
}
