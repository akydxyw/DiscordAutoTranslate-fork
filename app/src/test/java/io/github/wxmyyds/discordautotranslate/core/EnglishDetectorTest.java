package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import io.github.wxmyyds.discordautotranslate.core.EnglishDetector.Reason;

import org.junit.Test;

public class EnglishDetectorTest {

    private static EnglishDetector.Verdict detect(String text, String target) {
        return EnglishDetector.evaluate(text, 1, target);
    }

    private static void assertVerdict(Reason expected, EnglishDetector.Verdict actual) {
        assertEquals(expected, actual.reason);
        assertEquals(expected == Reason.TRANSLATABLE, actual.shouldTranslate);
    }

    private static void assertEquivalentDetection(String plain, String styled) {
        EnglishDetector.Verdict expected = detect(plain, Config.DEF_TARGET_LANG);
        EnglishDetector.Verdict actual = detect(styled, Config.DEF_TARGET_LANG);
        assertEquals(styled, expected.reason, actual.reason);
        assertEquals(styled, expected.shouldTranslate, actual.shouldTranslate);
    }

    @Test
    public void explicitDefaultTargetRequiresExtractableProseRatherThanNodeRatios() {
        assertVerdict(Reason.EMPTY, EnglishDetector.evaluate(null, 1, Config.DEF_TARGET_LANG));
        assertVerdict(Reason.EMPTY, detect(" \n\t", Config.DEF_TARGET_LANG));
        assertVerdict(Reason.NO_TEXT_NODE, EnglishDetector.evaluate("hello world", 0,
                Config.DEF_TARGET_LANG));
        for (int textNodes : new int[]{1, 4, 200}) {
            assertVerdict(Reason.TRANSLATABLE, EnglishDetector.evaluate(
                    "hello<keep1/><keep2/><keep3/><keep4/> world", textNodes, Config.DEF_TARGET_LANG));
        }
        assertVerdict(Reason.NO_VISIBLE_CONTENT, detect("<keep1/><keep2/>", Config.DEF_TARGET_LANG));
    }

    @Test
    public void explicitDefaultTargetPreservesLanguageAndLengthGuards() {
        assertVerdict(Reason.TOO_FEW_LETTERS, detect("hi", Config.DEF_TARGET_LANG));
        assertVerdict(Reason.LOW_LETTER_RATIO, detect("okay!!!!!!", Config.DEF_TARGET_LANG));
        assertVerdict(Reason.TARGET_LANGUAGE_SCRIPT, detect("今天天气很好", Config.DEF_TARGET_LANG));
        assertVerdict(Reason.TRANSLATABLE, detect("Hello, how are you?", Config.DEF_TARGET_LANG));
    }

    @Test
    public void translatesRussianIntoChinese() {
        assertVerdict(Reason.TRANSLATABLE, detect("Привет, как дела?", "简体中文"));
    }

    @Test
    public void translatesJapaneseIntoChinese() {
        assertVerdict(Reason.TRANSLATABLE, detect("こんにちは、元気ですか？", "简体中文"));
    }

    @Test
    public void translatesKoreanIntoChinese() {
        assertVerdict(Reason.TRANSLATABLE, detect("오늘 날씨가 정말 좋네요", "简体中文"));
    }

    @Test
    public void skipsTextAlreadyInChineseTargetLanguage() {
        assertVerdict(Reason.TARGET_LANGUAGE_SCRIPT, detect("今天天气很好", "简体中文"));
    }

    @Test
    public void skipsTextAlreadyInJapaneseTargetLanguage() {
        assertVerdict(Reason.TARGET_LANGUAGE_SCRIPT, detect("こんにちは、元気ですか？", "日本語"));
    }

    @Test
    public void skipsTextAlreadyInRussianTargetLanguage() {
        assertVerdict(Reason.TARGET_LANGUAGE_SCRIPT, detect("Привет, как дела?", "Русский"));
    }

