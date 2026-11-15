package io.github.wxmyyds.discordautotranslate.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reserved, content-free occurrence anchors for native inline-code nodes. Never repairs markers. */
public final class InlineCodeMarkers {
    private static final Pattern PREFIX = Pattern.compile("<\\s*/?\\s*keep", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXACT = Pattern.compile("<keep[1-9][0-9]*/>");

    private InlineCodeMarkers() { }

    /** Source text must not collide with the native-node anchor namespace. */
    public static boolean hasReservedPrefix(String text) {
        return text != null && PREFIX.matcher(text).find();
    }

    /** One syntax check shared by engine and reconstruction; null means malformed reserved syntax. */
    private static List<String> read(String text) {
        List<String> markers = new ArrayList<>();
        Matcher prefix = PREFIX.matcher(text);
        while (prefix.find()) {
            Matcher marker = EXACT.matcher(text);
            marker.region(prefix.start(), text.length());
            if (!marker.lookingAt()) return null;
            markers.add(marker.group());
        }
        return markers;
    }

    /** Requires exact syntax, multiplicity and occurrence order, including rejection of extra markers. */
    public static boolean matchesExpected(String translated, List<String> expected) {
        List<String> actual = read(translated);
        return actual != null && actual.equals(expected);
    }

    /** Analysis only: generated anchor characters are not natural-language text. */
    public static String withoutMarkers(String text) {
        return EXACT.matcher(text).replaceAll("");
    }

    public static boolean matchesSource(String translated, String source) {
        List<String> expected = read(source);
        return expected != null && matchesExpected(translated, expected);
    }
}
