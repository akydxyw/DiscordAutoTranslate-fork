package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import io.github.wxmyyds.discordautotranslate.core.Scheduler;
import io.github.wxmyyds.discordautotranslate.core.StateStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Shared Poll card choice and current membership; fake Primitive input, no Android views. */
public class PollCardStateTest {
    private static final String ROW = "123456789012345678";
    private static final String OTHER_ROW = "987654321098765432";
    private final HookBridge bridge = HookBridge.get();
    private final StateStore store = StateStore.get();
    private final Map<StateStore.Entry, String> originals = new IdentityHashMap<>();

    @Before
    public void setup() {
        ContentNodeTestProfile.activate(ContentNodesTest.class);
        store.onNewGeneration();
        Scheduler.reset();
        bridge.reset();
        RerenderRegistry.clear();
    }

    @After
    public void cleanup() throws Exception {
        try {
            Field field = Scheduler.class.getDeclaredField("sInstance");
            field.setAccessible(true);
            assertNull("Card binding, display and switching must not request translations",
                    field.get(null));
        } finally {
            bridge.reset();
            RerenderRegistry.clear();
            store.onNewGeneration();
            Scheduler.reset();
            ContentNodeTestProfile.clear();
        }
    }

    private String key(String row, String slot, String text) {
        return bridge.pollStateKey(row, slot,
                new ContentNodesTest.PrimitiveStructurableText(text));
    }

    private String key(String slot, String text) {
        return key(ROW, slot, text);
    }

    private StateStore.Entry state(String key, String original, StateStore.Status status,
                                   String translated, boolean entryOriginal) {
        StateStore.Entry entry = store.obtain(key,
                key.substring(key.lastIndexOf('\u0000') + 1));
        originals.put(entry, original);
        entry.status = status;
        entry.translatedText = translated;
        entry.showingOriginal = entryOriginal;
        return entry;
    }

    private StateStore.Entry translated(String key, String original, String translation,
                                        boolean entryOriginal) {
        return state(key, original, StateStore.Status.TRANSLATED, translation, entryOriginal);
    }

    private String original(StateStore.Entry entry) {
        assertTrue("Missing test source fixture", originals.containsKey(entry));
        return originals.get(entry);
    }

    private String display(String key, String original) {
        return PollTextProjection.display(original, store.peek(key), PollCardState.forKey(key));
    }

    private static void assertCurrent(PollCardState.Card card, String... keys) {
        assertEquals(Arrays.asList(keys), card.keys);
        for (String key : keys) assertSame(card, PollCardState.forKey(key));
    }

    @Test
    public void firstBindSnapshotsDeduplicatedCurrentKeysWithoutCreatingSegmentState() {
        String title = key("question", "Which option?");
        String first = key("answer:a", "First option");
        String second = key("answer:b", "Second option");
        List<String> input = new ArrayList<>(Arrays.asList(title, first, first, second));
        PollCardState.Card card = PollCardState.bind(ROW, title, input);
        assertEquals(title, card.titleKey);
        assertFalse(card.showingOriginal());
        assertCurrent(card, title, first, second);
        input.clear();
        assertCurrent(card, title, first, second);
        assertThrows(UnsupportedOperationException.class, () -> card.keys.add(title));
        for (String key : card.keys) assertNull(store.peek(key));
        assertNull(PollCardState.forKey(key("answer:missing", "not in this poll")));
        assertNull(PollCardState.forKey(ROW));
        assertNull(PollCardState.forKey(null));
        assertNull(PollCardState.forKey(bridge.embedStateKey(ROW,
                new ContentNodesTest.PrimitiveStructurableText("embed"))));
    }

