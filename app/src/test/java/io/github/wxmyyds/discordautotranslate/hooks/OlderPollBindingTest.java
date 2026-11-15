package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Shipped 340013 paths against native-named fields; no Android rendering or hook installation claim. */
public class OlderPollBindingTest {
    private static final String ROW = "123456789012345678";
    private static final String OTHER_ROW = "987654321098765432";
    private JSONObject shipped;
    private JSONObject questionSpec;
    private JSONObject answerSpec;

    @Before
    public void setup() throws Exception {
        String json = Files.readString(Path.of("src/main/assets/hook-config/340013.json"));
        HookConfiguration.parse(json, 340013);
        shipped = new JSONObject(json).getJSONObject("hooks");
        questionSpec = shipped.getJSONObject("poll.bind");
        answerSpec = new JSONObject(shipped.getJSONObject("poll.answerBind").toString());
        // Only native binding class names change; every field path comes from the shipped JSON.
        answerSpec.put("textBindingClass", PollTextAndImageAnswerViewBinding.class.getName());
        answerSpec.put("imageBindingClass", PollImageOnlyAnswerViewBinding.class.getName());
        ContentNodeTestProfile.activate(ContentNodesTest.class);
        PollCardState.clear();
    }

    @After
    public void cleanup() {
        PollCardState.clear();
        ContentNodeTestProfile.clear();
    }

    private static final class PollMedia {
        private Object text;
        private final Set<String> attachmentIds = Collections.singleton("attachment-id");
        private final Object emoji = new Object();
        private final Long stickerId = 123L;
        PollMedia(Object text) { this.text = text; }
    }

    private static final class PollAnswer {
        private final Object answerId;
        private Object pollMedia;
        private final String votes = new String("17");
        private final int votesPercentage = 40;
        private final Boolean isSelected = Boolean.TRUE;
        private final boolean didSelfVote = true;
        PollAnswer(Object id, Object text) { answerId = id; pollMedia = new PollMedia(text); }
    }

    private static final class PollAnswerAccessory {
        private Object messageId;
        private Object answer;
        private final List<Object> attachments = Collections.singletonList(new Object());
        private final boolean canSelectMultipleAnswers = true;
        private final boolean hasVoted = true;
        private final String tapAccessibilityLabel = "Select answer";
        PollAnswerAccessory(Object row, Object id, Object text) {
            messageId = row;
            answer = new PollAnswer(id, text);
        }
    }

    private static final class PollData {
        private Object question;
        private final List<PollAnswer> answers = Collections.singletonList(
                new PollAnswer("data-only-decoy", "not an answer accessory"));
        private final boolean canSelectMultipleAnswers = true;
        private final boolean hasVoted = true;
        private final String promptLabel = "Select an answer";
        PollData(Object text) { question = new PollMedia(text); }
    }

    private static final class PollMessageAccessory {
        private Object messageId;
        private Object data;
        private Object answers;
        PollMessageAccessory(Object row, Object text, Object answers) {
            messageId = row;
            data = new PollData(text);
            this.answers = answers;
        }
    }

    private static final class Sink {
        private final String nativeText;
        private final String contentDescription;
        Sink(String text) { nativeText = text; contentDescription = text; }
    }

    private static final class PollRecyclerViewBinding {
        private final Sink questionText = new Sink("native question");
        private final Sink promptText = new Sink("Select an answer");
        private final Object answers = new Object();
        private final Object rootView = new Object();
        private final Object primaryAction = new Object();
    }

    /** PollPresenter inherits its private binding from BasePollWithRecyclerView in 340013. */
    private static class BasePollWithRecyclerView {
        private Object binding = new PollRecyclerViewBinding();
    }
    private static final class PollPresenter extends BasePollWithRecyclerView {}
    private static final class PollViewHolder {
        private Object pollView = new PollPresenter();
    }

    private static class PollTextAndImageAnswerViewBinding {
        private final Sink label = new Sink("native answer");
        private final Sink image = new Sink("native image description");
        private final Sink voteCounts = new Sink("17 votes");
        private final Sink votePercentage = new Sink("40%");
        private final Object checkbox = new Object();
        private final Object rootView = new Object();
    }
    private static final class PollImageOnlyAnswerViewBinding {
        private final Sink image = new Sink("native image description");
        private final Sink voteCounts = new Sink("17 votes");
        private final Sink votePercentage = new Sink("40%");
        private final Object selectedIcon = new Object();
        private final Object rootView = new Object();
    }
    private static final class UnknownBinding {
        private final Sink label = new Sink("must not be guessed");
        private final Sink image = new Sink("must not be guessed");
    }
    private static final class DerivedTextBinding extends PollTextAndImageAnswerViewBinding {}
    private static final class MissingLabelBinding {
        private final Sink image = new Sink("not a label");
    }
    private static final class MissingImageBinding {
        private final Sink label = new Sink("not an image");
    }

