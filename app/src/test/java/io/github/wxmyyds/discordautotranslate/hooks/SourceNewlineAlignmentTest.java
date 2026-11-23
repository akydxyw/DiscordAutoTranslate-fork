package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.wxmyyds.discordautotranslate.core.ContentTree;
import io.github.wxmyyds.discordautotranslate.core.TextExtractor;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.BulletListContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.CodeBlockContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.EmphasisContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.HeadingContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.InlineCodeContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.LineBreakContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.LinkContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.NewLineContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.StrongContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.TextContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.EmojiAnchorTest.CustomEmojiContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.EmojiAnchorTest.UnicodeEmojiContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.OpaqueNodeTranslationTest.ChannelMentionContentNode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Captured source LF and normalized heading block boundaries prove slot alignment. */
public class SourceNewlineAlignmentTest {
    private static final String URL = "https://discord.gg/IJYuv3wZ";
    private static final String EMOJI = "\uD83D\uDCE2";

    @Before
    public void activateFixtureProfile() {
        ContentNodeTestProfile.activate(ContentNodesTest.class, EmojiAnchorTest.class,
                OpaqueNodeTranslationTest.class);
    }

    @After
    public void clearFixtureProfile() {
        ContentNodeTestProfile.clear();
    }

    @Test
    public void headingThenWhitespaceTextLfThenPlainTextAlignsAsTwoLines() {
        HeadingContentNode heading = new HeadingContentNode(list(text("Lower Rates")), 2);
        TextContentNode lf = text("\n");
        TextContentNode body = text("Available today");
        ContentTree tree = compiled(list(heading, lf, body));
        assertEquals("Lower Rates\nAvailable today", extracted(tree));
        assertEquals(2, tree.segmentCount);

        List<Object> out = replaced(tree, "优惠价格\n今日可用");
        HeadingContentNode rebuilt = (HeadingContentNode) out.get(0);
        assertEquals(2, rebuilt.component2());
        assertEquals("优惠价格", content(rebuilt.getContent().get(0)));
        assertSame(lf, out.get(1));
        assertEquals("今日可用", content(out.get(2)));
        assertEquals("Lower Rates", content(heading.getContent().get(0)));
        assertEquals("Available today", body.getContent());
    }

    @Test
    public void headingBlockBeforePlainTextKeepsInterveningSpaceAndAlignsAsTwoLines() {
        // Exact heading occurrence transitions now supply a shared block LF, not same-line ambiguity.
        HeadingContentNode heading = new HeadingContentNode(list(text("Heading")), 1);
        TextContentNode space = text(" ");
        TextContentNode body = text("Body");
        ContentTree tree = compiled(list(heading, space, body));
        assertEquals("Heading \nBody", extracted(tree));
        assertEquals(2, tree.segmentCount);
        assertFalse(tree.events.get(0).headingBreakBefore);
        assertFalse(tree.events.get(1).headingBreakBefore);
        assertTrue(tree.events.get(2).headingBreakBefore);

        List<Object> out = replaced(tree, "标题\n正文");
        assertEquals(3, out.size());
        HeadingContentNode rebuilt = (HeadingContentNode) out.get(0);
        assertEquals(1, rebuilt.component2());
        assertEquals("标题", content(rebuilt.getContent().get(0)));
        assertSame(space, out.get(1));
        assertEquals(" ", content(out.get(1)));
        assertEquals("正文", content(out.get(2)));
        assertEquals("标题 \n正文", extracted(compiled(out)));
        assertEquals("Heading", content(heading.getContent().get(0)));
        assertEquals("Body", body.getContent());
        assertEquals("Heading \nBody", extracted(tree));

        ContentNodes.Rejection merged = rejected(tree, "标题 正文", ContentNodes.RejectionReason.LINE_COUNT);
        assertEquals(2, merged.expected);
        assertEquals(1, merged.actual);
        ContentNodes.Rejection split = rejected(tree, "标题\n\n正文", ContentNodes.RejectionReason.LINE_COUNT);
        assertEquals(2, split.expected);
        assertEquals(1, split.actual);
        ContentNodes.Rejection extraLine = rejected(tree, "标题\n正文\n多余",
                ContentNodes.RejectionReason.LINE_COUNT);
        assertEquals(2, extraLine.expected);
        assertEquals(3, extraLine.actual);
        ContentNodes.Rejection extraParagraph = rejected(tree, "标题\n正文\n\n多余",
                ContentNodes.RejectionReason.PARAGRAPH_EXTRA);
        assertEquals(1, extraParagraph.expected);
        assertEquals(2, extraParagraph.actual);
    }

