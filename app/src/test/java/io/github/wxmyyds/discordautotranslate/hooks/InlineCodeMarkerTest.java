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

/** Inline-code occurrences provide exact same-line anchors; code is never read or translated. */
public class InlineCodeMarkerTest {
    @Before public void setup() { ContentNodeTestProfile.activate(ContentNodesTest.class, EmojiAnchorTest.class,
            InlineCodeMarkerTest.class); }
    @After public void cleanup() { ContentNodeTestProfile.clear(); }
    private static List<Object> list(Object... nodes) { return new ArrayList<>(Arrays.asList(nodes)); }
    private static TextContentNode text(String text) { return new TextContentNode(text); }
    private static ContentTree tree(Object... nodes) {
        ContentTree tree = ContentTree.compile(list(nodes));
        assertNotNull(tree);
        return tree;
    }
    private static String content(Object node) { return ((TextContentNode) node).getContent(); }
    private static String extract(ContentTree tree) { return TextExtractor.extractTree(tree).text; }
    private static void reject(ContentTree tree, String translated, ContentNodes.RejectionReason reason) {
        ContentNodes.Rejection failure = assertThrows(ContentNodes.Rejection.class,
                () -> ContentNodes.replaceChecked(tree, translated));
        assertEquals(reason, failure.reason);
        assertEquals(reason.name(), failure.getMessage());
        assertNull(failure.getCause());
        assertNull(ContentNodes.replacePlanned(tree, translated));
    }

    @Test public void observedSameLineCodeGapAlignsWithoutInventingNewline() {
        InlineCodeContentNode code = new InlineCodeContentNode("private-code");
        ContentTree tree = tree(text("Run "), code, text(" now"));
        assertEquals("Run <keep1/> now", extract(tree));
        assertEquals(2, tree.segmentCount);
        assertEquals("<keep1/>", tree.events.get(1).retainedMarker);
        List<Object> out = ContentNodes.replaceChecked(tree, "运行 <keep1/> 现在");
        assertEquals("运行 ", content(out.get(0)));
        assertSame(code, out.get(1));
        assertEquals(" 现在", content(out.get(2)));
        assertEquals("运行 <keep1/> 现在", extract(ContentTree.compile(out)));
        assertFalse(extract(tree).contains("private-code"));
        assertFalse(extract(tree).contains("\n"));
        reject(tree, "运行现在", ContentNodes.RejectionReason.RETAINED_MARKER);
    }

    @Test public void repeatedHostCodeGetsDistinctOccurrenceAnchors() {
        InlineCodeContentNode code = new InlineCodeContentNode("same");
        ContentTree tree = tree(text("First"), code, text("Second"), code, text("Third"));
        assertEquals("First<keep1/>Second<keep2/>Third", extract(tree));
        assertNotSame(tree.events.get(1).node, tree.events.get(3).node);
        List<Object> out = ContentNodes.replaceChecked(tree, "第一<keep1/>第二<keep2/>第三");
        assertSame(code, out.get(1));
        assertSame(code, out.get(3));
        assertEquals("第一", content(out.get(0)));
        assertEquals("第二", content(out.get(2)));
        assertEquals("第三", content(out.get(4)));
    }

    @Test public void missingDuplicateUnknownReorderedAndMalformedMarkersReject() {
        ContentTree tree = tree(text("A"), new InlineCodeContentNode("one"), text("B"),
                new InlineCodeContentNode("two"), text("C"));
        for (String response : new String[]{"甲乙丙", "甲<keep1/>乙丙", "甲<keep1/>乙<keep1/>丙",
                "甲<keep2/>乙<keep1/>丙", "甲<keep1/>乙<keep3/>丙", "甲<keep1 />乙<keep2/>丙",
                "甲<keep1>乙<keep2/>丙", "甲<KEEP1/>乙<keep2/>丙", "甲<keep01/>乙<keep2/>丙",
                "甲<keep1/>乙<keep2/>丙<keep", "甲<keep1/>乙<keep2/>丙</keep9>"}) {
            reject(tree, response, ContentNodes.RejectionReason.RETAINED_MARKER);
        }
    }