    @Test
    public void onlyCurrentTitleCanToggleEvenWhenNoSegmentHasTranslation() {
        String title = key("question", "Which option?");
        String answer = key("answer:a", "First option");
        String missingTitle = key("question", "unbound title");
        PollCardState.Card card = PollCardState.bind(ROW, title, Arrays.asList(title, answer));
        for (String rejected : Arrays.asList(answer, missingTitle, ROW, null)) {
            assertNull(PollCardState.toggle(rejected));
            assertFalse(card.showingOriginal());
        }
        assertSame(card, PollCardState.toggle(title));
        assertTrue(card.showingOriginal());
        assertNull(PollCardState.toggle(answer));
        assertTrue(card.showingOriginal());
        assertSame(card, PollCardState.toggle(title));
        assertFalse(card.showingOriginal());
        assertNull(store.peek(title));
        assertNull(store.peek(answer));
    }

    @Test
    public void titleToggleSynchronizesAllTranslatedAnswersDespiteConflictingEntryChoices() {
        String title = key("question", "Which option?");
        String first = key("answer:a", "First option");
        String second = key("answer:b", "Image option description");
        StateStore.Entry question = translated(title, "Which option?", "哪个选项？", true);
        StateStore.Entry a = translated(first, "First option", "第一个选项", false);
        StateStore.Entry b = translated(second, "Image option description", "图片选项说明", true);
        PollCardState.Card card = PollCardState.bind(ROW, title, Arrays.asList(title, first, second));
        assertEquals("哪个选项？", display(title, original(question)));
        assertEquals("第一个选项", display(first, original(a)));
        assertEquals("图片选项说明", display(second, original(b)));

        bridge.toggleShowingOriginal(title);
        assertTrue(card.showingOriginal());
        for (StateStore.Entry entry : Arrays.asList(question, a, b)) {
            assertSame(original(entry), display(entry.messageId, original(entry)));
        }
        for (String answer : Arrays.asList(first, second)) {
            bridge.toggleShowingOriginal(answer);
            bridge.onIconClicked(answer);
            assertTrue(card.showingOriginal());
        }
        bridge.onIconClicked(title);
        assertFalse(card.showingOriginal());
        for (StateStore.Entry entry : Arrays.asList(question, a, b)) {
            assertSame(entry.translatedText, display(entry.messageId, original(entry)));
            assertEquals(StateStore.Status.TRANSLATED, entry.status);
        }
        assertTrue(question.showingOriginal);
        assertFalse(a.showingOriginal);
        assertTrue(b.showingOriginal);
    }

    @Test
    public void lateTranslationAndRebindDoNotResetOriginalSelection() {
        String title = key("question", "Which option?");
        String answer = key("answer:a", "Late answer");
        StateStore.Entry question = translated(title, "Which option?", "哪个选项？", false);
        StateStore.Entry late = state(answer, "Late answer", StateStore.Status.TRANSLATING,
                null, true);
        PollCardState.Card card = PollCardState.bind(ROW, title, Arrays.asList(title, answer));
        bridge.toggleShowingOriginal(title);
        assertTrue(card.showingOriginal());
        assertEquals("Late answer", display(answer, original(late)));

        late.translatedText = "迟到的答案译文";
        late.showingOriginal = false; // Scheduler completion changes the entry, not the card.
        late.status = StateStore.Status.TRANSLATED;
        assertSame(original(late), display(answer, original(late)));
        assertSame(original(question), display(title, original(question)));
        PollCardState.Card rebound = PollCardState.bind(ROW, title, Arrays.asList(title, answer));
        assertTrue(rebound.showingOriginal());
        assertSame(original(late), display(answer, original(late)));
        bridge.toggleShowingOriginal(title);
        assertSame(late.translatedText, display(answer, original(late)));
        assertSame(question.translatedText, display(title, original(question)));
    }

