package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Tests the same ownership cleanup used by installation rollback and hot reload. */
public class HookCleanupTest {
    @Test public void successRemovesAllOwnershipExactlyOnce() {
        List<Object> owned = new ArrayList<>(Arrays.asList(new Object(), new Object()));
        List<Object> visited = new ArrayList<>();
        assertEquals(0, HookCleanup.remove(owned, visited::add));
        assertEquals(2, visited.size());
        assertTrue(owned.isEmpty());
    }

    @Test public void failureRetainsOnlyFailedHandlesAndStillAttemptsLaterHandles() {
        Object first = new Object(), failed = new Object(), last = new Object();
        List<Object> owned = new ArrayList<>(Arrays.asList(first, failed, last));
        List<Object> visited = new ArrayList<>();
        assertEquals(1, HookCleanup.remove(owned, handle -> {
            visited.add(handle);
            if (handle == failed) throw new IllegalStateException("private exception detail");
        }));
        assertEquals(Arrays.asList(first, failed, last), visited);
        assertEquals(1, owned.size());
        assertSame(failed, owned.get(0));
    }

    @Test public void multipleErrorsAreCountedWithoutThrowingOrLosingOwnership() {
        Object first = new Object(), last = new Object();
        List<Object> owned = new ArrayList<>(Arrays.asList(first, last));
        List<Object> visited = new ArrayList<>();
        assertEquals(2, HookCleanup.remove(owned, handle -> {
            visited.add(handle);
            throw new AssertionError("private error");
        }));
        assertEquals(Arrays.asList(first, last), visited);
        assertEquals(visited, owned);
    }

    @Test public void completedRemovalIsNotRepeatedOnExplicitNextCleanup() {
        Object first = new Object(), failed = new Object();
        List<Object> owned = new ArrayList<>(Arrays.asList(first, failed));
        assertEquals(1, HookCleanup.remove(owned, handle -> {
            if (handle == failed) throw new RuntimeException();
        }));
        List<Object> visited = new ArrayList<>();
        assertEquals(0, HookCleanup.remove(owned, visited::add));
        assertEquals(Arrays.asList(failed), visited);
        assertTrue(owned.isEmpty());
    }

    @Test public void emptyOwnershipDoesNotCallRemoval() {
        assertEquals(0, HookCleanup.remove(new ArrayList<Object>(), handle -> fail()));
    }
}
