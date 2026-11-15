package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import io.github.wxmyyds.discordautotranslate.core.StateStore;
import io.github.wxmyyds.discordautotranslate.core.TextExtractor;
import io.github.wxmyyds.discordautotranslate.core.TranslationCache;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

/** Reply consumers must read canonical state without creating requests or preview state. */
public class ReferencedMessageStateTest {
    private static final String ID = "123456789012345678";
    private HookBridge bridge;

    @Before
    public void setup() {
        ContentNodeTestProfile.activate(ContentNodesTest.class);
        StateStore.get().onNewGeneration();
        TranslationCache.get().clear();
        bridge = HookBridge.get();
        bridge.reset();
        HookRuntime.activate();
    }

    @After
    public void cleanup() {
        StateStore.get().onNewGeneration();
        TranslationCache.get().clear();
        bridge.reset();
        ContentNodeTestProfile.clear();
        HookRuntime.suspend();
    }

    private StateStore.Entry canonical(Object body) {
        String key = bridge.embedStateKey(ID, body);
        String hash = key.substring((ID + "\u0000embed\u0000").length());
        return StateStore.get().obtain(ID, hash);
    }

    @Test
    public void missingStateIsNotCreatedEvenIfContentCacheExists() {
        Object body = new ContentNodesTest.PrimitiveStructurableText("Hello world");
        String key = bridge.embedStateKey(ID, body);
        TranslationCache.get().put(key.substring((ID + "\u0000embed\u0000").length()), "你好");
        assertNull(bridge.currentTranslation(ID, body));
        assertNull(StateStore.get().peek(ID));
    }

    @Test
    public void omittedModelBodyReadsTheStateByReferencedIdNotDisplayedPreview() {
        Object body = new ContentNodesTest.PrimitiveStructurableText("Full original message");
        StateStore.Entry entry = canonical(body);
        entry.translatedText = "完整译文";
        entry.status = StateStore.Status.TRANSLATED;
        String revision = "revision-1";
        bridge.rememberMessageRevision(ID, revision);
        bridge.rememberCanonicalContent(ID, body);
        HookBridge.ReferencedTranslation reused = bridge.referencedTranslation(ID, revision);
        assertNotNull(reused);
        assertSame(entry, reused.state);
        assertSame(body, reused.originalContent);
        assertEquals("完整译文", reused.state.translatedText);
        assertNull(bridge.currentTranslation(ID, null));
        assertNull(bridge.referencedTranslation("987654321098765432", revision));
        assertNull(bridge.referencedTranslation(null, revision));
        assertNull(bridge.currentTranslation(null, body));
    }

    @Test
    public void richTextUsesTheSameSegmentHashAsTheCanonicalBody() {
        Object body = new ContentNodesTest.AnnotatedStructurableText(Arrays.asList(
                new ContentNodesTest.TextContentNode("Hello "),
                new ContentNodesTest.TextContentNode("world")));
        assertEquals(2, TextExtractor.extract(body).segmentCount);
        StateStore.Entry entry = canonical(body);
        HookBridge.ReferencedTranslation current = bridge.currentTranslation(ID, body);
        assertNotNull(current);
        assertSame(entry, current.state);
    }

    @Test
    public void editedBodyCannotConsumeAnOldTranslationOrResetItsState() {
        Object original = new ContentNodesTest.PrimitiveStructurableText("Original message");
        StateStore.Entry entry = canonical(original);
        bridge.rememberCanonicalContent(ID, original);
        StateStore.Source source = StateStore.get().source(ID);
        entry.translatedText = "旧译文";
        entry.status = StateStore.Status.TRANSLATED;
        Object edited = new ContentNodesTest.PrimitiveStructurableText("Edited message");
        assertNull(bridge.currentTranslation(ID, edited));
        assertSame(entry, StateStore.get().peek(ID));
        assertEquals(StateStore.Status.TRANSLATED, entry.status);
        assertSame(source, StateStore.get().source(ID));
        assertSame(original, source.content);
        assertEquals("Original message", TextExtractor.extract(original).text);
    }

