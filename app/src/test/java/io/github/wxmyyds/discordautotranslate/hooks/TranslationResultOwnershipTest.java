package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;
import io.github.wxmyyds.discordautotranslate.core.StateStore;
import io.github.wxmyyds.discordautotranslate.core.TranslationTaskId;
import io.github.wxmyyds.discordautotranslate.core.TranslationCache;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;

/** Real bridge callback integration, not a scheduler-only collector. */
public class TranslationResultOwnershipTest {
    private final HookBridge bridge = HookBridge.get();
    private final StateStore store = StateStore.get();
    @Before public void setup() { store.onNewGeneration(); bridge.reset(); TranslationCache.get().clear(); }
    @After public void cleanup() { store.onNewGeneration(); bridge.reset(); TranslationCache.get().clear(); }

    @Test public void discardedOldResultCannotResetNewTranslationOrPopulateCache() {
        TranslationTaskId old = store.begin(store.obtain("row", "old"));
        StateStore.Entry next = store.obtain("row", "new");
        TranslationTaskId current = store.begin(next);
        bridge.onTranslateResult(old, false, "old result", null);
        assertEquals(StateStore.Status.TRANSLATING, next.status);
        assertTrue(store.owns(current));
        assertNull(TranslationCache.get().get("old"));
        bridge.onTranslateResult(current, true, "new result", null);
        assertEquals("new result", next.translatedText);
        assertEquals("new result", TranslationCache.get().get("new"));
    }

    @Test public void inactiveRuntimeCannotCreateStateOrRetryFailedWork() {
        HookRuntime.suspend();
        Object input = new ContentNodesTest.PrimitiveStructurableText("Must not translate");
        bridge.onMessageBound("row", input);
        assertNull(store.peek("row"));
        StateStore.Entry failed = store.obtain("row", "hash");
        failed.status = StateStore.Status.FAILED;
        bridge.retry("row");
        assertEquals(StateStore.Status.FAILED, failed.status);
    }

    @Test public void oldGenerationCannotResetNewStateOrWriteItsCache() {
        TranslationTaskId old = store.begin(store.obtain("row", "hash"));
        store.onNewGeneration();
        StateStore.Entry next = store.obtain("row", "hash");
        TranslationTaskId current = store.begin(next);
        bridge.onTranslateResult(old, false, null, null);
        assertEquals(StateStore.Status.TRANSLATING, next.status);
        bridge.onTranslateResult(old, true, "stale", null);
        assertNull(TranslationCache.get().get("hash"));
        assertTrue(store.owns(current));
    }
}
