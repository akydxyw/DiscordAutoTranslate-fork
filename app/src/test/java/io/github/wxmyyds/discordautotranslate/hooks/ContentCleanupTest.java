package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.wxmyyds.discordautotranslate.core.ContentTree;
import io.github.wxmyyds.discordautotranslate.core.TextExtractor;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.BulletListContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.CodeBlockContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.InlineCodeContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.LineBreakContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.LinkContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.NewLineContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.ParagraphContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.StrongContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.TextContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.UserMentionContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.EmojiAnchorTest.UnicodeEmojiContentNode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/** Strict interval output accounting and allocation cleanup must preserve occurrence snapshots. */
public class ContentCleanupTest {
    private static final String URL = "https://example.invalid/ExactId";
    private static final String EXTRA_URL = "https://extra.invalid/unrequested";

    @Before public void setup() {
        ContentNodeTestProfile.activate(ContentNodesTest.class, EmojiAnchorTest.class);
    }

    @After public void cleanup() { ContentNodeTestProfile.clear(); }

    @Test public void extraUrlParagraphsWithoutSourceAnchorsAreRejectedNotDiscarded() {
        ContentTree single = tree(text("First"));
        for (String extra : new String[]{EXTRA_URL, "www.extra.invalid/path", "extra.invalid/path"}) {
            rejectExtra(single, "第一\n\n" + extra, 1, 2);
            rejectExtra(single, extra + "\n\n第一", 1, 2);
        }
        ContentTree multiple = tree(text("First\n\nSecond"));
        rejectExtra(multiple, "第一\n\n" + EXTRA_URL + "\n\n第二", 2, 3);
        assertEquals("First\n\nSecond", extract(multiple));
    }

    @Test public void extraUrlParagraphsInStyleDelimitedIntervalsAreRejected() {
        ContentTree tree = tree(text("Before"), new StrongContentNode(list(text("Styled"))), text("After"));
        assertEquals("Before<x1>Styled</x1>After", extract(tree));
        rejectExtra(tree, "之前\n\n" + EXTRA_URL + "<x1>样式</x1>之后", 1, 2);
        rejectExtra(tree, "之前<x1>样式</x1>之后\n\n" + EXTRA_URL, 1, 2);
        List<Object> out = ContentNodes.replaceChecked(tree, "之前<x1>样式</x1>之后");
        assertEquals("之前", content(out.get(0)));
        assertEquals("样式", content(((StrongContentNode) out.get(1)).getContent().get(0)));
        assertEquals("之后", content(out.get(2)));
    }

    @Test public void repeatedSourceUrlAnchorsKeepIndependentMultilineAndParagraphIntervals() {
        LinkContentNode shared = link(URL);
        NewLineContentNode firstBreak = new NewLineContentNode();
        LineBreakContentNode lastBreak = new LineBreakContentNode();
        ContentTree tree = tree(text("a\nb"), firstBreak, text("c"), shared,
                text("d\n\ne"), shared, text("f"), lastBreak, text("g\nh"));
        assertEquals(6, tree.segmentCount);
        assertEquals("a\nb\nc " + URL + " d\n\ne " + URL + " f\ng\nh", extract(tree));
        String translated = "甲\n乙\n丙\n\n" + URL + "\n\n丁\n\n戊\n\n" + URL + "\n\n己\n庚\n辛";
        List<Object> out = ContentNodes.replaceChecked(tree, translated);
        assertEquals("甲\n乙", content(out.get(0)));
        assertSame(firstBreak, out.get(1));
        assertEquals("丙", content(out.get(2)));
        assertSame(shared, out.get(3));
        assertEquals("丁\n\n戊", content(out.get(4)));
        assertSame(shared, out.get(5));
        assertEquals("己", content(out.get(6)));
        assertSame(lastBreak, out.get(7));
        assertEquals("庚\n辛", content(out.get(8)));
        assertEquals("甲\n乙\n丙 " + URL + " 丁\n\n戊 " + URL + " 己\n庚\n辛", extract(compiled(out)));
        rejectExtra(tree, translated.replace("丁\n\n戊", "丁\n\n" + EXTRA_URL + "\n\n戊"), 2, 3);
        reject(tree, translated.replaceFirst("ExactId", "ChangedId"), ContentNodes.RejectionReason.URL_COUNT);
        reject(tree, translated + URL, ContentNodes.RejectionReason.URL_COUNT);
    }