    /** Concrete answer holders inherit private binding from PollAnswerViewHolder. */
    private static class PollAnswerViewHolder {
        private final Object binding;
        PollAnswerViewHolder(Object binding) { this.binding = binding; }
    }
    private static final class AnswerHolder extends PollAnswerViewHolder {
        AnswerHolder(Object binding) { super(binding); }
    }
    private static final class PollAnswerAdapter {
        private Object items;
        private final Object onTapAnswer = new Object();
        private final Object onLongPressImage = new Object();
        PollAnswerAdapter(Object items) { this.items = items; }
    }

    /** Wrong shapes keep tempting alternate getters, which must never be consulted. */
    private static final class GetterOnly {
        public String getMessageId() { throw new AssertionError("No getter strategy"); }
        public String getAnswerId() { throw new AssertionError("No getter strategy"); }
        public String getText() { throw new AssertionError("No getter strategy"); }
        public List<?> getItems() { throw new AssertionError("No getter strategy"); }
        public Object getBinding() { throw new AssertionError("No getter strategy"); }
        public Object getPollView() { throw new AssertionError("No getter strategy"); }
    }

    private static List<Object> rootArgs(Object accessory) {
        return Arrays.asList(accessory, new Object(), new Object(), new Object());
    }
    private static List<Object> answerArgs(Object holder, Object position) {
        return Arrays.asList(holder, position);
    }
    private PollBinding question(PollViewHolder holder, PollMessageAccessory accessory) throws Exception {
        return PollBinding.question(holder, rootArgs(accessory), questionSpec);
    }
    private PollBinding answer(PollAnswerAdapter adapter, AnswerHolder holder, Object position)
            throws Exception {
        return PollBinding.answer(adapter, answerArgs(holder, position), answerSpec);
    }
    private static String key(String row, String slot, String text) {
        return HookBridge.get().pollStateKey(row, slot,
                new ContentNodesTest.PrimitiveStructurableText(text));
    }
    private static String key(PollBinding binding) {
        return key(binding.messageId, binding.slot, binding.text);
    }
    private String optionKey(Object option) throws Exception {
        return key(ROW, PollBinding.optionSlot(option, ROW, answerSpec),
                PollBinding.optionText(option, answerSpec));
    }

    @Test
    public void fixturesReplaceOnlyBindingClassNamesNotShippedPaths() throws Exception {
        JSONObject restored = new JSONObject(answerSpec.toString());
        JSONObject nativeSpec = shipped.getJSONObject("poll.answerBind");
        restored.put("textBindingClass", nativeSpec.getString("textBindingClass"));
        restored.put("imageBindingClass", nativeSpec.getString("imageBindingClass"));
        assertTrue(nativeSpec.similar(restored));
        assertSame(shipped.getJSONObject("poll.bind"), questionSpec);
    }

