package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict persisted directory and immutable, newest-first bounded observation updates. */
public class RecentLocationsTest {
    @Test
    public void emptyDirectoryParsesAndSerializesAsAnArray() {
        assertTrue(RecentLocations.parse("[]").isEmpty());
        assertEquals("[]", RecentLocations.toJson(Collections.emptyList()));
    }

    @Test
    public void strictSchemaRejectsNonArraysNonObjectsMissingAndUnknownKeys() {
        String[] invalid = {
                "", " ", "null", "{}", "true", "1", "\"directory\"",
                "[null]", "[[]]", "[true]", "[1]", "[\"entry\"]", "[{}]",
                "[{\"guildId\":\"10\"}]", "[{\"channelId\":\"20\"}]",
                "[{\"guildId\":null,\"channelId\":\"20\",\"label\":\"extra\"}]",
                "[{\"serverId\":\"10\",\"channelId\":\"20\"}]",
                "[{\"guildId\":\"10\",\"channel\":\"20\"}]"
        };
        for (String json : invalid) {
            assertThrows(json, IllegalArgumentException.class, () -> RecentLocations.parse(json));
        }
    }

    @Test
    public void valuesMustBeStringIdsWithJsonNullAllowedOnlyForGuild() {
        String[] invalidValues = {"null", "20", "20.0", "true", "false", "[]", "{}"};
        for (String value : invalidValues) {
            String channel = "[{\"guildId\":\"10\",\"channelId\":" + value + "}]";
            assertThrows(channel, IllegalArgumentException.class, () -> RecentLocations.parse(channel));
            if (!"null".equals(value)) {
                String guild = "[{\"guildId\":" + value + ",\"channelId\":\"20\"}]";
                assertThrows(guild, IllegalArgumentException.class, () -> RecentLocations.parse(guild));
            }
        }
        List<RecentLocations.Entry> dm = RecentLocations.parse(
                "[{\"guildId\":null,\"channelId\":\"20\"}]");
        assertEquals(1, dm.size());
        assertNull(dm.get(0).guildId);
        assertEquals("20", dm.get(0).channelId);
    }

    @Test
    public void constructorAndParserRequireCanonicalUnsigned64BitIds() {
        for (String invalid : new String[]{"", "0", "00", "01", " 1", "1 ", "+1", "-1",
                "1.0", "1e2", "\u0661", "\uff11", "18446744073709551616",
                "100000000000000000000"}) {
            assertThrows(invalid, IllegalArgumentException.class,
                    () -> new RecentLocations.Entry(null, invalid));
            assertThrows(invalid, IllegalArgumentException.class,
                    () -> new RecentLocations.Entry(invalid, "20"));
            String channel = "[{\"guildId\":null,\"channelId\":\"" + invalid + "\"}]";
            String guild = "[{\"guildId\":\"" + invalid + "\",\"channelId\":\"20\"}]";
            assertThrows(channel, IllegalArgumentException.class, () -> RecentLocations.parse(channel));
            assertThrows(guild, IllegalArgumentException.class, () -> RecentLocations.parse(guild));
        }
        assertThrows(IllegalArgumentException.class, () -> new RecentLocations.Entry("10", null));
        RecentLocations.Entry boundary = new RecentLocations.Entry(
                "18446744073709551615", "18446744073709551615");
        assertEquals("18446744073709551615", boundary.guildId);
        assertEquals("18446744073709551615", boundary.channelId);
        assertEntries(Collections.singletonList(boundary),
                RecentLocations.parse(RecentLocations.toJson(Collections.singletonList(boundary))));
    }

    @Test
    public void malformedJsonReportsItsJsonExceptionCause() {
        for (String json : new String[]{"[", "[{", "[{\"guildId\":"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> RecentLocations.parse(json));
            assertTrue(error.getCause() instanceof JSONException);
        }
    }