    @Test
    public void eitherTrailingOrLeadingLfProvesTheBoundaryAndIsPreserved() {
        for (boolean trailing : new boolean[]{true, false}) {
            TextContentNode first = text(trailing ? "first\n" : "first");
            TextContentNode second = text(trailing ? "second" : "\nsecond");
            ContentTree tree = compiled(list(first, second));
            List<Object> out = replaced(tree, "第一\n第二");
            assertEquals(trailing ? "第一\n" : "第一", content(out.get(0)));
            assertEquals(trailing ? "第二" : "\n第二", content(out.get(1)));
            assertEquals("第一\n第二", extracted(compiled(out)));
            assertEquals("first\nsecond", extracted(tree));
        }
    }

    @Test
    public void outerLeadingAndTrailingLfDoNotAddLinesToChunkCounts() {
        ContentTree tree = compiled(list(text("\n\n first\n"), text("second \n\n")));
        assertEquals(Arrays.asList(1), tree.root.children.get(0).chunkLineCounts);
        assertEquals(Arrays.asList(1), tree.root.children.get(1).chunkLineCounts);
        List<Object> out = replaced(tree, "\n第一\n第二\n");
        assertEquals("\n\n 第一\n", content(out.get(0)));
        assertEquals("第二 \n\n", content(out.get(1)));
    }

    @Test
    public void separateWhitespaceNodesAccumulateIntoParagraphBoundary() {
        TextContentNode first = text("\n\nfirst\n");
        TextContentNode spaces = text(" \t");
        TextContentNode lf = text("\n");
        TextContentNode second = text("\nsecond\n\n");
        ContentTree tree = compiled(list(first, spaces, lf, second));
        List<Object> out = replaced(tree, "第一\n\n第二");
        assertEquals("\n\n第一\n", content(out.get(0)));
        assertSame(spaces, out.get(1));
        assertSame(lf, out.get(2));
        assertEquals("\n第二\n\n", content(out.get(3)));
        rejected(tree, "第一\n第二", ContentNodes.RejectionReason.PARAGRAPH_MISSING);
        assertEquals("\n\nfirst\n \t\n\nsecond\n\n", extracted(tree));
    }

    @Test
    public void textLfAndBreakNodeAccumulateIntoParagraphNotConnectedLineRun() {
        NewLineContentNode lineBreak = new NewLineContentNode();
        ContentTree tree = compiled(list(text("first"), text("\n"), lineBreak, text("second")));
        List<Object> out = replaced(tree, "第一\n\n第二");
        assertSame(lineBreak, out.get(2));
        assertEquals("第一\n\n第二", extracted(compiled(out)));
        rejected(tree, "第一\n第二", ContentNodes.RejectionReason.PARAGRAPH_MISSING);
    }

    @Test
    public void bothNativeBreakTypesStillProveSingleSourceLineBoundary() {
        for (Object lineBreak : new Object[]{new NewLineContentNode(), new LineBreakContentNode()}) {
            ContentTree tree = compiled(list(text("first\ncontinuation"), lineBreak, text("second")));
            List<Object> out = replaced(tree, "第一\n续行\n第二");
            assertEquals("第一\n续行", content(out.get(0)));
            assertSame(lineBreak, out.get(1));
            assertEquals("第二", content(out.get(2)));
        }
    }

