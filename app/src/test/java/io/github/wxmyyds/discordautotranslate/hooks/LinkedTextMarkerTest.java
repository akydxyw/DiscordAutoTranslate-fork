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

/** Natural-language link display text shares exact format-span markers, not URL preservation. */
public class LinkedTextMarkerTest {
    @Before public void setup() { ContentNodeTestProfile.activate(ContentNodesTest.class, EmojiAnchorTest.class); }
    @After public void cleanup() { ContentNodeTestProfile.clear(); }
    private static List<Object> list(Object... nodes) { return new ArrayList<>(Arrays.asList(nodes)); }
    private static TextContentNode text(String text) { return new TextContentNode(text); }
    private static LinkContentNode link(Object context, Object... children) { return new LinkContentNode(list(children), context); }
    private static ContentTree tree(Object... nodes) {
        ContentTree tree = ContentTree.compile(list(nodes));
        assertNotNull(tree);
        return tree;
    }
    private static String extract(ContentTree tree) { return TextExtractor.extractTree(tree).text; }
    private static String content(Object node) { return ((TextContentNode) node).getContent(); }
    private static String label(Object node) { return content(((LinkContentNode) node).getContent().get(0)); }
    private static void reject(ContentTree tree, String response, ContentNodes.RejectionReason reason) {
        ContentNodes.Rejection rejection = assertThrows(ContentNodes.Rejection.class,
                () -> ContentNodes.replaceChecked(tree, response));
        assertEquals(reason, rejection.reason);
        assertNull(ContentNodes.replacePlanned(tree, response));
    }

    @Test public void observedRootToLinkLabelSameLineHasExactSpanBoundary() {
        Object context = new Object();
        LinkContentNode link = link(context, text("this guide"));
        ContentTree tree = tree(text("See "), link, text(" now"));
        assertEquals("See <x1>this guide</x1> now", extract(tree));
        assertEquals(3, tree.segmentCount);
        assertTrue(tree.events.get(1).styled);
        List<Object> out = ContentNodes.replaceChecked(tree, "参阅 <x1>本指南</x1> 现在");
        assertEquals("参阅 ", content(out.get(0)));
        assertEquals("本指南", label(out.get(1)));
        assertSame(context, ((LinkContentNode) out.get(1)).component2());
        assertEquals(" 现在", content(out.get(2)));
        assertEquals("参阅 <x1>本指南</x1> 现在", extract(ContentTree.compile(out)));
        assertEquals("this guide", label(link));
        reject(tree, "参阅 本指南 现在", ContentNodes.RejectionReason.STYLE_OPEN);
    }

    @Test public void inlineCodeBeforePlainAndLinkLabelKeepsSingleTokenChain() {
        InlineCodeContentNode code = new InlineCodeContentNode("private");
        ContentTree tree = tree(text("Run"), code, text("See"), link(new Object(), text("guide")), text("Now"));
        assertEquals("Run<keep1/>See<x1>guide</x1>Now", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "运行<keep1/>参阅<x1>指南</x1>现在");
        assertSame(code, out.get(1));
        assertEquals("参阅", content(out.get(2)));
        assertEquals("指南", label(out.get(3)));
        assertEquals("现在", content(out.get(4)));
    }

    @Test public void sharedLinkInstancesAreMarkedByOccurrenceNotIdentity() {
        Object context = new Object();
        LinkContentNode shared = link(context, text("guide"));
        ContentTree tree = tree(shared, shared);
        assertEquals("<x1>guide</x1><x2>guide</x2>", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "<x1>指南一</x1><x2>指南二</x2>");
        assertEquals("指南一", label(out.get(0)));
        assertEquals("指南二", label(out.get(1)));
        assertSame(context, ((LinkContentNode) out.get(0)).component2());
        assertSame(context, ((LinkContentNode) out.get(1)).component2());
    }

    @Test public void multipleTextChunksInsideLinkHaveOneMarkerPerSlot() {
        ContentTree tree = tree(link(new Object(), text("First"), text("Second\n\nThird")));
        assertEquals("<x1>First</x1><x2>Second</x2>\n\n<x3>Third</x3>", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "<x1>第一</x1><x2>第二</x2>\n\n<x3>第三</x3>");
        LinkContentNode rebuilt = (LinkContentNode) out.get(0);
        assertEquals("第一", content(rebuilt.getContent().get(0)));
        assertEquals("第二\n\n第三", content(rebuilt.getContent().get(1)));
    }

    @Test public void nestedStylesDoNotCreateNestedOrDuplicateMarkers() {
        ContentTree tree = tree(new StrongContentNode(list(text("Title "),
                link(new Object(), new EmphasisContentNode(list(text("guide")))), text(" end"))));
        assertEquals("<x1>Title</x1> <x2>guide</x2> <x3>end</x3>", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "<x1>标题</x1> <x2>指南</x2> <x3>结尾</x3>");
        StrongContentNode strong = (StrongContentNode) out.get(0);
        LinkContentNode rebuilt = (LinkContentNode) strong.getContent().get(1);
        assertEquals("指南", content(((EmphasisContentNode) rebuilt.getContent().get(0)).getContent().get(0)));
    }

