package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.wxmyyds.discordautotranslate.core.ContentTree;
import io.github.wxmyyds.discordautotranslate.core.TextExtractor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Regression coverage for the single occurrence snapshot used by extraction and reconstruction. */
public class ContentTreeTest {
    @Before
    public void activateFixtureProfile() {
        ContentNodeTestProfile.activate(ContentTreeTest.class, ContentNodesTest.class);
    }

    @After
    public void clearFixtureProfile() {
        ContentNodeTestProfile.clear();
    }

    // Instrumented fixtures retain the native getter/component/copy signatures used by ContentNodes.
    public static final class TextContentNode {
        private String content;
        private int reads;

        public TextContentNode(String content) { this.content = content; }
        public String getContent() { reads++; return content; }
        public String component1() { return content; }
        public TextContentNode copy(String content) { return new TextContentNode(content); }
    }

    public static final class ParagraphContentNode {
        private List<Object> content;
        private int reads;

        public ParagraphContentNode(List<Object> content) { this.content = content; }
        public List<Object> getContent() { reads++; return content; }
        public List<Object> component1() { return content; }
        public ParagraphContentNode copy(List<Object> content) {
            return new ParagraphContentNode(content);
        }
    }

    public static final class LinkContentNode {
        private final List<Object> content;
        private final Object linkContextData;
        private int reads;

        public LinkContentNode(List<Object> content, Object linkContextData) {
            this.content = content;
            this.linkContextData = linkContextData;
        }
        public List<Object> getContent() { reads++; return content; }
        public List<Object> component1() { return content; }
        public Object component2() { return linkContextData; }
        public LinkContentNode copy(List<Object> content, Object linkContextData) {
            return new LinkContentNode(content, linkContextData);
        }
    }

    public static final class BulletListContentNode {
        private final Long start;
        private final boolean ordered;
        private final List<List<Object>> items;
        private int reads;

        public BulletListContentNode(Long start, boolean ordered, List<List<Object>> items) {
            this.start = start;
            this.ordered = ordered;
            this.items = items;
        }
        public List<List<Object>> getItems() { reads++; return items; }
        public Long component1() { return start; }
        public boolean component2() { return ordered; }
        public List<List<Object>> component3() { return items; }
        public BulletListContentNode copy(Long start, boolean ordered, List<List<Object>> items) {
            return new BulletListContentNode(start, ordered, items);
        }
    }

    public static final class UnicodeEmojiContentNode {
        private final String content;
        private int reads;

        public UnicodeEmojiContentNode(String content) { this.content = content; }
        public String getContent() { reads++; return content; }
    }

    public static final class CustomEmojiContentNode {
        private final String alt;
        private int reads;

        public CustomEmojiContentNode(String alt) { this.alt = alt; }
        public String getAlt() { reads++; return alt; }
    }

    private static class HiddenChildren {
        final List<Object> content;
        int reads;

        HiddenChildren(List<Object> content) { this.content = content; }
        final List<Object> readChildren() { reads++; return content; }
    }

    public static final class UserMentionContentNode extends HiddenChildren {
        public UserMentionContentNode(List<Object> content) { super(content); }
        public List<Object> getContent() { return readChildren(); }
    }

    public static final class UserOrRoleMentionContentNode extends HiddenChildren {
        public UserOrRoleMentionContentNode(List<Object> content) { super(content); }
        public List<Object> getContent() { return readChildren(); }
    }

    public static final class StaticChannelMentionContentNode extends HiddenChildren {
        public StaticChannelMentionContentNode(List<Object> content) { super(content); }
        public List<Object> getContent() { return readChildren(); }
    }

    public static final class ChannelMentionContentNode extends HiddenChildren {
        public ChannelMentionContentNode(List<Object> content) { super(content); }
        public List<Object> getContent() { return readChildren(); }
    }

    public static final class ChannelNameContentNode extends HiddenChildren {
        public ChannelNameContentNode(List<Object> content) { super(content); }
        public List<Object> getContent() { return readChildren(); }
    }

    public static final class CommandMentionContentNode extends HiddenChildren {
        public CommandMentionContentNode(List<Object> content) { super(content); }
        public List<Object> getContent() { return readChildren(); }
    }

    public static final class FutureOpaqueContentNode extends HiddenChildren {
        public FutureOpaqueContentNode(List<Object> content) { super(content); }
        public List<Object> getContent() { return readChildren(); }
    }

    // Deliberately absent from ContentNodeTestProfile's semantic aliases.
    public static final class UnknownContentNode extends HiddenChildren {
        public UnknownContentNode(List<Object> content) { super(content); }
        public List<Object> getContent() { return readChildren(); }
    }

    public static final class InvalidChildren {
        public static final class ParagraphContentNode {
            private final Object content;

            public ParagraphContentNode(Object content) { this.content = content; }
            public Object getContent() { return content; }
        }
    }

