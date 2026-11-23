package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;

import io.github.wxmyyds.discordautotranslate.core.EnglishDetector.Reason;

import org.json.JSONObject;
import org.junit.Test;

public class RetainedNodeMarkersTest {
    @Test public void exactSyntaxOrderAndMultiplicityAreRequired() {
        assertTrue(RetainedNodeMarkers.matchesExpected("a<keep1/>b<keep2/>c", Arrays.asList("<keep1/>", "<keep2/>")));
        for (String value : new String[]{"<keep1/><keep1/>", "<keep2/><keep1/>", "<keep1 />",
                "<keep01/>", "<KEEP1/>", "<keep1>", "<keep", "</keep1/>", "< keep1/>", "<keep1/><keep999/>"}) {
            assertFalse(value, RetainedNodeMarkers.matchesExpected(value, Collections.singletonList("<keep1/>")));
        }
        assertTrue(RetainedNodeMarkers.matchesExpected("hello", Collections.emptyList()));
        assertFalse(RetainedNodeMarkers.matchesExpected("<keep1/>", Collections.emptyList()));
    }

    @Test public void sourceAndTranslationShareOneGrammar() {
        String source = "run<keep1/>now";
        assertTrue(RetainedNodeMarkers.matchesSource("运行<keep1/>现在", source));
        assertFalse(RetainedNodeMarkers.matchesSource("运行现在", source));
        assertFalse(RetainedNodeMarkers.matchesSource("运行<keep1/>现在", "run<keep1>now"));
        assertNull(Segments.plainTextViolation("运行<keep1/>现在", source));
        assertEquals("保留节点定位标记无效", Segments.plainTextViolation("运行现在", source));
        assertEquals("保留节点定位标记无效", Segments.plainTextViolation("正文<keep1/>", "plain"));
    }

    @Test public void markerCharactersCannotMakeShortTextTranslatable() {
        assertEquals("run", RetainedNodeMarkers.withoutMarkers("run<keep1/><keep2/>"));
        EnglishDetector.Verdict raw = EnglishDetector.evaluate("ok", 1, "简体中文");
        EnglishDetector.Verdict marked = EnglishDetector.evaluate("ok<keep1/>", 1, "简体中文");
        assertFalse(marked.shouldTranslate);
        assertEquals(Reason.TOO_FEW_LETTERS, raw.reason);
        assertEquals(raw.reason, marked.reason);
        assertEquals(EnglishDetector.evaluate("Read this", 1, "简体中文").reason,
                EnglishDetector.evaluate("Read<keep1/> this", 1, "简体中文").reason);
    }

    @Test public void lengthRatioDoesNotCountRetainedCodeMarkersAsNaturalLanguage() {
        String markers = String.join("", Collections.nCopies(20, "<keep1/>"));
        assertNull(Segments.plainTextViolation("你好" + markers, "hello" + markers));
        String source = String.join("", Collections.nCopies(50, "a")) + "<keep1/>";
        assertNotNull(Segments.plainTextViolation("短<keep1/>", source));
    }

    @Test public void detectorDoesNotSwallowTextAfterUrlCodeBoundary() {
        EnglishDetector.Verdict verdict = EnglishDetector.evaluate(
                "https://example.invalid<keep1/>hello", 2, "简体中文");
        assertTrue(verdict.reason.name(), verdict.shouldTranslate);
        assertEquals(Reason.TRANSLATABLE, verdict.reason);
        assertEquals("url text", RetainedNodeMarkers.maskedForDetection("url<keep1/>text"));
    }

    @Test public void detectionUnwrapsOnlyExactStyleTagsWithoutAddingWordBoundaries() {
        String[][] fixtures = {
                {"okay", "okay"},
                {"<x1>okay</x1>", "okay"},
                {"<x12><x345>okay</x345></x12>", "okay"},
                {"o<x1>ka</x1>y", "okay"},
                {"<x1>o</x1><x2>k</x2><x3>a</x3><x4>y</x4>", "okay"},
                {"h<x1>tt</x1>ps://example.invalid", "https://example.invalid"},
                {":s<x1>mi</x1>le:", ":smile:"},
                {"<x1>`some</x1><x2>Code`</x2>", "`someCode`"},
                {"<x1></x1>", ""},
                {"<x0>okay</x0>", "<x0>okay</x0>"},
                {"<x01>okay</x01>", "<x01>okay</x01>"},
                {"<X1>okay</X1>", "<X1>okay</X1>"},
                {"<x1 >okay</x1 >", "<x1 >okay</x1 >"},
                {"< x1>okay</ x1>", "< x1>okay</ x1>"},
                {"<x1 class=\"bold\">okay</x1 class=\"bold\">",
                        "<x1 class=\"bold\">okay</x1 class=\"bold\">"},
                {"<x1/>", "<x1/>"},
                {"<x1", "<x1"}
        };
        for (String[] fixture : fixtures) {
            assertEquals(fixture[0], fixture[1], RetainedNodeMarkers.maskedForDetection(fixture[0]));
        }
        assertEquals("<x1>okay</x1>", RetainedNodeMarkers.withoutMarkers("<x1>okay</x1><keep1/>"));
    }

