package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.wxmyyds.discordautotranslate.core.ContentTree;
import io.github.wxmyyds.discordautotranslate.core.EnglishDetector;
import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.RetainedNodeMarkers;
import io.github.wxmyyds.discordautotranslate.core.TextExtractor;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.BulletListContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.EmphasisContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.HeadingContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.LinkContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.NewLineContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.ParagraphContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.StrongContentNode;
import io.github.wxmyyds.discordautotranslate.hooks.ContentNodesTest.TextContentNode;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Markdown Test 2/3 的显式节点树回归，不实现 Markdown parser。
 * 完整格式样例验证生产捕获/检测；独立的小型 mixed fixture 验证严格回填，
 * 避免把语言判定回归与复杂列表/相邻正文的 alignment 问题混在一起。
 */
public class MarkdownDetectionTest {
    private static final String TARGET_LANGUAGE = "简体中文";
    private static final String PROSE =
            "Please read the release notes before changing the translation settings.";
    private static final String LONG_PROSE =
            "Please read the release notes before changing the translation settings. "
                    + "Every message should preserve its original formatting and native mentions. "
                    + "Share the translated explanation with the team after reviewing the results.";
    private static final String JAVASCRIPT =
            "const message = 'This English sentence belongs only to source code.';\n"
                    + "function showMessage(user) {\n"
                    + "  console.log(message, user);\n"
                    + "}\n"
                    + "showMessage('Alice');";

    @Before
    public void activateFixtureProfile() throws JSONException {
        ContentNodeTestProfile.activate(ContentNodesTest.class, MarkdownDetectionTest.class);
        // These real style semantics are not in the shared fixture registry. Extend only
        // this test's active profile; do not change ContentNodeTestProfile or production.
        JSONObject profile = new JSONObject(HookConfiguration.activeConfiguration().rawJson());
        JSONObject aliases = profile.getJSONObject("typeAliases");
        aliases.put(UnderlineContentNode.class.getName(), "UnderlineContentNode");
        aliases.put(SpoilerContentNode.class.getName(), "SpoilerContentNode");
        aliases.put(StrikethroughContentNode.class.getName(), "StrikethroughContentNode");
        HookConfiguration.activate(HookConfiguration.parse(profile.toString(), 352017));
    }

    @After
    public void clearFixtureProfile() {
        ContentNodeTestProfile.clear();
    }

    // Reflect.call suppresses invocation failures, so throwing getters alone are not
    // sufficient. Every test also checks these counters remain exactly zero.
    public abstract static class UnreadableLeaf {
        private final String payload;
        private int reads;

        UnreadableLeaf(String payload) { this.payload = payload; }

        final <T> T forbiddenRead() {
            reads++;
            throw new AssertionError("Retained node internals must not be read");
        }

        @Override public final String toString() { return forbiddenRead(); }
    }

    public static final class CodeBlockContentNode extends UnreadableLeaf {
        private final String language;
        private final boolean inQuote;

        public CodeBlockContentNode(String content, String language, boolean inQuote) {
            super(content);
            this.language = language;
            this.inQuote = inQuote;
        }

        public String getContent() { return forbiddenRead(); }
        public String component1() { return forbiddenRead(); }
        public String component2() { return forbiddenRead(); }
        public boolean component3() { return forbiddenRead(); }
    }

    public static final class InlineCodeContentNode extends UnreadableLeaf {
        public InlineCodeContentNode(String content) { super(content); }
        public String getContent() { return forbiddenRead(); }
        public String component1() { return forbiddenRead(); }
    }

    public static final class TimestampContentNode extends UnreadableLeaf {
        public TimestampContentNode(String timestamp) { super(timestamp); }
        public String getContent() { return forbiddenRead(); }
    }

    public static final class UserMentionContentNode extends UnreadableLeaf {
        public UserMentionContentNode(String mention) { super(mention); }
        public List<Object> getContent() { return forbiddenRead(); }
    }

    public static final class ChannelMentionContentNode extends UnreadableLeaf {
        public ChannelMentionContentNode(String mention) { super(mention); }
        public List<Object> getContent() { return forbiddenRead(); }
    }

