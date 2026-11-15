package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import io.github.wxmyyds.discordautotranslate.core.ContentTree;
import io.github.wxmyyds.discordautotranslate.core.EnglishDetector;
import io.github.wxmyyds.discordautotranslate.core.Scheduler;
import io.github.wxmyyds.discordautotranslate.core.TextExtractor;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.*;
import io.github.wxmyyds.discordautotranslate.hooks.EmojiAnchorTest.CustomEmojiContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.OpaqueNodeTranslationTest.ChannelMentionContentNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Every opaque/code leaf follows the same occurrence anchor contract, including unknown types. */
public class RetainedLeafMarkerTest {
    @Before public void setup() {
        ContentNodeTestProfile.activate(ContentNodesTest.class, EmojiAnchorTest.class, OpaqueNodeTranslationTest.class);
    }
    @After public void cleanup() { ContentNodeTestProfile.clear(); }
    private static List<Object> list(Object... nodes) { return new ArrayList<>(Arrays.asList(nodes)); }
    private static TextContentNode text(String s) { return new TextContentNode(s); }
    private static ContentTree tree(Object... nodes) {
        ContentTree tree = ContentTree.compile(list(nodes));
        assertNotNull(tree);
        return tree;
    }
    private static String extract(ContentTree tree) { return TextExtractor.extractTree(tree).text; }
    private static String content(Object node) { return ((TextContentNode) node).getContent(); }
    private static void reject(ContentTree tree, String translated) {
        ContentNodes.Rejection failure = assertThrows(ContentNodes.Rejection.class,
                () -> ContentNodes.replaceChecked(tree, translated));
        assertEquals(ContentNodes.RejectionReason.RETAINED_MARKER, failure.reason);
        assertEquals("RETAINED_MARKER", failure.getMessage());
        assertNull(failure.getCause());
        assertNull(ContentNodes.replacePlanned(tree, translated));
    }

    /** Deliberately not registered in a profile: preserve unknown timestamp-like leaf without introspection. */
    public static final class h5 {
        int reads;
        public String getContent() { reads++; throw new AssertionError("Do not inspect retained data"); }
        public String d() { reads++; throw new AssertionError("Do not read formatted timestamp"); }
        @Override public String toString() { throw new AssertionError("Do not serialize retained data"); }
    }
    public static final class FutureNode {
        int reads;
        public List<Object> getContent() { reads++; throw new AssertionError("Do not descend into unknown content"); }
    }

    @Test public void observedTimestampLikeOpaqueBetweenRootTextsHasExactBoundary() {
        h5 timestamp = new h5();
        ContentTree tree = tree(text("First"), timestamp, text("Second"));
        assertEquals("First<keep1/>Second", extract(tree));
        assertEquals(ContentTree.Kind.OPAQUE, tree.events.get(1).node.kind);
        List<Object> out = ContentNodes.replaceChecked(tree, "第一<keep1/>第二");
        assertEquals("第一", content(out.get(0)));
        assertSame(timestamp, out.get(1));
        assertEquals("第二", content(out.get(2)));
        assertEquals(0, timestamp.reads);
        assertEquals("第一<keep1/>第二", extract(ContentTree.compile(out)));
        reject(tree, "第一第二");
    }

    @Test public void oneNamespaceOrdersInlineCodeOpaqueAndCodeBlockTogether() {
        InlineCodeContentNode inline = new InlineCodeContentNode("secret-inline");
        h5 timestamp = new h5();
        CodeBlockContentNode block = new CodeBlockContentNode("secret-block", "java", true);
        ContentTree tree = tree(text("A"), inline, text("B"), timestamp, text("C"), block, text("D"));
        assertEquals("A<keep1/>B<keep2/>C<keep3/>D", extract(tree));
        TextExtractor.Result source = TextExtractor.extractTree(tree);
        assertEquals(4, source.segmentCount);
        assertEquals(4, source.textNodeCount);
        assertEquals(2, source.opaqueNodeCount);
        assertTrue(source.codeHeavy);
        assertFalse(source.text.contains("secret"));
        List<Object> out = ContentNodes.replaceChecked(tree, "甲<keep1/>乙<keep2/>丙<keep3/>丁");
        assertSame(inline, out.get(1));
        assertSame(timestamp, out.get(3));
        assertSame(block, out.get(5));
        assertEquals("丁", content(out.get(6)));
        reject(tree, "甲<keep1/>乙<keep3/>丙<keep2/>丁");
        reject(tree, "甲<keep1/>乙丙<keep3/>丁");
    }