    @Test
    public void connectedRunConsumesTwoOneTwoSourceLinesAndRejoinsEachSlot() {
        TextContentNode first = text("first\ncontinuation");
        TextContentNode lf = text("\n");
        TextContentNode middle = text("middle");
        TextContentNode last = text("\nlast\ncontinuation");
        ContentTree tree = compiled(list(first, lf, middle, last));
        assertEquals(3, tree.segmentCount);
        assertEquals(Arrays.asList(2), tree.root.children.get(0).chunkLineCounts);
        assertEquals(Arrays.asList(1), tree.root.children.get(2).chunkLineCounts);
        assertEquals(Arrays.asList(2), tree.root.children.get(3).chunkLineCounts);

        List<Object> out = replaced(tree, "第一\n首段续行\n中间\n末段\n末段续行");
        assertEquals("第一\n首段续行", content(out.get(0)));
        assertSame(lf, out.get(1));
        assertEquals("中间", content(out.get(2)));
        assertEquals("\n末段\n末段续行", content(out.get(3)));
        assertEquals("first\ncontinuation", first.getContent());
        assertEquals("middle", middle.getContent());
        assertEquals("\nlast\ncontinuation", last.getContent());
    }

    @Test
    public void mergedLineInMultilineConnectedRunIsRejectedWithTotalSourceCount() {
        ContentTree tree = mixedLineTree();
        ContentNodes.Rejection failure = rejected(tree, "第一\n中间\n末段\n续行",
                ContentNodes.RejectionReason.LINE_COUNT);
        assertEquals(5, failure.expected);
        assertEquals(4, failure.actual);
    }

    @Test
    public void extraLineInMultilineConnectedRunIsRejectedWithTotalSourceCount() {
        ContentTree tree = mixedLineTree();
        ContentNodes.Rejection failure = rejected(tree, "第一\n续行\n中间\n末段\n续行\n多余",
                ContentNodes.RejectionReason.LINE_COUNT);
        assertEquals(5, failure.expected);
        assertEquals(6, failure.actual);
    }

    @Test
    public void oneTextNodeCanSupplyParagraphChunkThenMultilineConnectedRun() {
        ContentTree tree = compiled(list(text("first\ncontinuation\n\nsecond"),
                text("\n"), text("third\ncontinuation")));
        assertEquals(Arrays.asList(2, 1), tree.root.children.get(0).chunkLineCounts);
        List<Object> out = replaced(tree, "第一\n首段续行\n\n第二\n第三\n末段续行");
        assertEquals("第一\n首段续行\n\n第二", content(out.get(0)));
        assertEquals("第三\n末段续行", content(out.get(2)));
    }

    @Test
    public void fifthArgumentCountsOnlyConnectedRunsAndKeepsSingletonBlockContract() {
        assertEquals(Arrays.asList("独立块", "甲\n乙", "丙", "丁\n戊"),
                ContentNodes.alignSpanToParts("独立块\n\n甲\n乙\n丙\n丁\n戊",
                        Arrays.asList(0, 1, 1, 1),
                        Arrays.asList(false, false, false, false),
                        Arrays.asList(false, false, true, true),
                        Arrays.asList(3, 2, 1, 2)));
        assertEquals(Arrays.asList("仍是\n一个\n独立块"),
                ContentNodes.alignSpanToParts("仍是\n一个\n独立块", Arrays.asList(0),
                        Arrays.asList(false), Arrays.asList(false), Arrays.asList(1)));
    }

    @Test
    public void styleAnchorsBoundMultilinePlainRunWithoutLosingStyleOrLineCounts() {
        StrongContentNode strong = new StrongContentNode(list(text("styled\ncontinuation")));
        EmphasisContentNode emphasis = new EmphasisContentNode(list(text("end")));
        ContentTree tree = compiled(list(strong, text("\n"), text("first\ncontinuation"),
                text("\n"), text("second"), text("\n"), emphasis));
        assertEquals("<x1>styled\ncontinuation</x1>\nfirst\ncontinuation\nsecond\n<x2>end</x2>",
                extracted(tree));
        List<Object> out = replaced(tree,
                "<x1>样式\n续行</x1>\n第一\n续行\n第二\n<x2>结尾</x2>");
        assertEquals("样式\n续行", content(((StrongContentNode) out.get(0)).getContent().get(0)));
        assertEquals("第一\n续行", content(out.get(2)));
        assertEquals("第二", content(out.get(4)));
        assertEquals("结尾", content(((EmphasisContentNode) out.get(6)).getContent().get(0)));
        assertEquals("styled\ncontinuation", content(strong.getContent().get(0)));
    }