    @Test
    public void untranslatedLabelsRemainNativeWhileTranslatedSiblingsFollowCardChoice() {
        String title = key("question", "Which option?");
        String ready = key("answer:ready", "Ready answer");
        String idle = key("answer:idle", "已经是目标语言");
        String pending = key("answer:pending", "Pending answer");
        String failed = key("answer:failed", "Failed answer");
        String absent = key("answer:absent", "Unregistered state");
        StateStore.Entry question = state(title, "Which option?", StateStore.Status.IDLE,
                "unused stale translation", true);
        StateStore.Entry translated = translated(ready, "Ready answer", "已完成答案", true);
        StateStore.Entry targetLanguage = state(idle, "已经是目标语言", StateStore.Status.IDLE,
                null, false);
        StateStore.Entry translating = state(pending, "Pending answer", StateStore.Status.TRANSLATING,
                "unused partial result", false);
        StateStore.Entry failure = state(failed, "Failed answer", StateStore.Status.FAILED,
                "unused failed result", true);
        PollCardState.Card card = PollCardState.bind(ROW, title,
                Arrays.asList(title, ready, idle, pending, failed, absent));
        for (boolean original : new boolean[] {false, true, false}) {
            if (card.showingOriginal() != original) bridge.toggleShowingOriginal(title);
            assertEquals(original, card.showingOriginal());
            assertSame(original ? original(translated) : translated.translatedText,
                    display(ready, original(translated)));
            for (StateStore.Entry entry : Arrays.asList(question, targetLanguage, translating, failure)) {
                assertSame(original(entry), display(entry.messageId, original(entry)));
            }
            assertEquals("Unregistered state", display(absent, "Unregistered state"));
        }
        assertNull(store.peek(absent));
    }

    @Test
    public void repeatedBindTitleEditsAndListEditsPreserveRowChoiceButRemoveStaleKeys() {
        String oldTitle = key("question", "Old question");
        String newTitle = key("question", "Edited question");
        String oldAnswer = key("answer:a", "Old answer");
        String newAnswer = key("answer:a", "Edited answer");
        String removed = key("answer:removed", "Removed option");
        String retained = key("answer:retained", "Retained option");
        StateStore.Entry stale = translated(removed, "Removed option", "旧选项译文", false);
        PollCardState.bind(ROW, oldTitle, Arrays.asList(oldTitle, oldAnswer, removed, retained));
        bridge.toggleShowingOriginal(oldTitle);
        PollCardState.Card repeated = PollCardState.bind(ROW, oldTitle,
                Arrays.asList(oldTitle, oldAnswer, removed, retained));
        assertTrue(repeated.showingOriginal());
        PollCardState.Card edited = PollCardState.bind(ROW, newTitle,
                Arrays.asList(newTitle, retained, newAnswer));
        assertTrue(edited.showingOriginal());
        assertEquals(newTitle, edited.titleKey);
        assertCurrent(edited, newTitle, retained, newAnswer);
        for (String old : Arrays.asList(oldTitle, oldAnswer, removed)) {
            assertNull(PollCardState.forKey(old));
            assertNull(PollCardState.toggle(old));
            bridge.toggleShowingOriginal(old);
            bridge.onIconClicked(old);
            assertTrue(edited.showingOriginal());
        }
        assertSame(stale, store.peek(removed));
        assertSame(original(stale), display(removed, original(stale)));
        bridge.toggleShowingOriginal(newTitle);
        assertFalse(edited.showingOriginal());
        PollCardState.Card reordered = PollCardState.bind(ROW, newTitle,
                Arrays.asList(newTitle, newAnswer, retained));
        assertFalse(reordered.showingOriginal());
        assertCurrent(reordered, newTitle, newAnswer, retained);
    }

    @Test
    public void equalLabelsWithDifferentAnswerIdsKeepDistinctResultsAcrossReordering() {
        String title = key("question", "same label");
        String first = key("answer:a", "same label");
        String second = key("answer:b", "same label");
        assertNotEquals(title, first);
        assertNotEquals(first, second);
        StateStore.Entry a = translated(first, "same label", "答案 A", true);
        StateStore.Entry b = translated(second, "same label", "答案 B", false);
        assertNotSame(a, b);
        PollCardState.bind(ROW, title, Arrays.asList(title, first, second));
        assertEquals("答案 A", display(first, "same label"));
        assertEquals("答案 B", display(second, "same label"));
        bridge.toggleShowingOriginal(title);
        PollCardState.Card reordered = PollCardState.bind(ROW, title, Arrays.asList(title, second, first));
        assertTrue(reordered.showingOriginal());
        assertCurrent(reordered, title, second, first);
        assertEquals("same label", display(first, "same label"));
        assertEquals("same label", display(second, "same label"));
        bridge.toggleShowingOriginal(title);
        assertEquals("答案 A", display(first, "same label"));
        assertEquals("答案 B", display(second, "same label"));
    }

