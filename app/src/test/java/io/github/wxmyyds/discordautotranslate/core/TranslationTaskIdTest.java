package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class TranslationTaskIdTest {
    @Test
    public void factoryRecordsVersionAndAllocatesUniqueAttempts() {
        TranslationTaskId first = TranslationTaskId.create("row", "hash", 9);
        TranslationTaskId next = TranslationTaskId.create("row", "hash", 9);
        assertEquals("row", first.messageId);
        assertEquals("hash", first.contentHash);
        assertEquals(9, first.generation);
        assertTrue(next.attempt > first.attempt);
        assertNotEquals(first, next);
    }

    @Test
    public void equalityAndHashCodeIncludeEveryIdentityComponent() throws Exception {
        Constructor<TranslationTaskId> constructor = TranslationTaskId.class.getDeclaredConstructor(
                String.class, String.class, int.class, long.class);
        constructor.setAccessible(true);
        TranslationTaskId first = constructor.newInstance("row", "hash", 9, 42L);
        TranslationTaskId equal = constructor.newInstance("row", "hash", 9, 42L);
        assertEquals(first, first);
        assertEquals(first, equal);
        assertEquals(equal, first);
        assertEquals(first.hashCode(), equal.hashCode());
        assertNotEquals(first, constructor.newInstance("other-row", "hash", 9, 42L));
        assertNotEquals(first, constructor.newInstance("row", "other-hash", 9, 42L));
        assertNotEquals(first, constructor.newInstance("row", "hash", 10, 42L));
        assertNotEquals(first, constructor.newInstance("row", "hash", 9, 43L));
        assertFalse(first.equals(null));
        assertFalse(first.equals("row"));
    }

    @Test
    public void identityIsImmutable() {
        assertTrue(Modifier.isFinal(TranslationTaskId.class.getModifiers()));
        for (Field field : TranslationTaskId.class.getFields()) {
            assertTrue(field.getName(), Modifier.isFinal(field.getModifiers()));
        }
        for (Constructor<?> constructor : TranslationTaskId.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        }
    }

    @Test(expected = NullPointerException.class)
    public void factoryRejectsNullMessageId() {
        TranslationTaskId.create(null, "hash", 1);
    }

    @Test(expected = NullPointerException.class)
    public void factoryRejectsNullContentHash() {
        TranslationTaskId.create("row", null, 1);
    }

    @Test
    public void concurrentFactoryCallsNeverReuseAttempt() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<TranslationTaskId>> creations = new ArrayList<>();
            for (int i = 0; i < 1024; i++) {
                creations.add(() -> TranslationTaskId.create("row", "hash", 1));
            }
            List<Future<TranslationTaskId>> results = pool.invokeAll(creations);
            Set<Long> attempts = new HashSet<>();
            Set<TranslationTaskId> identities = new HashSet<>();
            for (Future<TranslationTaskId> result : results) {
                TranslationTaskId id = result.get();
                assertTrue(attempts.add(id.attempt));
                assertTrue(identities.add(id));
            }
            assertEquals(1024, identities.size());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void defaultStringDoesNotExposeMessageIdOrContentHash() {
        TranslationTaskId id = TranslationTaskId.create("sensitive-message-id", "sensitive-content-hash", 1);
        assertFalse(id.toString().contains(id.messageId));
        assertFalse(id.toString().contains(id.contentHash));
    }
}
