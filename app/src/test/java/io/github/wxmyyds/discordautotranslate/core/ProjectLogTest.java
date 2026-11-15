package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the real static logger; the framework callback records observable output. */
public class ProjectLogTest {
    private final List<Entry> entries = new ArrayList<>();

    @Before
    public void setUp() {
        ProjectLog.configure(snapshot(false));
        ProjectLog.attach((priority, tag, message) -> entries.add(new Entry(priority, tag, message)));
    }

    @After
    public void tearDown() {
        ProjectLog.configure(snapshot(false));
        ProjectLog.attach(null);
    }

    @Test
    public void disabledLoggingSuppressesEveryEntryPointWithoutConsumingOnceKeys() {
        ProjectLog.info("hidden-info");
        ProjectLog.warn("hidden-warn");
        ProjectLog.infoOnce("info-key", "hidden-once-info");
        ProjectLog.warnOnce("warn-key", "hidden-once-warn");
        assertTrue(entries.isEmpty());

        ProjectLog.configure(snapshot(true));
        ProjectLog.infoOnce("info-key", "visible-info");
        ProjectLog.warnOnce("warn-key", "visible-warn");
        assertEquals(2, entries.size());
        assertEntry(0, Log.INFO, "visible-info");
        assertEntry(1, Log.WARN, "visible-warn");
    }

    @Test
    public void enabledLoggingPreservesSeverityTagAndRepeatedOrdinaryMessages() {
        ProjectLog.configure(snapshot(true));
        ProjectLog.info("info");
        ProjectLog.info("info");
        ProjectLog.warn("warn");
        ProjectLog.warn("warn");
        assertEquals(4, entries.size());
        assertEntry(0, Log.INFO, "info");
        assertEntry(1, Log.INFO, "info");
        assertEntry(2, Log.WARN, "warn");
        assertEntry(3, Log.WARN, "warn");
    }

    @Test
    public void onceKeysAreSharedAcrossSeverityAndIndependentOfMessage() {
        ProjectLog.configure(snapshot(true));
        ProjectLog.infoOnce("first", "same-message");
        ProjectLog.infoOnce("first", "changed-message");
        ProjectLog.warnOnce("first", "changed-severity");
        ProjectLog.warnOnce("second", "same-message");
        ProjectLog.infoOnce("second", "changed-severity");
        assertEquals(2, entries.size());
        assertEntry(0, Log.INFO, "same-message");
        assertEntry(1, Log.WARN, "same-message");
    }

    @Test
    public void unchangedConfigurationRetainsDedupeButOffOnStartsNewSession() {
        ProjectLog.configure(snapshot(true));
        ProjectLog.infoOnce("key", "first");
        ProjectLog.configure(snapshot(true));
        ProjectLog.warnOnce("key", "duplicate");
        assertEquals(1, entries.size());

        ProjectLog.configure(snapshot(false));
        ProjectLog.warnOnce("key", "disabled");
        ProjectLog.configure(snapshot(false));
        ProjectLog.configure(snapshot(true));
        ProjectLog.warnOnce("key", "new-session");
        assertEquals(2, entries.size());
        assertEntry(1, Log.WARN, "new-session");
    }

    @Test
    public void defaultAndInvalidConfigurationsDisablePreviouslyEnabledLogging() {
        Config.Snapshot[] disabled = {
                Config.read(new MemoryPrefs()),
                Config.invalidSnapshot(),
                Config.read(new MemoryPrefs().put(Config.KEY_LOGGING_ENABLED, "true")),
                Config.read(new MemoryPrefs().put(Config.KEY_LOGGING_ENABLED, true)
                        .put(Config.KEY_PROTOCOL, "invalid"))
        };
        for (Config.Snapshot configuration : disabled) {
            entries.clear();
            ProjectLog.configure(snapshot(true));
            ProjectLog.infoOnce("key", "before");
            ProjectLog.configure(configuration);
            ProjectLog.info("hidden-info");
            ProjectLog.warn("hidden-warn");
            ProjectLog.infoOnce("fresh-key", "hidden-once-info");
            ProjectLog.warnOnce("fresh-key", "hidden-once-warn");
            assertEquals(1, entries.size());
            ProjectLog.configure(snapshot(true));
            ProjectLog.warnOnce("fresh-key", "after");
            assertEquals(2, entries.size());
            assertEntry(1, Log.WARN, "after");
            ProjectLog.configure(snapshot(false));
        }
    }