    @Test
    public void styleBoundedPlainRunStillRejectsMergedOrExtraLine() {
        ContentTree tree = compiled(list(new StrongContentNode(list(text("heading"))),
                text("\n"), text("first\ncontinuation"), text("\n"), text("second"),
                new EmphasisContentNode(list(text("end")))));
        for (String run : new String[]{"第一\n第二", "第一\n续行\n第二\n多余"}) {
            ContentNodes.Rejection failure = rejected(tree,
                    "<x1>标题</x1>\n" + run + "<x2>末尾</x2>", ContentNodes.RejectionReason.LINE_COUNT);
            assertEquals(3, failure.expected);
        }
    }

    @Test
    public void repeatedUrlAndEmojiIntervalsEachConsumeTheirOwnMultilineCounts() {
        List<Object> nodes = anchorIntervalNodes();
        ContentTree tree = compiled(nodes);
        assertEquals(8, tree.segmentCount);
        List<Object> out = replaced(tree, anchorIntervalTranslation());
        for (int index : new int[]{3, 7, 11}) assertSame(nodes.get(index), out.get(index));
        assertEquals("甲\n乙", content(out.get(0)));
        assertEquals("丙", content(out.get(2)));
        assertEquals("丁", content(out.get(4)));
        assertEquals("戊\n己", content(out.get(6)));
        assertEquals("庚\n辛", content(out.get(8)));
        assertEquals("壬", content(out.get(10)));
        assertEquals("癸\n子", content(out.get(12)));
        assertEquals("丑", content(out.get(14)));
        for (int index : new int[]{1, 5, 9, 13}) assertSame(nodes.get(index), out.get(index));
    }

    @Test
    public void anchorIntervalsCannotBorrowLinesEvenWhenGlobalTotalMatches() {
        ContentTree tree = compiled(anchorIntervalNodes());
        ContentNodes.Rejection failure = rejected(tree,
                "甲\n丙 " + URL + " 丁\n戊\n己\n借来的行 " + EMOJI
                        + " 庚\n辛\n壬 " + URL + " 癸\n子\n丑",
                ContentNodes.RejectionReason.LINE_COUNT);
        assertEquals(3, failure.expected);
        assertEquals(2, failure.actual);
    }

    @Test
    public void repeatedExactUrlAndEmojiMustStillHaveExactAnchorCounts() {
        ContentTree tree = compiled(anchorIntervalNodes());
        String translated = anchorIntervalTranslation();
        rejected(tree, translated.replaceFirst("IJYuv3wZ", "lJYuv3wZ"),
                ContentNodes.RejectionReason.URL_COUNT);
        // emoji 被模型丢弃后两侧译文在多行结构里合并：行数对不上仍拒绝，
        // 但原因从 EMOJI_COUNT 变为 LINE_COUNT（2026-11-23 放宽锚点容忍后）。
        rejected(tree, translated.replace(EMOJI, ""), ContentNodes.RejectionReason.LINE_COUNT);
        rejected(tree, translated.replace(EMOJI, EMOJI + EMOJI), ContentNodes.RejectionReason.EMOJI_COUNT);
    }

