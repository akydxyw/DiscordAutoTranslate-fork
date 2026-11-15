package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import io.github.wxmyyds.discordautotranslate.core.ContentTree;
import io.github.wxmyyds.discordautotranslate.core.TextExtractor;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.TextContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.HeadingContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.ParagraphContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.StrongContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.LinkContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.BulletListContentNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;

/** Boundary decoration observes the same failing operation, never source or response strings. */
public class BoundaryDiagnosticsTest {
    @Before public void setup() {
        ContentNodeTestProfile.activate(ContentNodesTest.class, OpaqueNodeTranslationTest.class,
                EmojiAnchorTest.class, BoundaryDiagnosticsTest.class);
    }
    @After public void cleanup() { ContentNodeTestProfile.clear(); }

    private ContentNodes.Rejection ambiguous(List<?> nodes, String translated) {
        ContentTree tree = ContentTree.compile(nodes);
        assertNotNull(tree);
        ContentNodes.Rejection rejection = assertThrows(ContentNodes.Rejection.class,
                () -> ContentNodes.replaceChecked(tree, translated));
        assertEquals(ContentNodes.RejectionReason.SOURCE_BOUNDARY_AMBIGUOUS, rejection.reason);
        assertEquals("SOURCE_BOUNDARY_AMBIGUOUS", rejection.getMessage());
        assertNull(rejection.getCause());
        assertFalse(rejection.structure.isEmpty());
        assertNull(ContentNodes.replacePlanned(tree, translated));
        return rejection;
    }

    @Test public void rootSameLineReportsZeroSourceLfAndNoContainers() {
        String structure = ambiguous(Arrays.asList(new TextContentNode("private-source-a"),
                new TextContentNode(" "), new TextContentNode("private-source-b")),
                "私密译文").structure;
        assertTrue(structure.contains("leftSlot=0 rightSlot=1"));
        assertTrue(structure.contains("leftPath=ROOT"));
        assertTrue(structure.contains("rightPath=ROOT"));
        assertTrue(structure.contains("sameContainerPath=true"));
        assertTrue(structure.contains("sameTextOccurrence=false"));
        assertTrue(structure.contains("rightSourceLfBefore=0"));
        assertTrue(structure.contains("rightGapWhitespaceNodes=1"));
        assertFalse(structure.contains("private-source"));
        assertFalse(structure.contains("私密译文"));
    }

    @Test public void siblingContainersAndSharedContainerOccurrencesRemainDistinct() {
        String between = ambiguous(Arrays.asList(new HeadingContentNode(
                Arrays.asList(new TextContentNode("secret")), 1), new TextContentNode(" "),
                new ParagraphContentNode(Arrays.asList(new TextContentNode("hidden")))), "译文").structure;
        assertTrue(between.contains("leftPath=HeadingContentNode"));
        assertTrue(between.contains("rightPath=ParagraphContentNode"));
        assertTrue(between.contains("sameContainerPath=false"));
        ParagraphContentNode shared = new ParagraphContentNode(Arrays.asList(new TextContentNode("same")));
        String repeated = ambiguous(Arrays.asList(shared, shared), "译文").structure;
        assertTrue(repeated.contains("leftPath=ParagraphContentNode"));
        assertTrue(repeated.contains("rightPath=ParagraphContentNode"));
        assertTrue(repeated.contains("sameContainerPath=false"));
    }

    @Test public void nestedCapturedContainerPathIsImmutableAndPreservesEventStream() {
        TextContentNode first = new TextContentNode("first");
        ParagraphContentNode nested = new ParagraphContentNode(Arrays.asList(first,
                new StrongContentNode(Arrays.asList(new TextContentNode("second")))));
        ContentTree tree = ContentTree.compile(Arrays.asList(nested));
        assertNotNull(tree);
        assertEquals(2, tree.events.size());
        assertEquals(ContentTree.EventKind.NODE, tree.events.get(0).kind);
        assertFalse(tree.events.get(0).styled);
        assertTrue(tree.events.get(1).styled);
        assertEquals(1, tree.events.get(0).containers.size());
        assertEquals(2, tree.events.get(1).containers.size());
        assertSame(tree.root.children.get(0), tree.events.get(0).containers.get(0));
        assertSame(tree.events.get(0).containers.get(0), tree.events.get(1).containers.get(0));
        assertThrows(UnsupportedOperationException.class, () -> tree.events.get(1).containers.clear());
        assertEquals("first<x1>second</x1>", TextExtractor.extractTree(tree).text);
    }