    @Test public void noCodeTreeRejectsFabricatedMarkers() {
        reject(tree(text("Plain")), "正文<keep1/>", ContentNodes.RejectionReason.RETAINED_MARKER);
    }

    @Test public void markersInsideStyleCannotLeakIntoTextNode() {
        ContentTree tree = tree(new StrongContentNode(list(text("Title"))),
                new InlineCodeContentNode("one"), text("Body"));
        assertEquals("<x1>Title</x1><keep1/>Body", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "<x1>标题</x1><keep1/>正文");
        assertEquals("标题", content(((StrongContentNode) out.get(0)).getContent().get(0)));
        assertEquals("正文", content(out.get(2)));
        reject(tree, "<x1>标题<keep1/></x1>正文", ContentNodes.RejectionReason.ANCHOR_ORDER);
    }

    @Test public void styledAndPendingPlainRunsFlushAtSameInlineCodeToken() {
        InlineCodeContentNode code = new InlineCodeContentNode("one");
        ContentTree tree = tree(new StrongContentNode(list(text("Title"))), text("Before"), code,
                text("After"), new StrongContentNode(list(text("End"))));
        assertEquals("<x1>Title</x1>Before<keep1/>After<x2>End</x2>", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree,
                "<x1>标题</x1>之前<keep1/>之后<x2>结尾</x2>");
        assertEquals("之前", content(out.get(1)));
        assertSame(code, out.get(2));
        assertEquals("之后", content(out.get(3)));
        assertEquals("结尾", content(((StrongContentNode) out.get(4)).getContent().get(0)));
    }

    @Test public void consecutivePrefixAndSuffixCodeAnchorsConsumeNoTextSlots() {
        InlineCodeContentNode code = new InlineCodeContentNode("one");
        ContentTree tree = tree(code, code, text("Body"), code);
        assertEquals("<keep1/><keep2/>Body<keep3/>", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "<keep1/><keep2/>正文<keep3/>");
        assertSame(code, out.get(0));
        assertSame(code, out.get(1));
        assertEquals("正文", content(out.get(2)));
        assertSame(code, out.get(3));
        reject(tree, "多余<keep1/><keep2/>正文<keep3/>", ContentNodes.RejectionReason.UNASSIGNED_TEXT);
    }

    @Test public void realLfAcrossCodeIsPreservedOnceOnEitherSide() {
        for (boolean before : new boolean[]{true, false}) {
            TextContentNode first = text(before ? "First\n" : "First");
            TextContentNode second = text(before ? "Second" : "\nSecond");
            ContentTree tree = tree(first, new InlineCodeContentNode("hidden\n\ncode"), second);
            List<Object> out = ContentNodes.replaceChecked(tree,
                    before ? "第一\n<keep1/>第二" : "第一<keep1/>\n第二");
            assertEquals(before ? "第一\n" : "第一", content(out.get(0)));
            assertEquals(before ? "第二" : "\n第二", content(out.get(2)));
            assertEquals(before ? "First\n<keep1/>Second" : "First<keep1/>\nSecond", extract(tree));
        }
    }

    @Test public void multilineSlotsKeepInternalNewlinesAndCapturedAllotment() {
        ContentTree tree = tree(text("First\nContinuation"), new InlineCodeContentNode("secret"),
                text("Second\nContinuation"));
        List<Object> out = ContentNodes.replaceChecked(tree, "第一\n补充<keep1/>第二\n补充");
        assertEquals("第一\n补充", content(out.get(0)));
        assertEquals("第二\n补充", content(out.get(2)));
    }

