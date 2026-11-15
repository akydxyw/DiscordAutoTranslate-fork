package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;
import static io.github.wxmyyds.discordautotranslate.hooks.ContentNodes.RejectionReason.*;

import io.github.wxmyyds.discordautotranslate.core.ContentTree;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.TextContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.LinkContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.StrongContentNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;

/** Inspect fixed classifications through the same checked operation used by production. */
public class ContentRejectionTest {
    @Before public void setup() {
        ContentNodeTestProfile.activate(ContentNodesTest.class, EmojiAnchorTest.class);
    }
    @After public void cleanup() { ContentNodeTestProfile.clear(); }

    private ContentNodes.Rejection rejected(List<?> source, String translation,
                                            ContentNodes.RejectionReason expected) {
        ContentTree tree = ContentTree.compile(source);
        assertNotNull(tree);
        try {
            ContentNodes.replaceChecked(tree, translation);
            throw new AssertionError("Expected " + expected);
        } catch (ContentNodes.Rejection rejection) {
            assertSame(expected, rejection.reason);
            assertEquals(expected.name(), rejection.getMessage());
            assertNull(rejection.getCause());
            assertNull(ContentNodes.replacePlanned(tree, translation));
            return rejection;
        }
    }

    @Test public void repeatedUrlMultiplicityIsReportedWithoutItsValue() {
        String url = "https://private.invalid/path?identity=secret";
        List<?> source = Arrays.asList(new TextContentNode("Title"),
                new LinkContentNode(Arrays.asList(new TextContentNode(url)), new Object()),
                new LinkContentNode(Arrays.asList(new TextContentNode(url)), new Object()));
        ContentNodes.Rejection rejection = rejected(source, "标题 " + url, URL_COUNT);
        assertEquals(2, rejection.expected);
        assertEquals(1, rejection.actual);
        assertFalse(rejection.toString().contains("secret"));
    }

    @Test public void customEmojiMultiplicityIsNotConfusedWithUrlMismatch() {
        List<?> source = Arrays.asList(new TextContentNode("Title"),
                new EmojiAnchorTest.CustomEmojiContentNode(123L, ":private_name:"));
        ContentNodes.Rejection rejection = rejected(source, "标题", EMOJI_COUNT);
        assertEquals(1, rejection.expected);
        assertEquals(0, rejection.actual);
        assertFalse(rejection.toString().contains("private_name"));
    }

    @Test public void preservedCountsWithReorderedAnchorsReportOrderFailure() {
        String first = "https://first.invalid", second = "https://second.invalid";
        List<?> source = Arrays.asList(new TextContentNode("First"),
                new LinkContentNode(Arrays.asList(new TextContentNode(first)), new Object()),
                new TextContentNode("Second"),
                new LinkContentNode(Arrays.asList(new TextContentNode(second)), new Object()));
        rejected(source, "一 " + second + " 二 " + first, ANCHOR_ORDER);
    }

    @Test public void styleOpeningClosingAndEmptyContentHaveDistinctReasons() {
        List<?> source = Arrays.asList(new StrongContentNode(Arrays.asList(new TextContentNode("Title"))));
        rejected(source, "标题", STYLE_OPEN);
        rejected(source, "<x1>标题", STYLE_CLOSE);
        rejected(source, "<x1> </x1>", STYLE_EMPTY);
    }

    @Test public void modelExtraTextOutsideStyledSpanHasNoAssignedSourceSlot() {
        List<?> source = Arrays.asList(new StrongContentNode(Arrays.asList(new TextContentNode("Title"))));
        rejected(source, "额外文字<x1>标题</x1>", UNASSIGNED_TEXT);
    }