    @Test
    public void nativeGettersAreReadOnlyOncePerOccurrenceDuringCompile() {
        TextContentNode before = new TextContentNode(" before ");
        ParagraphContentNode paragraph = new ParagraphContentNode(list(before));
        TextContentNode urlText = new TextContentNode("https://example.com/ExactId");
        LinkContentNode url = new LinkContentNode(list(urlText), new Object());
        UnicodeEmojiContentNode unicode = new UnicodeEmojiContentNode("\uD83D\uDE42");
        CustomEmojiContentNode custom = new CustomEmojiContentNode(":wave:");
        TextContentNode itemText = new TextContentNode("item");
        BulletListContentNode bullets = new BulletListContentNode(7L, true,
                items(list(itemText)));

        ContentTree tree = compiled(list(paragraph, url, unicode, custom, bullets));
        assertEquals(2, tree.segmentCount);
        assertEquals(ContentTree.Kind.URL, tree.root.children.get(1).kind);
        assertEquals("https://example.com/ExactId", tree.root.children.get(1).text);
        assertTrue(tree.root.children.get(1).children.isEmpty());
        assertEquals(ContentTree.Kind.EMOJI, tree.root.children.get(2).kind);
        assertEquals(ContentTree.Kind.EMOJI, tree.root.children.get(3).kind);
        String expected = " before  https://example.com/ExactId  \uD83D\uDE42  :wave: \n• item";
        assertEquals(expected, extracted(tree).text);
        assertEquals(expected, extracted(tree).text);

        for (int attempt = 0; attempt < 2; attempt++) {
            List<Object> rebuilt = ContentNodes.replacePlanned(tree,
                    "之前 https://example.com/ExactId \uD83D\uDE42 :wave: \n• 项目");
            assertNotNull(rebuilt);
            assertEquals(" 之前 ", ((TextContentNode)
                    ((ParagraphContentNode) rebuilt.get(0)).content.get(0)).content);
            assertSame(url, rebuilt.get(1));
            assertSame(unicode, rebuilt.get(2));
            assertSame(custom, rebuilt.get(3));
            BulletListContentNode rebuiltList = (BulletListContentNode) rebuilt.get(4);
            assertEquals(Long.valueOf(7L), rebuiltList.start);
            assertTrue(rebuiltList.ordered);
            assertEquals("项目", ((TextContentNode) rebuiltList.items.get(0).get(0)).content);
        }
        assertEquals(1, paragraph.reads);
        assertEquals(1, before.reads);
        assertEquals(1, url.reads);
        assertEquals(1, urlText.reads);
        assertEquals(1, unicode.reads);
        assertEquals(1, custom.reads);
        assertEquals(1, bullets.reads);
        assertEquals(1, itemText.reads);
    }

    @Test
    public void sourceMutationCannotChangeCapturedTextListsOrRebuildShape() {
        TextContentNode paragraphs = new TextContentNode("  first\n\nsecond  ");
        TextContentNode itemText = new TextContentNode("third");
        List<Object> item = list(itemText);
        List<List<Object>> rawItems = items(item);
        BulletListContentNode bullets = new BulletListContentNode(9L, false, rawItems);
        List<Object> children = list(paragraphs, bullets);
        ParagraphContentNode paragraph = new ParagraphContentNode(children);
        List<Object> source = list(paragraph);
        ContentTree tree = compiled(source);
        String expected = "  first\n\nsecond  \n• third";
        assertEquals(expected, extracted(tree).text);
        assertEquals(3, tree.segmentCount);

        source.clear();
        source.add(new TextContentNode("new root"));
        children.clear();
        children.add(new TextContentNode("new child"));
        paragraph.content = list(new TextContentNode("new getter result"));
        rawItems.clear();
        item.clear();
        item.add(new TextContentNode("new item"));
        paragraphs.content = "\nmutated without the captured whitespace\n";
        itemText.content = "mutated item";

        assertEquals(expected, extracted(tree).text);
        assertEquals(3, extracted(tree).segmentCount);
        for (int attempt = 0; attempt < 2; attempt++) {
            List<Object> rebuilt = ContentNodes.replacePlanned(tree, "第一\n\n第二\n• 第三");
            assertNotNull(rebuilt);
            assertEquals(1, rebuilt.size());
            ParagraphContentNode rebuiltParagraph = (ParagraphContentNode) rebuilt.get(0);
            assertEquals(2, rebuiltParagraph.content.size());
            assertEquals("  第一\n\n第二  ",
                    ((TextContentNode) rebuiltParagraph.content.get(0)).content);
            BulletListContentNode rebuiltList = (BulletListContentNode)
                    rebuiltParagraph.content.get(1);
            assertEquals(Long.valueOf(9L), rebuiltList.start);
            assertFalse(rebuiltList.ordered);
            assertEquals(1, rebuiltList.items.size());
            assertEquals(1, rebuiltList.items.get(0).size());
            assertEquals("第三", ((TextContentNode) rebuiltList.items.get(0).get(0)).content);
            assertNotSame(paragraph.content, rebuiltParagraph.content);
            assertNotSame(rawItems, rebuiltList.items);
            assertNotSame(item, rebuiltList.items.get(0));
        }
        assertEquals(1, paragraph.reads);
        assertEquals(1, paragraphs.reads);
        assertEquals(1, bullets.reads);
        assertEquals(1, itemText.reads);
        assertEquals("mutated item", itemText.content);
        assertEquals("new root", ((TextContentNode) source.get(0)).content);
        assertEquals(expected, extracted(tree).text);
    }