    public static final class UserOrRoleMentionContentNode extends UnreadableLeaf {
        public UserOrRoleMentionContentNode(String mention) { super(mention); }
        public List<Object> getContent() { return forbiddenRead(); }
    }

    /** Deliberately unmapped: even an unknown node with children is a retained leaf. */
    public static final class FutureNode extends UnreadableLeaf {
        public FutureNode(String payload) { super(payload); }
        public List<Object> getContent() { return forbiddenRead(); }
    }

    public static final class UnderlineContentNode {
        private final List<Object> content;
        public UnderlineContentNode(List<Object> content) { this.content = content; }
        public List<Object> getContent() { return content; }
        public List<Object> component1() { return content; }
        public UnderlineContentNode copy(List<Object> content) {
            return new UnderlineContentNode(content);
        }
    }

    public static final class SpoilerContentNode {
        private final List<Object> content;
        public SpoilerContentNode(List<Object> content) { this.content = content; }
        public List<Object> getContent() { return content; }
        public List<Object> component1() { return content; }
        public SpoilerContentNode copy(List<Object> content) { return new SpoilerContentNode(content); }
    }

    public static final class StrikethroughContentNode {
        private final List<Object> content;
        public StrikethroughContentNode(List<Object> content) { this.content = content; }
        public List<Object> getContent() { return content; }
        public List<Object> component1() { return content; }
        public StrikethroughContentNode copy(List<Object> content) {
            return new StrikethroughContentNode(content);
        }
    }

    private static List<Object> list(Object... nodes) {
        return new ArrayList<>(Arrays.asList(nodes));
    }

