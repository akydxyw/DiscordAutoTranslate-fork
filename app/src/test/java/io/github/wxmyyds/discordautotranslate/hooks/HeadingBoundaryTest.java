package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import io.github.wxmyyds.discordautotranslate.core.ContentTree;
import io.github.wxmyyds.discordautotranslate.core.TextExtractor;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.*;
import io.github.wxmyyds.discordautotranslate.hooks.EmojiAnchorTest.CustomEmojiContentNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Logical heading block boundaries are captured once; native models still render all spacing. */
public class HeadingBoundaryTest {
    @Before public void setup() {
        ContentNodeTestProfile.activate(ContentNodesTest.class, EmojiAnchorTest.class);
    }
    @After public void cleanup() { ContentNodeTestProfile.clear(); }

    private static List<Object> list(Object... nodes) { return new ArrayList<>(Arrays.asList(nodes)); }
    private static TextContentNode text(String s) { return new TextContentNode(s); }
    private static HeadingContentNode heading(Object... nodes) { return new HeadingContentNode(list(nodes), 2); }
    private static ContentTree tree(Object... nodes) {
        ContentTree tree = ContentTree.compile(list(nodes));
        assertNotNull(tree);
        return tree;
    }
    private static String extract(ContentTree tree) { return TextExtractor.extractTree(tree).text; }
    private static String content(Object node) { return ((TextContentNode) node).getContent(); }
    private static long breaks(ContentTree tree) {
        return tree.events.stream().filter(e -> e.headingBreakBefore).count();
    }
    private static void reject(ContentTree tree, String translated, ContentNodes.RejectionReason reason) {
        ContentNodes.Rejection failure = assertThrows(ContentNodes.Rejection.class,
                () -> ContentNodes.replaceChecked(tree, translated));
        assertEquals(reason, failure.reason);
        assertNull(ContentNodes.replacePlanned(tree, translated));
    }

    @Test public void observedHeadingDirectlyFollowedByRootTextGetsSharedBoundary() {
        HeadingContentNode heading = heading(text("Title"));
        TextContentNode body = text("Body");
        ContentTree tree = tree(heading, body);
        assertEquals("Title\nBody", extract(tree));
        assertEquals(2, tree.segmentCount);
        assertEquals(2, tree.events.size());
        assertEquals(1, breaks(tree));
        assertFalse(tree.events.get(0).headingBreakBefore);
        assertTrue(tree.events.get(1).headingBreakBefore);
        List<Object> output = ContentNodes.replaceChecked(tree, "标题\n正文");
        HeadingContentNode rebuilt = (HeadingContentNode) output.get(0);
        assertEquals(2, rebuilt.component2());
        assertEquals("标题", content(rebuilt.getContent().get(0)));
        assertEquals("正文", content(output.get(1)));
        assertEquals("Title", content(heading.getContent().get(0)));
        assertEquals("Body", body.getContent());
        assertEquals("标题\n正文", extract(ContentTree.compile(output)));
    }