    @Test public void urlChunkWithinLabelRemainsAnchorBetweenFlatSpanMarkers() {
        String url = "https://example.invalid/ExactId";
        ContentTree tree = tree(link(new Object(), text("First\n\n" + url + "\n\nLast")));
        assertEquals("<x1>First</x1>\n\n" + url + "\n\n<x2>Last</x2>", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree,
                "<x1>第一</x1>\n\n" + url + "\n\n<x2>最后</x2>");
        assertEquals("第一\n\n" + url + "\n\n最后", label(out.get(0)));
    }

    @Test public void linkAroundStyledChildrenKeepsOneMarkerPerTextChunk() {
        Object context = new Object();
        ContentTree tree = tree(link(context, new StrongContentNode(list(text("Bold"))), text(" Tail")));
        assertEquals("<x1>Bold</x1> <x2>Tail</x2>", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "<x1>加粗</x1> <x2>尾部</x2>");
        LinkContentNode rebuilt = (LinkContentNode) out.get(0);
        assertSame(context, rebuilt.component2());
        assertEquals("加粗", content(((StrongContentNode) rebuilt.getContent().get(0)).getContent().get(0)));
        assertEquals(" 尾部", content(rebuilt.getContent().get(1)));
    }

    @Test public void bareUrlLinksStillHaveNoTextMarkersAndRetainOriginalIdentity() {
        String url = "https://example.invalid/ExactId";
        LinkContentNode link = link(new Object(), text(url));
        ContentTree tree = tree(text("Before"), link, text("After"));
        assertEquals("Before " + url + " After", extract(tree));
        assertEquals(ContentTree.Kind.URL, tree.root.children.get(1).kind);
        assertEquals(2, tree.segmentCount);
        List<Object> out = ContentNodes.replaceChecked(tree, "之前 " + url + " 之后");
        assertSame(link, out.get(1));
    }

    @Test public void labelWithEmojiAndInlineCodePreservesNativeChildren() {
        CustomEmojiContentNode emoji = new CustomEmojiContentNode(42L, ":party:");
        InlineCodeContentNode code = new InlineCodeContentNode("private");
        ContentTree tree = tree(link(new Object(), text("First"), emoji, code, text("Last")));
        assertEquals("<x1>First</x1> :party: <keep1/><x2>Last</x2>", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "<x1>第一</x1> :party: <keep1/><x2>最后</x2>");
        LinkContentNode rebuilt = (LinkContentNode) out.get(0);
        assertSame(emoji, rebuilt.getContent().get(1));
        assertSame(code, rebuilt.getContent().get(2));
        assertEquals("最后", content(rebuilt.getContent().get(3)));
    }

    @Test public void emptyLinkDoesNotCreateMarkerOrDisambiguatePlainText() {
        ContentTree tree = tree(text("First"), link(new Object()), text("Last"));
        assertEquals("FirstLast", extract(tree));
        reject(tree, "第一\n最后", ContentNodes.RejectionReason.SOURCE_BOUNDARY_AMBIGUOUS);
    }

    @Test public void labelMarkerDamageIsRepairedOrRejected() {
        ContentTree tree = tree(text("See "), link(new Object(), text("guide")));
        reject(tree, "参阅<x2>指南</x2>", ContentNodes.RejectionReason.STYLE_OPEN);
        // 闭标记被模型丢弃：样式内容延伸到结尾，仍可回填（2026-11-23 放宽）。
        List<Object> repaired = ContentNodes.replaceChecked(tree, "参阅<x1>指南");
        assertEquals("指南", label(repaired.get(1)));
        reject(tree, "参阅<x1></x1>", ContentNodes.RejectionReason.STYLE_EMPTY);
    }

    @Test public void unexpectedNestedStyleTagsCannotLeakIntoLinkedText() {
        ContentTree tree = tree(text("See "), link(new Object(), text("guide")), text(" now"));
        String source = "See <x1>guide</x1> now";
        assertEquals(source, extract(tree));
        for (String response : new String[]{
                "参阅 <x1>本<x2>指南</x2></x1> 现在",
                "参阅 <x1>本<x2>指南</x1> 现在",
                "参阅 <x1>本</x2>指南</x1> 现在",
                "参阅 <x1>本<x1>指南</x1></x1> 现在",
                "参阅 <x1>本<x9/>指南</x1> 现在",
                "参阅 <x1>本<x指南</x1> 现在"
        }) {
            assertNull(response, ContentNodes.replacePlanned(tree, response));
            assertEquals(source, extract(tree));
        }
        List<Object> out = ContentNodes.replaceChecked(tree, "参阅 <x1>本指南</x1> 现在");
        assertEquals("本指南", label(out.get(1)));
        assertEquals("参阅 <x1>本指南</x1> 现在", extract(ContentTree.compile(out)));
    }