    @Test public void paragraphCountReasonsPreserveOldWholeTreeRejection() {
        List<?> source = Arrays.asList(new TextContentNode("First"), new TextContentNode("\n\n"),
                new TextContentNode("Second"));
        ContentNodes.Rejection missing = rejected(source, "一", PARAGRAPH_MISSING);
        assertEquals(2, missing.expected);
        assertEquals(1, missing.actual);
        ContentNodes.Rejection extra = rejected(source, "一\n\n二\n\n三", PARAGRAPH_EXTRA);
        assertEquals(2, extra.expected);
        assertEquals(3, extra.actual);
        assertEquals("First", ((TextContentNode) source.get(0)).getContent());
    }

    @Test public void ambiguousSourceBoundaryIsNotClaimedAsAModelCountError() {
        List<?> source = Arrays.asList(new ContentNodesTest.HeadingContentNode(
                Arrays.asList(new TextContentNode("Title")), 1), new TextContentNode(" "),
                new TextContentNode("5M+ FREE TOKENS"));
        rejected(source, "标题 500万以上免费令牌", SOURCE_BOUNDARY_AMBIGUOUS);
    }

    @Test public void explicitLineCountMismatchReportsExpectedAndActual() {
        List<?> source = Arrays.asList(new TextContentNode("First"),
                new ContentNodesTest.NewLineContentNode(), new TextContentNode("Second"));
        ContentNodes.Rejection rejection = rejected(source, "只有一行", LINE_COUNT);
        assertEquals(2, rejection.expected);
        assertEquals(1, rejection.actual);
    }

    public static final class BrokenTextFixture {
        public static final class TextContentNode {
            public TextContentNode(int ignored) { }
            public String getContent() { return "Original"; }
        }
    }

    public static final class BrokenContainerFixture {
        public static final class StrongContentNode {
            private final List<?> content;
            public StrongContentNode(List<?> content) { this.content = content; }
            public List<?> getContent() { return content; }
            // Deliberately no copy method in this data-class fixture profile.
        }
    }

    @Test public void nativeTextConstructorFailureHasItsOwnFixedReason() {
        ContentNodeTestProfile.activate(BrokenTextFixture.class);
        rejected(Arrays.asList(new BrokenTextFixture.TextContentNode(0)), "译文", TEXT_CONSTRUCTOR);
    }

    @Test public void nativeContainerConstructorFailureHasItsOwnFixedReason() {
        ContentNodeTestProfile.activate(ContentNodesTest.class, BrokenContainerFixture.class);
        rejected(Arrays.asList(new BrokenContainerFixture.StrongContentNode(
                Arrays.asList(new TextContentNode("Original")))), "<x1>译文</x1>", CONTAINER_CONSTRUCTOR);
    }

    @Test public void completeRepeatedLinkAndEmojiPlanStillRebuildsWithoutChangingInputs() {
        String url = "https://repeat.invalid";
        List<?> source = Arrays.asList(
                new StrongContentNode(Arrays.asList(new TextContentNode("Title"))),
                new TextContentNode("\n\n"),
                new EmojiAnchorTest.UnicodeEmojiContentNode("👉", "point", false),
                new LinkContentNode(Arrays.asList(new TextContentNode(url)), new Object()),
                new EmojiAnchorTest.UnicodeEmojiContentNode("👈", "point", false),
                new TextContentNode("\n\n"), new TextContentNode("Tokens"),
                new TextContentNode("\n\n"),
                new EmojiAnchorTest.UnicodeEmojiContentNode("👉", "point", false),
                new LinkContentNode(Arrays.asList(new TextContentNode(url)), new Object()),
                new EmojiAnchorTest.UnicodeEmojiContentNode("👈", "point", false));
        List<Object> output = ContentNodes.replacePlanned(ContentTree.compile(source),
                "<x1>标题</x1>\n\n👉 " + url + " 👈\n\n令牌\n\n👉 " + url + " 👈");
        assertNotNull(output);
        assertEquals("令牌", ((TextContentNode) output.get(6)).getContent());
        assertEquals("Tokens", ((TextContentNode) source.get(6)).getContent());
        for (int i : new int[]{2, 3, 4, 8, 9, 10}) assertSame(source.get(i), output.get(i));
    }
}
