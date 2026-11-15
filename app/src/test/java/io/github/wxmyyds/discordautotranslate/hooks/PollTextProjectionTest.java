package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import io.github.wxmyyds.discordautotranslate.core.Scheduler;
import io.github.wxmyyds.discordautotranslate.core.StateStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Native label projection reads the card choice, never requests or mutates segment state. */
public class PollTextProjectionTest {
    private static final String ROW = "123456789012345678";
    private static final String ORIGINAL = "  Original label\n😀  ";
    private static final String SOURCE_ORIGINAL = "stored original differs from native label";
    private final StateStore store = StateStore.get();
    private String titleKey;
    private String answerKey;

    @Before
    public void setup() {
        ContentNodeTestProfile.activate(ContentNodesTest.class);
        store.onNewGeneration();
        Scheduler.reset();
        PollCardState.clear();
        titleKey = key("question", "Question");
        answerKey = key("answer:a", ORIGINAL);
    }

    @After
    public void cleanup() {
        PollCardState.clear();
        store.onNewGeneration();
        Scheduler.reset();
        ContentNodeTestProfile.clear();
    }

    private String key(String slot, String text) {
        return HookBridge.get().pollStateKey(ROW, slot,
                new ContentNodesTest.PrimitiveStructurableText(text));
    }

    private PollCardState.Card card(boolean showingOriginal) {
        PollCardState.clear();
        PollCardState.Card card = PollCardState.bind(ROW, titleKey,
                Arrays.asList(titleKey, answerKey));
        if (showingOriginal) assertSame(card, PollCardState.toggle(titleKey));
        assertEquals(showingOriginal, card.showingOriginal());
        return card;
    }

    private StateStore.Entry state() {
        StateStore.Entry entry = store.obtain(answerKey, "retained-content-hash");
        store.rememberSource(answerKey, new StateStore.Source(SOURCE_ORIGINAL, false, null));
        return entry;
    }

    /** Read-only observation catches even unexpected entries under unrelated IDs. */
    private Map<?, ?> entriesSnapshot() throws Exception {
        Field field = StateStore.class.getDeclaredField("entries");
        field.setAccessible(true);
        return new HashMap<>((Map<?, ?>) field.get(store));
    }

    /** A projection must not even initialize the request scheduler. */
    private static void assertNoScheduler() throws Exception {
        Field field = Scheduler.class.getDeclaredField("sInstance");
        field.setAccessible(true);
        assertNull(field.get(null));
    }

    private static final class EntrySnapshot {
        private final String id;
        private final String hash;
        private final StateStore.Source source;
        private final Object original;
        private final String translated;
        private final StateStore.Status status;
        private final boolean showingOriginal;
        private final String error;

        EntrySnapshot(StateStore.Entry entry) {
            id = entry.messageId;
            hash = entry.contentHash;
            source = StateStore.get().source(id);
            assertNotNull(source);
            original = source.content;
            translated = entry.translatedText;
            status = entry.status;
            showingOriginal = entry.showingOriginal;
            error = entry.lastError;
        }

        void assertUnchanged(StateStore.Entry entry) {
            assertSame(id, entry.messageId);
            assertSame(hash, entry.contentHash);
            assertSame(source, StateStore.get().source(id));
            assertSame(original, source.content);
            assertSame(SOURCE_ORIGINAL, source.content);
            assertSame(translated, entry.translatedText);
            assertSame(status, entry.status);
            assertEquals(showingOriginal, entry.showingOriginal);
            assertSame(error, entry.lastError);
        }
    }

    @Test
    public void cardChoiceExclusivelyControlsAllStatusesAndConflictingEntryFlags() throws Exception {
        StateStore.Entry entry = state();
        entry.lastError = "retained diagnostic";
        Map<?, ?> before = entriesSnapshot();
        int generation = store.generation();
        for (boolean cardOriginal : new boolean[] {false, true}) {
            PollCardState.Card card = card(cardOriginal);
            for (StateStore.Status status : StateStore.Status.values()) {
                for (boolean entryOriginal : new boolean[] {false, true}) {
                    for (String translated : Arrays.asList(null, "", "  完整译文\n😀  ")) {
                        entry.status = status;
                        entry.showingOriginal = entryOriginal;
                        entry.translatedText = translated;
                        EntrySnapshot snapshot = new EntrySnapshot(entry);
                        String expected = status == StateStore.Status.TRANSLATED
                                && !cardOriginal && translated != null ? translated : ORIGINAL;
                        assertSame(expected, PollTextProjection.display(ORIGINAL, entry, card));
                        snapshot.assertUnchanged(entry);
                        assertEquals(cardOriginal, card.showingOriginal());
                        assertSame(card, PollCardState.forKey(answerKey));
                        assertSame(entry, store.peek(answerKey));
                        assertEquals(before, entriesSnapshot());
                        assertEquals(generation, store.generation());
                        assertNoScheduler();
                    }
                }
            }
        }
    }