    @Test
    public void twoCodeBlockMarkersProveBoundaryWithoutInventingParagraph() {
        CodeBlockContentNode firstCode = new CodeBlockContentNode("one\nline", "java", false);
        CodeBlockContentNode secondCode = new CodeBlockContentNode("two", "text", true);
        ContentTree tree = compiled(list(text("first"), firstCode, secondCode, text("second")));
        assertEquals("first<keep1/><keep2/>second", extracted(tree));
        List<Object> out = replaced(tree, "第一<keep1/><keep2/>第二");
        assertEquals("第一", content(out.get(0)));
        assertSame(firstCode, out.get(1));
        assertSame(secondCode, out.get(2));
        assertEquals("第二", content(out.get(3)));
        assertEquals("第一<keep1/><keep2/>第二", extracted(compiled(out)));
        rejected(tree, "第一<keep1/>第二", ContentNodes.RejectionReason.RETAINED_MARKER);
        rejected(tree, "第一<keep2/>第二", ContentNodes.RejectionReason.RETAINED_MARKER);
        rejected(tree, "第一\n\n第二", ContentNodes.RejectionReason.RETAINED_MARKER);
        assertSame(firstCode, tree.root.children.get(1).original);
        assertSame(secondCode, tree.root.children.get(2).original);
    }

    @Test
    public void genuineTextLfAcrossTwoCodeBlocksRemainsOneLineBoundaryNotParagraph() {
        CodeBlockContentNode firstCode = new CodeBlockContentNode("one", "java", false);
        CodeBlockContentNode secondCode = new CodeBlockContentNode("two", "text", false);
        ContentTree tree = compiled(list(text("first"), text("\n"), firstCode, secondCode,
                text("second\ncontinuation")));
        assertEquals("first\n<keep1/><keep2/>second\ncontinuation", extracted(tree));
        List<Object> out = replaced(tree, "第一\n<keep1/><keep2/>第二\n续行");
        assertEquals("第一", content(out.get(0)));
        assertSame(firstCode, out.get(2));
        assertSame(secondCode, out.get(3));
        assertEquals("第二\n续行", content(out.get(4)));
        assertEquals("第一\n<keep1/><keep2/>第二\n续行", extracted(compiled(out)));
        rejected(tree, "第一\n第二\n续行", ContentNodes.RejectionReason.RETAINED_MARKER);
    }

    @Test
    public void requestPreservingResponseSucceedsForTextLfThenCodeBlockThenText() {
        TextContentNode first = text("First\n");
        CodeBlockContentNode code = new CodeBlockContentNode("hidden\n\ncode", "java", false);
        TextContentNode second = text("Second");
        ContentTree tree = compiled(list(first, code, second));
        assertEquals("First\n<keep1/>Second", extracted(tree));

        List<Object> out = replaced(tree, "第一\n<keep1/>第二");
        assertEquals("第一\n", content(out.get(0)));
        assertSame(code, out.get(1));
        assertEquals("第二", content(out.get(2)));
        assertEquals("第一\n<keep1/>第二", extracted(compiled(out)));
        rejected(tree, "第一\n第二", ContentNodes.RejectionReason.RETAINED_MARKER);
        assertEquals("First\n", first.getContent());
        assertEquals("Second", second.getContent());
    }

    @Test
    public void retainedMarkersProveBoundaryWithoutUsingHiddenLf() {
        for (Object hidden : hiddenLfNodes()) {
            TextContentNode first = text("first");
            TextContentNode second = text("second");
            ContentTree tree = compiled(list(first, hidden, second));
            assertEquals("first<keep1/>second", extracted(tree));
            List<Object> out = replaced(tree, "第一<keep1/>第二");
            assertEquals("第一", content(out.get(0)));
            assertSame(hidden, out.get(1));
            assertEquals("第二", content(out.get(2)));
            assertEquals("第一<keep1/>第二", extracted(compiled(out)));
            if (hidden instanceof InlineCodeContentNode) {
                assertEquals("hidden\n\ncode", ((InlineCodeContentNode) hidden).getContent());
            }
            rejected(tree, "第一\n第二", ContentNodes.RejectionReason.RETAINED_MARKER);
            rejected(tree, "第一\n\n第二", ContentNodes.RejectionReason.RETAINED_MARKER);
            assertEquals(2, tree.segmentCount);
            assertEquals("first", first.getContent());
            assertEquals("second", second.getContent());
        }
    }