    @Test public void rootTextBeforeHeadingAndAfterHeadingUsesTwoBoundaries() {
        ContentTree tree = tree(text("Before"), heading(text("Title")), text("After"));
        assertEquals("Before\nTitle\nAfter", extract(tree));
        assertEquals(2, breaks(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "之前\n标题\n之后");
        assertEquals("之前", content(out.get(0)));
        assertEquals("标题", content(((HeadingContentNode) out.get(1)).getContent().get(0)));
        assertEquals("之后", content(out.get(2)));
    }

    @Test public void explicitTextLfAndBreakNodesAreNotDoubledOnEitherSide() {
        for (Object boundary : list(text("\n"), new NewLineContentNode(), new LineBreakContentNode())) {
            ContentTree tree = tree(text("Before"), boundary, heading(text("Title")), boundary, text("After"));
            assertEquals("Before\nTitle\nAfter", extract(tree));
            assertEquals(0, breaks(tree));
            List<Object> out = ContentNodes.replaceChecked(tree, "之前\n标题\n之后");
            assertSame(boundary, out.get(1));
            assertSame(boundary, out.get(3));
        }
    }

    @Test public void leadingTrailingAndHorizontalWhitespaceIsPreservedWithoutDoubleLf() {
        ContentTree leading = tree(heading(text("Title")), text(" \nBody"));
        ContentTree trailing = tree(heading(text("Title\n ")), text("Body"));
        assertEquals("Title \nBody", extract(leading));
        assertEquals("Title\n Body", extract(trailing));
        assertEquals(0, breaks(leading));
        assertEquals(0, breaks(trailing));
        assertEquals(" \n正文", content(ContentNodes.replaceChecked(leading, "标题\n正文").get(1)));
        HeadingContentNode out = (HeadingContentNode) ContentNodes.replaceChecked(trailing, "标题\n正文").get(0);
        assertEquals("标题\n ", content(out.getContent().get(0)));
        TextContentNode space = text(" \t");
        ContentTree horizontal = tree(heading(text("Title")), space, text("Body"));
        assertEquals("Title \t\nBody", extract(horizontal));
        assertEquals(1, breaks(horizontal));
        assertSame(space, ContentNodes.replaceChecked(horizontal, "标题\n正文").get(1));
    }

    @Test public void sourceBlankParagraphRemainsBlankParagraph() {
        TextContentNode blank = text("\n \n");
        ContentTree tree = tree(heading(text("Title")), blank, text("Body"));
        assertEquals("Title\n \nBody", extract(tree));
        assertEquals(0, breaks(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "标题\n\n正文");
        assertSame(blank, out.get(1));
        reject(tree, "标题\n正文", ContentNodes.RejectionReason.PARAGRAPH_MISSING);
    }

    @Test public void sharedHeadingObjectOccurrencesHaveIndependentBoundaries() {
        HeadingContentNode shared = heading(text("Same"));
        ContentTree tree = tree(shared, shared);
        assertEquals("Same\nSame", extract(tree));
        assertEquals(1, breaks(tree));
        assertNotSame(tree.events.get(0).containers.get(0), tree.events.get(1).containers.get(0));
        List<Object> out = ContentNodes.replaceChecked(tree, "第一\n第二");
        assertEquals("第一", content(((HeadingContentNode) out.get(0)).getContent().get(0)));
        assertEquals("第二", content(((HeadingContentNode) out.get(1)).getContent().get(0)));
    }

    @Test public void emptyHeadingDoesNotFabricateSeparationBetweenPlainSlots() {
        HeadingContentNode empty = heading();
        ContentTree tree = tree(text("First"), empty, text("Second"));
        assertEquals("FirstSecond", extract(tree));
        assertEquals(0, breaks(tree));
        reject(tree, "第一\n第二", ContentNodes.RejectionReason.SOURCE_BOUNDARY_AMBIGUOUS);
    }

    @Test public void nestedNonHeadingContainersDoNotSplitWithinHeading() {
        ContentTree tree = tree(heading(new ParagraphContentNode(list(text("First"))), text("Second")));
        assertEquals("FirstSecond", extract(tree));
        assertEquals(0, breaks(tree));
        reject(tree, "第一\n第二", ContentNodes.RejectionReason.SOURCE_BOUNDARY_AMBIGUOUS);
    }

    @Test public void nestedStyleBoundaryStaysOutsideMarkerAndOriginalTreeShape() {
        ContentTree tree = tree(heading(new StrongContentNode(list(text("Title")))), text("Body"));
        assertEquals("<x1>Title</x1>\nBody", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "<x1>标题</x1>\n正文");
        assertEquals("标题", content(((StrongContentNode) ((HeadingContentNode) out.get(0))
                .getContent().get(0)).getContent().get(0)));
        assertEquals("正文", content(out.get(1)));
        assertEquals("<x1>标题</x1>\n正文", extract(ContentTree.compile(out)));
    }

    @Test public void internalMultilineHeadingUsesCapturedSlotLineCounts() {
        ContentTree tree = tree(heading(text("First\nSecond")), text("Body"));
        assertEquals("First\nSecond\nBody", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "第一\n第二\n正文");
        assertEquals("第一\n第二", content(((HeadingContentNode) out.get(0)).getContent().get(0)));
        reject(tree, "标题\n正文", ContentNodes.RejectionReason.LINE_COUNT);
    }

    @Test public void singleHeadingDoesNotAddLeadingOrTrailingRequestLf() {
        ContentTree tree = tree(heading(text("Title")));
        assertEquals("Title", extract(tree));
        assertEquals(0, breaks(tree));
        assertEquals(1, ContentNodes.replaceChecked(tree, "标题").size());
    }

    @Test public void mergedAndExtraResponseLinesStillRejectWholeTree() {
        ContentTree tree = tree(heading(text("Title")), text("Body"));
        reject(tree, "标题正文", ContentNodes.RejectionReason.LINE_COUNT);
        reject(tree, "标题\n正文\n多余", ContentNodes.RejectionReason.LINE_COUNT);
        reject(tree, "标题\n\n正文", ContentNodes.RejectionReason.LINE_COUNT);
    }

    @Test public void headingInsideListUsesItemBoundaryAndStillSeparatesOnListExit() {
        BulletListContentNode list = new BulletListContentNode(null, false,
                Arrays.asList(list(heading(text("Title")))));
        ContentTree tree = tree(list, text("Body"));
        assertEquals("\n• Title\nBody", extract(tree));
        assertEquals(1, breaks(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "• 标题\n正文");
        assertEquals("正文", content(out.get(1)));
        HeadingContentNode rebuilt = (HeadingContentNode) ((BulletListContentNode) out.get(0))
                .getItems().get(0).get(0);
        assertEquals("标题", content(rebuilt.getContent().get(0)));
    }

    @Test public void headingFollowedByListDoesNotDoubleItemLf() {
        BulletListContentNode list = new BulletListContentNode(null, false, Arrays.asList(list(text("Item"))));
        ContentTree tree = tree(heading(text("Title")), list);
        assertEquals("Title\n• Item", extract(tree));
        assertEquals(0, breaks(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "标题\n• 项目");
        assertEquals("项目", content(((BulletListContentNode) out.get(1)).getItems().get(0).get(0)));
    }

    @Test public void anchorLeafAtHeadingExitKeepsBoundaryAndIdentity() {
        String url = "https://example.invalid/path";
        LinkContentNode link = new LinkContentNode(list(text(url)), new Object());
        CustomEmojiContentNode emoji = new CustomEmojiContentNode(42L, ":party:");
        ContentTree tree = tree(heading(text("Title")), link, emoji, text("Body"));
        assertEquals("Title\n " + url + "  :party: Body", extract(tree));
        assertEquals(1, breaks(tree));
        assertTrue(tree.events.get(1).headingBreakBefore);
        List<Object> out = ContentNodes.replaceChecked(tree, "标题\n" + url + " :party: 正文");
        assertSame(link, out.get(1));
        assertSame(emoji, out.get(2));
        assertEquals("正文", content(out.get(3)));
    }

    @Test public void leadingBoundaryUsesPrefixLfNotInternalBodyLf() {
        ContentTree tree = tree(heading(text("Title")), text("First\nSecond"));
        assertEquals("Title\nFirst\nSecond", extract(tree));
        assertEquals(1, breaks(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "标题\n第一\n第二");
        assertEquals("第一\n第二", content(out.get(1)));
        reject(tree, "标题\n正文", ContentNodes.RejectionReason.LINE_COUNT);
    }

    @Test public void retainedCodeAtHeadingExitKeepsStructureAndBoundary() {
        CodeBlockContentNode code = new CodeBlockContentNode("secret", "java", false);
        ContentTree tree = tree(heading(text("Title")), code, text("Body"));
        assertEquals("Title\n<keep1/>Body", extract(tree));
        assertEquals(1, breaks(tree));
        assertTrue(io.github.wxmyyds.discordautotranslate.core.EnglishDetector.evaluate(
                extract(tree), TextExtractor.extractTree(tree).textNodeCount, "简体中文").shouldTranslate);
        List<Object> out = ContentNodes.replaceChecked(tree, "标题\n<keep1/>正文");
        assertSame(code, out.get(1));
        assertEquals("正文", content(out.get(2)));
        assertEquals("标题\n<keep1/>正文", extract(ContentTree.compile(out)));
        reject(tree, "标题\n正文", ContentNodes.RejectionReason.RETAINED_MARKER);
    }

    @Test public void capturedHeadingBoundaryDoesNotRereadMutatedWhitespace() {
        List<Object> children = list(text("Title"));
        List<Object> source = list(new HeadingContentNode(children, 2), text("Body"));
        ContentTree captured = ContentTree.compile(source);
        assertNotNull(captured);
        source.add(1, text("\n"));
        assertEquals("Title\nBody", extract(captured));
        assertEquals(1, breaks(captured));
        assertEquals(0, breaks(ContentTree.compile(source)));
    }
}