    @Test public void completeStyleSequenceRejectsMissingDuplicateExtraReorderedAndMalformedTags() {
        ContentTree tree = tree(text("See "), link(new Object(), text("first")), text(" and "),
                new StrongContentNode(list(text("second"))), text(" now"));
        String source = "See <x1>first</x1> and <x2>second</x2> now";
        assertEquals(source, extract(tree));
        String[] invalid = {
                "参阅 第一 和 <x2>第二</x2> 现在",
                "参阅 <x1>第一 和 <x2>第二</x2> 现在",
                "参阅 <x1>第一</x1> 和 第二 现在",
                "参阅 <x1>第一</x1><x1>重复</x1> 和 <x2>第二</x2> 现在",
                "参阅 <x1>第一</x1></x1> 和 <x2>第二</x2> 现在",
                "参阅 <x1>第一</x1> 和 <x2>第二</x2><x3>额外</x3> 现在",
                "参阅 <x9>额外</x9><x1>第一</x1> 和 <x2>第二</x2> 现在",
                "参阅 <x1>第一</x1> 和 <x9>额外</x9><x2>第二</x2> 现在",
                "参阅 <x2>第二</x2> 和 <x1>第一</x1> 现在",
                "参阅 <x1><x2>第二</x2>第一</x1> 现在",
                "参阅 <x1>第一<x2>第二</x1></x2> 现在",
                "参阅 <x01>第一</x01> 和 <x2>第二</x2> 现在",
                "参阅 <x0>第一</x0> 和 <x2>第二</x2> 现在",
                "参阅 <X1>第一</X1> 和 <x2>第二</x2> 现在",
                "参阅 <x1/>第一</x1> 和 <x2>第二</x2> 现在",
                "参阅 <x1 attr='bad'>第一</x1> 和 <x2>第二</x2> 现在",
                "参阅 <x1>第一</x1> 和 <x2>第二</x2> 现在<x3",
                "参阅 <x1>第一</x1> 和 <x2>第二</x2> 现在</x",
                "参阅 <x1>第一</x1> 和 <x2>第二</x2> 现在<x3/>"
        };
        for (String response : invalid) {
            assertNull(response, ContentNodes.replacePlanned(tree, response));
            assertEquals(source, extract(tree));
        }
        String valid = "参阅 <x1>第一</x1> 和 <x2>第二</x2> 现在";
        List<Object> out = ContentNodes.replaceChecked(tree, valid);
        assertEquals(valid, extract(ContentTree.compile(out)));
        assertEquals("第一", label(out.get(1)));
        assertEquals("第二", content(((StrongContentNode) out.get(3)).getContent().get(0)));
        // 标记内的多余空白做无损修复后照常回填（2026-11-23 放宽）。
        String sloppy = "参阅 < x1>第一</ x1> 和 <x2>第二</x2> 现在";
        List<Object> repaired = ContentNodes.replaceChecked(tree, sloppy);
        assertEquals("第一", label(repaired.get(1)));
        String sloppyClose = "参阅 <x1>第一</x1 > 和 <x2>第二</x2> 现在";
        List<Object> repairedClose = ContentNodes.replaceChecked(tree, sloppyClose);
        assertEquals("第一", label(repairedClose.get(1)));
        String spaced = "参阅 <x1 >第一</x1> 和 <x2>第二</x2> 现在";
        List<Object> repairedSpaced = ContentNodes.replaceChecked(tree, spaced);
        assertEquals("第一", label(repairedSpaced.get(1)));
    }

    @Test public void unexpectedStyleTagsAreRejectedWhenThePlanHasNoStyledSlots() {
        ContentTree tree = tree(text("Plain text"));
        assertEquals("Plain text", extract(tree));
        for (String response : new String[]{
                "普通<x1>文本</x1>", "普通<x2>文本</x2>", "普通</x1>文本",
                "普通<x1/>", "普通<x1", "普通</x", "普通<X1>文本</X1>",
                "普通< x1>文本</ x1>", "普通<x0>文本</x0>", "普通<x01>文本</x01>"
        }) {
            assertNull(response, ContentNodes.replacePlanned(tree, response));
            assertEquals("Plain text", extract(tree));
        }
        assertEquals("普通文本", content(ContentNodes.replaceChecked(tree, "普通文本").get(0)));
    }

    @Test public void ordinaryXmlTagNamesAreNotReservedStyleMarkers() {
        ContentTree tree = tree(text("Use <xml> tags and <xylophone> names"));
        String translated = "使用 <xml> 标签和 <xylophone> 名称";
        assertEquals(translated, content(ContentNodes.replaceChecked(tree, translated).get(0)));
    }

    @Test public void headingBoundaryPrecedesLabelMarkerWithoutNativeNewlineInsertion() {
        HeadingContentNode heading = new HeadingContentNode(list(text("Title")), 2);
        Object context = new Object();
        ContentTree tree = tree(heading, link(context, text("guide")));
        assertEquals("Title\n<x1>guide</x1>", extract(tree));
        List<Object> out = ContentNodes.replaceChecked(tree, "标题\n<x1>指南</x1>");
        assertEquals("标题", content(((HeadingContentNode) out.get(0)).getContent().get(0)));
        assertEquals("指南", label(out.get(1)));
        assertSame(context, ((LinkContentNode) out.get(1)).component2());
        assertEquals(2, out.size());
    }
}
