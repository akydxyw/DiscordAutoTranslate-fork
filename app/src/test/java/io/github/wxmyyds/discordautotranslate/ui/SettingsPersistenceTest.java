package io.github.wxmyyds.discordautotranslate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.ConfigurationSync;
import io.github.wxmyyds.discordautotranslate.core.PreferenceCommit;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercise the Activity's production loading/delivery policy without Android widget behavior. */
public class SettingsPersistenceTest {
    @Test
    public void loadReturnsBeforeAnyStoreAccessAndCoalescesPendingLoads() {
        Tasks worker = new Tasks();
        Tasks main = new Tasks();
        SettingsPersistence persistence = new SettingsPersistence(worker, main::execute);
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<ConfigurationSync.LocalState> loaded = new AtomicReference<>();
        SharedPreferences prefs = prefs(() -> {
            assertTrue(worker.running);
            assertTrue(Thread.holdsLock(PreferenceCommit.class));
            reads.incrementAndGet();
        });
        persistence.start();
        persistence.load(prefs, () -> 0, () -> false, (state, replace) -> {
            assertTrue(main.running);
            assertTrue(replace);
            loaded.set(state);
        });
        persistence.load(prefs, () -> 0, () -> false,
                (state, replace) -> { throw new AssertionError("Duplicate load"); });
        assertTrue(persistence.isLoading());
        assertEquals(0, reads.get());
        assertEquals(1, worker.tasks.size());
        assertNull(loaded.get());
        worker.runNext();
        assertTrue(persistence.isLoading());
        assertEquals(1, reads.get());
        assertNull(loaded.get());
        main.runNext();
        assertFalse(persistence.isLoading());
        assertNull(loaded.get().snapshot.configError);
        assertEquals("saved-model", loaded.get().snapshot.model);
    }

    @Test
    public void draftRestoredBeforeLoadCompletionIsNeverReplaced() {
        for (boolean restoredBeforeSubmission : new boolean[]{false, true}) {
            Tasks worker = new Tasks();
            Tasks main = new Tasks();
            SettingsPersistence persistence = new SettingsPersistence(worker, main::execute);
            long[] generation = {restoredBeforeSubmission ? 1 : 0};
            boolean[] dirty = {restoredBeforeSubmission};
            AtomicInteger completions = new AtomicInteger();
            persistence.start();
            persistence.load(prefs(() -> { }), () -> generation[0], () -> dirty[0],
                    (state, replace) -> {
                        assertFalse(replace);
                        assertNull(state.snapshot.configError);
                        completions.incrementAndGet();
                    });
            worker.runNext();
            // Restore can occur after the worker read but before main consumes its result.
            generation[0]++;
            dirty[0] = true;
            main.runNext();
            assertFalse(persistence.isLoading());
            assertEquals(1, completions.get());
        }
    }

    @Test
    public void newerFormGenerationRejectsAnOlderLoadEvenWhenItIsNowClean() {
        Tasks worker = new Tasks();
        Tasks main = new Tasks();
        SettingsPersistence persistence = new SettingsPersistence(worker, main::execute);
        long[] generation = {0};
        AtomicInteger completions = new AtomicInteger();
        persistence.start();
        persistence.load(prefs(() -> { }), () -> generation[0], () -> false,
                (state, replace) -> {
                    assertFalse(replace);
                    completions.incrementAndGet();
                });
        worker.runNext();
        generation[0]++;
        main.runNext();
        assertEquals(1, completions.get());
    }

    @Test
    public void stoppedLoadAndPersistenceCompletionsWaitForStartAndKeepTheirOrder() {
        Tasks worker = new Tasks();
        Tasks main = new Tasks();
        SettingsPersistence persistence = new SettingsPersistence(worker, main::execute);
        List<String> delivered = new ArrayList<>();
        boolean[] dirty = {false};
        persistence.start();
        persistence.load(prefs(() -> { }), () -> 0, () -> dirty[0], (state, replace) -> {
            assertFalse(replace);
            delivered.add("load");
            // A reconnect may be scheduled only after the loaded form becomes usable.
            worker.execute(() -> persistence.post(() -> delivered.add("reconnect")));
        });
        worker.execute(() -> persistence.post(() -> delivered.add("save")));
        persistence.stop();
        worker.runNext();
        worker.runNext();
        main.runNext();
        main.runNext();
        assertTrue(persistence.isLoading());
        assertTrue(delivered.isEmpty());
        dirty[0] = true;
        persistence.start();
        assertEquals(List.of("load", "save"), delivered);
        assertFalse(persistence.isLoading());
        worker.runNext();
        main.runNext();
        assertEquals(List.of("load", "save", "reconnect"), delivered);
        persistence.start();
        assertEquals(3, delivered.size());
    }

    @Test
    public void destructionDropsBothDeferredAndLateCompletions() {
        for (boolean completionAlreadyDeferred : new boolean[]{false, true}) {
            Tasks worker = new Tasks();
            Tasks main = new Tasks();
            SettingsPersistence persistence = new SettingsPersistence(worker, main::execute);
            AtomicInteger delivered = new AtomicInteger();
            persistence.load(prefs(() -> { }), () -> 0, () -> false,
                    (state, replace) -> delivered.incrementAndGet());
            worker.runNext();
            if (completionAlreadyDeferred) {
                main.runNext();
            }
            persistence.destroy();
            persistence.post(delivered::incrementAndGet);
            while (!main.tasks.isEmpty()) {
                main.runNext();
            }
            persistence.start();
            assertEquals(0, delivered.get());
            assertFalse(persistence.isLoading());
        }
    }

    @Test
    public void rejectedLoadClearsThePendingFlag() {
        SettingsPersistence persistence = new SettingsPersistence(
                task -> { throw new RejectedExecutionException(); }, Runnable::run);
        try {
            persistence.load(prefs(() -> { }), () -> 0, () -> false,
                    (state, replace) -> { throw new AssertionError("Rejected load completed"); });
            throw new AssertionError("Expected executor rejection");
        } catch (RejectedExecutionException expected) {
            assertFalse(persistence.isLoading());
        }
    }

    private static SharedPreferences prefs(Runnable read) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("getAll")) {
                        throw new AssertionError("Unexpected store access: " + method.getName());
                    }
                    read.run();
                    return Collections.singletonMap(Config.KEY_MODEL, "saved-model");
                });
    }

    private static final class Tasks implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        boolean running;

        @Override public void execute(Runnable task) {
            tasks.add(task);
        }

        void runNext() {
            running = true;
            try {
                tasks.remove().run();
            } finally {
                running = false;
            }
        }
    }
}