    @Test
    public void noCardAlwaysKeepsNativeLabelEvenWhenSegmentHasTranslation() throws Exception {
        StateStore.Entry entry = state();
        entry.status = StateStore.Status.TRANSLATED;
        entry.translatedText = "existing translation must not select itself";
        entry.lastError = "retained";
        Map<?, ?> before = entriesSnapshot();
        int generation = store.generation();
        for (boolean entryOriginal : new boolean[] {false, true}) {
            entry.showingOriginal = entryOriginal;
            EntrySnapshot snapshot = new EntrySnapshot(entry);
            for (String original : Arrays.asList(null, "", "  ", ORIGINAL)) {
                assertSame(original, PollTextProjection.display(original, entry,
                        PollCardState.forKey(answerKey)));
            }
            snapshot.assertUnchanged(entry);
        }
        assertNull(PollCardState.forKey(answerKey));
        assertEquals(before, entriesSnapshot());
        assertEquals(generation, store.generation());
        assertNoScheduler();
    }

    @Test
    public void absentStateReturnsRawLabelsWithoutRequestsOrStateCreation() throws Exception {
        Map<?, ?> before = entriesSnapshot();
        int generation = store.generation();
        for (boolean showingOriginal : new boolean[] {false, true}) {
            PollCardState.Card card = card(showingOriginal);
            for (String original : Arrays.asList(null, "", "  ", ORIGINAL)) {
                for (int i = 0; i < 3; i++) {
                    assertSame(original, PollTextProjection.display(original,
                            store.peek(answerKey), card));
                }
            }
            assertEquals(showingOriginal, card.showingOriginal());
        }
        assertNull(store.peek(answerKey));
        assertEquals(before, entriesSnapshot());
        assertEquals(generation, store.generation());
        assertNoScheduler();
    }

    @Test
    public void nativeNullOrEmptyLabelNeverBecomesAPlaceholderOrResetsRetainedSource() throws Exception {
        StateStore.Entry entry = state();
        entry.lastError = "keep me";
        for (boolean cardOriginal : new boolean[] {false, true}) {
            PollCardState.Card card = card(cardOriginal);
            for (StateStore.Status status : StateStore.Status.values()) {
                entry.status = status;
                for (String original : Arrays.asList(null, "", "  ")) {
                    for (boolean entryOriginal : new boolean[] {false, true}) {
                        entry.showingOriginal = entryOriginal;
                        for (String translated : Arrays.asList(null, "", "译文")) {
                            entry.translatedText = translated;
                            EntrySnapshot snapshot = new EntrySnapshot(entry);
                            String expected = status == StateStore.Status.TRANSLATED
                                    && !cardOriginal && translated != null ? translated : original;
                            assertSame(expected, PollTextProjection.display(original, entry, card));
                            snapshot.assertUnchanged(entry);
                        }
                    }
                }
            }
            assertEquals(cardOriginal, card.showingOriginal());
        }
        assertSame(entry, store.peek(answerKey));
        assertNoScheduler();
    }

    @Test
    public void projectingMissingSlotDoesNotTouchAnExistingOtherSlot() throws Exception {
        StateStore.Entry entry = state();
        entry.status = StateStore.Status.TRANSLATED;
        entry.translatedText = "另一答案译文";
        entry.showingOriginal = true;
        entry.lastError = "retained";
        PollCardState.Card card = card(false);
        EntrySnapshot snapshot = new EntrySnapshot(entry);
        Map<?, ?> before = entriesSnapshot();
        assertSame(ORIGINAL, PollTextProjection.display(ORIGINAL, store.peek(titleKey), card));
        assertNull(store.peek(titleKey));
        assertSame(entry, store.peek(answerKey));
        snapshot.assertUnchanged(entry);
        assertEquals(before, entriesSnapshot());
        assertFalse(card.showingOriginal());
        assertNoScheduler();
    }
}