    @Test
    public void sharedTextInstanceProducesDistinctOccurrencesAndTranslations() {
        TextContentNode shared = new TextContentNode("hello");
        ContentNodesTest.NewLineContentNode lineBreak = new ContentNodesTest.NewLineContentNode();
        ContentTree tree = compiled(list(shared, lineBreak, shared));
        ContentTree.Node first = tree.root.children.get(0);
        ContentTree.Node second = tree.root.children.get(2);
        assertNotSame(first, second);
        assertSame(shared, first.original);
        assertSame(shared, second.original);
        assertEquals(1, first.segmentCount);
        assertEquals(1, second.segmentCount);
        assertEquals(2, tree.segmentCount);
        assertEquals("hello\nhello", extracted(tree).text);

        List<Object> rebuilt = ContentNodes.replacePlanned(tree, "第一\n第二");
        assertNotNull(rebuilt);
        assertEquals("第一", ((TextContentNode) rebuilt.get(0)).content);
        assertEquals("第二", ((TextContentNode) rebuilt.get(2)).content);
        assertNotSame(rebuilt.get(0), rebuilt.get(2));
        assertSame(lineBreak, rebuilt.get(1));
        // Read once per occurrence, not once per identity and never again during consumption.
        assertEquals(2, shared.reads);
        assertEquals("hello", shared.content);
    }

    @Test
    public void sharedContainerIsCapturedAndRebuiltSeparatelyAtEachPosition() {
        TextContentNode text = new TextContentNode("hello");
        ParagraphContentNode shared = new ParagraphContentNode(list(text));
        ContentTree tree = compiled(list(shared, new ContentNodesTest.LineBreakContentNode(), shared));
        assertNotSame(tree.root.children.get(0), tree.root.children.get(2));
        assertNotSame(tree.root.children.get(0).children.get(0),
                tree.root.children.get(2).children.get(0));
        assertEquals("hello\nhello", extracted(tree).text);

        List<Object> rebuilt = ContentNodes.replacePlanned(tree, "第一\n第二");
        assertNotNull(rebuilt);
        ParagraphContentNode first = (ParagraphContentNode) rebuilt.get(0);
        ParagraphContentNode second = (ParagraphContentNode) rebuilt.get(2);
        assertNotSame(first, second);
        assertNotSame(first.content, second.content);
        assertEquals("第一", ((TextContentNode) first.content.get(0)).content);
        assertEquals("第二", ((TextContentNode) second.content.get(0)).content);
        assertSame(text, shared.content.get(0));
        assertEquals("hello", text.content);
        assertEquals(2, shared.reads);
        assertEquals(2, text.reads);
    }

    @Test
    public void nestedBulletAtDepthElevenUsesTheSameEventsForExtractionAndRebuild() {
        ContentNodesTest.BulletListContentNode nested = new ContentNodesTest.BulletListContentNode(
                9L, true, items(list(new ContentNodesTest.TextContentNode("inner"))));
        ContentNodesTest.BulletListContentNode outer = new ContentNodesTest.BulletListContentNode(
                3L, false, items(list(new ContentNodesTest.TextContentNode("outer"), nested),
                        list(new ContentNodesTest.TextContentNode("last"))));
        // Ten paragraphs put the outer list at depth 10 and the nested list at depth 11.
        // Its text is at MAX_DEPTH (12); Java items/item lists must not add semantic depth.
        List<Object> source = wrapParagraphs(10, outer);
        ContentTree tree = compiled(source);
        assertEquals(3, tree.segmentCount);
        assertEquals("\n• outer\n• inner\n• last", extracted(tree).text);
        assertEquals(3, extracted(tree).textNodeCount);
        assertEquals(0, extracted(tree).opaqueNodeCount);
        assertEventKinds(tree, ContentTree.EventKind.LIST_START, ContentTree.EventKind.ITEM_START,
                ContentTree.EventKind.NODE, ContentTree.EventKind.LIST_START,
                ContentTree.EventKind.ITEM_START, ContentTree.EventKind.NODE,
                ContentTree.EventKind.ITEM_END, ContentTree.EventKind.LIST_END,
                ContentTree.EventKind.ITEM_END, ContentTree.EventKind.ITEM_START,
                ContentTree.EventKind.NODE, ContentTree.EventKind.ITEM_END,
                ContentTree.EventKind.LIST_END);
        assertSame(outer, tree.events.get(0).node.original);
        assertSame(nested, tree.events.get(3).node.original);
        for (ContentTree.Event event : tree.events) assertFalse(event.styled);

        List<Object> rebuilt = ContentNodes.replacePlanned(tree, "• 外层\n• 内层\n• 末项");
        assertNotNull(rebuilt);
        assertEquals("\n• 外层\n• 内层\n• 末项", extracted(compiled(rebuilt)).text);
        List<Object> cursor = rebuilt;
        for (int depth = 0; depth < 10; depth++) {
            assertEquals(1, cursor.size());
            cursor = ((ContentNodesTest.ParagraphContentNode) cursor.get(0)).getContent();
        }
        ContentNodesTest.BulletListContentNode rebuiltOuter =
                (ContentNodesTest.BulletListContentNode) cursor.get(0);
        assertEquals(Long.valueOf(3L), rebuiltOuter.component1());
        assertFalse(rebuiltOuter.component2());
        assertEquals(2, rebuiltOuter.getItems().size());
        assertEquals(2, rebuiltOuter.getItems().get(0).size());
        assertEquals("外层", ((ContentNodesTest.TextContentNode)
                rebuiltOuter.getItems().get(0).get(0)).getContent());
        ContentNodesTest.BulletListContentNode rebuiltNested =
                (ContentNodesTest.BulletListContentNode) rebuiltOuter.getItems().get(0).get(1);
        assertEquals(Long.valueOf(9L), rebuiltNested.component1());
        assertTrue(rebuiltNested.component2());
        assertEquals(1, rebuiltNested.getItems().size());
        assertEquals("内层", ((ContentNodesTest.TextContentNode)
                rebuiltNested.getItems().get(0).get(0)).getContent());
        assertEquals("末项", ((ContentNodesTest.TextContentNode)
                rebuiltOuter.getItems().get(1).get(0)).getContent());
        assertEquals("\n• outer\n• inner\n• last", extracted(tree).text);
        assertNull(ContentNodes.replacePlanned(tree, "外层\n内层"));
    }