    @Test public void ordinarySingleTextKeepsMultipleParagraphsAndItsSourceUrlChunk() {
        String original = " \nFirst\ncontinuation\n \n" + URL + "\n\nSecond\n\nThird \n";
        ContentTree tree = tree(text(original));
        ContentTree.Node node = tree.root.children.get(0);
        assertEquals(Arrays.asList("First\ncontinuation", URL, "Second", "Third"), node.chunks);
        assertEquals(Arrays.asList("\n \n", "\n\n", "\n\n"), node.separators);
        assertEquals(Arrays.asList(2, 1, 1, 1), node.chunkLineCounts);
        assertEquals(Arrays.asList(true, false, true, true), node.translatable);
        assertEquals(3, tree.segmentCount);
        assertEquals(original, extract(tree));
        String translated = " \n甲\n乙\n\n" + URL + "\n\n丙\n\n丁 \n";
        List<Object> out = ContentNodes.replaceChecked(tree, translated);
        assertEquals(" \n甲\n乙\n \n" + URL + "\n\n丙\n\n丁 \n", content(out.get(0)));
        assertEquals(content(out.get(0)), extract(compiled(out)));
        rejectExtra(tree, translated.replace("丙\n\n丁", "丙\n\n" + EXTRA_URL + "\n\n丁"), 2, 3);
    }

    @Test public void repeatedSourceUrlChunksInSharedTextConsumeOnlyTheirOwnIntervals() {
        String original = "First\n\n" + URL + "\n\nSecond";
        TextContentNode shared = text(original);
        NewLineContentNode lineBreak = new NewLineContentNode();
        ContentTree tree = tree(shared, lineBreak, shared);
        assertEquals(4, tree.segmentCount);
        assertNotSame(tree.root.children.get(0), tree.root.children.get(2));
        assertSame(shared, tree.root.children.get(0).original);
        assertSame(shared, tree.root.children.get(2).original);
        String translated = "甲\n\n" + URL + "\n\n乙\n丙\n\n" + URL + "\n\n丁";
        List<Object> out = ContentNodes.replaceChecked(tree, translated);
        assertEquals("甲\n\n" + URL + "\n\n乙", content(out.get(0)));
        assertSame(lineBreak, out.get(1));
        assertEquals("丙\n\n" + URL + "\n\n丁", content(out.get(2)));
        assertNotSame(out.get(0), out.get(2));
        assertEquals(translated, extract(compiled(out)));
        assertEquals(original, shared.getContent());
        rejectExtra(tree, translated + "\n\n" + EXTRA_URL, 1, 2);
    }

    @Test public void listFlagSlicesStillPreserveMultilineSlotsAndListMetadata() {
        BulletListContentNode bullets = new BulletListContentNode(3L, true,
                items(list(text("First")), list(text("Second\ncontinuation"))));
        ContentTree tree = tree(bullets);
        assertEquals("\n• First\n• Second\ncontinuation", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "• 第一\n• 第二\n补充");
        BulletListContentNode rebuilt = (BulletListContentNode) out.get(0);
        assertEquals(Long.valueOf(3L), rebuilt.component1());
        assertTrue(rebuilt.component2());
        assertEquals("第一", content(rebuilt.getItems().get(0).get(0)));
        assertEquals("第二\n补充", content(rebuilt.getItems().get(1).get(0)));
        assertEquals("\n• 第一\n• 第二\n补充", extract(compiled(out)));
        assertEquals("First", content(bullets.getItems().get(0).get(0)));
        assertEquals("Second\ncontinuation", content(bullets.getItems().get(1).get(0)));
    }

    @Test public void textWhitespaceAndBlankLineRulesRemainUnchanged() {
        for (String blank : new String[]{null, "", " \n\n\t"}) {
            ContentTree.Node node = tree(text(blank)).root.children.get(0);
            assertEquals(Collections.singletonList(""), node.chunks);
            assertTrue(node.separators.isEmpty());
            assertEquals(Collections.singletonList(false), node.translatable);
            assertEquals(Collections.singletonList(1), node.chunkLineCounts);
            assertEquals(0, node.segmentCount);
        }
        TextContentNode whitespace = text(" \n\n\t");
        ContentTree tree = tree(text("First"), whitespace, text("Second"));
        List<Object> out = ContentNodes.replaceChecked(tree, "\n\n第一\n \n第二\n\n");
        assertSame(whitespace, out.get(1));
        assertEquals("第一 \n\n\t第二", extract(compiled(out)));
        assertEquals("甲\n\n乙\n\n丙", content(ContentNodes.replaceChecked(
                tree(text("First\n\nSecond\n\nThird")), "甲\n\n乙\n\n丙").get(0)));
    }