    @Test
    public void rootEnumeratesAllAccessoriesIncludingHiddenOptionsWithoutTouchingCallbacks()
            throws Exception {
        String raw = new String("  Which option?\n😀  ");
        PollAnswerAccessory first = new PollAnswerAccessory(ROW, "a", "visible option");
        PollAnswerAccessory hidden = new PollAnswerAccessory(ROW, "b", "hidden option");
        PollAnswerAccessory last = new PollAnswerAccessory(ROW, "c", "last hidden option");
        List<PollAnswerAccessory> all = Arrays.asList(first, hidden, last);
        PollMessageAccessory accessory = new PollMessageAccessory(ROW, raw, all);
        PollViewHolder holder = new PollViewHolder();
        Object presenter = holder.pollView;
        Object inheritedBinding = ((BasePollWithRecyclerView) presenter).binding;
        PollRecyclerViewBinding views = (PollRecyclerViewBinding) inheritedBinding;
        List<Object> args = rootArgs(accessory);
        Object[] callbacks = {args.get(1), args.get(2), args.get(3)};
        PollBinding binding = PollBinding.question(holder, args, questionSpec);
        assertEquals(ROW, PollBinding.questionRow(args, questionSpec));
        assertSame(raw, binding.text);
        assertEquals("question", binding.slot);
        assertSame(presenter, binding.host);
        assertSame(views.questionText, binding.receiver);
        assertSame(views.questionText, PollBinding.receiver(holder, Collections.emptyList(),
                questionSpec, false));
        assertSame(all, PollBinding.options(args, questionSpec));
        List<String> keys = new ArrayList<>();
        keys.add(key(binding));
        for (Object option : PollBinding.options(args, questionSpec)) keys.add(optionKey(option));
        assertEquals(Arrays.asList(key(binding), key(ROW, "answer:a", "visible option"),
                key(ROW, "answer:b", "hidden option"), key(ROW, "answer:c", "last hidden option")), keys);
        // Arrange the shared card seam from the complete root enumeration, not visible rows.
        PollCardState.Card card = PollCardState.bind(ROW, key(binding), keys);
        assertEquals(4, card.keys.size());
        assertSame(card, PollCardState.forKey(optionKey(hidden)));
        assertTrue(PollCardState.canReplay(optionKey(last), optionKey(last)));
        PollAnswerAdapter visibleOnly = new PollAnswerAdapter(Collections.singletonList(first));
        assertEquals(optionKey(first), key(answer(visibleOnly,
                new AnswerHolder(new PollTextAndImageAnswerViewBinding()), 0)));
        assertEquals(1, ((List<?>) visibleOnly.items).size());
        assertSame(all, accessory.answers);
        String[] optionIds = {"a", "b", "c"};
        String[] optionTexts = {"visible option", "hidden option", "last hidden option"};
        for (int i = 0; i < all.size(); i++) {
            PollAnswerAccessory option = all.get(i);
            PollAnswer nativeAnswer = (PollAnswer) option.answer;
            assertSame(ROW, option.messageId);
            assertEquals(optionIds[i], nativeAnswer.answerId);
            assertEquals(optionTexts[i], ((PollMedia) nativeAnswer.pollMedia).text);
            assertEquals("17", nativeAnswer.votes);
            assertEquals(40, nativeAnswer.votesPercentage);
            assertSame(Boolean.TRUE, nativeAnswer.isSelected);
            assertTrue(nativeAnswer.didSelfVote);
            assertEquals(Collections.singleton("attachment-id"),
                    ((PollMedia) nativeAnswer.pollMedia).attachmentIds);
            assertEquals(1, option.attachments.size());
        }
        assertSame(ROW, accessory.messageId);
        assertSame(raw, ((PollMedia) ((PollData) accessory.data).question).text);
        assertEquals("data-only-decoy", ((PollData) accessory.data).answers.get(0).answerId);
        assertTrue(((PollData) accessory.data).canSelectMultipleAnswers);
        assertTrue(((PollData) accessory.data).hasVoted);
        assertEquals("Select an answer", ((PollData) accessory.data).promptLabel);
        assertSame(presenter, holder.pollView);
        assertSame(inheritedBinding, ((BasePollWithRecyclerView) presenter).binding);
        for (int i = 0; i < callbacks.length; i++) assertSame(callbacks[i], args.get(i + 1));
        assertTrue(binding.matches(views.questionText, raw, false));
        assertTrue(binding.matches(views.questionText, new String(raw), false));
        assertFalse(binding.matches(views.questionText, raw.trim(), false));
        assertFalse(binding.matches(views.questionText, new StringBuilder(raw), false));
        assertFalse(binding.matches(views.questionText, raw, true));
        assertFalse(binding.matches(views.questionText, null, false));
        for (Object neighbor : Arrays.asList(views.promptText, views.answers, views.rootView,
                views.primaryAction, presenter, new Sink(raw))) {
            assertFalse(binding.matches(neighbor, raw, false));
        }
        assertFalse(binding.matches(views.questionText, views.promptText.nativeText, false));
        assertEquals("native question", views.questionText.nativeText);
        assertEquals("native question", views.questionText.contentDescription);
        assertEquals("Select an answer", views.promptText.nativeText);
    }