    @Test
    public void duplicateChannelsAreRejectedRegardlessOfGuildOrDmIdentity() {
        for (String secondGuild : new String[]{"\"10\"", "\"11\"", "null"}) {
            String json = "[{\"guildId\":\"10\",\"channelId\":\"20\"},"
                    + "{\"guildId\":" + secondGuild + ",\"channelId\":\"20\"}]";
            assertThrows(json, IllegalArgumentException.class, () -> RecentLocations.parse(json));
        }
        assertThrows(IllegalArgumentException.class, () -> RecentLocations.parse(
                "[{\"guildId\":null,\"channelId\":\"20\"},"
                        + "{\"guildId\":null,\"channelId\":\"20\"}]"));
        // Servers are not unique keys: distinct channels in one server are valid.
        List<RecentLocations.Entry> sameGuild = Arrays.asList(entry("20"), entry("21"));
        assertEntries(sameGuild, RecentLocations.parse(RecentLocations.toJson(sameGuild)));
    }

    @Test
    public void parsingAcceptsExactly128EntriesAndRejects129InsteadOfTruncating() {
        assertEquals(128, RecentLocations.LIMIT);
        List<RecentLocations.Entry> maximum = directory(128);
        assertEntries(maximum, RecentLocations.parse(RecentLocations.toJson(maximum)));
        String excessive = RecentLocations.toJson(directory(129));
        assertThrows(IllegalArgumentException.class, () -> RecentLocations.parse(excessive));
    }

    @Test
    public void observationPrependsNewestWithoutMutatingThePreviousDirectory() {
        RecentLocations.Entry first = entry("20");
        RecentLocations.Entry second = entry("21");
        List<RecentLocations.Entry> input = new ArrayList<>(Arrays.asList(first, second));
        RecentLocations.Entry incoming = new RecentLocations.Entry(null, "22");
        List<RecentLocations.Entry> observed = RecentLocations.observe(input, incoming);
        assertSame(incoming, observed.get(0));
        assertSame(first, observed.get(1));
        assertSame(second, observed.get(2));
        assertEntries(Arrays.asList(first, second), input);
        input.clear();
        assertEntries(Arrays.asList(incoming, first, second), observed);
        assertImmutable(observed);
        assertSame(incoming, RecentLocations.observe(Collections.emptyList(), incoming).get(0));
    }

    @Test
    public void reobservingAChannelMovesItToFrontOnlyOnceAndPreservesOtherOrder() {
        List<RecentLocations.Entry> original = Arrays.asList(entry("20"), entry("21"), entry("22"));
        RecentLocations.Entry incoming = entry("21");
        List<RecentLocations.Entry> moved = RecentLocations.observe(original, incoming);
        assertSame(incoming, moved.get(0));
        assertEntries(Arrays.asList(incoming, original.get(0), original.get(2)), moved);
        assertEquals(3, moved.size());
        assertEntries(Arrays.asList(entry("20"), entry("21"), entry("22")), original);
        List<RecentLocations.Entry> newestAgain = RecentLocations.observe(moved, entry("21"));
        assertEntries(moved, newestAgain);
        assertEquals(3, newestAgain.size());
    }

    @Test
    public void repeatedObservationsAreBoundedLruWithTheNewestAtIndexZero() {
        List<RecentLocations.Entry> locations = Collections.emptyList();
        for (int i = 1; i <= 128; i++) {
            locations = RecentLocations.observe(locations, entry(Integer.toString(i)));
            assertEquals(i, locations.size());
        }
        assertEquals("128", locations.get(0).channelId);
        assertEquals("1", locations.get(127).channelId);
        List<RecentLocations.Entry> beforeTouch = locations;
        locations = RecentLocations.observe(locations, entry("1"));
        assertEquals("1", locations.get(0).channelId);
        assertEquals("2", locations.get(127).channelId);
        assertEquals("1", beforeTouch.get(127).channelId);

        locations = RecentLocations.observe(locations, entry("129"));
        assertEquals(128, locations.size());
        assertEquals("129", locations.get(0).channelId);
        assertEquals("1", locations.get(1).channelId);
        assertEquals("3", locations.get(127).channelId);
        Set<String> channels = new HashSet<>();
        for (RecentLocations.Entry location : locations) assertTrue(channels.add(location.channelId));
        assertTrue(channels.contains("1"));
        assertFalse(channels.contains("2"));
        assertEntries(locations, RecentLocations.parse(RecentLocations.toJson(locations)));
        assertImmutable(locations);
    }