    @Test
    public void nestedStylesMarkEachSegmentAndPreserveNativeContainerFields() {
        Object context = new Object();
        ContentNodesTest.LinkContentNode link = new ContentNodesTest.LinkContentNode(
                list(new ContentNodesTest.TextContentNode("italic\n\nagain")), context);
        ContentNodesTest.EmphasisContentNode emphasis =
                new ContentNodesTest.EmphasisContentNode(list(link));
        ContentNodesTest.StrongContentNode strong = new ContentNodesTest.StrongContentNode(list(
                new ContentNodesTest.TextContentNode("bold "), emphasis,
                new ContentNodesTest.TextContentNode(" end")));
        ContentNodesTest.HeadingContentNode heading = new ContentNodesTest.HeadingContentNode(
                list(new ContentNodesTest.ParagraphContentNode(list(
                        new ContentNodesTest.TextContentNode("hello "), strong,
                        new ContentNodesTest.TextContentNode(" tail")))), 4);
        ContentTree tree = compiled(list(heading));
        String original = "hello <x1>bold</x1> <x2>italic</x2>\n\n<x3>again</x3> "
                + "<x4>end</x4> tail";
        assertEquals(original, extracted(tree).text);
        assertEquals(6, tree.segmentCount);
        assertEquals(5, extracted(tree).textNodeCount);
        assertEquals(5, tree.events.size());
        assertFalse(tree.events.get(0).styled);
        assertTrue(tree.events.get(1).styled);
        assertTrue(tree.events.get(2).styled);
        assertTrue(tree.events.get(3).styled);
        assertFalse(tree.events.get(4).styled);
        assertEquals(2, tree.events.get(2).node.segmentCount);

        String translated = "你好 <x1>粗体</x1> <x2>斜体</x2>\n\n<x3>再次</x3> "
                + "<x4>结尾</x4> 尾部";
        List<Object> rebuilt = ContentNodes.replacePlanned(tree, translated);
        assertNotNull(rebuilt);
        ContentNodesTest.HeadingContentNode rebuiltHeading =
                (ContentNodesTest.HeadingContentNode) rebuilt.get(0);
        assertEquals(4, rebuiltHeading.component2());
        List<Object> children = ((ContentNodesTest.ParagraphContentNode)
                rebuiltHeading.getContent().get(0)).getContent();
        assertEquals("你好 ", ((ContentNodesTest.TextContentNode) children.get(0)).getContent());
        ContentNodesTest.StrongContentNode rebuiltStrong =
                (ContentNodesTest.StrongContentNode) children.get(1);
        assertEquals("粗体 ", ((ContentNodesTest.TextContentNode)
                rebuiltStrong.getContent().get(0)).getContent());
        ContentNodesTest.EmphasisContentNode rebuiltEmphasis =
                (ContentNodesTest.EmphasisContentNode) rebuiltStrong.getContent().get(1);
        ContentNodesTest.LinkContentNode rebuiltLink =
                (ContentNodesTest.LinkContentNode) rebuiltEmphasis.getContent().get(0);
        assertSame(context, rebuiltLink.component2());
        assertEquals("斜体\n\n再次", ((ContentNodesTest.TextContentNode)
                rebuiltLink.getContent().get(0)).getContent());
        assertEquals(" 结尾", ((ContentNodesTest.TextContentNode)
                rebuiltStrong.getContent().get(2)).getContent());
        assertEquals(" 尾部", ((ContentNodesTest.TextContentNode) children.get(2)).getContent());
        assertEquals(translated, extracted(compiled(rebuilt)).text);
        assertEquals(original, extracted(tree).text);
        assertNull(ContentNodes.replacePlanned(tree, translated.replace("<x2>", "")));
        assertNull(ContentNodes.replacePlanned(tree,
                translated.replace("<x2>", "<x3>").replace("</x2>", "</x3>")));
    }