    @Test
    public void textAndImageBindingsSelectExactInheritedReceiverAndPreserveNativeModel()
            throws Exception {
        String raw = new String("  Answer 😀\nfull label  ");
        String stableId = new String("stable-answer-id");
        PollAnswerAccessory item = new PollAnswerAccessory(ROW, stableId, raw);
        PollAnswer nativeAnswer = (PollAnswer) item.answer;
        PollMedia media = (PollMedia) nativeAnswer.pollMedia;
        Object emoji = media.emoji;
        Set<String> attachmentIds = media.attachmentIds;
        List<Object> attachments = item.attachments;
        String votes = nativeAnswer.votes;
        List<PollAnswerAccessory> items = new ArrayList<>(Arrays.asList(
                new PollAnswerAccessory(OTHER_ROW, "decoy", "decoy text"), item));
        PollAnswerAdapter adapter = new PollAnswerAdapter(items);
        Object onTap = adapter.onTapAnswer;
        Object onLongPress = adapter.onLongPressImage;
        PollTextAndImageAnswerViewBinding textViews = new PollTextAndImageAnswerViewBinding();
        PollImageOnlyAnswerViewBinding imageViews = new PollImageOnlyAnswerViewBinding();
        for (Object views : Arrays.asList(textViews, imageViews)) {
            AnswerHolder holder = new AnswerHolder(views);
            PollBinding binding = answer(adapter, holder, 1);
            String boundKey = key(binding);
            PollCardState.bind(ROW, null, Collections.singletonList(boundKey));
            assertTrue(PollCardState.canReplay(key(answer(adapter, holder, 1)), boundKey));
            boolean image = views == imageViews;
            Sink receiver = image ? imageViews.image : textViews.label;
            assertEquals(ROW, binding.messageId);
            assertEquals("answer:stable-answer-id", binding.slot);
            assertSame(raw, binding.text);
            assertSame(receiver, binding.host);
            assertSame(receiver, binding.receiver);
            assertEquals(image, binding.accessibility);
            assertSame(views, ((PollAnswerViewHolder) holder).binding);
            assertSame(receiver, PollBinding.receiver(adapter, answerArgs(holder, -1), answerSpec, true));
            assertTrue(binding.matches(receiver, raw, image));
            assertTrue(binding.matches(receiver, new String(raw), image));
            assertFalse(binding.matches(receiver, raw, !image));
            assertFalse(binding.matches(receiver, raw.trim(), image));
            assertFalse(binding.matches(receiver, new StringBuilder(raw), image));
            assertFalse(binding.matches(receiver, null, image));
            assertFalse(binding.matches(new Sink(raw), raw, image));
            List<Object> neighbors = image
                    ? Arrays.asList(imageViews.voteCounts, imageViews.votePercentage,
                            imageViews.selectedIcon, imageViews.rootView, textViews.label)
                    : Arrays.asList(textViews.image, textViews.voteCounts, textViews.votePercentage,
                            textViews.checkbox, textViews.rootView, imageViews.image);
            for (Object neighbor : neighbors) assertFalse(binding.matches(neighbor, raw, image));
            assertFalse(binding.matches(receiver, "17 votes", image));
            assertFalse(binding.matches(receiver, "40%", image));
            assertFalse(binding.matches(receiver, item.tapAccessibilityLabel, image));
        }
        assertSame(items, adapter.items);
        assertSame(item, items.get(1));
        assertEquals(2, items.size());
        assertSame(ROW, item.messageId);
        assertSame(stableId, nativeAnswer.answerId);
        assertSame(nativeAnswer, item.answer);
        assertSame(media, nativeAnswer.pollMedia);
        assertSame(raw, media.text);
        assertSame(attachmentIds, media.attachmentIds);
        assertSame(emoji, media.emoji);
        assertEquals(Long.valueOf(123), media.stickerId);
        assertSame(attachments, item.attachments);
        assertSame(votes, nativeAnswer.votes);
        assertEquals("17", nativeAnswer.votes);
        assertEquals(40, nativeAnswer.votesPercentage);
        assertSame(Boolean.TRUE, nativeAnswer.isSelected);
        assertTrue(nativeAnswer.didSelfVote);
        assertTrue(item.canSelectMultipleAnswers);
        assertTrue(item.hasVoted);
        assertSame(onTap, adapter.onTapAnswer);
        assertSame(onLongPress, adapter.onLongPressImage);
        assertEquals("native answer", textViews.label.nativeText);
        assertEquals("native answer", textViews.label.contentDescription);
        assertEquals("native image description", textViews.image.contentDescription);
        assertEquals("native image description", imageViews.image.contentDescription);
        assertEquals("17 votes", textViews.voteCounts.nativeText);
        assertEquals("17 votes", imageViews.voteCounts.nativeText);
    }