    @Test
    public void changedGuildOrDmIdentityForTheSameChannelIsRejected() {
        for (String originalGuild : new String[]{null, "10", "11"}) {
            for (String incomingGuild : new String[]{null, "10", "11"}) {
                RecentLocations.Entry original = new RecentLocations.Entry(originalGuild, "20");
                List<RecentLocations.Entry> current = Collections.singletonList(original);
                RecentLocations.Entry incoming = new RecentLocations.Entry(incomingGuild, "20");
                if (java.util.Objects.equals(originalGuild, incomingGuild)) {
                    List<RecentLocations.Entry> observed = RecentLocations.observe(current, incoming);
                    assertEquals(1, observed.size());
                    assertSame(incoming, observed.get(0));
                } else {
                    assertThrows(IllegalArgumentException.class,
                            () -> RecentLocations.observe(current, incoming));
                }
                assertSame(original, current.get(0));
                assertEquals(originalGuild, current.get(0).guildId);
            }
        }
    }

    @Test
    public void fullDirectoryStillChecksTheOldestMatchingChannelsGuildBeforeDroppingIt() {
        List<RecentLocations.Entry> full = directory(128);
        // Incoming occupies index zero before scanning; the last original entry is outside
        // the copying capacity. Its identity must nevertheless be checked, not evicted silently.
        assertThrows(IllegalArgumentException.class,
                () -> RecentLocations.observe(full, new RecentLocations.Entry("11", "128")));
        assertThrows(IllegalArgumentException.class,
                () -> RecentLocations.observe(full, new RecentLocations.Entry(null, "128")));
        assertEquals(128, full.size());
        assertEquals("10", full.get(127).guildId);
        List<RecentLocations.Entry> moved = RecentLocations.observe(full, entry("128"));
        assertEquals(128, moved.size());
        assertEquals("128", moved.get(0).channelId);
        assertEquals("127", moved.get(127).channelId);
    }

    @Test
    public void roundTripPreservesNullDmIdsOrderAndStableCompactSchema() {
        List<RecentLocations.Entry> entries = Arrays.asList(
                new RecentLocations.Entry(null, "20"),
                new RecentLocations.Entry("10", "21"),
                new RecentLocations.Entry("18446744073709551615", "18446744073709551615"));
        String expected = "[{\"guildId\":null,\"channelId\":\"20\"},"
                + "{\"guildId\":\"10\",\"channelId\":\"21\"},"
                + "{\"guildId\":\"18446744073709551615\","
                + "\"channelId\":\"18446744073709551615\"}]";
        assertEquals(expected, RecentLocations.toJson(entries));
        List<RecentLocations.Entry> parsed = RecentLocations.parse(expected);
        assertEntries(entries, parsed);
        assertEquals(expected, RecentLocations.toJson(parsed));
        assertImmutable(parsed);
        assertEquals("[{\"guildId\":null,\"channelId\":\"20\"}]", RecentLocations.toJson(
                RecentLocations.parse("[{\"channelId\":\"20\",\"guildId\":null}]")));
    }

    private static RecentLocations.Entry entry(String channel) {
        return new RecentLocations.Entry("10", channel);
    }

    private static List<RecentLocations.Entry> directory(int count) {
        List<RecentLocations.Entry> entries = new ArrayList<>();
        for (int i = 1; i <= count; i++) entries.add(entry(Integer.toString(i)));
        return entries;
    }

    /** Entry has no value equals(): compare the persisted identity fields explicitly. */
    private static void assertEntries(List<RecentLocations.Entry> expected,
                                      List<RecentLocations.Entry> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals("guild at " + i, expected.get(i).guildId, actual.get(i).guildId);
            assertEquals("channel at " + i, expected.get(i).channelId, actual.get(i).channelId);
        }
    }

    private static void assertImmutable(List<RecentLocations.Entry> entries) {
        assertThrows(UnsupportedOperationException.class, () -> entries.add(entry("999")));
        assertThrows(UnsupportedOperationException.class, () -> entries.set(0, entry("999")));
        assertThrows(UnsupportedOperationException.class, () -> entries.remove(0));
        assertThrows(UnsupportedOperationException.class, entries::clear);
    }
}