    @Test
    public void capturedTextLfAcrossInlineCodeAndOpaqueNodesStillProvesBoundary() {
        for (Object hidden : hiddenLfNodes()) {
            TextContentNode lf = text("\n");
            ContentTree tree = compiled(list(text("first\ncontinuation"), lf, hidden, text("second")));
            assertEquals("first\ncontinuation\n<keep1/>second", extracted(tree));
            List<Object> out = replaced(tree, "第一\n续行\n<keep1/>第二");
            rejected(tree, "第一\n续行\n第二", ContentNodes.RejectionReason.RETAINED_MARKER);
            assertEquals("第一\n续行", content(out.get(0)));
            assertSame(lf, out.get(1));
            assertSame(hidden, out.get(2));
            assertEquals("第二", content(out.get(3)));
        }
    }

    @Test
    public void inlineCodeIntervalsKeepSourceLfCountsWithoutBorrowingLines() {
        InlineCodeContentNode code = new InlineCodeContentNode("hidden\n\ncode");
        TextContentNode firstLf = text("\n");
        TextContentNode secondLf = text("\n");
        ContentTree tree = compiled(list(text("first\ncontinuation"), firstLf, text("middle"),
                code, text("second"), secondLf, text("last\ncontinuation")));
        assertEquals("first\ncontinuation\nmiddle<keep1/>second\nlast\ncontinuation", extracted(tree));
        assertEquals(4, tree.segmentCount);

        List<Object> out = replaced(tree, "第一\n首段续行\n中间<keep1/>第二\n末段\n末段续行");
        assertEquals("第一\n首段续行", content(out.get(0)));
        assertSame(firstLf, out.get(1));
        assertEquals("中间", content(out.get(2)));
        assertSame(code, out.get(3));
        assertEquals("第二", content(out.get(4)));
        assertSame(secondLf, out.get(5));
        assertEquals("末段\n末段续行", content(out.get(6)));
        assertEquals("hidden\n\ncode", code.getContent());

        ContentNodes.Rejection merged = rejected(tree,
                "第一\n中间<keep1/>第二\n末段\n末段续行", ContentNodes.RejectionReason.LINE_COUNT);
        assertEquals(3, merged.expected);
        assertEquals(2, merged.actual);
        ContentNodes.Rejection extra = rejected(tree,
                "第一\n首段续行\n中间<keep1/>第二\n末段\n末段续行\n多余",
                ContentNodes.RejectionReason.LINE_COUNT);
        assertEquals(3, extra.expected);
        assertEquals(4, extra.actual);
        ContentNodes.Rejection borrowed = rejected(tree,
                "第一\n中间<keep1/>第二\n末段\n末段续行\n借来的行",
                ContentNodes.RejectionReason.LINE_COUNT);
        assertEquals(3, borrowed.expected);
        assertEquals(2, borrowed.actual);
        rejected(tree, "第一\n首段续行\n中间第二\n末段\n末段续行",
                ContentNodes.RejectionReason.RETAINED_MARKER);
        assertEquals("first\ncontinuation\nmiddle<keep1/>second\nlast\ncontinuation", extracted(tree));
    }

    @Test
    public void genuineSameLineTextNodesRemainAmbiguousEvenForMatchingLineTotals() {
        ContentTree tree = compiled(list(text("first\ncontinuation"), text(" "), text("second")));
        rejected(tree, "第一\n续行\n第二", ContentNodes.RejectionReason.SOURCE_BOUNDARY_AMBIGUOUS);
        rejected(tree, "第一 续行 第二", ContentNodes.RejectionReason.SOURCE_BOUNDARY_AMBIGUOUS);
    }

    @Test
    public void multilineListItemContinuationAndNextItemRejoinSlotsAndKeepListMetadata() {
        TextContentNode lf = text("\n");
        BulletListContentNode bullets = multilineList(lf);
        ContentTree tree = compiled(list(bullets));
        assertEquals(3, tree.segmentCount);
        List<Object> out = replaced(tree, "7. 第一\n首项续行\n补充\n8. 第二\n次项续行");
        BulletListContentNode rebuilt = (BulletListContentNode) out.get(0);
        assertEquals(Long.valueOf(7L), rebuilt.component1());
        assertTrue(rebuilt.component2());
        assertEquals(2, rebuilt.getItems().size());
        assertEquals("第一\n首项续行", content(rebuilt.getItems().get(0).get(0)));
        assertSame(lf, rebuilt.getItems().get(0).get(1));
        assertEquals("补充", content(rebuilt.getItems().get(0).get(2)));
        assertEquals("第二\n次项续行", content(rebuilt.getItems().get(1).get(0)));
        assertEquals("first\ncontinuation", content(bullets.getItems().get(0).get(0)));
    }