    @Test
    public void reorderedSameLabelsEditedKeysAndCurrentMembershipUseProductionReplayGuard()
            throws Exception {
        PollAnswerAccessory first = new PollAnswerAccessory(ROW, "a", "same label");
        PollAnswerAccessory second = new PollAnswerAccessory(ROW, "b", "same label");
        List<PollAnswerAccessory> items = new ArrayList<>(Arrays.asList(first, second));
        PollAnswerAdapter adapter = new PollAnswerAdapter(items);
        AnswerHolder holder = new AnswerHolder(new PollTextAndImageAnswerViewBinding());
        PollBinding captured = answer(adapter, holder, 0);
        String boundKey = key(captured);
        String secondKey = optionKey(second);
        String titleKey = key(ROW, "question", "question");
        PollCardState.bind(ROW, titleKey, Arrays.asList(titleKey, boundKey, secondKey));
        assertTrue(PollCardState.canReplay(key(answer(adapter, holder, 0)), boundKey));
        assertNotEquals(boundKey, secondKey);
        assertNull(PollCardState.toggle(secondKey));
        Collections.swap(items, 0, 1);
        PollBinding replacement = answer(adapter, holder, 0);
        assertEquals(captured.text, replacement.text);
        assertSame(captured.receiver, replacement.receiver);
        assertNotEquals(captured.slot, replacement.slot);
        assertFalse(PollCardState.canReplay(key(replacement), boundKey));
        assertTrue(PollCardState.canReplay(key(answer(adapter, holder, 1)), boundKey));
        PollMedia media = (PollMedia) ((PollAnswer) first.answer).pollMedia;
        media.text = "edited label";
        String editedKey = key(answer(adapter, holder, 1));
        assertNotEquals(boundKey, editedKey);
        assertFalse(PollCardState.canReplay(editedKey, boundKey));
        assertFalse(PollCardState.canReplay(editedKey, editedKey));
        assertNull(PollCardState.forKey(editedKey));
        media.text = "same label";
        first.messageId = OTHER_ROW;
        assertFalse(PollCardState.canReplay(key(answer(adapter, holder, 1)), boundKey));
        first.messageId = ROW;
        PollCardState.invalidate(ROW);
        assertFalse(PollCardState.canReplay(boundKey, boundKey));
        assertFalse(PollCardState.canReplay(secondKey, secondKey));
        PollCardState.bind(ROW, titleKey, Arrays.asList(titleKey, secondKey));
        assertFalse(PollCardState.canReplay(boundKey, boundKey));
        assertTrue(PollCardState.canReplay(secondKey, secondKey));
        assertFalse(PollCardState.canReplay(null, secondKey));
        assertFalse(PollCardState.canReplay(secondKey, null));
        media.text = "edited label";
        PollCardState.bind(ROW, titleKey, Arrays.asList(titleKey, editedKey));
        assertFalse(PollCardState.canReplay(boundKey, boundKey));
        assertTrue(PollCardState.canReplay(key(answer(adapter, holder, 1)), editedKey));
    }

    @Test
    public void nullEmptyAndWhitespaceTextRemainVerbatimWithoutInventedLabels() throws Exception {
        for (String raw : Arrays.asList(null, "", "  ")) {
            PollBinding question = question(new PollViewHolder(),
                    new PollMessageAccessory(ROW, raw, Collections.emptyList()));
            assertSame(raw, question.text);
            assertTrue(question.matches(question.receiver, raw, false));
            assertFalse(question.matches(question.receiver, "Question", false));
            PollAnswerAccessory item = new PollAnswerAccessory(ROW, "a", raw);
            assertSame(raw, PollBinding.optionText(item, answerSpec));
            assertEquals("answer:a", PollBinding.optionSlot(item, ROW, answerSpec));
            for (Object views : Arrays.asList(new PollTextAndImageAnswerViewBinding(),
                    new PollImageOnlyAnswerViewBinding())) {
                PollBinding binding = answer(new PollAnswerAdapter(Collections.singletonList(item)),
                        new AnswerHolder(views), 0);
                assertSame(raw, binding.text);
                assertTrue(binding.matches(binding.receiver, raw, binding.accessibility));
                assertFalse(binding.matches(binding.receiver, "Answer", binding.accessibility));
                assertFalse(binding.matches(binding.receiver, new StringBuilder(), binding.accessibility));
            }
        }
    }