    @Test public void codeAndOpaqueGapReportsKindsNotContents() {
        String structure = ambiguous(Arrays.asList(new TextContentNode("first"),
                new ContentNodesTest.InlineCodeContentNode("credential-secret"),
                new OpaqueNodeTranslationTest.ChannelMentionContentNode(
                        Arrays.asList(new TextContentNode("hidden-name"))),
                new TextContentNode("second")), "译文").structure;
        assertTrue(structure.contains("rightGapInlineCode=1"));
        assertTrue(structure.contains("rightGapOpaque=1"));
        assertTrue(structure.contains("ChannelMentionContentNode"));
        assertTrue(structure.contains("rightSourceLfBefore=0"));
        assertFalse(structure.contains("credential-secret"));
        assertFalse(structure.contains("hidden-name"));
    }

    @Test public void anchorIntervalLocalBoundaryMapsBackToGlobalSlot() {
        String url = "https://private.invalid/token-secret";
        String structure = ambiguous(Arrays.asList(new TextContentNode("before"),
                new LinkContentNode(Arrays.asList(new TextContentNode(url)), new Object()),
                new TextContentNode("first"), new TextContentNode("second")),
                "之前 " + url + " 译文").structure;
        assertTrue(structure.contains("intervalSlots=2 leftSlot=1 rightSlot=2"));
        assertTrue(structure.contains("leftGapUrls=1"));
        assertFalse(structure.contains("token-secret"));
        assertFalse(structure.contains("https://"));
    }

    @Test public void styleIntervalLocalBoundaryMapsBackToGlobalSlot() {
        String structure = ambiguous(Arrays.asList(new StrongContentNode(
                Arrays.asList(new TextContentNode("before"))), new TextContentNode("first"),
                new TextContentNode("second")), "<x1>之前</x1>译文").structure;
        assertTrue(structure.contains("intervalSlots=2 leftSlot=1 rightSlot=2"));
        assertTrue(structure.contains("leftStyled=false"));
        assertTrue(structure.contains("rightStyled=false"));
    }

    @Test public void listSameLineSlotsKeepCapturedListPathAndDepth() {
        BulletListContentNode list = new BulletListContentNode(null, false,
                Arrays.asList(Arrays.asList(new TextContentNode("first"), new TextContentNode("second"))));
        String structure = ambiguous(Arrays.asList(list), "• 译文").structure;
        assertTrue(structure.contains("leftPath=BulletListContentNode"));
        assertTrue(structure.contains("rightListDepth=1"));
        assertTrue(structure.contains("leftGapItemStarts=1"));
        assertTrue(structure.contains("rightGapItemStarts=0"));
        assertTrue(structure.contains("sameContainerPath=true"));
    }

    @Test public void otherRejectionsDoNotAcquireUnrelatedStructureAndSuccessRemainsUnchanged() {
        ContentTree styled = ContentTree.compile(Arrays.asList(new StrongContentNode(
                Arrays.asList(new TextContentNode("first")))));
        ContentNodes.Rejection rejection = assertThrows(ContentNodes.Rejection.class,
                () -> ContentNodes.replaceChecked(styled, "译文"));
        assertEquals(ContentNodes.RejectionReason.STYLE_OPEN, rejection.reason);
        assertEquals("", rejection.structure);
        ContentTree lines = ContentTree.compile(Arrays.asList(new TextContentNode("first"),
                new TextContentNode("\n"), new TextContentNode("second")));
        assertEquals("first\nsecond", TextExtractor.extractTree(lines).text);
        List<Object> output = ContentNodes.replaceChecked(lines, "一\n二");
        assertEquals("一", ((TextContentNode) output.get(0)).getContent());
        assertEquals("二", ((TextContentNode) output.get(2)).getContent());
    }

    public static final class UnknownOne { }
    public static final class UnknownTwo { }
    public static final class UnknownThree { }
    public static final class UnknownFour { }
    public static final class UnknownFive { }

    @Test public void gapTypesAreExplicitlyBoundedButCountsAreNotDropped() {
        String structure = ambiguous(Arrays.asList(new TextContentNode("first"), new UnknownOne(),
                new UnknownTwo(), new UnknownThree(), new UnknownFour(), new UnknownFive(),
                new TextContentNode("second")), "译文").structure;
        assertTrue(structure.contains("rightGapOpaque=5"));
        assertTrue(structure.contains("rightGapTypesTruncated=true"));
        assertTrue(structure.contains("UnknownOne"));
        assertFalse(structure.contains("UnknownFive"));
    }
}