    @Test
    public void mentionsChannelsAndUnknownNodesNeverTraverseHiddenChildren() {
        TextContentNode hiddenText = new TextContentNode("must stay hidden");
        List<Object> hidden = list(hiddenText);
        hidden.add(hidden); // A hidden cycle proves the compiler must not even enter this list.
        HiddenChildren[] opaque = {new UserMentionContentNode(hidden),
                new UserOrRoleMentionContentNode(hidden), new StaticChannelMentionContentNode(hidden),
                new ChannelMentionContentNode(hidden), new ChannelNameContentNode(hidden),
                new CommandMentionContentNode(hidden), new FutureOpaqueContentNode(hidden),
                new UnknownContentNode(hidden)};
        List<Object> source = list(new TextContentNode("visible"));
        source.addAll(Arrays.asList(opaque));
        ContentTree tree = compiled(source);
        assertEquals(1, tree.segmentCount);
        TextExtractor.Result result = extracted(tree);
        assertEquals("visible        ", result.text);
        assertEquals(1, result.textNodeCount);
        assertEquals(opaque.length, result.opaqueNodeCount);
        assertNull(tree.root.children.get(opaque.length).semantic);

        List<Object> rebuilt = ContentNodes.replacePlanned(tree, "译文");
        assertNotNull(rebuilt);
        assertEquals(source.size(), rebuilt.size());
        assertEquals("译文", ((TextContentNode) rebuilt.get(0)).content);
        for (int i = 0; i < opaque.length; i++) {
            ContentTree.Node occurrence = tree.root.children.get(i + 1);
            assertEquals(ContentTree.Kind.OPAQUE, occurrence.kind);
            assertTrue(occurrence.children.isEmpty());
            assertSame(opaque[i], occurrence.original);
            assertSame(opaque[i], rebuilt.get(i + 1));
            assertEquals(0, opaque[i].reads);
            assertSame(hidden, opaque[i].content);
        }
        assertEquals(0, hiddenText.reads);
    }

    @Test
    public void codeAndUrlEmojiAnchorsArePreservedWithoutConsumingTranslations() {
        ContentNodesTest.InlineCodeContentNode inline =
                new ContentNodesTest.InlineCodeContentNode("secret inline");
        ContentNodesTest.CodeBlockContentNode code =
                new ContentNodesTest.CodeBlockContentNode("secret block", "javascript", true);
        UnicodeEmojiContentNode unicode = new UnicodeEmojiContentNode("\uD83D\uDE42");
        CustomEmojiContentNode custom = new CustomEmojiContentNode(":wave:");
        Object context = new Object();
        ContentNodesTest.LinkContentNode url = new ContentNodesTest.LinkContentNode(
                list(new ContentNodesTest.TextContentNode("https://example.com/ExactId")), context);
        ContentTree tree = compiled(list(new TextContentNode("first"), inline, code, unicode,
                new TextContentNode("second"), url, custom, new TextContentNode("third")));
        TextExtractor.Result result = extracted(tree);
        assertEquals("first   \uD83D\uDE42 second https://example.com/ExactId  :wave: third", result.text);
        assertEquals(3, result.segmentCount);
        assertEquals(3, result.textNodeCount);
        assertEquals(4, result.opaqueNodeCount);
        assertTrue(result.codeHeavy);
        assertFalse(result.text.contains("secret"));
        assertEquals(ContentTree.Kind.INLINE_CODE, tree.root.children.get(1).kind);
        assertEquals(ContentTree.Kind.CODE_BLOCK, tree.root.children.get(2).kind);
        assertEquals(0, tree.root.children.get(5).segmentCount);

        String translated = "第一 \uD83D\uDE42 第二 https://example.com/ExactId :wave: 第三";
        List<Object> rebuilt = ContentNodes.replacePlanned(tree, translated);
        assertNotNull(rebuilt);
        assertEquals("第一", ((TextContentNode) rebuilt.get(0)).content);
        assertSame(inline, rebuilt.get(1));
        assertSame(code, rebuilt.get(2));
        assertSame(unicode, rebuilt.get(3));
        assertEquals("第二", ((TextContentNode) rebuilt.get(4)).content);
        assertSame(url, rebuilt.get(5));
        assertSame(context, ((ContentNodesTest.LinkContentNode) rebuilt.get(5)).component2());
        assertSame(custom, rebuilt.get(6));
        assertEquals("第三", ((TextContentNode) rebuilt.get(7)).content);
        assertEquals("secret inline", inline.getContent());
        assertEquals("secret block", code.getContent());
        assertEquals("javascript", code.component2());
        assertTrue(code.component3());
        assertNull(ContentNodes.replacePlanned(tree, translated.replace("ExactId", "Exactld")));
        assertNull(ContentNodes.replacePlanned(tree, translated.replace("\uD83D\uDE42", "")));
        assertNull(ContentNodes.replacePlanned(tree, translated + " \uD83D\uDE42"));
        assertNull(ContentNodes.replacePlanned(tree,
                "第一 :wave: 第二 https://example.com/ExactId \uD83D\uDE42 第三"));
        assertEquals(1, unicode.reads);
        assertEquals(1, custom.reads);
    }