    @Test public void codeMarkerAndUrlEmojiUseOneOrderedAnchorStream() {
        String url = "https://example.invalid/path";
        LinkContentNode link = new LinkContentNode(list(text(url)), new Object());
        CustomEmojiContentNode emoji = new CustomEmojiContentNode(42L, ":party:");
        ContentTree tree = tree(text("A"), new InlineCodeContentNode("one"), text("B"), link,
                text("C"), emoji, text("D"));
        List<Object> out = ContentNodes.replaceChecked(tree, "甲<keep1/>乙" + url + "丙:party:丁");
        assertSame(link, out.get(3));
        assertSame(emoji, out.get(5));
        assertEquals("丁", content(out.get(6)));
        reject(tree, "甲" + url + "乙<keep1/>丙:party:丁", ContentNodes.RejectionReason.PARAGRAPH_MISSING);
    }

    @Test public void nativeListShapesAndTitleBoundaryRemainWithCodeMarkers() {
        InlineCodeContentNode code = new InlineCodeContentNode("one");
        HeadingContentNode heading = new HeadingContentNode(list(text("Title")), 3);
        BulletListContentNode list = new BulletListContentNode(7L, false,
                Arrays.asList(list(text("Run"), code, text("Now")), list(text("Next"))));
        ContentTree tree = tree(heading, list);
        assertEquals("Title\n• Run<keep1/>Now\n• Next", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "标题\n• 运行<keep1/>现在\n• 下一项");
        assertEquals(3, ((HeadingContentNode) out.get(0)).component2());
        BulletListContentNode rebuilt = (BulletListContentNode) out.get(1);
        assertEquals(Long.valueOf(7L), rebuilt.component1());
        assertSame(code, rebuilt.getItems().get(0).get(1));
        assertEquals("运行", content(rebuilt.getItems().get(0).get(0)));
        assertEquals("现在", content(rebuilt.getItems().get(0).get(2)));
        assertEquals("下一项", content(rebuilt.getItems().get(1).get(0)));
    }

    @Test public void supplementaryTextBeforeMarkerDoesNotCorruptUtf16Boundaries() {
        ContentTree tree = tree(text("First\uD83D\uDE00"), new InlineCodeContentNode("one"), text("Second"));
        List<Object> out = ContentNodes.replaceChecked(tree, "第一\uD83D\uDE00<keep1/>第二");
        assertEquals("第一\uD83D\uDE00", content(out.get(0)));
        assertEquals("第二", content(out.get(2)));
    }

    @Test public void sourceReservedNamespaceRejectsBeforeSendingAndSplitCollisionRejectsExtraction() {
        for (String source : new String[]{"<keep1/>", "literal </keep", "< KEEP2 />", "<keeping>"}) {
            assertNull(ContentTree.compile(list(text(source), new InlineCodeContentNode("one"))));
            assertNull(TextExtractor.extract(new PrimitiveStructurableText(source)));
        }
        ContentTree split = tree(text("<"), text("keep1/>"), new InlineCodeContentNode("one"), text("Body"));
        assertNull(TextExtractor.extractTree(split));
        ContentTree styledSplit = tree(new StrongContentNode(list(text("<ke"))),
                new EmphasisContentNode(list(text("ep9/>"))), new InlineCodeContentNode("one"));
        assertNull(TextExtractor.extractTree(styledSplit));
        ContentTree styled = tree(new StrongContentNode(list(text("First"))),
                new EmphasisContentNode(list(text("Second"))));
        reject(styled, "<x1><ke</x1><x2>ep9/></x2>", ContentNodes.RejectionReason.RETAINED_MARKER);
    }

    public static final class GetterFixture {
        public static final class InlineCodeContentNode {
            int reads;
            public String getContent() { reads++; throw new AssertionError("must not read native code"); }
        }
    }

    @Test public void markerCaptureDoesNotReadInlineCodeContentGetter() {
        GetterFixture.InlineCodeContentNode code = new GetterFixture.InlineCodeContentNode();
        ContentTree tree = tree(code, text("Body"));
        assertNull(tree.events.get(0).node.text);
        assertEquals("<keep1/>", tree.events.get(0).retainedMarker);
        assertEquals("<keep1/>Body", extract(tree));
        assertSame(code, ContentNodes.replaceChecked(tree, "<keep1/>正文").get(0));
        assertEquals(0, code.reads);
    }
}
