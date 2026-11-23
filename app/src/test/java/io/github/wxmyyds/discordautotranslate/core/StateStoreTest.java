package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;

public class StateStoreTest {
    private final StateStore store = StateStore.get();
    @Before public void reset() { store.onNewGeneration(); }

    @Test public void rejectedOldContentCannotChangeNewAttempt() {
        StateStore.Entry old = store.obtain("row", "h1");
        TranslationTaskId a = store.begin(old);
        StateStore.Entry edited = store.obtain("row", "h2");
        TranslationTaskId b = store.begin(edited);
        assertNotSame(old, edited);
        assertFalse(store.complete(a, false, null, null));
        assertEquals(StateStore.Status.TRANSLATING, edited.status);
        assertTrue(store.owns(b));
        assertTrue(store.complete(b, true, "译文", null));
        assertEquals("译文", edited.translatedText);
    }

    @Test public void sameContentRetryHasDistinctAttemptOwnership() {
        StateStore.Entry entry = store.obtain("row", "hash");
        TranslationTaskId a = store.begin(entry);
        assertTrue(store.complete(a, true, null, "failed"));
        entry.status = StateStore.Status.IDLE;
        TranslationTaskId b = store.begin(entry);
        assertNotEquals(a, b);
        assertFalse(store.complete(a, false, null, null));
        assertEquals(StateStore.Status.TRANSLATING, entry.status);
        assertTrue(store.complete(b, true, "ok", null));
    }

    @Test public void newGenerationRejectsOldAttemptEvenWithIdenticalHash() {
        TranslationTaskId old = store.begin(store.obtain("row", "hash"));
        store.onNewGeneration();
        StateStore.Entry current = store.obtain("row", "hash");
        TranslationTaskId task = store.begin(current);
        assertFalse(store.complete(old, false, null, null));
        assertTrue(store.owns(task));
        assertEquals(StateStore.Status.TRANSLATING, current.status);
    }

    @Test public void everyStatusIsBoundedAndSourceEvictsWithRecord() {
        for (int i = 0; i < 512; i++) {
            StateStore.Entry entry = store.obtain("row" + i, "h" + i);
            entry.status = StateStore.Status.TRANSLATED;
            store.rememberSource(entry.messageId, new StateStore.Source(new Object(), false, null));
        }
        assertEquals(512, store.size());
        assertNotNull(store.peek("row0")); // LRU touch protects this record.
        store.obtain("extra", "hash");
        assertEquals(512, store.size());
        assertNotNull(store.source("row0"));
        assertNull(store.peek("row1"));
        assertNull(store.source("row1"));
        for (int i = 0; i < 600; i++) store.begin(store.obtain("pending" + i, "h"));
        assertEquals(512, store.size());
    }

    @Test public void evictedAndRecreatedRecordRejectsEvenSameVersionAttempt() {
        TranslationTaskId old = store.begin(store.obtain("row", "h"));
        for (int i = 0; i < 512; i++) store.obtain("other" + i, "h");
        StateStore.Entry rebound = store.obtain("row", "h");
        TranslationTaskId next = store.begin(rebound);
        assertFalse(store.complete(old, true, "old", null));
        assertTrue(store.owns(next));
        assertNull(rebound.translatedText);
    }

    @Test public void ownershipSweepCannotPromoteOldPendingWorkOverNewAdmission() {
        java.util.List<TranslationTaskId> old = new java.util.ArrayList<>();
        for (int i = 0; i < 512; i++) old.add(store.begin(store.obtain("row" + i, "h")));
        TranslationTaskId newest = store.begin(store.obtain("newest", "h"));
        for (TranslationTaskId task : old) store.owns(task);
        store.obtain("next", "h");
        assertTrue(store.owns(newest));
        assertNull(store.peek("row1"));
        assertNotNull(store.peek("newest"));
    }

    @Test public void disabledCurrentAttemptCanReturnIdleWithoutTouchingOriginalChoice() {
        StateStore.Entry entry = store.obtain("row", "h");
        TranslationTaskId task = store.begin(entry);
        entry.showingOriginal = true;
        assertTrue(store.complete(task, false, null, null));
        assertEquals(StateStore.Status.IDLE, entry.status);
        assertTrue(entry.showingOriginal);
        assertFalse(store.owns(task));
    }
}