    @Test
    public void multilineListRejectsMergedAndExtraContinuationLines() {
        ContentTree tree = compiled(list(multilineList(text("\n"))));
        for (String translated : new String[]{"• 第一\n补充\n• 第二\n续行",
                "• 第一\n续行\n补充\n• 第二\n续行\n多余"}) {
            ContentNodes.Rejection failure = rejected(tree, translated, ContentNodes.RejectionReason.LINE_COUNT);
            assertEquals(5, failure.expected);
        }
    }

    @Test
    public void synthesizedAdFixtureKeepsHeadingPricesRepeatedUrlEmojiBracketAndInlineHash() {
        // Deliberately synthesized regression fixture, NOT an exact native-device tree capture.
        String alt = ":party:";
        HeadingContentNode heading = new HeadingContentNode(list(text("Lower Rates Are Back")), 2);
        LinkContentNode firstUrl = link(URL);
        LinkContentNode secondUrl = link(URL);
        CustomEmojiContentNode custom = new CustomEmojiContentNode(42L, alt);
        List<Object> nodes = list(heading, text("\n"),
                new EmphasisContentNode(list(text("$5 per million coins"))), text("\n"),
                new EmphasisContentNode(list(text("$10 per two million coins"))), text("\n"),
                new EmphasisContentNode(list(text("$20 per five million coins"))), text("\n"),
                text("Fast delivery\nSecure trades"), text("\n"), text("5M coins available now"),
                text("\n"), firstUrl, text(" ["), custom, text("] Order today"), text("\n"),
                text("Limited stock\nBuy now"), text("\n"), secondUrl, text("\n"),
                text("Thanks #market"), text("\n"), text("5M more coins\nReady today"));
        ContentTree tree = compiled(nodes);
        assertEquals(11, tree.segmentCount);
        assertEquals("Lower Rates Are Back\n<x1>$5 per million coins</x1>\n"
                        + "<x2>$10 per two million coins</x2>\n<x3>$20 per five million coins</x3>\n"
                        + "Fast delivery\nSecure trades\n5M coins available now\n " + URL
                        + "  [ " + alt + " ] Order today\nLimited stock\nBuy now\n " + URL
                        + " \nThanks #market\n5M more coins\nReady today", extracted(tree));
        String translated = "优惠价格回归\n<x1>$5 / 百万金币</x1>\n"
                + "<x2>$10 / 两百万金币</x2>\n<x3>$20 / 五百万金币</x3>\n"
                + "快速交付\n安全交易\n现有 5M 金币\n" + URL + " [" + alt
                + "] 立即下单\n库存有限\n现在购买\n" + URL
                + "\n谢谢 #market\n再有 5M 金币\n今日可交付";
        List<Object> out = replaced(tree, translated);
        assertEquals(nodes.size(), out.size());
        HeadingContentNode rebuiltHeading = (HeadingContentNode) out.get(0);
        assertEquals(2, rebuiltHeading.component2());
        assertEquals("优惠价格回归", content(rebuiltHeading.getContent().get(0)));
        String[] prices = {"$5 / 百万金币", "$10 / 两百万金币", "$20 / 五百万金币"};
        for (int i = 0; i < prices.length; i++) {
            assertEquals(prices[i], content(((EmphasisContentNode) out.get(2 + i * 2)).getContent().get(0)));
        }
        assertSame(firstUrl, out.get(12));
        assertSame(custom, out.get(14));
        assertSame(secondUrl, out.get(19));
        assertEquals(" [", content(out.get(13)));
        assertEquals("] 立即下单", content(out.get(15)));
        assertEquals("谢谢 #market", content(out.get(21)));
        assertEquals("再有 5M 金币\n今日可交付", content(out.get(23)));
        for (int index : new int[]{1, 3, 5, 7, 9, 11, 16, 18, 20, 22}) {
            assertSame(nodes.get(index), out.get(index));
        }
        assertEquals("优惠价格回归\n<x1>$5 / 百万金币</x1>\n"
                        + "<x2>$10 / 两百万金币</x2>\n<x3>$20 / 五百万金币</x3>\n"
                        + "快速交付\n安全交易\n现有 5M 金币\n " + URL + "  [ " + alt
                        + " ] 立即下单\n库存有限\n现在购买\n " + URL
                        + " \n谢谢 #market\n再有 5M 金币\n今日可交付", extracted(compiled(out)));
        assertEquals("Lower Rates Are Back", content(heading.getContent().get(0)));
        assertEquals("$5 per million coins", content(((EmphasisContentNode) nodes.get(2)).getContent().get(0)));
        rejected(tree, translated.replace("安全交易\n", ""), ContentNodes.RejectionReason.LINE_COUNT);
        rejected(tree, translated.replace("<x2>", "<x9>"), ContentNodes.RejectionReason.STYLE_OPEN);
    }