    @Test
    public void urlAndBlankChunksInsideTextKeepCapturedSeparators() {
        TextContentNode text = new TextContentNode(
                "  first\n\nhttps://example.com/ExactId\n\nsecond  ");
        ContentTree tree = compiled(list(text));
        assertEquals(2, tree.segmentCount);
        assertEquals(2, tree.root.children.get(0).segmentCount);
        assertEquals(text.content, extracted(tree).text);
        List<Object> rebuilt = ContentNodes.replacePlanned(tree,
                "第一\n\nhttps://example.com/ExactId\n\n第二");
        assertNotNull(rebuilt);
        assertEquals("  第一\n\nhttps://example.com/ExactId\n\n第二  ",
                ((TextContentNode) rebuilt.get(0)).content);
        assertEquals(1, text.reads);
    }

    @Test
    public void nullInputsAreRejectedWithoutDereferencingATree() {
        assertNull(ContentTree.compile(null));
        assertNull(TextExtractor.extractTree(null));
        assertNull(ContentPlanTestSupport.replace(null, "译文"));
        assertNull(ContentNodes.replacePlanned(null, "译文"));
        assertNull(ContentNodes.replacePlanned(null, null));
        ContentTree tree = compiled(list(new TextContentNode("hello")));
        assertNull(ContentNodes.replacePlanned(tree, null));
        assertNull(ContentNodes.replacePlanned(tree, " \n\t"));
    }

    @Test
    public void emptyTreesAndNullOccurrencesPreserveStructureWithoutInventingSegments() {
        ContentTree empty = compiled(list());
        assertEquals(ContentTree.Kind.SEQUENCE, empty.root.kind);
        assertEquals(0, empty.segmentCount);
        assertTrue(empty.events.isEmpty());
        assertEquals("", extracted(empty).text);
        assertEquals(0, extracted(empty).textNodeCount);
        assertEquals(0, extracted(empty).opaqueNodeCount);
        assertFalse(extracted(empty).codeHeavy);
        assertNull(ContentNodes.replacePlanned(empty, "译文"));

        TextContentNode nullText = new TextContentNode(null);
        TextContentNode blank = new TextContentNode(" \t");
        ContentTree tree = compiled(list(null, nullText, blank, new TextContentNode("visible"), null));
        assertEquals(5, tree.root.children.size());
        assertEquals(3, tree.events.size());
        assertEquals(" \tvisible", extracted(tree).text);
        assertEquals(1, tree.segmentCount);
        assertEquals(2, extracted(tree).textNodeCount);
        assertEquals(0, extracted(tree).opaqueNodeCount);
        List<Object> rebuilt = ContentNodes.replacePlanned(tree, "译文");
        assertNotNull(rebuilt);
        assertEquals(5, rebuilt.size());
        assertNull(rebuilt.get(0));
        assertSame(nullText, rebuilt.get(1));
        assertSame(blank, rebuilt.get(2));
        assertEquals("译文", ((TextContentNode) rebuilt.get(3)).content);
        assertNull(rebuilt.get(4));
    }

    @Test
    public void twelveNativeContainersAreAcceptedButThirteenRejectTheWholeMessage() {
        List<Object> valid = wrapParagraphs(12, new ContentNodesTest.TextContentNode("deep"));
        ContentTree tree = compiled(valid);
        assertEquals("deep", extracted(tree).text);
        assertEquals(1, tree.segmentCount);
        List<Object> rebuilt = ContentNodes.replacePlanned(tree, "深层");
        assertNotNull(rebuilt);
        assertEquals("深层", extracted(compiled(rebuilt)).text);

        List<Object> invalid = list(new ContentNodesTest.TextContentNode("prefix"));
        invalid.addAll(wrapParagraphs(13, new ContentNodesTest.TextContentNode("too deep")));
        invalid.add(new ContentNodesTest.TextContentNode("suffix"));
        assertRejected(invalid);
    }