    @Test
    public void replacingAndDetachingFrameworkRemovesOldCallback() {
        ProjectLog.configure(snapshot(true));
        ProjectLog.info("old");
        List<String> replacement = new ArrayList<>();
        ProjectLog.attach((priority, tag, message) -> replacement.add(message));
        ProjectLog.warn("replacement");
        ProjectLog.attach(null);
        ProjectLog.info("detached");
        assertEquals(1, entries.size());
        assertEntry(0, Log.INFO, "old");
        assertEquals(1, replacement.size());
        assertEquals("replacement", replacement.get(0));
    }

    @Test
    public void frameworkFailuresDoNotEscapeOrBreakGateAndDedupe() {
        AtomicInteger calls = new AtomicInteger();
        ProjectLog.attach((priority, tag, message) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("diagnostic destination failed");
        });
        ProjectLog.configure(snapshot(true));
        ProjectLog.info("info");
        ProjectLog.warn("warn");
        ProjectLog.infoOnce("key", "once");
        ProjectLog.warnOnce("key", "duplicate");
        assertEquals(3, calls.get());
        ProjectLog.configure(snapshot(false));
        ProjectLog.warn("disabled");
        assertEquals(3, calls.get());
        ProjectLog.configure(snapshot(true));
        ProjectLog.warnOnce("key", "new-session");
        assertEquals(4, calls.get());
    }

    @Test
    public void disablingWaitsForInFlightFrameworkOutputAndPreventsLaterWrites() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch disabling = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger outputTimeouts = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        ProjectLog.attach((priority, tag, message) -> {
            writes.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) outputTimeouts.incrementAndGet();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                outputTimeouts.incrementAndGet();
            }
        });
        ProjectLog.configure(snapshot(true));
        try {
            Future<?> writing = workers.submit(() -> ProjectLog.info("in-flight"));
            assertTrue("output did not start", entered.await(2, TimeUnit.SECONDS));
            Future<?> disabled = workers.submit(() -> {
                disabling.countDown();
                ProjectLog.configure(snapshot(false));
            });
            assertTrue("disable worker did not start", disabling.await(2, TimeUnit.SECONDS));
            try {
                disabled.get(100, TimeUnit.MILLISECONDS);
                fail("disable returned while framework output was still active");
            } catch (TimeoutException expected) {
                assertFalse(disabled.isDone());
            }
            release.countDown();
            writing.get(2, TimeUnit.SECONDS);
            disabled.get(2, TimeUnit.SECONDS);
            ProjectLog.info("after-disable");
            ProjectLog.warnOnce("key", "after-disable-once");
            assertEquals(1, writes.get());
            assertEquals(0, outputTimeouts.get());
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue("logger workers did not terminate", workers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static Config.Snapshot snapshot(boolean enabled) {
        return Config.read(new MemoryPrefs().put(Config.KEY_LOGGING_ENABLED, enabled));
    }

    private void assertEntry(int index, int priority, String message) {
        Entry entry = entries.get(index);
        assertEquals(priority, entry.priority);
        assertEquals("DiscordAutoTranslate", entry.tag);
        assertEquals(message, entry.message);
    }

    private static final class Entry {
        final int priority;
        final String tag;
        final String message;

        Entry(int priority, String tag, String message) {
            this.priority = priority;
            this.tag = tag;
            this.message = message;
        }
    }
}