    @Test
    public void invalidPositionsArgumentsAndListsRejectWithoutAlternateSource() throws Exception {
        PollAnswerAccessory item = new PollAnswerAccessory(ROW, "a", "label");
        PollAnswerAdapter adapter = new PollAnswerAdapter(Collections.singletonList(item));
        AnswerHolder holder = new AnswerHolder(new PollTextAndImageAnswerViewBinding());
        for (Object position : Arrays.asList(-1, 1, Integer.MAX_VALUE, 0L, "0", null)) {
            assertThrows(IllegalStateException.class, () -> answer(adapter, holder, position));
        }
        for (Object items : Arrays.asList(null, "not a list", new PollAnswerAccessory[]{item})) {
            PollAnswerAdapter malformed = new PollAnswerAdapter(items);
            assertSame(((PollTextAndImageAnswerViewBinding) ((PollAnswerViewHolder) holder).binding).label,
                    PollBinding.receiver(malformed, answerArgs(holder, -1), answerSpec, true));
            assertThrows(IllegalStateException.class, () -> answer(malformed, holder, 0));
            PollMessageAccessory root = new PollMessageAccessory(ROW, "question", items);
            assertThrows(IllegalStateException.class, () -> PollBinding.options(rootArgs(root), questionSpec));
        }
        assertThrows(IllegalStateException.class, () -> PollBinding.question(new PollViewHolder(),
                Collections.emptyList(), questionSpec));
        assertThrows(IllegalStateException.class, () -> PollBinding.questionRow(Collections.emptyList(), questionSpec));
        assertThrows(IllegalStateException.class, () -> PollBinding.options(Collections.emptyList(), questionSpec));
        assertThrows(IllegalStateException.class, () -> PollBinding.answer(adapter, Collections.emptyList(), answerSpec));
        assertThrows(IllegalStateException.class, () -> PollBinding.answer(adapter,
                Collections.singletonList(holder), answerSpec));
        assertThrows(IllegalStateException.class, () -> PollBinding.answer(adapter,
                Arrays.asList(0, holder), answerSpec));
    }

    @Test
    public void nonStringTextAndIdentityNeverCoerceAndOptionsRequireTheirOwnRow() throws Exception {
        AnswerHolder holder = new AnswerHolder(new PollTextAndImageAnswerViewBinding());
        for (Object invalid : Arrays.asList(123L, new StringBuilder("label"))) {
            assertThrows(IllegalStateException.class, () -> question(new PollViewHolder(),
                    new PollMessageAccessory(invalid, "question", Collections.emptyList())));
            assertThrows(IllegalStateException.class, () -> question(new PollViewHolder(),
                    new PollMessageAccessory(ROW, invalid, Collections.emptyList())));
            for (PollAnswerAccessory item : Arrays.asList(
                    new PollAnswerAccessory(invalid, "a", "label"),
                    new PollAnswerAccessory(ROW, invalid, "label"))) {
                assertThrows(IllegalStateException.class, () -> answer(
                        new PollAnswerAdapter(Collections.singletonList(item)), holder, 0));
                assertThrows(IllegalStateException.class, () -> PollBinding.optionSlot(item, ROW, answerSpec));
            }
            PollAnswerAccessory badText = new PollAnswerAccessory(ROW, "a", invalid);
            assertThrows(IllegalStateException.class, () -> answer(
                    new PollAnswerAdapter(Collections.singletonList(badText)), holder, 0));
            assertThrows(IllegalStateException.class, () -> PollBinding.optionText(badText, answerSpec));
        }
        for (Object invalidId : Arrays.asList(null, "", "a\u0000b")) {
            PollAnswerAccessory item = new PollAnswerAccessory(ROW, invalidId, "label");
            assertThrows(IllegalStateException.class, () -> answer(
                    new PollAnswerAdapter(Collections.singletonList(item)), holder, 0));
            assertThrows(IllegalStateException.class, () -> PollBinding.optionSlot(item, ROW, answerSpec));
        }
        PollAnswerAccessory otherRow = new PollAnswerAccessory(OTHER_ROW, "a", "label");
        assertThrows(IllegalStateException.class, () -> PollBinding.optionSlot(otherRow, ROW, answerSpec));
    }

