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

    @Test public void labelMarkerDamageRemainsWholeTreeRejection() {
        ContentTree tree = tree(text("See "), link(new Object(), text("guide")));
        reject(tree, "参阅<x2>指南</x2>", ContentNodes.RejectionReason.STYLE_OPEN);
        reject(tree, "参阅<x1>指南", ContentNodes.RejectionReason.STYLE_CLOSE);
        reject(tree, "参阅<x1></x1>", ContentNodes.RejectionReason.STYLE_EMPTY);
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