    @Test
    public void listAndNativeContainerCyclesRejectTheEntireMessage() {
        List<Object> self = list(new ContentNodesTest.TextContentNode("prefix"));
        self.add(self);
        self.add(new ContentNodesTest.TextContentNode("suffix"));
        assertRejected(self);

        List<Object> first = list();
        List<Object> second = list(first);
        first.add(second);
        assertRejected(list(new ContentNodesTest.TextContentNode("prefix"), first,
                new ContentNodesTest.TextContentNode("suffix")));

        List<Object> children = list(new ContentNodesTest.TextContentNode("inside"));
        ParagraphContentNode cyclic = new ParagraphContentNode(children);
        children.add(cyclic);
        assertRejected(list(new ContentNodesTest.TextContentNode("prefix"), cyclic,
                new ContentNodesTest.TextContentNode("suffix")));
    }

    @Test
    public void malformedNativeChildrenRejectTheWholeMessageInsteadOfKeepingPartialText() {
        for (Object children : new Object[]{null, "not a list"}) {
            assertRejected(list(new ContentNodesTest.TextContentNode("prefix"),
                    new InvalidChildren.ParagraphContentNode(children),
                    new ContentNodesTest.TextContentNode("suffix")));
        }
    }

    @Test
    public void listWrappersDoNotCountAsSemanticDepthButStillHaveAPathBudget() {
        // The path limit is 64 identities: 63 lists plus one native leaf are valid.
        List<Object> valid = wrapLists(62, new TextContentNode("deep"));
        ContentTree tree = compiled(valid);
        assertEquals("deep", extracted(tree).text);
        assertEquals(1, tree.segmentCount);
        List<Object> rebuilt = ContentNodes.replacePlanned(tree, "深层");
        assertNotNull(rebuilt);
        assertEquals("深层", extracted(compiled(rebuilt)).text);
        List<?> cursor = rebuilt;
        for (int wrapper = 0; wrapper < 62; wrapper++) {
            assertEquals(1, cursor.size());
            assertTrue(cursor.get(0) instanceof List<?>);
            cursor = (List<?>) cursor.get(0);
        }
        assertEquals("深层", ((TextContentNode) cursor.get(0)).content);

        // One more wrapper exceeds the path budget despite semantic depth remaining zero.
        List<Object> invalid = wrapLists(63, new TextContentNode("too far"));
        invalid.add(0, new TextContentNode("prefix"));
        invalid.add(new TextContentNode("suffix"));
        assertRejected(invalid);
    }

    @Test
    public void occurrenceBudgetCountsSharedPositionsAndRejectsRatherThanTruncating() {
        TextContentNode shared = new TextContentNode("hello");
        // MAX_OCCURRENCES is 8192, including the root sequence, not just distinct identities.
        List<Object> source = new ArrayList<>(Collections.nCopies(8191, shared));
        ContentTree tree = compiled(source);
        assertEquals(8191, tree.root.children.size());
        assertEquals(8191, tree.events.size());
        assertEquals(8191, tree.segmentCount);
        assertEquals(8191, extracted(tree).textNodeCount);
        assertEquals(8191, shared.reads);
        assertNotSame(tree.root.children.get(0), tree.root.children.get(8190));

        source.add(shared);
        assertRejected(source);
        assertEquals(8191, tree.root.children.size());
        assertEquals(8191, extracted(tree).segmentCount);
    }

    @Test
    public void changedRetainedContainerOrUrlChildrenRejectTheWholeReplacement() {
        List<Object> tailChildren = list(new UnicodeEmojiContentNode("🙂"));
        ParagraphContentNode tail = new ParagraphContentNode(tailChildren);
        ContentTree tree = compiled(list(new TextContentNode("hello"), tail));
        assertNotNull(ContentNodes.replacePlanned(tree, "你好 🙂"));
        tailChildren.clear();
        tailChildren.add(new CustomEmojiContentNode(":new:"));
        assertNull(ContentNodes.replacePlanned(tree, "你好 🙂"));

        List<Object> urlChildren = list(new TextContentNode("https://example.com/ExactId"));
        LinkContentNode url = new LinkContentNode(urlChildren, new Object());
        ContentTree linkTree = compiled(list(new TextContentNode("hello"), url));
        assertNotNull(ContentNodes.replacePlanned(linkTree, "你好 https://example.com/ExactId"));
        urlChildren.set(0, new TextContentNode("https://example.com/ChangedId"));
        assertNull(ContentNodes.replacePlanned(linkTree, "你好 https://example.com/ExactId"));
    }

    @Test
    public void capturedTextChunksDefineSegmentCountsWithoutAnIndependentCounter() {
        for (String blank : new String[]{null, "", "\n\n"}) {
            assertEquals(0, compiled(list(new TextContentNode(blank))).segmentCount);
        }
        assertEquals(2, compiled(list(new TextContentNode("你好\n\n世界"))).segmentCount);
        assertEquals(3, compiled(list(new TextContentNode("a\n\nb\n\nc"))).segmentCount);
        assertEquals(1, compiled(list(new TextContentNode("first\nsecond"))).segmentCount);
        assertEquals(1, compiled(list(new TextContentNode("\n\nhello\n\n"))).segmentCount);
    }