    @Test
    public void skipsTextAlreadyInBengaliTargetLanguage() {
        assertVerdict(Reason.TARGET_LANGUAGE_SCRIPT, detect("বাংলা ভাষার একটি বাক্য", "বাংলা"));
    }

    @Test
    public void preservesEnglishDetection() {
        assertVerdict(Reason.TRANSLATABLE, detect("Hello, how are you?", "简体中文"));
    }

    @Test
    public void ignoresLinksAndCodeWhenCountingLetters() {
        assertVerdict(Reason.TOO_FEW_LETTERS,
                detect("https://example.com `someCode` !!!", "简体中文"));
        assertVerdict(Reason.NO_VISIBLE_CONTENT,
                detect("https://example.com `someCode` :smile:", "简体中文"));
    }

    @Test
    public void generatedStyleWrappersHaveSameVerdictAsPlainText() {
        assertVerdict(Reason.TRANSLATABLE, detect("<x1>okay</x1>", Config.DEF_TARGET_LANG));
        assertEquivalentDetection("okay", "<x1>okay</x1>");
        assertEquivalentDetection("okay", "<x12><x345>okay</x345></x12>");
        assertEquivalentDetection("hi", "<x1>hi</x1>");
        assertEquivalentDetection("okay!!!!!!", "<x1>okay</x1>!!!!!!");
        assertEquivalentDetection("今天天气很好", "<x1>今天天气很好</x1>");
        assertEquivalentDetection("Hello, how are you?", "<x1>Hello,</x1> how <x2>are you?</x2>");
        assertVerdict(Reason.NO_VISIBLE_CONTENT, detect("<x1></x1>", Config.DEF_TARGET_LANG));
    }

    @Test
    public void styleBoundariesDoNotSplitWordsOrIgnoredTokens() {
        assertEquivalentDetection("okay", "o<x1>ka</x1>y");
        assertEquivalentDetection("okay", "<x1>o</x1><x2>k</x2><x3>a</x3><x4>y</x4>");
        assertEquivalentDetection("https://example.invalid", "h<x1>tt</x1>ps://example.invalid");
        assertEquivalentDetection(":smile:", ":s<x1>mi</x1>le:");
        assertEquivalentDetection("`someCode`", "<x1>`some</x1><x2>Code`</x2>");
    }

    @Test
    public void keepBoundariesSeparateUrlsFromNaturalTextAfterUnwrappingStyles() {
        assertVerdict(Reason.TRANSLATABLE,
                detect("<x1>https://example.invalid</x1><keep1/><x2>okay</x2>", Config.DEF_TARGET_LANG));
        assertVerdict(Reason.TRANSLATABLE,
                detect("https://example.invalid<keep1/><keep2/>o<x1>ka</x1>y", Config.DEF_TARGET_LANG));
        assertVerdict(Reason.TRANSLATABLE,
                detect("<x1>okay</x1><keep1/>https://example.invalid", Config.DEF_TARGET_LANG));
        assertVerdict(Reason.NO_VISIBLE_CONTENT,
                detect("<keep1/><x1>https://example.invalid</x1><keep2/>", Config.DEF_TARGET_LANG));
    }

    @Test
    public void specialTargetLanguageConfigurationCannotEnterReason() {
        String target = "Русский\nprivate=<keep1/><x1>secret</x1>\r\t\"\\";
        EnglishDetector.Verdict skipped = detect("Привет, как дела?", target);
        assertVerdict(Reason.TARGET_LANGUAGE_SCRIPT, skipped);
        assertEquals("TARGET_LANGUAGE_SCRIPT", skipped.reason.name());
        assertFalse(skipped.reason.name().contains(target));
        EnglishDetector.Verdict accepted = detect("okay", target);
        assertVerdict(Reason.TRANSLATABLE, accepted);
        assertEquals("TRANSLATABLE", accepted.reason.name());
        assertFalse(accepted.reason.name().contains(target));
    }
}