    @Test
    public void projectedReplyUsesCanonicalContentNotItsDifferentText() {
        Object original = new ContentNodesTest.PrimitiveStructurableText("body\nwith attachment");
        Object projected = new ContentNodesTest.PrimitiveStructurableText("body with attachment");
        StateStore.Entry entry = canonical(original);
        entry.translatedText = "正文\n含附件";
        entry.status = StateStore.Status.TRANSLATED;
        bridge.rememberMessageRevision(ID, null);
        bridge.rememberCanonicalContent(ID, original);
        assertNull(bridge.currentTranslation(ID, projected));
        HookBridge.ReferencedTranslation reused = bridge.referencedTranslation(ID, null);
        assertNotNull(reused);
        assertSame(entry, reused.state);
        assertSame(original, reused.originalContent);
    }

    @Test
    public void editRevisionRejectsOldAndFutureReplyModels() {
        Object original = new ContentNodesTest.PrimitiveStructurableText("Body text");
        canonical(original);
        bridge.rememberMessageRevision(ID, "revision-1");
        bridge.rememberCanonicalContent(ID, original);
        assertNotNull(bridge.referencedTranslation(ID, "revision-1"));
        assertNull(bridge.referencedTranslation(ID, null));
        assertNull(bridge.referencedTranslation(ID, "revision-2"));
        bridge.rememberMessageRevision(ID, "revision-2");
        // New bind arrived before its body renderer: do not relabel the old body.
        assertNull(bridge.referencedTranslation(ID, "revision-1"));
        assertNull(bridge.referencedTranslation(ID, "revision-2"));
    }

    @Test
    public void missingRevisionAndStaleCanonicalHashDoNotBypassGuards() {
        Object original = new ContentNodesTest.PrimitiveStructurableText("Body text");
        StateStore.Entry entry = canonical(original);
        bridge.rememberCanonicalContent(ID, original);
        assertNull(bridge.referencedTranslation(ID, null));
        bridge.rememberMessageRevision(ID, null);
        bridge.rememberCanonicalContent(ID, original);
        assertNotNull(bridge.referencedTranslation(ID, null));
        entry.contentHash = "edited-hash";
        assertNull(bridge.referencedTranslation(ID, null));
        assertNull(bridge.referencedTranslation(null, null));
    }

    @Test
    public void canonicalProjectionReusesStatusWithoutCreatingOrOverridingIt() {
        Object original = new ContentNodesTest.PrimitiveStructurableText("Body text");
        bridge.rememberMessageRevision(ID, null);
        bridge.rememberCanonicalContent(ID, original);
        assertNull(bridge.referencedTranslation(ID, null));
        assertNull(StateStore.get().peek(ID));
        StateStore.Entry entry = canonical(original);
        bridge.rememberCanonicalContent(ID, original);
        for (StateStore.Status status : StateStore.Status.values()) {
            entry.status = status;
            entry.showingOriginal = true;
            HookBridge.ReferencedTranslation reused = bridge.referencedTranslation(ID, null);
            assertNotNull(reused);
            assertSame(entry, reused.state);
            assertSame(original, reused.originalContent);
            assertEquals(status, entry.status);
            assertTrue(entry.showingOriginal);
        }
    }

    @Test
    public void retryCannotRelabelOldBodyAfterNewModelRevisionArrives() {
        Object original = new ContentNodesTest.PrimitiveStructurableText("Body text");
        StateStore.Entry entry = canonical(original);
        entry.status = StateStore.Status.FAILED;
        entry.lastError = "retained";
        bridge.rememberMessageRevision(ID, "revision-1");
        bridge.rememberCanonicalContent(ID, original);
        bridge.rememberMessageRevision(ID, "revision-2");
        bridge.retry(ID);
        assertEquals(StateStore.Status.FAILED, entry.status);
        assertEquals("retained", entry.lastError);
        assertNull(bridge.referencedTranslation(ID, "revision-2"));
        assertNull(bridge.referencedTranslation(ID, "revision-1"));
    }