    @Test public void allNonTextKindsHaveSharedEmptyMetadataAndImmutableChildren() throws Exception {
        ParagraphContentNode paragraph = new ParagraphContentNode(list(text("First")));
        BulletListContentNode bullets = new BulletListContentNode(3L, true,
                items(list(text("Item")), list(new UserMentionContentNode("mention"))));
        ContentTree tree = tree(paragraph, bullets, link(URL),
                new UnicodeEmojiContentNode("anchor", "unused", false),
                new InlineCodeContentNode("private"), new CodeBlockContentNode("private", "java", true),
                new NewLineContentNode(), new UserMentionContentNode("mention"), null,
                list(text("Nested")));
        EnumSet<ContentTree.Kind> seen = EnumSet.noneOf(ContentTree.Kind.class);
        inspectSnapshot(tree.root, seen);
        assertEquals(EnumSet.allOf(ContentTree.Kind.class), seen);
        assertSame(Collections.emptyList(), tree.root.children.get(2).children);
        assertFalse(capturedSequence(tree.root.children.get(2)).children.isEmpty());
        assertSame(tree.root.children.get(0), tree.events.get(0).containers.get(0));
    }

    @Test public void sharedContainerPositionsKeepSeparateSnapshotsAfterHostListsMutate() throws Exception {
        TextContentNode originalText = text("First\n\nSecond");
        List<Object> children = list(originalText);
        ParagraphContentNode shared = new ParagraphContentNode(children);
        NewLineContentNode lineBreak = new NewLineContentNode();
        List<Object> source = list(shared, lineBreak, shared);
        ContentTree tree = compiled(source);
        ContentTree.Node first = tree.root.children.get(0);
        ContentTree.Node second = tree.root.children.get(2);
        assertNotSame(first, second);
        assertSame(shared, first.original);
        assertSame(shared, second.original);
        assertNotSame(first.children, second.children);
        assertNotSame(first.children.get(0), second.children.get(0));
        assertSame(first.children, capturedSequence(first).children);
        assertSame(second.children, capturedSequence(second).children);
        assertSame(children, capturedSequence(first).original);
        assertSame(children, capturedSequence(second).original);
        assertSame(first, tree.events.get(0).containers.get(0));
        assertSame(second, tree.events.get(2).containers.get(0));
        children.clear();
        children.add(text("Changed host text"));
        source.clear();
        source.add(text("Changed root"));
        assertEquals(3, tree.root.children.size());
        assertEquals(1, first.children.size());
        assertSame(originalText, first.children.get(0).original);
        assertSame(originalText, second.children.get(0).original);
        assertFalse(ContentTree.retainedStructureMatches(tree.root));
        assertFalse(ContentTree.retainedStructureMatches(first));
        assertEquals("First\n\nSecond\nFirst\n\nSecond", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "甲\n\n乙\n丙\n\n丁");
        assertEquals(3, out.size());
        assertNotSame(out.get(0), out.get(2));
        assertEquals("甲\n\n乙", content(((ParagraphContentNode) out.get(0)).getContent().get(0)));
        assertSame(lineBreak, out.get(1));
        assertEquals("丙\n\n丁", content(((ParagraphContentNode) out.get(2)).getContent().get(0)));
        assertEquals("First\n\nSecond", originalText.getContent());
        assertEquals("Changed host text", content(children.get(0)));
    }

    @Test public void nestedUrlBackingShapeStillRejectsChangedRetainedDescendants() throws Exception {
        List<Object> inner = list(text(URL));
        ParagraphContentNode display = new ParagraphContentNode(inner);
        List<Object> children = list(display);
        LinkContentNode link = new LinkContentNode(children, new Object());
        ContentTree tree = tree(text("Before"), link);
        ContentTree.Node url = tree.root.children.get(1);
        ContentTree.Node captured = capturedSequence(url);
        assertEquals(ContentTree.Kind.URL, url.kind);
        assertSame(Collections.emptyList(), url.children);
        assertEquals(0, url.segmentCount);
        assertSame(children, captured.original);
        assertSame(display, captured.children.get(0).original);
        assertTrue(ContentTree.retainedStructureMatches(url));
        assertSame(link, ContentNodes.replaceChecked(tree, "之前 " + URL).get(1));
        inner.set(0, text("https://example.invalid/ChangedId"));
        assertSame(children, captured.original);
        assertEquals(URL, captured.children.get(0).children.get(0).text);
        assertEquals("Before " + URL + " ", extract(tree));
        assertFalse(ContentTree.retainedStructureMatches(url));
        reject(tree, "之前 " + URL, ContentNodes.RejectionReason.RETAINED_STRUCTURE_CHANGED);
    }

