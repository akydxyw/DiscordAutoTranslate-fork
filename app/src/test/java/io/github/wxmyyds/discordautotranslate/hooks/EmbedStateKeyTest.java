package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Complete Embed captures own card identity; segment hashes still share the normal cache. */
public class EmbedStateKeyTest {
    private static final String ROW = "123456789012345678";
    private final HookBridge bridge = HookBridge.get();

    @Before
    public void activateFixtureProfile() { ContentNodeTestProfile.activate(ContentNodesTest.class); }

    @After
    public void clearFixtureProfile() { ContentNodeTestProfile.clear(); }

    private static Object text(String value) {
        return new ContentNodesTest.PrimitiveStructurableText(value);
    }

    private String keyFor(String row, Object text) {
        return bridge.embedStateKeys(row, Collections.singletonList(text)).get(0);
    }

    private static String contentHash(String key) {
        return key.substring(key.lastIndexOf('\u0000') + 1);
    }

    @Test
    public void completeCaptureIsStableAcrossEquivalentNativeInstances() {
        List<String> first = bridge.embedStateKeys(ROW, Arrays.asList(text("title"), text("description")));
        List<String> repeated = bridge.embedStateKeys(ROW, Arrays.asList(text("title"), text("description")));
        assertEquals(first, repeated);
        assertNotEquals(first.get(0), first.get(1));
        assertEquals(HookBridge.cardIdentityOf(first.get(0)), HookBridge.cardIdentityOf(first.get(1)));
        assertNotEquals(first, bridge.embedStateKeys("987654321098765432",
                Arrays.asList(text("title"), text("description"))));
    }

    @Test
    public void equalPrimaryTextsInDifferentSameRowCardsKeepIndependentKeysAndOwners() {
        List<String> first = bridge.embedStateKeys(ROW,
                Arrays.asList(text("shared title"), text("first description")));
        List<String> second = bridge.embedStateKeys(ROW,
                Arrays.asList(text("shared title"), text("second description")));
        assertNotEquals(first.get(0), second.get(0));
        assertNotEquals(HookBridge.cardIdentityOf(first.get(0)), HookBridge.cardIdentityOf(second.get(0)));
        assertEquals(contentHash(first.get(0)), contentHash(second.get(0)));
        for (String key : Arrays.asList(first.get(0), first.get(1), second.get(0), second.get(1))) {
            assertTrue(HookBridge.isCardKey(key));
            assertEquals(ROW, HookBridge.cardRowOf(key));
            String[] components = key.split("\u0000", -1);
            assertEquals(4, components.length);
            assertEquals(ROW, components[0]);
            assertEquals("embed", components[1]);
            assertEquals(64, components[2].length());
            assertEquals(64, components[3].length());
        }
    }

    @Test
    public void sequenceOrderLengthAndEmptyPositionsParticipateInCardIdentity() {
        Object first = text("first");
        Object second = text("second");
        List<String> ordered = bridge.embedStateKeys(ROW, Arrays.asList(first, second));
        List<String> reversed = bridge.embedStateKeys(ROW, Arrays.asList(second, first));
        List<String> extended = bridge.embedStateKeys(ROW, Arrays.asList(first, second, first));
        List<String> withEmpty = bridge.embedStateKeys(ROW, Arrays.asList(first, null, second));
        List<String> trailingEmpty = bridge.embedStateKeys(ROW, Arrays.asList(first, second, null));
        for (List<String> changed : Arrays.asList(reversed, extended, withEmpty, trailingEmpty)) {
            assertNotEquals(HookBridge.cardIdentityOf(ordered.get(0)),
                    HookBridge.cardIdentityOf(changed.get(0)));
        }
        assertEquals(contentHash(ordered.get(0)), contentHash(reversed.get(1)));
        assertNull(withEmpty.get(1));
        assertEquals(3, withEmpty.size());
        assertNotEquals(HookBridge.cardIdentityOf(withEmpty.get(0)),
                HookBridge.cardIdentityOf(trailingEmpty.get(0)));
    }

    @Test
    public void duplicateTextWithinOneCardSharesOneSegmentKey() {
        List<String> keys = bridge.embedStateKeys(ROW,
                Arrays.asList(text("same text"), text("same text"), text("different text")));
        assertEquals(keys.get(0), keys.get(1));
        assertNotEquals(keys.get(0), keys.get(2));
        assertEquals(HookBridge.cardIdentityOf(keys.get(0)), HookBridge.cardIdentityOf(keys.get(2)));
    }

    @Test
    public void emptyTextPreservesNullSlotsWithoutProducingKeys() {
        assertEquals(Arrays.asList(null, null, null, null), bridge.embedStateKeys(ROW,
                Arrays.asList(text(""), null, text(" \n\t "), new Object())));
        assertTrue(bridge.embedStateKeys(ROW, Collections.emptyList()).isEmpty());
        assertNull(keyFor(ROW, null));
    }

    @Test
    public void embedKeyIsRecognizableAndCarriesRowId() {
        String key = keyFor(ROW, text("Hello"));
        assertTrue(HookBridge.isCardKey(key));
        assertFalse(HookBridge.isCardKey(ROW));
        assertFalse(HookBridge.isCardKey(null));
        assertEquals(ROW, HookBridge.cardRowOf(key));
        assertEquals(ROW, HookBridge.cardRowOf(ROW));
        assertNull(HookBridge.cardRowOf(null));
    }

    @Test
    public void sameRowEmbedSegmentsRemainEligibleForNativeReplay() {
        List<String> keys = bridge.embedStateKeys(ROW, Arrays.asList(text("title"), text("description")));
        String otherRow = keyFor("987654321098765432", text("title"));
        assertTrue(RerenderRegistry.stillBound(keys.get(0), keys.get(1)));
        assertTrue(RerenderRegistry.stillBound(keys.get(1), keys.get(0)));
        assertFalse(RerenderRegistry.stillBound(keys.get(0), otherRow));
        assertFalse(RerenderRegistry.stillBound(keys.get(0), ROW));
        assertFalse(RerenderRegistry.stillBound(ROW, keys.get(0)));
    }

    @Test
    public void iconKeyMarkingIsExplicitPerCard() {
        List<String> keys = bridge.embedStateKeys(ROW, Arrays.asList(text("primary"), text("secondary")));
        String key = keys.get(0);
        String other = keys.get(1);
        try (InlineIconInjector.HostScope first = InlineIconInjector.enterHost(null, key)) {
            assertTrue(first.allows(key));
            assertFalse(first.allows(other));
            assertFalse(first.allows(null));
            try (InlineIconInjector.HostScope second = InlineIconInjector.enterHost(null, other)) {
                assertTrue(second.allows(other));
                assertFalse(second.allows(key));
            }
            assertSame(first, InlineIconInjector.currentHost());
            first.recordInjection(key);
            assertFalse(first.allows(key));
        }
        assertNull(InlineIconInjector.currentHost());
        try (InlineIconInjector.HostScope body = InlineIconInjector.enterHost(null, null)) {
            assertTrue(body.allows(ROW));
            assertFalse(body.allows(key));
        }
    }
}