    @Test
    public void multipleCardsAndBodyEmbedStateAreIsolatedDespiteEqualLabelsAndIds() {
        String title = key("question", "same label");
        String answer = key("answer:a", "same label");
        String otherTitle = key(OTHER_ROW, "question", "same label");
        String otherAnswer = key(OTHER_ROW, "answer:a", "same label");
        StateStore.Entry first = translated(answer, "same label", "第一张卡片", true);
        StateStore.Entry second = translated(otherAnswer, "same label", "第二张卡片", false);
        String embed = bridge.embedStateKey(ROW,
                new ContentNodesTest.PrimitiveStructurableText("same label"));
        StateStore.Entry embedState = translated(embed, "same label", "Embed 译文", false);
        StateStore.Entry bodyState = translated(ROW, "same label", "正文译文", true);
        PollCardState.Card card = PollCardState.bind(ROW, title, Arrays.asList(title, answer));
        PollCardState.Card other = PollCardState.bind(OTHER_ROW, otherTitle,
                Arrays.asList(otherTitle, otherAnswer));
        assertNotSame(card, other);
        bridge.toggleShowingOriginal(title);
        assertTrue(card.showingOriginal());
        assertFalse(other.showingOriginal());
        assertSame(original(first), display(answer, original(first)));
        assertSame(second.translatedText, display(otherAnswer, original(second)));
        PollCardState.Card otherRebound = PollCardState.bind(OTHER_ROW, otherTitle,
                Arrays.asList(otherTitle, otherAnswer));
        assertFalse(otherRebound.showingOriginal());
        bridge.toggleShowingOriginal(otherTitle);
        assertTrue(card.showingOriginal());
        assertTrue(otherRebound.showingOriginal());
        bridge.toggleShowingOriginal(title);
        assertFalse(card.showingOriginal());
        assertTrue(otherRebound.showingOriginal());
        assertTrue(first.showingOriginal);
        assertFalse(second.showingOriginal);
        assertFalse(embedState.showingOriginal);
        assertTrue(bodyState.showingOriginal);
        assertEquals("Embed 译文", embedState.translatedText);
        assertEquals("正文译文", bodyState.translatedText);
        assertNull(PollCardState.forKey(embed));
        assertNull(PollCardState.forKey(ROW));
    }

    @Test
    public void invalidIncomingRootBlocksOldMembershipButPreservesChoiceOnSuccessfulBind() {
        String title = key("question", "Question");
        String answer = key("answer:a", "Answer");
        String otherTitle = key(OTHER_ROW, "question", "Other question");
        PollCardState.bind(ROW, title, Arrays.asList(title, answer));
        PollCardState.Card other = PollCardState.bind(OTHER_ROW, otherTitle, Arrays.asList(otherTitle));
        PollCardState.toggle(title);
        PollCardState.invalidate(ROW);
        assertNull(PollCardState.forKey(title));
        assertNull(PollCardState.forKey(answer));
        assertNull(PollCardState.toggle(title));
        assertSame(other, PollCardState.forKey(otherTitle));
        assertFalse(other.showingOriginal());
        assertTrue(PollCardState.bind(ROW, title, Arrays.asList(title, answer)).showingOriginal());
    }

