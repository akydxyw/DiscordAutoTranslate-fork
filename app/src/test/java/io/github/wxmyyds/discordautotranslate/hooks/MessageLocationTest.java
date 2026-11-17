package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Profile-driven reflection on JVM fixtures, not a claim to validate Discord bytecode. */
public class MessageLocationTest {
    private static final int[] VERSIONS = {340013, 352017};

    @Test
    public void bothShippedProfilesKeepTheExactLocationTypesAndGetterNames() throws Exception {
        for (int version : VERSIONS) {
            HookConfiguration profile = parse(shipped(version), version);
            assertEquals("com.discord.chat.bridge.Message", profile.typeClass("Message"));
            assertEquals("com.discord.primitives.GuildId", profile.typeClass("GuildId"));
            assertEquals("getChannelId-o4g7jtM", profile.accessorName("Message", "getChannelId"));
            assertEquals("getGuildId-qOKuAAo", profile.accessorName("Message", "getGuildId"));
            assertEquals("toString", profile.accessorName("GuildId", "asString"));
        }
    }

    @Test
    public void bothProfilesReadOnlyConfiguredLongChannelAndWrapperStringGetters() throws Exception {
        for (int version : VERSIONS) {
            HookConfiguration profile = parse(fixtureProfile(version), version);
            Class<?> messageType = Class.forName(profile.typeClass("Message"));
            MessageLocation.Reader reader = MessageLocation.Reader.create(profile, messageType);
            GuildId guild = new GuildId("18446744073709551615");
            Message message = new Message(223456789012345678L, guild);

            MessageLocation location = reader.read(message);

            assertEquals("223456789012345678", location.channelId);
            assertEquals("18446744073709551615", location.guildId);
            assertEquals(1, message.channelReads);
            assertEquals(1, message.guildReads);
            assertEquals(1, guild.textReads);
            // The fixture's semantic-name getters, public fields and toString are decoys.
            // Decoy methods throw, so a guessed getter/string rendering cannot pass this test.
        }
    }

    @Test
    public void nullGuildWrapperCapturesADirectMessageWithoutCallingItsTextGetter() throws Exception {
        for (int version : VERSIONS) {
            Message message = new Message(123456789012345678L, null);
            MessageLocation location = reader(fixtureProfile(version), version).read(message);
            assertEquals("123456789012345678", location.channelId);
            assertNull(location.guildId);
            assertEquals(1, message.channelReads);
            assertEquals(1, message.guildReads);
        }
    }

    @Test
    public void primitiveChannelBitsAreRenderedAsUnsignedCanonicalDecimal() throws Exception {
        long[] values = {1L, Long.MAX_VALUE, Long.MIN_VALUE, -1L};
        String[] expected = {"1", "9223372036854775807", "9223372036854775808",
                "18446744073709551615"};
        for (int version : VERSIONS) {
            MessageLocation.Reader reader = reader(fixtureProfile(version), version);
            for (int i = 0; i < values.length; i++) {
                MessageLocation location = reader.read(new Message(values[i], null));
                assertEquals(expected[i], location.channelId);
                assertNull(location.guildId);
            }
            assertThrows(IllegalArgumentException.class, () -> reader.read(new Message(0L, null)));
        }
    }

    @Test
    public void invalidGuildTextIsNotNormalizedParsedOrReplacedByAnotherGetter() throws Exception {
        for (int version : VERSIONS) {
            MessageLocation.Reader reader = reader(fixtureProfile(version), version);
            for (String invalid : new String[]{"", "0", "01", " 10", "10 ", "+10", "-10",
                    "1.0", "1e2", "GuildId(value=10)", "\u0661", "18446744073709551616"}) {
                GuildId guild = new GuildId(invalid);
                Message message = new Message(20L, guild);
                assertThrows(invalid, IllegalArgumentException.class, () -> reader.read(message));
                assertEquals(1, guild.textReads);
            }
        }
    }

    @Test
    public void locationConstructorRejectsInvalidIdsButAcceptsNullGuildForDm() {
        MessageLocation dm = new MessageLocation(null, "20");
        assertNull(dm.guildId);
        assertEquals("20", dm.channelId);
        for (String invalid : new String[]{null, "", "0", "01", " 1", "+1", "-1",
                "\uff11", "18446744073709551616"}) {
            assertThrows(IllegalArgumentException.class, () -> new MessageLocation("10", invalid));
            if (invalid != null) {
                assertThrows(IllegalArgumentException.class, () -> new MessageLocation(invalid, "20"));
            }
        }
    }