    @Test public void unknownChildrenAreNotReadOrUsedAsTranslationSlots() {
        FutureNode unknown = new FutureNode();
        ContentTree tree = tree(text("A"), unknown, text("B"));
        assertEquals("A<keep1/>B", extract(tree));
        assertEquals(2, tree.segmentCount);
        List<Object> out = ContentNodes.replaceChecked(tree, "甲<keep1/>乙");
        assertSame(unknown, out.get(1));
        assertEquals(0, unknown.reads);
    }

    @Test public void nullOccurrencesHaveNoMarkerAndNoInventedBoundary() {
        ContentTree tree = tree(text("A"), null, new h5(), text("B"));
        assertEquals("A<keep1/>B", extract(tree));
        assertEquals(3, tree.events.size());
        List<Object> out = ContentNodes.replaceChecked(tree, "甲<keep1/>乙");
        assertNull(out.get(1));
        assertSame(tree.root.children.get(2).original, out.get(2));
        ContentTree plainNull = tree(text("A"), null, text("B"));
        assertEquals("AB", extract(plainNull));
        ContentNodes.Rejection failure = assertThrows(ContentNodes.Rejection.class,
                () -> ContentNodes.replaceChecked(plainNull, "甲\n乙"));
        assertEquals(ContentNodes.RejectionReason.SOURCE_BOUNDARY_AMBIGUOUS, failure.reason);
    }

    @Test public void sharedOpaqueOccurrencesAreNumberedIndependently() {
        h5 shared = new h5();
        ContentTree tree = tree(shared, text("A"), shared, text("B"), shared);
        assertEquals("<keep1/>A<keep2/>B<keep3/>", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "<keep1/>甲<keep2/>乙<keep3/>");
        assertSame(shared, out.get(0));
        assertSame(shared, out.get(2));
        assertSame(shared, out.get(4));
        assertEquals("乙", content(out.get(3)));
    }

    @Test public void realLfAcrossRetainedLeavesRemainsInOriginalTextNodesOnly() {
        h5 timestamp = new h5();
        TextContentNode blank = text("\n\n");
        ContentTree tree = tree(text("First\n"), timestamp, text("Second"), blank,
                new FutureNode(), text("Third"));
        assertEquals("First\n<keep1/>Second\n\n<keep2/>Third", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "第一\n<keep1/>第二\n\n<keep2/>第三");
        assertEquals("第一\n", content(out.get(0)));
        assertSame(timestamp, out.get(1));
        assertEquals("第二", content(out.get(2)));
        assertSame(blank, out.get(3));
        assertEquals("第三", content(out.get(5)));
    }

    @Test public void retainedNodeInsideMarkedLinkIsBetweenFlatTextTags() {
        h5 timestamp = new h5();
        Object context = new Object();
        LinkContentNode link = new LinkContentNode(list(text("A"), timestamp, text("B")), context);
        ContentTree tree = tree(link);
        assertEquals("<x1>A</x1><keep1/><x2>B</x2>", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "<x1>甲</x1><keep1/><x2>乙</x2>");
        LinkContentNode rebuilt = (LinkContentNode) out.get(0);
        assertSame(context, rebuilt.component2());
        assertSame(timestamp, rebuilt.getContent().get(1));
        assertEquals("乙", content(rebuilt.getContent().get(2)));
    }

    @Test public void retainedNodesBetweenStylesCannotAbsorbTranslatedText() {
        h5 timestamp = new h5();
        ContentTree tree = tree(new StrongContentNode(list(text("A"))), timestamp,
                new EmphasisContentNode(list(text("B"))));
        assertEquals("<x1>A</x1><keep1/><x2>B</x2>", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "<x1>甲</x1><keep1/><x2>乙</x2>");
        assertSame(timestamp, out.get(1));
        assertEquals("乙", content(((EmphasisContentNode) out.get(2)).getContent().get(0)));
    }

    @Test public void listShapesAndMentionChildrenStayNative() {
        ChannelMentionContentNode mention = new ChannelMentionContentNode(list(text("private-name")));
        h5 timestamp = new h5();
        BulletListContentNode list = new BulletListContentNode(3L, false,
                Arrays.asList(list(text("A"), mention, text("B")), list(timestamp, text("C"))));
        ContentTree tree = tree(list);
        assertEquals("\n• A<keep1/>B\n• <keep2/>C", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "• 甲<keep1/>乙\n• <keep2/>丙");
        BulletListContentNode rebuilt = (BulletListContentNode) out.get(0);
        assertSame(mention, rebuilt.getItems().get(0).get(1));
        assertSame(timestamp, rebuilt.getItems().get(1).get(0));
        assertEquals("丙", content(rebuilt.getItems().get(1).get(1)));
        assertFalse(extract(tree).contains("private-name"));
    }