    @Test
    public void chunkLineCountsAreImmutableAndSharedNodeMutationCannotChangeCapturedAlignment() {
        String original = " first\ncontinuation\n\nsecond ";
        TextContentNode shared = new TextContentNode(original);
        ContentNodesTest.TextContentNode lf = new ContentNodesTest.TextContentNode("\n");
        ContentTree tree = compiled(list(shared, lf, shared));
        ContentTree.Node first = tree.root.children.get(0);
        ContentTree.Node second = tree.root.children.get(2);
        assertNotSame(first, second);
        assertSame(shared, first.original);
        assertSame(shared, second.original);
        assertEquals(Arrays.asList(2, 1), first.chunkLineCounts);
        assertEquals(Arrays.asList(2, 1), second.chunkLineCounts);
        assertEquals(first.chunks.size(), first.chunkLineCounts.size());
        assertThrows(UnsupportedOperationException.class, () -> first.chunkLineCounts.clear());
        assertThrows(UnsupportedOperationException.class, () -> second.chunkLineCounts.set(0, 9));
        assertEquals(2, shared.reads);

        shared.content = "mutated single-line text";
        for (int attempt = 0; attempt < 2; attempt++) {
            assertEquals(original + "\n" + original, extracted(tree).text);
            assertEquals(Arrays.asList(2, 1), first.chunkLineCounts);
            assertEquals(Arrays.asList(2, 1), second.chunkLineCounts);
            List<Object> rebuilt = ContentNodes.replaceChecked(tree, "甲\n乙\n\n丙\n丁\n戊\n\n己");
            assertEquals(" 甲\n乙\n\n丙 ", ((TextContentNode) rebuilt.get(0)).content);
            assertSame(lf, rebuilt.get(1));
            assertEquals(" 丁\n戊\n\n己 ", ((TextContentNode) rebuilt.get(2)).content);
            assertNotSame(rebuilt.get(0), rebuilt.get(2));
            assertEquals(2, shared.reads);
            assertEquals("mutated single-line text", shared.content);
        }

        ContentTree.Node mixed = compiled(list(new TextContentNode(
                "\n first\ncontinuation\n\nhttps://example.com/ExactId\n\nlast\ncontinuation\n")))
                .root.children.get(0);
        assertEquals(Arrays.asList(2, 1, 2), mixed.chunkLineCounts);
        assertEquals(Arrays.asList(true, false, true), mixed.translatable);
        assertEquals(2, mixed.segmentCount);
    }

    @Test
    public void snapshotChildAndEventCollectionsAreImmutable() {
        ContentTree tree = compiled(list(new ParagraphContentNode(list(new TextContentNode("hello")))));
        assertThrows(UnsupportedOperationException.class, () -> tree.root.children.clear());
        assertThrows(UnsupportedOperationException.class,
                () -> tree.root.children.get(0).children.clear());
        assertThrows(UnsupportedOperationException.class, () -> tree.events.clear());
        assertEquals("hello", extracted(tree).text);
    }

    private static ContentTree compiled(List<?> source) {
        ContentTree tree = ContentTree.compile(source);
        assertNotNull("valid fixture must compile without truncation", tree);
        return tree;
    }

    private static TextExtractor.Result extracted(ContentTree tree) {
        TextExtractor.Result result = TextExtractor.extractTree(tree);
        assertNotNull(result);
        assertEquals(tree.segmentCount, result.segmentCount);
        return result;
    }

    private static void assertRejected(List<Object> source) {
        assertNull("invalid tree must not return a partial snapshot", ContentTree.compile(source));
        assertNull("invalid annotated extraction must not expose prefix/suffix text",
                TextExtractor.extract(new ContentNodesTest.AnnotatedStructurableText(source)));
        assertNull("test-only list setup must use the same rejecting compiler",
                ContentPlanTestSupport.replace(source, "译文"));
    }

    private static void assertEventKinds(ContentTree tree, ContentTree.EventKind... kinds) {
        List<ContentTree.EventKind> actual = new ArrayList<>();
        for (ContentTree.Event event : tree.events) actual.add(event.kind);
        assertEquals(Arrays.asList(kinds), actual);
    }

    private static List<Object> wrapParagraphs(int count, Object leaf) {
        Object node = leaf;
        for (int depth = 0; depth < count; depth++) {
            node = new ContentNodesTest.ParagraphContentNode(list(node));
        }
        return list(node);
    }

    private static List<Object> wrapLists(int count, Object leaf) {
        List<Object> nodes = list(leaf);
        for (int depth = 0; depth < count; depth++) nodes = list(nodes);
        return nodes;
    }

    private static List<Object> list(Object... nodes) {
        return new ArrayList<>(Arrays.asList(nodes));
    }

    @SafeVarargs
    private static List<List<Object>> items(List<Object>... entries) {
        return new ArrayList<>(Arrays.asList(entries));
    }
}