    @Test
    public void missingNativeRootFieldsRejectWithoutGetterOrViewGuessing() throws Exception {
        GetterOnly wrongShape = new GetterOnly();
        PollViewHolder holder = new PollViewHolder();
        assertThrows(IllegalStateException.class, () -> PollBinding.question(holder,
                rootArgs(wrongShape), questionSpec));
        assertThrows(IllegalStateException.class, () -> PollBinding.options(rootArgs(wrongShape), questionSpec));
        PollMessageAccessory accessory = new PollMessageAccessory(ROW, "question", Collections.emptyList());
        accessory.data = wrongShape;
        assertThrows(IllegalStateException.class, () -> question(holder, accessory));
        accessory.data = new PollData("question");
        ((PollData) accessory.data).question = wrongShape;
        assertThrows(IllegalStateException.class, () -> question(holder, accessory));
        ((PollData) accessory.data).question = new PollMedia("question");
        assertThrows(IllegalStateException.class, () -> PollBinding.question(wrongShape,
                rootArgs(accessory), questionSpec));
        holder.pollView = wrongShape;
        assertThrows(IllegalStateException.class, () -> question(holder, accessory));
        holder.pollView = new PollPresenter();
        ((BasePollWithRecyclerView) holder.pollView).binding = wrongShape;
        assertThrows(IllegalStateException.class, () -> question(holder, accessory));
        assertThrows(IllegalStateException.class, () -> PollBinding.receiver(holder,
                rootArgs(accessory), questionSpec, false));
    }

    @Test
    public void missingNativeAnswerFieldsAndUnmappedBindingsRejectWithoutGetterStrategy()
            throws Exception {
        GetterOnly wrongShape = new GetterOnly();
        AnswerHolder holder = new AnswerHolder(new PollTextAndImageAnswerViewBinding());
        PollAnswerAccessory item = new PollAnswerAccessory(ROW, "a", "label");
        PollAnswerAdapter adapter = new PollAnswerAdapter(Collections.singletonList(item));
        assertThrows(IllegalStateException.class, () -> PollBinding.answer(wrongShape,
                answerArgs(holder, 0), answerSpec));
        assertThrows(IllegalStateException.class, () -> answer(
                new PollAnswerAdapter(Collections.singletonList(wrongShape)), holder, 0));
        assertThrows(IllegalStateException.class, () -> PollBinding.optionSlot(wrongShape, ROW, answerSpec));
        item.answer = wrongShape;
        assertThrows(IllegalStateException.class, () -> answer(adapter, holder, 0));
        assertThrows(IllegalStateException.class, () -> PollBinding.optionSlot(item, ROW, answerSpec));
        assertThrows(IllegalStateException.class, () -> PollBinding.optionText(item, answerSpec));
        item.answer = new PollAnswer("a", "label");
        ((PollAnswer) item.answer).pollMedia = wrongShape;
        assertThrows(IllegalStateException.class, () -> answer(adapter, holder, 0));
        assertThrows(IllegalStateException.class, () -> PollBinding.optionText(item, answerSpec));
        ((PollAnswer) item.answer).pollMedia = new PollMedia("label");
        assertThrows(IllegalStateException.class, () -> PollBinding.answer(adapter,
                answerArgs(wrongShape, 0), answerSpec));
        for (Object binding : Arrays.asList(null, new UnknownBinding(), new DerivedTextBinding())) {
            AnswerHolder unknown = new AnswerHolder(binding);
            assertThrows(IllegalStateException.class, () -> answer(adapter, unknown, 0));
            assertThrows(IllegalStateException.class, () -> PollBinding.receiver(adapter,
                    answerArgs(unknown, 0), answerSpec, true));
        }
        // Class-name substitution is still the only metadata change: paths remain label/image.
        JSONObject missingLabel = new JSONObject(answerSpec.toString())
                .put("textBindingClass", MissingLabelBinding.class.getName());
        JSONObject missingImage = new JSONObject(answerSpec.toString())
                .put("imageBindingClass", MissingImageBinding.class.getName());
        assertThrows(IllegalStateException.class, () -> PollBinding.answer(adapter,
                answerArgs(new AnswerHolder(new MissingLabelBinding()), 0), missingLabel));
        assertThrows(IllegalStateException.class, () -> PollBinding.answer(adapter,
                answerArgs(new AnswerHolder(new MissingImageBinding()), 0), missingImage));
    }
}
