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
        assertEquals(2, subscriptions.dispatchSnapshot().size());

        subscriptions.remove(first);
        subscriptions.remove(first);
        assertFalse(subscriptions.contains(first));
        assertTrue(subscriptions.contains(second));
        assertSame(second, subscriptions.dispatchSnapshot().get(0).get());
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
        List<WeakReference<Object>> snapshot = subscriptions.dispatchSnapshot();

        subscriptions.remove(second);
        subscriptions.add(addedDuringDispatch);

        assertEquals(2, snapshot.size());
        assertSame(second, snapshot.get(1).get());
        assertFalse(subscriptions.isBound(snapshot.get(1))); // Dispatch membership skips removed identity.
        assertFalse(snapshot.stream().anyMatch(ref -> ref.get() == addedDuringDispatch));
        assertTrue(subscriptions.contains(addedDuringDispatch));
        assertSame(addedDuringDispatch, subscriptions.dispatchSnapshot().get(1).get());
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

        assertEquals(1, subscriptions.dispatchSnapshot().size());
        assertSame(retained, subscriptions.dispatchSnapshot().get(0).get());
        assertEquals(1, references.size());
        assertTrue(subscriptions.add(released));
        subscriptions.remove(retained);
        assertSame(released, subscriptions.dispatchSnapshot().get(0).get());
    }
}