    @SafeVarargs
    private static List<List<Object>> items(List<Object>... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static TextContentNode text(String content) { return new TextContentNode(content); }

    private static TextExtractor.Result capture(List<Object> nodes) {
        ContentTree tree = ContentTree.compile(nodes);
        assertNotNull("Explicit fixture must compile to a production occurrence plan", tree);
        TextExtractor.Result source = TextExtractor.extractTree(tree);
        assertNotNull(source);
        assertSame(tree, source.tree);
        return source;
    }

    private static EnglishDetector.Verdict evaluate(TextExtractor.Result source) {
        return EnglishDetector.evaluate(source.text, source.textNodeCount, TARGET_LANGUAGE);
    }

    private static void assertUnread(List<? extends UnreadableLeaf> leaves) {
        for (UnreadableLeaf leaf : leaves) {
            assertEquals(leaf.getClass().getSimpleName() + " internal read count", 0, leaf.reads);
        }
    }

    private static final class MarkdownFixture {
        final List<Object> nodes;
        final List<UnreadableLeaf> retained;

        MarkdownFixture(List<Object> nodes, UnreadableLeaf... retained) {
            this.nodes = nodes;
            this.retained = Arrays.asList(retained);
        }
    }

    private static MarkdownFixture markdownFixture(String title) {
        InlineCodeContentNode inline = new InlineCodeContentNode("showMessage('private-inline')");
        TimestampContentNode timestamp = new TimestampContentNode("<t:1700000000:F>");
        UserMentionContentNode user = new UserMentionContentNode("<@100000000000000001>");
        ChannelMentionContentNode channel = new ChannelMentionContentNode("<#100000000000000002>");
        UserOrRoleMentionContentNode role =
                new UserOrRoleMentionContentNode("<@&100000000000000003>");
        FutureNode unknown = new FutureNode("Hidden English child must never become prose.");
        CodeBlockContentNode code = new CodeBlockContentNode(JAVASCRIPT, "js", false);

        List<Object> nodes = list(
                new HeadingContentNode(list(text(title)), 2),
                new NewLineContentNode(),
                new ParagraphContentNode(list(text(
                        "This message checks translation of natural language with Markdown formatting."))),
                new BulletListContentNode(1L, true, items(
                        list(text("First, read the release notes before installing this update.")),
                        list(text("Second, check your language settings before sending a message.")),
                        list(text("Third, share the results so everyone can reproduce the behavior.")))),
                new BulletListContentNode(null, false, items(
                        list(text("Keep the original formatting when translating this sentence.")),
                        list(text("Preserve every native mention and timestamp without reading its label.")))),
                new NewLineContentNode(),
                new ParagraphContentNode(list(
                        text("The following styles contain "),
                        new StrongContentNode(list(text("bold English text"))), text(", "),
                        new EmphasisContentNode(list(text("italic English text"))), text(", "),
                        new UnderlineContentNode(list(text("underlined English text"))), text(", "),
                        new SpoilerContentNode(list(text("hidden English text"))), text(", "),
                        new StrikethroughContentNode(list(text("removed English text"))), text(", and "),
                        new LinkContentNode(list(text("read the documentation")),
                                "https://example.invalid/markdown-guide"), text("."))),
                new NewLineContentNode(),
                new ParagraphContentNode(list(text("Inline examples remain native: "), inline,
                        text(" while the explanation is translated."))),
                new NewLineContentNode(),
                new ParagraphContentNode(list(text("Meeting time: "), timestamp,
                        text("; invite "), user, text(" to "), channel, text(" with "), role,
                        text(", and preserve this future node "), unknown, text("."))),
                new NewLineContentNode(), code);
        return new MarkdownFixture(nodes, inline, timestamp, user, channel, role, unknown, code);
    }

    private static void assertFullMarkdownDetected(String title) {
        MarkdownFixture fixture = markdownFixture(title);
        TextExtractor.Result source = capture(fixture.nodes);
        // This is the actual extractTree contract: both native list kinds use • in
        // the request; ordered/start metadata remains in the captured native tree.
        assertEquals(title + "\n"
                + "This message checks translation of natural language with Markdown formatting.\n"
                + "• First, read the release notes before installing this update.\n"
                + "• Second, check your language settings before sending a message.\n"
                + "• Third, share the results so everyone can reproduce the behavior.\n"
                + "• Keep the original formatting when translating this sentence.\n"
                + "• Preserve every native mention and timestamp without reading its label.\n"
                + "The following styles contain <x1>bold English text</x1>, "
                + "<x2>italic English text</x2>, <x3>underlined English text</x3>, "
                + "<x4>hidden English text</x4>, <x5>removed English text</x5>, "
                + "and <x6>read the documentation</x6>.\n"
                + "Inline examples remain native: <keep1/> while the explanation is translated.\n"
                + "Meeting time: <keep2/>; invite <keep3/> to <keep4/> with <keep5/>, "
                + "and preserve this future node <keep6/>.\n<keep7/>", source.text);
        assertEquals(28, source.textNodeCount);
        assertEquals(6, source.opaqueNodeCount);
        assertTrue(evaluate(source).shouldTranslate);
        assertEquals(EnglishDetector.Reason.TRANSLATABLE, evaluate(source).reason);
        assertSame(fixture.retained.get(6), source.tree.root.children.get(12).original);
        assertEquals(ContentTree.Kind.CODE_BLOCK, source.tree.root.children.get(12).kind);
        assertNull(HookConfiguration.semanticTypeName(FutureNode.class));
        assertFalse(source.text.contains("console.log"));
        assertFalse(source.text.contains("private-inline"));
        assertFalse(source.text.contains("Hidden English child"));
        assertFalse(source.text.contains("1700000000"));
        assertFalse(source.text.contains("100000000000000001"));
        assertFalse(source.text.contains("https://example.invalid/markdown-guide"));
        assertUnread(fixture.retained);
    }

    /** Exact user-provided Test 2/3 prose and node positions, not a parsed device tree. */
    @Test
    public void markdownTest2WithTrailingJavascriptStillDetectsNaturalLanguage() {
        InlineCodeContentNode firstInline = new InlineCodeContentNode("inline code");
        InlineCodeContentNode thirdInline = new InlineCodeContentNode("inline code");
        TimestampContentNode relative = new TimestampContentNode("<t:1735689600:R>");
        TimestampContentNode date = new TimestampContentNode("<t:1735689600:D>");
        ChannelMentionContentNode channel = new ChannelMentionContentNode("<#123456789012345678>");
        UserOrRoleMentionContentNode role = new UserOrRoleMentionContentNode("<@&123456789012345678>");
        UserMentionContentNode user = new UserMentionContentNode("<@123456789012345678>");
        CodeBlockContentNode code = new CodeBlockContentNode(
                "console.log(\"This code block checks syntax highlighting and horizontal scrolling in one English sentence.\");",
                "js", false);
        TextExtractor.Result source = capture(list(
                new HeadingContentNode(list(text("Discord Markdown Test 2/3")), 1),
                new NewLineContentNode(),
                new BulletListContentNode(1L, true, items(
                        list(text("This ordered item checks "), new StrongContentNode(list(text("bold"))),
                                text(", "), new EmphasisContentNode(list(text("italic"))), text(", "),
                                new UnderlineContentNode(list(text("underline"))), text(", "),
                                new SpoilerContentNode(list(text("spoiler"))), text(", "), firstInline,
                                text(", and a timestamp "), relative, text(" in one English sentence.")),
                        list(text("This second ordered item checks "),
                                new StrikethroughContentNode(list(text("strike"))), text(", a "),
                                new LinkContentNode(list(text("masked link")), "https://discord.com"),
                                text(", and a channel mention "), channel, text(" in one English sentence.")),
                        list(text("This third ordered item checks "),
                                new UnderlineContentNode(list(new StrongContentNode(list(text("bold underline"))))),
                                text(", "), thirdInline, text(", and a role mention "), role,
                                text(" in one English sentence.")))),
                new BulletListContentNode(null, false, items(
                        list(text("This bullet checks a timestamp "), date, text(", a user mention "), user,
                                text(", and "), new StrongContentNode(list(text("bold text"))),
                                text(" in one English sentence.")),
                        list(text("This second bullet checks "), new EmphasisContentNode(list(text("italic text"))),
                                text(", "), new UnderlineContentNode(list(text("underlined text"))), text(", and "),
                                new SpoilerContentNode(list(text("spoiler text"))), text(" in one English sentence.")))),
                new NewLineContentNode(), code));
        assertEquals(EnglishDetector.Reason.TRANSLATABLE, evaluate(source).reason);
        assertTrue(source.text.startsWith("Discord Markdown Test 2/3\n"));
        assertTrue(source.text.contains("<x1>bold</x1>"));
        assertTrue(source.text.contains("<x6>masked link</x6>"));
        assertTrue(source.text.contains("<x7>bold underline</x7>"));
        assertTrue(source.text.endsWith("\n<keep8/>"));
        assertEquals(5, source.text.split("• ", -1).length - 1);
        assertEquals(5, source.text.split("in one English sentence\\.", -1).length - 1);
        assertEquals(8, source.text.split("<keep", -1).length - 1);
        assertFalse(source.text.contains("console.log"));
        assertFalse(source.text.contains("syntax highlighting"));
        assertFalse(source.text.contains("1735689600"));
        assertFalse(source.text.contains("123456789012345678"));
        assertFalse(source.text.contains("https://discord.com"));
        assertUnread(Arrays.asList(firstInline, thirdInline, relative, date, channel, role, user, code));
    }

    @Test
    public void additionalFormattedParagraphsWithTrailingJavascriptStillDetectNaturalLanguage() {
        assertFullMarkdownDetected("Additional Markdown Fixture");
    }

    @Test
    public void pureCodeAndKeepOnlyTreesDoNotTriggerTranslation() {
        CodeBlockContentNode code = new CodeBlockContentNode(JAVASCRIPT, "js", true);
        InlineCodeContentNode inline = new InlineCodeContentNode("Read this private English example");
        TextExtractor.Result codeOnly = capture(list(code, inline));
        assertEquals("<keep1/><keep2/>", codeOnly.text);
        assertEquals(0, codeOnly.textNodeCount);
        assertEquals(0, codeOnly.segmentCount);
        assertFalse(evaluate(codeOnly).shouldTranslate);
        assertEquals(EnglishDetector.Reason.NO_VISIBLE_CONTENT, evaluate(codeOnly).reason);
        assertNull(ContentNodes.replacePlanned(codeOnly.tree, "<keep1/><keep2/>"));
        assertUnread(Arrays.asList(code, inline));

        TimestampContentNode timestamp = new TimestampContentNode("<t:1700000000:F>");
        UserMentionContentNode user = new UserMentionContentNode("<@1>");
        ChannelMentionContentNode channel = new ChannelMentionContentNode("<#2>");
        UserOrRoleMentionContentNode role = new UserOrRoleMentionContentNode("<@&3>");
        FutureNode unknown = new FutureNode("This hidden English sentence is not a text node.");
        TextExtractor.Result keepOnly = capture(list(timestamp, user, channel, role, unknown));
        assertEquals("<keep1/><keep2/><keep3/><keep4/><keep5/>", keepOnly.text);
        assertEquals(0, keepOnly.textNodeCount);
        assertEquals(0, keepOnly.segmentCount);
        assertEquals(5, keepOnly.opaqueNodeCount);
        assertFalse(evaluate(keepOnly).shouldTranslate);
        assertEquals(EnglishDetector.Reason.NO_VISIBLE_CONTENT, evaluate(keepOnly).reason);
        assertNull(ContentNodes.replacePlanned(keepOnly.tree,
                "<keep1/><keep2/><keep3/><keep4/><keep5/>"));
        assertUnread(Arrays.asList(timestamp, user, channel, role, unknown));
    }

    private static final class DenseFixture {
        final List<Object> nodes;
        final List<UnreadableLeaf> retained = new ArrayList<>();

        DenseFixture(List<Object> proseNodes) {
            nodes = new ArrayList<>(proseNodes);
            for (int i = 0; i < 20; i++) {
                retained.add(new TimestampContentNode("<t:1700000000:F>"));
                retained.add(new UserMentionContentNode("<@1>"));
                retained.add(new ChannelMentionContentNode("<#2>"));
                retained.add(new UserOrRoleMentionContentNode("<@&3>"));
                retained.add(new FutureNode("Private English text in an unknown child"));
            }
            nodes.addAll(retained);
        }
    }

    private static List<List<Object>> proseShapes() {
        // Splits deliberately fall inside words. Unwrapping styles must not turn
        // "release" or "translation" into additional detection punctuation.
        return Arrays.asList(
                list(text(PROSE)),
                list(text("Please re"), text("ad the re"),
                        text("lease notes before changing the trans"), text("lation settings.")),
                list(new StrongContentNode(list(text("Please re"))),
                        new EmphasisContentNode(list(text("ad the re"))),
                        new UnderlineContentNode(list(text("lease notes before changing the trans"))),
                        new SpoilerContentNode(list(text("lation settings.")))));
    }

    @Test
    public void mergingSplittingStylingAndManyOpaqueNodesKeepTheSameVerdict() {
        EnglishDetector.Verdict reference = evaluate(capture(list(text(PROSE))));
        assertTrue(reference.shouldTranslate);
        int[] expectedTextCounts = {1, 4, 4};
        List<List<Object>> shapes = proseShapes();
        for (int i = 0; i < shapes.size(); i++) {
            TextExtractor.Result plain = capture(shapes.get(i));
            assertEquals(expectedTextCounts[i], plain.textNodeCount);
            assertEquals(PROSE, RetainedNodeMarkers.maskedForDetection(plain.text));
            assertEquals(reference.shouldTranslate, evaluate(plain).shouldTranslate);
            assertEquals(reference.reason, evaluate(plain).reason);

            DenseFixture dense = new DenseFixture(shapes.get(i));
            TextExtractor.Result withOpaque = capture(dense.nodes);
            assertEquals(expectedTextCounts[i], withOpaque.textNodeCount);
            assertEquals(100, withOpaque.opaqueNodeCount);
            assertEquals(PROSE, RetainedNodeMarkers.maskedForDetection(withOpaque.text).trim());
            assertTrue(withOpaque.text.endsWith("<keep100/>"));
            assertEquals(reference.shouldTranslate, evaluate(withOpaque).shouldTranslate);
            assertEquals(reference.reason, evaluate(withOpaque).reason);
            assertUnread(dense.retained);
        }
    }

    @Test
    public void longBodyWithManyMentionsAndTimestampsRemainsTranslatable() {
        DenseFixture fixture = new DenseFixture(list(text(LONG_PROSE)));
        TextExtractor.Result source = capture(fixture.nodes);
        assertEquals(1, source.textNodeCount);
        assertEquals(100, source.opaqueNodeCount);
        assertEquals(LONG_PROSE, RetainedNodeMarkers.maskedForDetection(source.text).trim());
        assertTrue(evaluate(source).shouldTranslate);
        assertEquals(EnglishDetector.Reason.TRANSLATABLE, evaluate(source).reason);
        assertUnread(fixture.retained);
    }

    private static void assertDenseRejected(String body, EnglishDetector.Reason expectedReason) {
        DenseFixture fixture = new DenseFixture(list(text(body)));
        TextExtractor.Result source = capture(fixture.nodes);
        assertEquals(1, source.textNodeCount);
        assertEquals(100, source.opaqueNodeCount);
        assertFalse(evaluate(source).shouldTranslate);
        assertEquals(expectedReason, evaluate(source).reason);
        assertUnread(fixture.retained);
    }

    @Test
    public void hiWithManyKeepsDoesNotGainLettersFromMarkerNames() {
        assertDenseRejected("hi", EnglishDetector.Reason.TOO_FEW_LETTERS);
    }

    @Test
    public void chineseBodyWithManyKeepsStillMatchesTheTargetLanguage() {
        assertDenseRejected("请阅读发行说明并保留消息中的原始格式。",
                EnglishDetector.Reason.TARGET_LANGUAGE_SCRIPT);
    }

    @Test
    public void lowLetterRatioBodyWithManyKeepsStillDoesNotTranslate() {
        assertDenseRejected("Read 1234567890 !!! ??? 1234567890",
                EnglishDetector.Reason.LOW_LETTER_RATIO);
    }

    private static final String MIXED_SOURCE =
            "<x1>Please read the release notes.</x1>\n"
                    + "<x2>Keep this translation readable.</x2>\n"
                    + "Invite <keep1/> before trying <keep2/>.\n<keep3/>";
    private static final String MIXED_TRANSLATION =
            "<x1>请阅读发行说明。</x1>\n"
                    + "<x2>保持译文易读。</x2>\n"
                    + "邀请 <keep1/> 之后尝试 <keep2/>。\n<keep3/>";

    private static final class MixedFixture {
        final UserMentionContentNode mention = new UserMentionContentNode("<@1>");
        final InlineCodeContentNode inline = new InlineCodeContentNode("showMessage('Alice')");
        final CodeBlockContentNode code = new CodeBlockContentNode(JAVASCRIPT, "js", false);
        final ParagraphContentNode paragraph = new ParagraphContentNode(list(
                new StrongContentNode(list(text("Please read the release notes."))),
                new NewLineContentNode(),
                new EmphasisContentNode(list(text("Keep this translation readable."))),
                new NewLineContentNode(), text("Invite "), mention,
                text(" before trying "), inline, text(".")));
        final NewLineContentNode codeBreak = new NewLineContentNode();
        final List<Object> nodes = list(paragraph, codeBreak, code);
        final TextExtractor.Result source = capture(nodes);

        MixedFixture() {
            assertEquals(MIXED_SOURCE, source.text);
            assertEquals(5, source.textNodeCount);
            assertEquals(2, source.opaqueNodeCount);
            assertTrue(evaluate(source).shouldTranslate);
        }

        void assertOriginalUntouched() {
            assertSame(paragraph, nodes.get(0));
            assertSame(codeBreak, nodes.get(1));
            assertSame(code, nodes.get(2));
            assertSame(mention, paragraph.getContent().get(5));
            assertSame(inline, paragraph.getContent().get(7));
            assertEquals("Please read the release notes.", ((TextContentNode)
                    ((StrongContentNode) paragraph.getContent().get(0)).getContent().get(0)).getContent());
            assertEquals("Keep this translation readable.", ((TextContentNode)
                    ((EmphasisContentNode) paragraph.getContent().get(2)).getContent().get(0)).getContent());
            assertEquals("Invite ", ((TextContentNode) paragraph.getContent().get(4)).getContent());
            assertEquals(" before trying ", ((TextContentNode) paragraph.getContent().get(6)).getContent());
            assertEquals(".", ((TextContentNode) paragraph.getContent().get(8)).getContent());
            assertEquals(JAVASCRIPT, ((UnreadableLeaf) code).payload);
            assertEquals("js", code.language);
            assertFalse(code.inQuote);
            assertUnread(Arrays.asList(mention, inline, code));
        }
    }

    @Test
    public void mixedCodeBlockDetectionAndStrictPlannedReplacementPreserveNativeObjects() {
        MixedFixture fixture = new MixedFixture();
        // Literal model reply: every xN/keepN and every line is explicitly present.
        // No ContentPlanTestSupport helper or marker-repair/injection is involved.
        List<Object> translated = ContentNodes.replacePlanned(fixture.source.tree, MIXED_TRANSLATION);
        assertNotNull(translated);
        assertEquals(3, translated.size());
        ParagraphContentNode paragraph = (ParagraphContentNode) translated.get(0);
        assertSame(fixture.mention, paragraph.getContent().get(5));
        assertSame(fixture.inline, paragraph.getContent().get(7));
        assertSame(fixture.codeBreak, translated.get(1));
        assertSame(fixture.code, translated.get(2));
        assertSame(fixture.paragraph.getContent().get(1), paragraph.getContent().get(1));
        assertSame(fixture.paragraph.getContent().get(3), paragraph.getContent().get(3));
        assertEquals("请阅读发行说明。", ((TextContentNode)
                ((StrongContentNode) paragraph.getContent().get(0)).getContent().get(0)).getContent());
        assertEquals("保持译文易读。", ((TextContentNode)
                ((EmphasisContentNode) paragraph.getContent().get(2)).getContent().get(0)).getContent());
        assertEquals("邀请 ", ((TextContentNode) paragraph.getContent().get(4)).getContent());
        assertEquals(" 之后尝试 ", ((TextContentNode) paragraph.getContent().get(6)).getContent());
        assertEquals("。", ((TextContentNode) paragraph.getContent().get(8)).getContent());
        assertEquals(MIXED_TRANSLATION, capture(translated).text);
        fixture.assertOriginalUntouched();
    }

    @Test
    public void missingDuplicateOrReorderedKeepMarkersRejectTheWholeMixedReplacement() {
        MixedFixture fixture = new MixedFixture();
        String[] invalidReplies = {
                // Missing keep2, even though all natural-language lines remain.
                "<x1>请阅读发行说明。</x1>\n<x2>保持译文易读。</x2>\n"
                        + "邀请 <keep1/> 之后尝试。\n<keep3/>",
                // Duplicate keep1.
                "<x1>请阅读发行说明。</x1>\n<x2>保持译文易读。</x2>\n"
                        + "邀请 <keep1/><keep1/> 之后尝试 <keep2/>。\n<keep3/>",
                // Reordered keep1/keep2.
                "<x1>请阅读发行说明。</x1>\n<x2>保持译文易读。</x2>\n"
                        + "邀请 <keep2/> 之后尝试 <keep1/>。\n<keep3/>"
        };
        for (String reply : invalidReplies) {
            assertNull("Strict replacement must not repair retained markers",
                    ContentNodes.replacePlanned(fixture.source.tree, reply));
            fixture.assertOriginalUntouched();
        }
    }

    @Test
    public void missingDuplicateOrReorderedStyleMarkersRejectTheWholeMixedReplacement() {
        MixedFixture fixture = new MixedFixture();
        String[] invalidReplies = {
                // Missing x1, with every keep marker still present and ordered.
                "请阅读发行说明。\n<x2>保持译文易读。</x2>\n"
                        + "邀请 <keep1/> 之后尝试 <keep2/>。\n<keep3/>",
                // Duplicate x1.
                "<x1>请阅读发行说明。</x1><x1>重复正文。</x1>\n<x2>保持译文易读。</x2>\n"
                        + "邀请 <keep1/> 之后尝试 <keep2/>。\n<keep3/>",
                // Reordered x1/x2.
                "<x2>保持译文易读。</x2>\n<x1>请阅读发行说明。</x1>\n"
                        + "邀请 <keep1/> 之后尝试 <keep2/>。\n<keep3/>"
        };
        for (String reply : invalidReplies) {
            assertNull("Strict replacement must not repair or move style markers",
                    ContentNodes.replacePlanned(fixture.source.tree, reply));
            fixture.assertOriginalUntouched();
        }
    }
}