    @Test
    public void retryRejectsUnknownSnapshotWhenRevisionBecomesKnown() {
        Object original = new ContentNodesTest.PrimitiveStructurableText("Body text");
        StateStore.Entry entry = canonical(original);
        entry.status = StateStore.Status.FAILED;
        bridge.rememberCanonicalContent(ID, original);
        bridge.rememberMessageRevision(ID, null);
        bridge.retry(ID);
        assertEquals(StateStore.Status.FAILED, entry.status);
        assertNull(bridge.referencedTranslation(ID, null));
    }

    @Test
    public void retryRejectsStateHashThatNoLongerMatchesTheSnapshot() {
        Object original = new ContentNodesTest.PrimitiveStructurableText("Body text");
        StateStore.Entry entry = canonical(original);
        bridge.rememberMessageRevision(ID, null);
        bridge.rememberCanonicalContent(ID, original);
        entry.contentHash = "new-content-hash";
        entry.status = StateStore.Status.FAILED;
        bridge.retry(ID);
        assertEquals(StateStore.Status.FAILED, entry.status);
        assertEquals("new-content-hash", entry.contentHash);
    }

    @Test
    public void identicalEmbedObjectsUseTheirOwnCurrentPlansWithoutInvalidatingSharedState() {
        Object first = new ContentNodesTest.AnnotatedStructurableText(Arrays.asList(
                new ContentNodesTest.TextContentNode("same text")));
        Object second = new ContentNodesTest.AnnotatedStructurableText(Arrays.asList(
                new ContentNodesTest.TextContentNode("same text")));
        String key = bridge.embedStateKey(ID, first);
        assertEquals(key, bridge.embedStateKey(ID, second));
        String hash = key.substring((ID + "\u0000embed\u0000").length());
        StateStore.Entry entry = StateStore.get().obtain(key, hash);
        bridge.rememberCanonicalContent(key, second);
        entry.status = StateStore.Status.TRANSLATED;
        entry.translatedText = "相同文字";
        HookBridge.ReferencedTranslation a = bridge.currentTranslation(key, first);
        HookBridge.ReferencedTranslation b = bridge.currentTranslation(key, second);
        assertNotNull(a);
        assertNotNull(b);
        assertSame(first, a.originalContent);
        assertSame(second, b.originalContent);
        assertSame(entry, a.state);
        assertSame(entry, b.state);
        assertNotSame(a.extracted.tree, b.extracted.tree);
        assertEquals(StateStore.Status.TRANSLATED, entry.status);
    }

    @Test
    public void sameOuterObjectWithMutatedBodyCannotUseAnOldCapturedHash() {
        java.util.List<Object> children = new java.util.ArrayList<>(Arrays.asList(
                new ContentNodesTest.TextContentNode("original text")));
        Object body = new ContentNodesTest.AnnotatedStructurableText(children);
        StateStore.Entry entry = canonical(body);
        bridge.rememberCanonicalContent(ID, body);
        entry.status = StateStore.Status.TRANSLATED;
        entry.translatedText = "原译文";
        assertNotNull(bridge.currentTranslation(ID, body));
        children.set(0, new ContentNodesTest.TextContentNode("edited text"));
        assertNull(bridge.currentTranslation(ID, body));
        assertEquals(StateStore.Status.TRANSLATED, entry.status);
        assertEquals("原译文", entry.translatedText);
    }

    @Test
    public void consumerDoesNotOverrideToggleOrFailedAndInFlightStatus() {
        Object body = new ContentNodesTest.PrimitiveStructurableText("Original message");
        StateStore.Entry entry = canonical(body);
        for (StateStore.Status status : StateStore.Status.values()) {
            entry.status = status;
            entry.showingOriginal = true;
            HookBridge.ReferencedTranslation current = bridge.currentTranslation(ID, body);
            assertNotNull(current);
            assertSame(entry, current.state);
            assertEquals(status, entry.status);
            assertTrue(entry.showingOriginal);
        }
    }
}