    @Test public void bareUrlAndEmojiKeepTheirAnchorTypeAlongsideRetainedMarkers() {
        String url = "https://example.invalid/id";
        LinkContentNode link = new LinkContentNode(list(text(url)), new Object());
        CustomEmojiContentNode emoji = new CustomEmojiContentNode(42L, ":party:");
        h5 timestamp = new h5();
        ContentTree tree = tree(text("A"), link, text("B"), timestamp, text("C"), emoji, text("D"));
        assertEquals("A " + url + " B<keep1/>C :party: D", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "甲" + url + "乙<keep1/>丙:party:丁");
        assertSame(link, out.get(1));
        assertSame(timestamp, out.get(3));
        assertSame(emoji, out.get(5));
        assertEquals("丁", content(out.get(6)));
    }

    @Test public void retainedOnlyInputAndShortBodyDoNotBecomeNaturalLanguageByMarkers() {
        ContentTree retained = tree(new h5(), new FutureNode());
        TextExtractor.Result empty = TextExtractor.extractTree(retained);
        assertEquals("<keep1/><keep2/>", empty.text);
        assertEquals(0, empty.segmentCount);
        assertFalse(EnglishDetector.evaluate(empty.text, empty.textNodeCount, empty.opaqueNodeCount,
                empty.codeHeavy, "简体中文").shouldTranslate);
        assertNull(ContentNodes.replacePlanned(retained, "<keep1/><keep2/>"));
        ContentTree shortBody = tree(new h5(), text("ok"));
        TextExtractor.Result shortSource = TextExtractor.extractTree(shortBody);
        assertFalse(EnglishDetector.evaluate(shortSource.text, shortSource.textNodeCount,
                shortSource.opaqueNodeCount, shortSource.codeHeavy, "简体中文").shouldTranslate);
    }

    @Test public void itemAnchorPrefixDoesNotConsumeBodyTextOrPreviousItem() {
        h5 retained = new h5();
        BulletListContentNode list = new BulletListContentNode(null, false,
                Arrays.asList(list(text("First")), list(retained, text("Second"))));
        ContentTree tree = tree(list);
        List<Object> out = ContentNodes.replaceChecked(tree, "• 第一\n• <keep1/>第二");
        BulletListContentNode rebuilt = (BulletListContentNode) out.get(0);
        assertEquals("第一", content(rebuilt.getItems().get(0).get(0)));
        assertSame(retained, rebuilt.getItems().get(1).get(0));
        assertEquals("第二", content(rebuilt.getItems().get(1).get(1)));
        BulletListContentNode starts = new BulletListContentNode(null, false,
                Arrays.asList(list(retained, text("Body"))));
        ContentNodes.Rejection failure = assertThrows(ContentNodes.Rejection.class,
                () -> ContentNodes.replaceChecked(tree(starts), "• 多余正文<keep1/>正文"));
        assertEquals(ContentNodes.RejectionReason.UNASSIGNED_TEXT, failure.reason);
    }

    @Test public void listPrefixAllowsCapturedLfBeforeFirstRetainedAnchor() {
        for (Object newline : list(text("\n"), new NewLineContentNode(), new LineBreakContentNode())) {
            for (boolean styled : new boolean[]{false, true}) {
                h5 retained = new h5();
                Object body = styled ? new StrongContentNode(list(text("Body"))) : text("Body");
                BulletListContentNode list = new BulletListContentNode(null, false,
                        Arrays.asList(list(text("Earlier")), list(newline, retained, body)));
                ContentTree tree = tree(list);
                String bodySource = styled ? "<x1>Body</x1>" : "Body";
                assertEquals("\n• Earlier\n• \n<keep1/>" + bodySource, extract(tree));
                List<Object> out = ContentNodes.replaceChecked(tree,
                        "• 前项\n• \n<keep1/>" + (styled ? "<x1>正文</x1>" : "正文"));
                BulletListContentNode rebuilt = (BulletListContentNode) out.get(0);
                assertEquals("前项", content(rebuilt.getItems().get(0).get(0)));
                assertSame(newline, rebuilt.getItems().get(1).get(0));
                assertSame(retained, rebuilt.getItems().get(1).get(1));
                assertEquals("正文", content(styled
                        ? ((StrongContentNode) rebuilt.getItems().get(1).get(2)).getContent().get(0)
                        : rebuilt.getItems().get(1).get(2)));
            }
        }
    }

    @Test public void generalizedInputHashCannotReuseOldWhitespaceOnlyPlan() {
        ContentTree tree = tree(text("hello"), new h5(), text("world"));
        assertNotEquals(Scheduler.contentHash("hello world", "config"),
                Scheduler.contentHash(extract(tree), "config"));
        assertEquals(Scheduler.contentHash(extract(tree), "config"),
                Scheduler.contentHash(extract(tree(text("hello"), new h5(), text("world"))), "config"));
    }
}