    @Test public void retainedContainerListAndSequenceShapesAreStillChecked() {
        UserMentionContentNode mention = new UserMentionContentNode("mention");
        List<Object> children = list(mention);
        ParagraphContentNode paragraph = new ParagraphContentNode(children);
        ContentTree paragraphTree = tree(text("Before"), paragraph);
        assertSame(paragraph, ContentNodes.replaceChecked(paragraphTree, "之前<keep1/>").get(1));
        children.add(mention);
        assertEquals(1, paragraphTree.root.children.get(1).children.size());
        assertEquals("Before<keep1/>", extract(paragraphTree));
        reject(paragraphTree, "之前<keep1/>", ContentNodes.RejectionReason.RETAINED_STRUCTURE_CHANGED);

        List<Object> item = list(mention);
        List<List<Object>> items = items(item);
        BulletListContentNode bullets = new BulletListContentNode(3L, false, items);
        ContentTree listTree = tree(text("Before"), bullets);
        String translated = "之前\n• <keep1/>";
        assertSame(bullets, ContentNodes.replaceChecked(listTree, translated).get(1));
        item.set(0, new UserMentionContentNode("changed"));
        reject(listTree, translated, ContentNodes.RejectionReason.RETAINED_STRUCTURE_CHANGED);
        item.set(0, mention);
        items.add(list(mention));
        assertEquals(1, listTree.root.children.get(1).children.size());
        reject(listTree, translated, ContentNodes.RejectionReason.RETAINED_STRUCTURE_CHANGED);

        List<Object> sequence = list(mention);
        ContentTree sequenceTree = tree(text("Before"), sequence);
        assertSame(sequence, ContentNodes.replaceChecked(sequenceTree, "之前<keep1/>").get(1));
        sequence.clear();
        reject(sequenceTree, "之前<keep1/>", ContentNodes.RejectionReason.RETAINED_STRUCTURE_CHANGED);
    }

    private static void inspectSnapshot(ContentTree.Node node, EnumSet<ContentTree.Kind> seen) throws Exception {
        seen.add(node.kind);
        assertThrows(UnsupportedOperationException.class, () -> node.children.add(node));
        if (!node.children.isEmpty()) {
            assertThrows(UnsupportedOperationException.class, () -> node.children.set(0, node));
        }
        if (node.kind != ContentTree.Kind.TEXT) {
            assertEquals(0, node.segmentCount);
            assertEquals(0, node.leadingEnd);
            assertEquals(0, node.trailingStart);
            assertSame(Collections.emptyList(), node.chunks);
            assertSame(Collections.emptyList(), node.separators);
            assertSame(Collections.emptyList(), node.translatable);
            assertSame(Collections.emptyList(), node.chunkLineCounts);
        }
        ContentTree.Node sequence = capturedSequence(node);
        if (node.kind == ContentTree.Kind.CONTAINER || node.kind == ContentTree.Kind.LIST) {
            assertNotNull(sequence);
            assertEquals(ContentTree.Kind.SEQUENCE, sequence.kind);
            assertSame(sequence.children, node.children);
        } else if (node.kind != ContentTree.Kind.SEQUENCE) {
            assertSame(Collections.emptyList(), node.children);
        }
        for (ContentTree.Node child : node.children) inspectSnapshot(child, seen);
        if (sequence != null) inspectSnapshot(sequence, seen);
    }

    /** Test-only inspection of the existing private capture; do not widen the production API. */
    private static ContentTree.Node capturedSequence(ContentTree.Node node) throws Exception {
        Field field = ContentTree.Node.class.getDeclaredField("sourceSequence");
        field.setAccessible(true);
        return (ContentTree.Node) field.get(node);
    }

    private static void rejectExtra(ContentTree tree, String translated, int expected, int actual) {
        ContentNodes.Rejection failure = reject(tree, translated, ContentNodes.RejectionReason.PARAGRAPH_EXTRA);
        assertEquals(expected, failure.expected);
        assertEquals(actual, failure.actual);
    }

    private static ContentNodes.Rejection reject(ContentTree tree, String translated, ContentNodes.RejectionReason reason) {
        ContentNodes.Rejection failure = assertThrows(ContentNodes.Rejection.class,
                () -> ContentNodes.replaceChecked(tree, translated));
        assertEquals(reason, failure.reason);
        return failure;
    }

    private static String extract(ContentTree tree) {
        TextExtractor.Result result = TextExtractor.extractTree(tree);
        assertNotNull(result);
        assertEquals(tree.segmentCount, result.segmentCount);
        return result.text;
    }

    private static ContentTree tree(Object... nodes) { return compiled(list(nodes)); }
    private static ContentTree compiled(List<?> nodes) {
        ContentTree tree = ContentTree.compile(nodes);
        assertNotNull(tree);
        return tree;
    }
    private static List<Object> list(Object... nodes) { return new ArrayList<>(Arrays.asList(nodes)); }
    @SafeVarargs private static List<List<Object>> items(List<Object>... items) {
        return new ArrayList<>(Arrays.asList(items));
    }
    private static TextContentNode text(String value) { return new TextContentNode(value); }
    private static String content(Object node) { return ((TextContentNode) node).getContent(); }
    private static LinkContentNode link(String url) { return new LinkContentNode(list(text(url)), new Object()); }
}