    @Test public void detectionMasksKeepMarkersAsSpacesAfterUnwrappingStyles() {
        String[][] fixtures = {
                {"url<keep1/>text", "url text"},
                {"url<keep1/><keep2/>text", "url  text"},
                {"<x1>url</x1><keep1/><x2>text</x2>", "url text"},
                {"<x1>url<keep1/>te</x1>xt", "url text"},
                {"https://example.invalid<keep1/>o<x1>ka</x1>y", "https://example.invalid okay"},
                {"o<x1>ka</x1>y<keep1/>https://example.invalid", "okay https://example.invalid"},
                {"<x1><keep1/></x1><keep2/>", "  "},
                {"<x1><ke</x1><x2>ep1/></x2>okay", " okay"},
                {"url<keep01/>text", "url<keep01/>text"},
                {"url<KEEP1/>text", "url<KEEP1/>text"},
                {"url<keep1 />text", "url<keep1 />text"}
        };
        for (String[] fixture : fixtures) {
            assertEquals(fixture[0], fixture[1], RetainedNodeMarkers.maskedForDetection(fixture[0]));
        }
    }

    @Test public void lengthRatioThresholdAndBoundsStayUnchanged() {
        String marker = "<keep1/>";
        String source = String.join("", Collections.nCopies(40, "a")) + marker;
        String minimum = String.join("", Collections.nCopies(10, "中")) + marker;
        String tooShort = String.join("", Collections.nCopies(9, "中")) + marker;
        String maximum = String.join("", Collections.nCopies(120, "中")) + marker;
        String tooLong = String.join("", Collections.nCopies(121, "中")) + marker;
        assertNull(Segments.plainTextViolation(minimum, source));
        assertEquals("译文长度异常（9/40）：疑似内容丢失", Segments.plainTextViolation(tooShort, source));
        assertNull(Segments.plainTextViolation(maximum, source));
        assertEquals("译文长度异常（121/40）：疑似复读", Segments.plainTextViolation(tooLong, source));
        String shortSource = String.join("", Collections.nCopies(39, "a")) + marker;
        assertNull(Segments.plainTextViolation("中" + marker, shortSource));
        assertEquals("译文为空", Segments.plainTextViolation(" \n<keep1/>\t", source));
    }

    @Test public void markerOnlyOutputIsEmptyEvenForShortSources() {
        assertEquals("译文为空", Segments.plainTextViolation("<keep1/>", "hello<keep1/>world"));
        assertNull(Segments.plainTextViolation("你好<keep1/>世界", "hello<keep1/>world"));
    }

    @Test public void reservedPrefixAssembledAcrossStyleTagsIsRejected() {
        assertFalse(RetainedNodeMarkers.matchesExpected("<x1><ke</x1><x2>ep9/></x2>", Collections.emptyList()));
        assertFalse(RetainedNodeMarkers.matchesExpected("<x1><ke</x1><x2>ep9</x2>", Collections.emptyList()));
        assertFalse(RetainedNodeMarkers.matchesSource("正文", "<x1><ke</x1><x2>ep9/></x2>"));
        assertTrue(RetainedNodeMarkers.matchesSource("<x1>正文</x1><keep1/>", "<x1>Body</x1><keep1/>"));
    }

    @Test public void preservationRuleIsMandatoryForCustomPromptInEveryProtocol() throws Exception {
        for (String protocol : new String[]{Config.PROTOCOL_OPENAI_COMPATIBLE,
                Config.PROTOCOL_OPENAI_RESPONSES, Config.PROTOCOL_ANTHROPIC_MESSAGES}) {
            JSONObject body = new JSONObject(Protocols.requestBody(protocol, "Run<keep1/>now",
                    "Custom prompt", "model", false));
            String prompt = Config.PROTOCOL_OPENAI_COMPATIBLE.equals(protocol)
                    ? body.getJSONArray("messages").getJSONObject(0).getString("content")
                    : body.getString(Config.PROTOCOL_OPENAI_RESPONSES.equals(protocol) ? "instructions" : "system");
            assertEquals("Custom prompt\n\n" + Protocols.WRAPPER_RULE, prompt);
            assertTrue(prompt.contains("<keep1/>"));
            assertTrue(prompt.contains("exactly once"));
            assertTrue(prompt.contains("original position and order"));
        }
    }
}