    @Test
    public void aggregateTitleStatusWorksEvenWithMissingIndividualTitleState() {
        String title = key("question", "Question");
        String answer = key("answer:a", "Answer");
        PollCardState.Card card = PollCardState.bind(ROW, title, Arrays.asList(title, answer));
        assertEquals(StateStore.Status.IDLE, card.status());
        StateStore.Entry option = translated(answer, "Answer", "答案", false);
        assertNull(store.peek(title));
        assertEquals(StateStore.Status.TRANSLATED, card.status());
        option.status = StateStore.Status.FAILED;
        assertEquals(StateStore.Status.FAILED, card.status());
        option.status = StateStore.Status.TRANSLATING;
        assertEquals(StateStore.Status.TRANSLATING, card.status());
        bridge.onIconClicked(title);
        assertTrue(card.showingOriginal());
    }

    @Test
    public void clearRemovesAllGroupsAndResetsChoiceWithoutMutatingSegmentResults() {
        String title = key("question", "Question");
        String answer = key("answer:a", "Answer");
        String otherTitle = key(OTHER_ROW, "question", "Other question");
        StateStore.Entry entry = translated(answer, "Answer", "答案", true);
        PollCardState.bind(ROW, title, Arrays.asList(title, answer));
        PollCardState.bind(OTHER_ROW, otherTitle, Arrays.asList(otherTitle));
        PollCardState.toggle(title);
        PollCardState.toggle(otherTitle);
        PollCardState.clear();
        for (String key : Arrays.asList(title, answer, otherTitle)) {
            assertNull(PollCardState.forKey(key));
            assertNull(PollCardState.toggle(key));
        }
        assertSame(entry, store.peek(answer));
        assertTrue(entry.showingOriginal);
        assertSame(original(entry), display(answer, original(entry)));
        PollCardState.Card rebound = PollCardState.bind(ROW, title, Arrays.asList(title, answer));
        assertFalse(rebound.showingOriginal());
        assertSame(entry.translatedText, display(answer, original(entry)));
        PollCardState.Card other = PollCardState.bind(OTHER_ROW, otherTitle, Arrays.asList(otherTitle));
        assertFalse(other.showingOriginal());
    }

    @Test
    public void bridgeResetClearsGroupsEvenWhileSegmentResultsRemainStored() {
        String title = key("question", "Question");
        String answer = key("answer:a", "Answer");
        StateStore.Entry entry = translated(answer, "Answer", "答案", false);
        PollCardState.bind(ROW, title, Arrays.asList(title, answer));
        PollCardState.toggle(title);
        int generation = store.generation();
        bridge.reset();
        assertEquals(generation, store.generation());
        assertNull(PollCardState.forKey(title));
        assertNull(PollCardState.forKey(answer));
        assertNull(PollCardState.toggle(title));
        assertSame(entry, store.peek(answer));
        assertSame(original(entry), display(answer, original(entry)));
        assertFalse(PollCardState.bind(ROW, title, Arrays.asList(title, answer)).showingOriginal());
    }

    @Test
    public void newGenerationLifecycleClearsGroupMembershipChoiceAndOldTranslations() {
        String title = key("question", "Question");
        String answer = key("answer:a", "Answer");
        String otherTitle = key(OTHER_ROW, "question", "Other question");
        translated(answer, "Answer", "旧译文", false);
        PollCardState.bind(ROW, title, Arrays.asList(title, answer));
        PollCardState.bind(OTHER_ROW, otherTitle, Arrays.asList(otherTitle));
        PollCardState.toggle(title);
        PollCardState.toggle(otherTitle);
        int oldGeneration = store.generation();
        // ModuleEntry's generation lifecycle advances the store and resets bridge mappings.
        store.onNewGeneration();
        bridge.reset();
        assertEquals(oldGeneration + 1, store.generation());
        for (String key : Arrays.asList(title, answer, otherTitle)) {
            assertNull(store.peek(key));
            assertNull(PollCardState.forKey(key));
            assertNull(PollCardState.toggle(key));
        }
        PollCardState.Card rebound = PollCardState.bind(ROW, title, Arrays.asList(title, answer));
        assertFalse(rebound.showingOriginal());
        assertEquals("Answer", display(answer, "Answer"));
        translated(answer, "Answer", "新译文", true);
        assertEquals("新译文", display(answer, "Answer"));
    }
}
