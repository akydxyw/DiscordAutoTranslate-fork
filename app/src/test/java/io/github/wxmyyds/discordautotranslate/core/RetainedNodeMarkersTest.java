package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
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
        EnglishDetector.Verdict raw = EnglishDetector.evaluate("ok", 1, 1, false, "简体中文");
        EnglishDetector.Verdict marked = EnglishDetector.evaluate("ok<keep1/>", 1, 1, false, "简体中文");
        assertFalse(marked.shouldTranslate);
        assertEquals(raw.reason, marked.reason);
        assertEquals(EnglishDetector.evaluate("Read this", 1, 1, false, "简体中文").reason,
                EnglishDetector.evaluate("Read<keep1/> this", 1, 1, false, "简体中文").reason);
    }

    @Test public void lengthRatioDoesNotCountRetainedCodeMarkersAsNaturalLanguage() {
        String markers = String.join("", Collections.nCopies(20, "<keep1/>"));
        assertNull(Segments.plainTextViolation("你好" + markers, "hello" + markers));
        String source = String.join("", Collections.nCopies(50, "a")) + "<keep1/>";
        assertNotNull(Segments.plainTextViolation("短<keep1/>", source));
    }

    @Test public void detectorDoesNotSwallowTextAfterUrlCodeBoundary() {
        EnglishDetector.Verdict verdict = EnglishDetector.evaluate(
                "https://example.invalid<keep1/>hello", 2, 1, false, "简体中文");
        assertTrue(verdict.reason, verdict.shouldTranslate);
        assertEquals("url text", RetainedNodeMarkers.maskedForDetection("url<keep1/>text"));
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
