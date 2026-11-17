package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TranslationScopeTest {
    private static final String EMPTY_TRUE =
            "{\"defaultTranslate\":true,\"servers\":[],\"channels\":[]}";
    private static final String[] INVALID_IDS = {
            null, "", "0", "00", "01", " 1", "1 ", "+1", "-1", "1.0", "1e2",
            "1\n", "1a", "\u0661", "\uff11", "18446744073709551616",
            "99999999999999999999", "100000000000000000000"
    };

    @Test
    public void allIsDefaultTranslateWithoutRules() {
        assertTrue(TranslationScope.ALL.defaultTranslate);
        assertTrue(TranslationScope.ALL.servers.isEmpty());
        assertTrue(TranslationScope.ALL.channels.isEmpty());
        assertTrue(TranslationScope.ALL.allows("1", "2"));
        assertTrue(TranslationScope.ALL.allows(null, "2"));
        assertEquals(EMPTY_TRUE, TranslationScope.ALL.toJson());
        assertEquals(TranslationScope.ALL, TranslationScope.parse(EMPTY_TRUE));
    }

    @Test
    public void absentRulesUseEitherDefault() {
        for (boolean defaultTranslate : new boolean[]{false, true}) {
            TranslationScope scope = new TranslationScope(defaultTranslate,
                    Collections.emptyList(), Collections.emptyList());
            assertEquals(defaultTranslate, scope.allows("1", "2"));
            assertEquals(defaultTranslate, scope.allows(null, "2"));
        }
    }

    @Test
    public void channelOverridesConflictingServerForBothDefaults() {
        for (boolean defaultTranslate : new boolean[]{false, true}) {
            TranslationScope scope = new TranslationScope(defaultTranslate,
                    Arrays.asList(rule("1", true), rule("2", false)),
                    Arrays.asList(rule("3", false), rule("4", true)));
            assertFalse(scope.allows("1", "3"));
            assertTrue(scope.allows("2", "4"));
            assertTrue(scope.allows("1", "5"));
            assertFalse(scope.allows("2", "5"));
            assertEquals(defaultTranslate, scope.allows("6", "5"));
            assertFalse(scope.allows("6", "3"));
            assertTrue(scope.allows("6", "4"));
        }
    }

    @Test
    public void directMessagesUseOnlyChannelAndDefaultEvenWhenChannelEqualsServerId() {
        for (boolean defaultTranslate : new boolean[]{false, true}) {
            TranslationScope scope = new TranslationScope(defaultTranslate,
                    Collections.singletonList(rule("1", !defaultTranslate)),
                    Collections.singletonList(rule("2", !defaultTranslate)));
            assertEquals(defaultTranslate, scope.allows(null, "1"));
            assertEquals(!defaultTranslate, scope.allows(null, "2"));
            assertEquals(defaultTranslate, scope.allows(null, "3"));
        }
    }

    @Test
    public void removingRuleRestoresInheritanceWithoutStoredInheritState() {
        TranslationScope channelOverride = new TranslationScope(false,
                Collections.singletonList(rule("1", true)),
                Collections.singletonList(rule("2", false)));
        assertFalse(channelOverride.allows("1", "2"));
        TranslationScope inheritServer = new TranslationScope(false,
                channelOverride.servers, Collections.emptyList());
        assertTrue(inheritServer.allows("1", "2"));
        TranslationScope inheritDefault = new TranslationScope(false,
                Collections.emptyList(), Collections.emptyList());
        assertFalse(inheritDefault.allows("1", "2"));
    }

    @Test
    public void validIdsIncludeTheUnsigned64Boundary() {
        for (String id : new String[]{"1", "9", "10", "9223372036854775807",
                "9223372036854775808", "18446744073709551615"}) {
            assertTrue(id, TranslationScope.isValidId(id));
            assertEquals(id, rule(id, true).id);
        }
    }

    @Test
    public void invalidIdsAreRejectedByValidatorRulesAndParser() {
        for (String id : INVALID_IDS) {
            assertFalse(String.valueOf(id), TranslationScope.isValidId(id));
            assertThrows(IllegalArgumentException.class,
                    () -> new TranslationScope.Rule(id, "label", true));
        }
        for (String id : new String[]{"", "0", "01", " 1", "1 ", "+1", "-1",
                "\u0661", "18446744073709551616", "100000000000000000000"}) {
            String json = withRules("[{\"id\":\"" + id
                    + "\",\"label\":\"label\",\"translate\":true}]", "[]");
            assertThrows(IllegalArgumentException.class, () -> TranslationScope.parse(json));
        }
    }

    @Test
    public void invalidContextNeverAllowsTranslationEvenWithAllowingRules() {
        TranslationScope scope = new TranslationScope(true,
                Collections.singletonList(rule("1", true)),
                Collections.singletonList(rule("2", true)));
        for (String id : INVALID_IDS) {
            assertFalse(scope.allows("1", id));
            assertFalse(scope.allows(null, id));
            if (id != null) assertFalse(scope.allows(id, "2"));
        }
        assertTrue(scope.allows("18446744073709551615", "18446744073709551615"));
    }

    @Test
    public void labelsAreTrimmedAndLimitedAfterTrimming() {
        assertEquals("name", new TranslationScope.Rule("1", " \tname\n ", true).label);
        assertEquals("", new TranslationScope.Rule("1", " \t\n ", true).label);
        assertEquals("", new TranslationScope.Rule("1", "", true).label);
        String eighty = repeat('x', 80);
        assertEquals(eighty, new TranslationScope.Rule("1", " " + eighty + " ", true).label);
        assertThrows(IllegalArgumentException.class,
                () -> new TranslationScope.Rule("1", null, true));
        assertThrows(IllegalArgumentException.class,
                () -> new TranslationScope.Rule("1", repeat('x', 81), true));
        assertThrows(IllegalArgumentException.class,
                () -> TranslationScope.parse(withRules("[{\"id\":\"1\",\"label\":\""
                        + repeat('x', 81) + "\",\"translate\":true}]", "[]")));
    }

    @Test
    public void namesNeverParticipateInMatching() {
        TranslationScope scope = new TranslationScope(false,
                Collections.singletonList(new TranslationScope.Rule("1", "3", true)),
                Collections.singletonList(new TranslationScope.Rule("2", "4", true)));
        assertFalse(scope.allows("3", "4"));
        assertFalse(scope.allows(null, "4"));
        assertTrue(scope.allows("1", "4"));
        assertTrue(scope.allows("3", "2"));
        TranslationScope renamed = new TranslationScope(false,
                Collections.singletonList(new TranslationScope.Rule("1", "renamed server", true)),
                Collections.singletonList(new TranslationScope.Rule("2", "renamed channel", true)));
        assertNotEquals(scope, renamed);
        assertEquals(scope.allows("1", "4"), renamed.allows("1", "4"));
        assertEquals(scope.allows("3", "2"), renamed.allows("3", "2"));
        assertEquals(scope.allows("3", "4"), renamed.allows("3", "4"));
    }

    @Test
    public void strictRootSchemaRejectsMissingUnknownNullAndIncorrectTypes() {
        String[] invalid = {
                null, "", " \n\t", "null", "[]", "true", "1", "\"text\"", "{}",
                "{\"defaultTranslate\":true,\"servers\":[]}",
                "{\"defaultTranslate\":true,\"channels\":[]}",
                "{\"servers\":[],\"channels\":[]}",
                "{\"defaultTranslate\":true,\"servers\":[],\"channels\":[],\"extra\":1}",
                "{\"defaultTranslate\":true,\"servers\":[],\"channel\":[]}",
                "{\"defaultTranslate\":\"true\",\"servers\":[],\"channels\":[]}",
                "{\"defaultTranslate\":1,\"servers\":[],\"channels\":[]}",
                "{\"defaultTranslate\":null,\"servers\":[],\"channels\":[]}",
                withRules("null", "[]"), withRules("[]", "null"),
                withRules("{}", "[]"), withRules("[]", "{}"),
                withRules("\"[]\"", "[]"), withRules("[]", "true"),
                EMPTY_TRUE + " {}", EMPTY_TRUE + " unexpected"
        };
        for (String json : invalid) {
            assertThrows(String.valueOf(json), IllegalArgumentException.class,
                    () -> TranslationScope.parse(json));
        }
    }

    @Test
    public void strictRuleSchemaRejectsMissingUnknownNullAndIncorrectTypesInBothLists() {
        String[] invalidItems = {
                "null", "[]", "true", "1", "\"rule\"", "{}",
                "{\"label\":\"name\",\"translate\":true}",
                "{\"id\":\"1\",\"translate\":true}",
                "{\"id\":\"1\",\"label\":\"name\"}",
                "{\"id\":\"1\",\"label\":\"name\",\"translate\":true,\"extra\":null}",
                "{\"id\":1,\"label\":\"name\",\"translate\":true}",
                "{\"id\":null,\"label\":\"name\",\"translate\":true}",
                "{\"id\":\"1\",\"label\":1,\"translate\":true}",
                "{\"id\":\"1\",\"label\":null,\"translate\":true}",
                "{\"id\":\"1\",\"label\":\"name\",\"translate\":\"false\"}",
                "{\"id\":\"1\",\"label\":\"name\",\"translate\":0}",
                "{\"id\":\"1\",\"label\":\"name\",\"translate\":null}",
                "{\"id\":true,\"label\":{},\"translate\":[]}"
        };
        for (String item : invalidItems) {
            assertThrows(item, IllegalArgumentException.class,
                    () -> TranslationScope.parse(withRules("[" + item + "]", "[]")));
            assertThrows(item, IllegalArgumentException.class,
                    () -> TranslationScope.parse(withRules("[]", "[" + item + "]")));
        }
    }

    @Test
    public void malformedJsonPreservesTheJsonExceptionAsCause() {
        for (String json : new String[]{"{", "{\"defaultTranslate\":", "["}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TranslationScope.parse(json));
            assertTrue(error.getCause() instanceof JSONException);
        }
    }

    @Test
    public void duplicateIdsAreRejectedWithinEitherListButNotAcrossNamespaces() {
        List<TranslationScope.Rule> duplicate = Arrays.asList(
                new TranslationScope.Rule("1", "first", true),
                new TranslationScope.Rule("1", "second", false));
        assertThrows(IllegalArgumentException.class,
                () -> new TranslationScope(true, duplicate, Collections.emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> new TranslationScope(true, Collections.emptyList(), duplicate));
        String duplicateJson = "[{\"id\":\"1\",\"label\":\"first\",\"translate\":true},"
                + "{\"id\":\"1\",\"label\":\"second\",\"translate\":false}]";
        assertThrows(IllegalArgumentException.class,
                () -> TranslationScope.parse(withRules(duplicateJson, "[]")));
        assertThrows(IllegalArgumentException.class,
                () -> TranslationScope.parse(withRules("[]", duplicateJson)));
        TranslationScope scope = new TranslationScope(true,
                Collections.singletonList(rule("1", true)),
                Collections.singletonList(rule("1", false)));
        assertFalse(scope.allows("1", "1"));
        assertEquals(scope, TranslationScope.parse(scope.toJson()));
    }

    @Test
    public void eachListAllows256RulesAndRejects257InConstructorAndParser() {
        List<TranslationScope.Rule> maximum = rules(256);
        TranslationScope scope = new TranslationScope(false, maximum, maximum);
        assertEquals(256, scope.servers.size());
        assertEquals(256, scope.channels.size());
        assertEquals(scope, TranslationScope.parse(scope.toJson()));
        List<TranslationScope.Rule> excessive = rules(257);
        assertThrows(IllegalArgumentException.class,
                () -> new TranslationScope(false, excessive, Collections.emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> new TranslationScope(false, Collections.emptyList(), excessive));
        // Extend a valid serialized array, without constructing an invalid scope.
        String array = scope.toJson();
        int start = array.indexOf('[');
        int end = array.indexOf(']', start);
        String excessiveJson = array.substring(start, end)
                + ",{\"id\":\"257\",\"label\":\"extra\",\"translate\":true}]";
        assertThrows(IllegalArgumentException.class,
                () -> TranslationScope.parse(withRules(excessiveJson, "[]")));
        assertThrows(IllegalArgumentException.class,
                () -> TranslationScope.parse(withRules("[]", excessiveJson)));
    }

    @Test
    public void constructorRejectsNullListsAndNullRules() {
        assertThrows(IllegalArgumentException.class,
                () -> new TranslationScope(true, null, Collections.emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> new TranslationScope(true, Collections.emptyList(), null));
        List<TranslationScope.Rule> nullRule = Collections.singletonList(null);
        assertThrows(IllegalArgumentException.class,
                () -> new TranslationScope(true, nullRule, Collections.emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> new TranslationScope(true, Collections.emptyList(), nullRule));
    }

    @Test
    public void modelCopiesListsAndExposesOnlyImmutableState() throws Exception {
        List<TranslationScope.Rule> servers = new ArrayList<>(Collections.singletonList(rule("1", true)));
        List<TranslationScope.Rule> channels = new ArrayList<>(Collections.singletonList(rule("2", false)));
        TranslationScope scope = new TranslationScope(true, servers, channels);
        servers.clear();
        channels.set(0, rule("3", true));
        assertEquals(Collections.singletonList(rule("1", true)), scope.servers);
        assertEquals(Collections.singletonList(rule("2", false)), scope.channels);
        for (List<TranslationScope.Rule> exposed : Arrays.asList(scope.servers, scope.channels)) {
            assertThrows(UnsupportedOperationException.class, () -> exposed.add(rule("4", true)));
            assertThrows(UnsupportedOperationException.class, () -> exposed.set(0, rule("4", true)));
            assertThrows(UnsupportedOperationException.class, () -> exposed.remove(0));
            assertThrows(UnsupportedOperationException.class, exposed::clear);
        }
        assertThrows(UnsupportedOperationException.class,
                () -> TranslationScope.ALL.servers.add(rule("4", true)));
        assertThrows(UnsupportedOperationException.class,
                () -> TranslationScope.ALL.channels.add(rule("4", true)));
        assertTrue(Modifier.isFinal(TranslationScope.class.getModifiers()));
        assertTrue(Modifier.isFinal(TranslationScope.Rule.class.getModifiers()));
        for (String field : new String[]{"defaultTranslate", "servers", "channels", "ALL"}) {
            assertTrue(Modifier.isFinal(TranslationScope.class.getField(field).getModifiers()));
        }
        for (String field : new String[]{"id", "label", "translate"}) {
            assertTrue(Modifier.isFinal(TranslationScope.Rule.class.getField(field).getModifiers()));
        }
    }

    @Test
    public void compactSerializationHasFixedKeyOrderAndPreservesRuleOrder() {
        TranslationScope scope = new TranslationScope(false,
                Arrays.asList(new TranslationScope.Rule("2", " two ", true), rule("1", false)),
                Collections.singletonList(new TranslationScope.Rule("3", "", true)));
        String expected = "{\"defaultTranslate\":false,\"servers\":["
                + "{\"id\":\"2\",\"label\":\"two\",\"translate\":true},"
                + "{\"id\":\"1\",\"label\":\"label\",\"translate\":false}],"
                + "\"channels\":[{\"id\":\"3\",\"label\":\"\",\"translate\":true}]}";
        assertEquals(expected, scope.toJson());
        assertEquals(expected, scope.toJson());
        assertEquals(expected, TranslationScope.parse(expected).toJson());
        TranslationScope reorderedKeys = TranslationScope.parse(
                "{\"channels\":[],\"servers\":[],\"defaultTranslate\":true}");
        assertEquals(EMPTY_TRUE, reorderedKeys.toJson());
    }

    @Test
    public void roundTripPreservesEscapedLabelsContentAndDecisions() {
        TranslationScope scope = new TranslationScope(false,
                Collections.singletonList(new TranslationScope.Rule("1", "引号\"反斜线\\换行\n标签", true)),
                Collections.singletonList(new TranslationScope.Rule("18446744073709551615", "DM\t标签", false)));
        TranslationScope parsed = TranslationScope.parse(scope.toJson());
        assertEquals(scope, parsed);
        assertEquals(scope.hashCode(), parsed.hashCode());
        assertEquals(scope.toJson(), parsed.toJson());
        assertTrue(parsed.allows("1", "2"));
        assertFalse(parsed.allows("1", "18446744073709551615"));
        assertFalse(parsed.allows(null, "18446744073709551615"));
    }

    @Test
    public void equalityAndHashCodeAreByAllContentIncludingLabelsAndOrder() {
        TranslationScope.Rule first = new TranslationScope.Rule("1", "name", true);
        TranslationScope.Rule equalRule = new TranslationScope.Rule("1", " name ", true);
        assertEquals(first, equalRule);
        assertEquals(first.hashCode(), equalRule.hashCode());
        assertNotEquals(first, new TranslationScope.Rule("2", "name", true));
        assertNotEquals(first, new TranslationScope.Rule("1", "renamed", true));
        assertNotEquals(first, new TranslationScope.Rule("1", "name", false));
        assertFalse(first.equals(null));
        assertFalse(first.equals("name"));
        TranslationScope scope = new TranslationScope(false, Arrays.asList(first, rule("2", false)),
                Collections.singletonList(rule("3", true)));
        TranslationScope equal = new TranslationScope(false, Arrays.asList(equalRule, rule("2", false)),
                Collections.singletonList(rule("3", true)));
        assertEquals(scope, scope);
        assertEquals(scope, equal);
        assertEquals(equal, scope);
        assertEquals(scope.hashCode(), equal.hashCode());
        assertNotEquals(scope, new TranslationScope(true, scope.servers, scope.channels));
        assertNotEquals(scope, new TranslationScope(false, scope.channels, scope.servers));
        assertNotEquals(scope, new TranslationScope(false, Arrays.asList(rule("2", false), first), scope.channels));
        assertNotEquals(scope, new TranslationScope(false, scope.servers, Collections.emptyList()));
        assertFalse(scope.equals(null));
        assertFalse(scope.equals("scope"));
    }

    @Test
    public void matchingPolicyIgnoresLabelsAndOrderButKeepsDefaultIdsActionsAndPartitions() {
        TranslationScope scope = new TranslationScope(false,
                Arrays.asList(rule("1", true), rule("2", false)),
                Arrays.asList(rule("3", false), rule("4", true)));
        TranslationScope renamed = new TranslationScope(false,
                Arrays.asList(new TranslationScope.Rule("1", "renamed", true), rule("2", false)),
                Arrays.asList(new TranslationScope.Rule("3", "renamed", false), rule("4", true)));
        TranslationScope reordered = new TranslationScope(false,
                Arrays.asList(rule("2", false), rule("1", true)),
                Arrays.asList(rule("4", true), rule("3", false)));
        assertTrue(scope.sameMatchingPolicy(scope));
        assertTrue(scope.sameMatchingPolicy(TranslationScope.parse(scope.toJson())));
        for (TranslationScope same : Arrays.asList(renamed, reordered)) {
            assertNotEquals(scope, same);
            assertNotEquals(scope.toJson(), same.toJson());
            assertTrue(scope.sameMatchingPolicy(same));
            assertTrue(same.sameMatchingPolicy(scope));
        }
        TranslationScope[] changed = {
                new TranslationScope(true, scope.servers, scope.channels),
                new TranslationScope(false, Arrays.asList(rule("5", true), rule("2", false)), scope.channels),
                new TranslationScope(false, scope.servers, Arrays.asList(rule("5", false), rule("4", true))),
                new TranslationScope(false, Arrays.asList(rule("1", false), rule("2", false)), scope.channels),
                new TranslationScope(false, scope.servers, Arrays.asList(rule("3", true), rule("4", true))),
                new TranslationScope(false, scope.channels, scope.servers),
                new TranslationScope(false, Collections.emptyList(), scope.channels),
                new TranslationScope(false, scope.servers, Collections.emptyList())
        };
        for (TranslationScope different : changed) {
            assertFalse(scope.sameMatchingPolicy(different));
            assertFalse(different.sameMatchingPolicy(scope));
        }
        assertFalse(scope.sameMatchingPolicy(null));
    }

    @Test
    public void listOnlyEditorDraftAndAppliedSnapshotStayIsolated() {
        TranslationScope initial = new TranslationScope(false,
                Collections.singletonList(rule("1", true)), Collections.singletonList(rule("2", false)));
        List<TranslationScope.Rule> servers = new ArrayList<>(initial.servers);
        List<TranslationScope.Rule> channels = new ArrayList<>(initial.channels);
        servers.set(0, new TranslationScope.Rule("1", "edited", false));
        channels.add(rule("3", true));
        // Cancel leaves the initial snapshot untouched; Apply freezes the current containers.
        assertEquals(Collections.singletonList(rule("1", true)), initial.servers);
        assertEquals(Collections.singletonList(rule("2", false)), initial.channels);
        TranslationScope applied = new TranslationScope(true, servers, channels);
        servers.clear();
        channels.clear();
        assertEquals("edited", applied.servers.get(0).label);
        assertFalse(applied.servers.get(0).translate);
        assertEquals(2, applied.channels.size());
        assertTrue(applied.defaultTranslate);
    }

    private static TranslationScope.Rule rule(String id, boolean translate) {
        return new TranslationScope.Rule(id, "label", translate);
    }

    private static List<TranslationScope.Rule> rules(int count) {
        List<TranslationScope.Rule> rules = new ArrayList<>();
        for (int i = 1; i <= count; i++) rules.add(rule(Integer.toString(i), true));
        return rules;
    }

    private static String withRules(String servers, String channels) {
        return "{\"defaultTranslate\":true,\"servers\":" + servers + ",\"channels\":" + channels + "}";
    }

    private static String repeat(char character, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, character);
        return new String(characters);
    }
}