    private static ContentTree mixedLineTree() {
        return compiled(list(text("first\ncontinuation"), text("\n"), text("middle"),
                text("\n"), text("last\ncontinuation")));
    }

    private static List<Object> anchorIntervalNodes() {
        return list(text("a\nb"), text("\n"), text("c"), link(URL),
                text("d"), text("\n"), text("e\nf"),
                new UnicodeEmojiContentNode(EMOJI, "x", false),
                text("g\nh"), text("\n"), text("i"), link(URL),
                text("j\nk"), text("\n"), text("l"));
    }

    private static String anchorIntervalTranslation() {
        return "甲\n乙\n丙 " + URL + " 丁\n戊\n己 " + EMOJI
                + " 庚\n辛\n壬 " + URL + " 癸\n子\n丑";
    }

    private static List<Object> hiddenLfNodes() {
        return list(new InlineCodeContentNode("hidden\n\ncode"),
                new ChannelMentionContentNode(list(text("hidden\n\ndisplay name"))));
    }

    private static BulletListContentNode multilineList(TextContentNode lf) {
        List<List<Object>> items = new ArrayList<>();
        items.add(list(text("first\ncontinuation"), lf, text("additional detail")));
        items.add(list(text("second\ncontinuation")));
        return new BulletListContentNode(7L, true, items);
    }

    private static LinkContentNode link(String url) {
        return new LinkContentNode(list(text(url)), new Object());
    }

    private static TextContentNode text(String value) {
        return new TextContentNode(value);
    }

    private static String content(Object node) {
        return ((TextContentNode) node).getContent();
    }

    private static ContentTree compiled(List<?> nodes) {
        ContentTree tree = ContentTree.compile(nodes);
        assertNotNull("valid synthetic fixture must compile", tree);
        return tree;
    }

    private static String extracted(ContentTree tree) {
        TextExtractor.Result result = TextExtractor.extractTree(tree);
        assertNotNull(result);
        assertEquals(tree.segmentCount, result.segmentCount);
        return result.text;
    }

    private static List<Object> replaced(ContentTree tree, String translated) {
        List<Object> out = ContentNodes.replaceChecked(tree, translated);
        assertNotNull(out);
        return out;
    }

    private static ContentNodes.Rejection rejected(ContentTree tree, String translated,
                                                    ContentNodes.RejectionReason reason) {
        ContentNodes.Rejection failure = assertThrows(ContentNodes.Rejection.class,
                () -> ContentNodes.replaceChecked(tree, translated));
        assertEquals(reason, failure.reason);
        assertNull("public replacement boundary must reject the whole tree",
                ContentNodes.replacePlanned(tree, translated));
        return failure;
    }

    private static List<Object> list(Object... nodes) {
        return new ArrayList<>(Arrays.asList(nodes));
    }
}