    @Test
    public void missingMetadataFailsAtReaderCreationWithoutImplicitSemanticGetters() throws Exception {
        for (int version : VERSIONS) {
            for (String member : new String[]{"getChannelId", "getGuildId", "asString", "GuildId"}) {
                JSONObject root = fixtureProfile(version);
                if ("GuildId".equals(member)) {
                    root.getJSONObject("types").remove("GuildId");
                } else {
                    String owner = "asString".equals(member) ? "GuildId" : "Message";
                    root.getJSONObject("accessors").getJSONObject(owner).remove(member);
                }
                IllegalStateException error = assertThrows(member, IllegalStateException.class,
                        () -> reader(root, version));
                assertEquals("Missing message location metadata", error.getMessage());
            }
        }
    }

    @Test
    public void wrongExactGetterNamesAndNonZeroArityFailRatherThanTryingDecoys() throws Exception {
        for (int version : VERSIONS) {
            for (String member : new String[]{"getChannelId", "getGuildId", "asString"}) {
                JSONObject root = fixtureProfile(version);
                String owner = "asString".equals(member) ? "GuildId" : "Message";
                root.getJSONObject("accessors").getJSONObject(owner).put(member, "missingExactGetter");
                IllegalStateException error = assertThrows(member, IllegalStateException.class,
                        () -> reader(root, version));
                assertTrue(error.getCause() instanceof NoSuchMethodException);
            }
            JSONObject root = fixtureProfile(version);
            root.getJSONObject("accessors").getJSONObject("Message")
                    .put("getChannelId", "channelWithArgument");
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> reader(root, version));
            assertTrue(error.getCause() instanceof NoSuchMethodException);
        }
    }

    @Test
    public void boxedOrOtherChannelReturnsAndNonWrapperGuildReturnsAreRejected() throws Exception {
        for (int version : VERSIONS) {
            for (String getter : new String[]{"boxedChannel", "stringChannel", "intChannel"}) {
                JSONObject root = fixtureProfile(version);
                root.getJSONObject("accessors").getJSONObject("Message").put("getChannelId", getter);
                assertInvalidDescriptor(root, version);
            }
            for (String getter : new String[]{"objectGuild", "stringGuild", "longGuild"}) {
                JSONObject root = fixtureProfile(version);
                root.getJSONObject("accessors").getJSONObject("Message").put("getGuildId", getter);
                assertInvalidDescriptor(root, version);
            }
            JSONObject root = fixtureProfile(version);
            root.getJSONObject("types").put("GuildId", OtherGuildId.class.getName());
            // Even another wrapper with the right text method is not the configured return type.
            assertInvalidDescriptor(root, version);
        }
    }

    @Test
    public void wrapperTextMustReturnStringAndEveryGetterMustBeAnInstanceMethod() throws Exception {
        for (int version : VERSIONS) {
            for (String getter : new String[]{"objectText", "longText", "staticText"}) {
                JSONObject root = fixtureProfile(version);
                root.getJSONObject("accessors").getJSONObject("GuildId").put("asString", getter);
                assertInvalidDescriptor(root, version);
            }
            JSONObject channel = fixtureProfile(version);
            channel.getJSONObject("accessors").getJSONObject("Message")
                    .put("getChannelId", "staticChannel");
            assertInvalidDescriptor(channel, version);
            JSONObject guild = fixtureProfile(version);
            guild.getJSONObject("accessors").getJSONObject("Message").put("getGuildId", "staticGuild");
            assertInvalidDescriptor(guild, version);
        }
    }

    @Test
    public void getterInvocationFailuresPropagateWithoutAlternateLocationAccess() throws Exception {
        for (int version : VERSIONS) {
            MessageLocation.Reader reader = reader(fixtureProfile(version), version);
            for (String failingGetter : new String[]{"channel", "guild", "text"}) {
                GuildId guild = new GuildId("10");
                Message message = new Message(20L, guild);
                message.failChannel = "channel".equals(failingGetter);
                message.failGuild = "guild".equals(failingGetter);
                guild.failText = "text".equals(failingGetter);
                IllegalStateException error = assertThrows(IllegalStateException.class,
                        () -> reader.read(message));
                assertEquals("Message location capture failed", error.getMessage());
                assertTrue(error.getCause() instanceof InvocationTargetException);
                assertTrue(error.getCause().getCause() instanceof IllegalStateException);
                assertEquals(1, message.channelReads);
                assertEquals(message.failChannel ? 0 : 1, message.guildReads);
                assertEquals("text".equals(failingGetter) ? 1 : 0, guild.textReads);
            }
        }
    }

    private static void assertInvalidDescriptor(JSONObject root, int version) throws Exception {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> reader(root, version));
        assertEquals("Invalid message location descriptor", error.getMessage());
    }

    private static MessageLocation.Reader reader(JSONObject root, int version) throws Exception {
        HookConfiguration profile = parse(root, version);
        return MessageLocation.Reader.create(profile, Class.forName(profile.typeClass("Message")));
    }

    private static HookConfiguration parse(JSONObject root, int version) throws Exception {
        return HookConfiguration.parse(root.toString(), version);
    }

    private static JSONObject shipped(int version) throws Exception {
        String json = Files.readString(Path.of("src/main/assets/hook-config/" + version + ".json"));
        HookConfiguration.parse(json, version);
        return new JSONObject(json);
    }

    private static JSONObject fixtureProfile(int version) throws Exception {
        JSONObject root = shipped(version);
        root.getJSONObject("types").put("Message", Message.class.getName())
                .put("GuildId", GuildId.class.getName());
        // Java cannot declare Kotlin's hyphenated getter names. Replace only location types
        // and accessors in the real profile; the shipped-name assertions above stay independent.
        root.getJSONObject("accessors").getJSONObject("Message")
                .put("getChannelId", "nativeChannel").put("getGuildId", "nativeGuild");
        root.getJSONObject("accessors").getJSONObject("GuildId").put("asString", "nativeText");
        return root;
    }

    public static final class Message {
        private final long rawChannel;
        private final GuildId rawGuild;
        public final String channelId = "999";
        public final String guildId = "888";
        int channelReads;
        int guildReads;
        boolean failChannel;
        boolean failGuild;

        Message(long channel, GuildId guild) { rawChannel = channel; rawGuild = guild; }

        private long nativeChannel() {
            channelReads++;
            if (failChannel) throw new IllegalStateException("channel getter failed");
            return rawChannel;
        }

        private GuildId nativeGuild() {
            guildReads++;
            if (failGuild) throw new IllegalStateException("guild getter failed");
            return rawGuild;
        }

        public long getChannelId() { throw new AssertionError("Unconfigured channel getter"); }
        public GuildId getGuildId() { throw new AssertionError("Unconfigured guild getter"); }
        @Override public String toString() { throw new AssertionError("Message string guessing"); }

        public Long boxedChannel() { throw new AssertionError("Boxed channel invoked"); }
        public String stringChannel() { throw new AssertionError("String channel invoked"); }
        public int intChannel() { throw new AssertionError("Int channel invoked"); }
        public long channelWithArgument(long ignored) { throw new AssertionError("Argument getter invoked"); }
        public Object objectGuild() { throw new AssertionError("Object guild invoked"); }
        public String stringGuild() { throw new AssertionError("String guild invoked"); }
        public long longGuild() { throw new AssertionError("Long guild invoked"); }
        public static long staticChannel() { throw new AssertionError("Static channel invoked"); }
        public static GuildId staticGuild() { throw new AssertionError("Static guild invoked"); }
    }

    public static final class GuildId {
        private final String rawText;
        int textReads;
        boolean failText;

        GuildId(String text) { rawText = text; }

        private String nativeText() {
            textReads++;
            if (failText) throw new IllegalStateException("wrapper getter failed");
            return rawText;
        }

        public String asString() { throw new AssertionError("Unconfigured wrapper getter"); }
        @Override public String toString() { throw new AssertionError("Unconfigured wrapper rendering"); }
        public Object objectText() { throw new AssertionError("Object text invoked"); }
        public long longText() { throw new AssertionError("Long text invoked"); }
        public static String staticText() { throw new AssertionError("Static text invoked"); }
    }

    public static final class OtherGuildId {
        private String nativeText() { throw new AssertionError("Wrong wrapper invoked"); }
    }
}
