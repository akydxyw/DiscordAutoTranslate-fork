package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** JVM-only interface proxies model memory-before-disk writes and failed restoration. */
public class ConfigurationSyncTest {
    private static final String INITIALIZED = "settings_initialized";

    @Test
    public void firstImportFailureRestoresAbsentMarkerAndNeverPromotesAuthority() {
        FakePrefs local = localBeforeImport();
        FakePrefs remote = remoteValues();
        Map<String, Object> before = new HashMap<>(local.memory);
        local.outcomes.add(false);
        local.outcomes.add(true);

        ConfigurationSync.Result failed = ConfigurationSync.synchronize(local.prefs, remote.prefs);

        assertEquals(ConfigurationSync.Direction.IMPORT, failed.direction);
        assertEquals(ConfigurationSync.Status.LOCAL_COMMIT_FAILED, failed.status);
        assertFalse(failed.imported());
        assertFalse(failed.localCommitted);
        assertFalse(failed.transaction.rollbackFailed);
        assertEquals(before, local.memory);
        assertEquals(before, local.disk);
        assertFalse(local.memory.containsKey(INITIALIZED));
        assertEquals(2, local.commits);
        assertEquals(1, local.editors);
        assertEquals(0, remote.editors);
        assertFalse(PreferenceCommit.isPoisoned(local.prefs));

        // A distinct reconnection, not an in-operation retry, must still import.
        remote.memory.put(Config.KEY_MODEL, "new-remote-model");
        local.outcomes.add(true);
        ConfigurationSync.Result reconnected = ConfigurationSync.synchronize(local.prefs, remote.prefs);
        assertTrue(reconnected.imported());
        assertEquals("new-remote-model", local.disk.get(Config.KEY_MODEL));
        assertEquals(Boolean.TRUE, local.disk.get(INITIALIZED));
        assertEquals(0, remote.commits);
    }

    @Test
    public void throwingImportRestoresExplicitFalseMarker() {
        FakePrefs local = localBeforeImport();
        local.memory.put(INITIALIZED, false);
        local.disk.put(INITIALIZED, false);
        Map<String, Object> before = new HashMap<>(local.memory);
        RuntimeException failure = new IllegalStateException();
        local.outcomes.add(failure);
        local.outcomes.add(true);

        ConfigurationSync.Result result = ConfigurationSync.synchronize(local.prefs, remoteValues().prefs);

        assertFalse(result.imported());
        assertEquals(failure, result.failure);
        assertEquals(before, local.memory);
        assertEquals(before, local.disk);
        assertEquals(Boolean.FALSE, local.memory.get(INITIALIZED));
        assertFalse(PreferenceCommit.isPoisoned(local.prefs));
    }

    @Test
    public void failedRollbackPoisonsImportAndReconnectDoesNotReadOrWriteRemote() {
        FakePrefs local = localBeforeImport();
        FakePrefs remote = remoteValues();
        Map<String, Object> before = new HashMap<>(local.memory);
        local.outcomes.add(false);
        local.outcomes.add(false);

        ConfigurationSync.Result failed = ConfigurationSync.synchronize(local.prefs, remote.prefs);
        assertFalse(failed.imported());
        assertTrue(failed.transaction.rollbackFailed);
        assertEquals(before, local.memory);
        assertTrue(PreferenceCommit.isPoisoned(local.prefs));
        int localReads = local.reads;
        int remoteReads = remote.reads;

        ConfigurationSync.Result reconnect = ConfigurationSync.synchronize(local.prefs, remote.prefs);
        assertEquals(ConfigurationSync.Status.POISONED, reconnect.status);
        assertFalse(reconnect.imported());
        assertEquals(localReads, local.reads);
        assertEquals(remoteReads, remote.reads);
        assertEquals(2, local.commits);
        assertEquals(0, remote.editors);

        // Only a user-requested save/reset can clear poison and establish authority.
        local.outcomes.add(true);
        assertTrue(ConfigurationSync.save(local.prefs, null, null).localCommitted);
        assertFalse(PreferenceCommit.isPoisoned(local.prefs));
        remote.outcomes.add(true);
        ConfigurationSync.Result afterReset = ConfigurationSync.synchronize(local.prefs, remote.prefs);
        assertEquals(ConfigurationSync.Direction.EXPORT, afterReset.direction);
        assertTrue(afterReset.succeeded());
        assertFalse(remote.memory.containsKey(Config.KEY_MODEL));
        assertFalse(remote.memory.containsKey(INITIALIZED));
    }

    @Test
    public void throwingRollbackStagingRetainsFirstSnapshotAndBlocksTrueMemoryMarker() {
        FakePrefs local = localBeforeImport();
        FakePrefs remote = remoteValues();
        Map<String, Object> before = new HashMap<>(local.memory);
        local.outcomes.add(false);
        local.throwOnRestore = true;

        ConfigurationSync.Result failed = ConfigurationSync.synchronize(local.prefs, remote.prefs);
        assertTrue(failed.transaction.rollbackFailed);
        assertNotNull(failed.transaction.rollbackFailure);
        assertEquals(Boolean.TRUE, local.memory.get(INITIALIZED));
        assertTrue(PreferenceCommit.isPoisoned(local.prefs));
        assertEquals(ConfigurationSync.Status.POISONED,
                ConfigurationSync.synchronize(local.prefs, remote.prefs).status);
        assertEquals(0, remote.editors);
        assertEquals(1, local.commits);

        local.throwOnRestore = false;
        local.outcomes.add(false);
        local.outcomes.add(true);
        ConfigurationSync.Result explicitReset = ConfigurationSync.save(local.prefs, null, null);
        assertFalse(explicitReset.localCommitted);
        assertEquals(before, local.memory);
        assertEquals(before, local.disk);
        assertFalse(local.memory.containsKey(INITIALIZED));
        assertFalse(PreferenceCommit.isPoisoned(local.prefs));
    }

    @Test
    public void successfulImportThenReconnectionExportsWithoutAnotherLocalCommit() {
        FakePrefs local = localBeforeImport();
        FakePrefs remote = remoteValues();
        remote.memory.put(INITIALIZED, false); // Remote never owns the local authority marker.
        remote.memory.put("unrelated_remote", "untouched");
        local.outcomes.add(true);

        ConfigurationSync.Result imported = ConfigurationSync.synchronize(local.prefs, remote.prefs);
        assertTrue(imported.imported());
        assertEquals(Boolean.TRUE, local.disk.get(INITIALIZED));
        assertEquals("untouched", local.disk.get("unrelated_local"));
        assertFalse(local.disk.containsKey(Config.KEY_API_URL)); // Absent source removes target key.
        assertEquals(1, local.commits);
        assertEquals(0, remote.commits);

        FakePrefs newRemote = remoteValues();
        newRemote.memory.put(Config.KEY_MODEL, "must-not-import");
        newRemote.memory.put(INITIALIZED, false);
        newRemote.outcomes.add(true);
        ConfigurationSync.Result exported = ConfigurationSync.synchronize(local.prefs, newRemote.prefs);
        assertEquals(ConfigurationSync.Direction.EXPORT, exported.direction);
        assertTrue(exported.succeeded());
        assertFalse(exported.imported());
        assertEquals("remote-model", newRemote.disk.get(Config.KEY_MODEL));
        assertEquals(Boolean.FALSE, newRemote.disk.get(INITIALIZED));
        assertEquals(1, local.commits);
        assertEquals(1, newRemote.commits);
    }

    @Test
    public void remoteFailureNeverRetriesImportsOrRollsBackConfirmedLocalSave() {
        FakePrefs local = localBeforeImport();
        FakePrefs remote = remoteValues();
        local.outcomes.add(true);
        remote.outcomes.add(false);
        Config.Snapshot snapshot = Config.read(new MemoryPrefs()
                .put(Config.KEY_MODEL, "saved-model")
                .put(Config.KEY_PROTOCOL, Config.PROTOCOL_OPENAI_RESPONSES));

        ConfigurationSync.Result saved = ConfigurationSync.save(local.prefs, remote.prefs, snapshot);
        assertTrue(saved.localCommitted);
        assertEquals(ConfigurationSync.Status.REMOTE_COMMIT_FAILED, saved.status);
        assertEquals(Boolean.TRUE, local.disk.get(INITIALIZED));
        assertEquals("saved-model", local.disk.get(Config.KEY_MODEL));
        assertEquals(1, local.commits);
        assertEquals(1, remote.commits);
        assertFalse(remote.memory.containsKey(INITIALIZED));

        FakePrefs newRemote = remoteValues();
        newRemote.outcomes.add(true);
        ConfigurationSync.Result reconnect = ConfigurationSync.synchronize(local.prefs, newRemote.prefs);
        assertEquals(ConfigurationSync.Direction.EXPORT, reconnect.direction);
        assertEquals("saved-model", newRemote.disk.get(Config.KEY_MODEL));
        assertEquals(1, local.commits);
    }

    @Test
    public void failedSaveKeepsPriorAuthorityAndDoesNotTouchRemote() {
        FakePrefs local = localBeforeImport();
        local.memory.put(INITIALIZED, true);
        local.disk.put(INITIALIZED, true);
        FakePrefs remote = remoteValues();
        Map<String, Object> before = new HashMap<>(local.memory);
        local.outcomes.add(false);
        local.outcomes.add(true);

        ConfigurationSync.Result failed = ConfigurationSync.save(local.prefs, remote.prefs,
                Config.read(new MemoryPrefs().put(Config.KEY_MODEL, "unsaved-model")));
        assertFalse(failed.localCommitted);
        assertEquals(before, local.memory);
        assertEquals(before, local.disk);
        assertEquals(0, remote.editors);

        remote.outcomes.add(true);
        assertEquals(ConfigurationSync.Direction.EXPORT,
                ConfigurationSync.synchronize(local.prefs, remote.prefs).direction);
        assertFalse(remote.disk.containsKey(Config.KEY_MODEL));
    }

    @Test
    public void validatesEntireImportAndExportBeforeCreatingTargetEditor() {
        for (Object value : new Object[]{null, 3L, 0.5f, new Object(), "invalid-protocol", 0, -1, false}) {
            FakePrefs local = localBeforeImport();
            FakePrefs remote = remoteValues();
            // Last key ensures earlier fields would have staged before a partial validation.
            remote.memory.put(Config.KEY_MAX_CONCURRENCY, value);
            Map<String, Object> before = new HashMap<>(local.memory);
            ConfigurationSync.Result failed = ConfigurationSync.synchronize(local.prefs, remote.prefs);
            assertEquals(ConfigurationSync.Status.INVALID_SOURCE, failed.status);
            assertEquals(before, local.memory);
            assertEquals(0, local.editors);
            assertEquals(0, local.mutations);
            assertEquals(0, local.commits);
            assertEquals(0, remote.editors);

            local.memory.put(INITIALIZED, true);
            local.memory.put(Config.KEY_MAX_CONCURRENCY, value);
            failed = ConfigurationSync.synchronize(local.prefs, remote.prefs);
            assertEquals(ConfigurationSync.Direction.EXPORT, failed.direction);
            assertEquals(ConfigurationSync.Status.INVALID_SOURCE, failed.status);
            assertEquals(0, remote.editors);
        }
    }

    @Test
    public void invalidInitializationMarkerNeverChoosesDirection() {
        FakePrefs local = localBeforeImport();
        local.memory.put(INITIALIZED, "true");
        FakePrefs remote = remoteValues();
        ConfigurationSync.Result result = ConfigurationSync.synchronize(local.prefs, remote.prefs);
        assertEquals(ConfigurationSync.Status.INVALID_SOURCE, result.status);
        assertNull(result.direction);
        assertEquals(0, local.editors);
        assertEquals(0, remote.reads);
    }

    @Test
    public void validationUsesOneDetachedSourceSnapshotForStaging() {
        FakePrefs local = localBeforeImport();
        FakePrefs remote = remoteValues();
        local.onEdit = () -> remote.memory.put(Config.KEY_MODEL, "changed-after-validation");
        local.outcomes.add(true);

        assertTrue(ConfigurationSync.synchronize(local.prefs, remote.prefs).imported());
        assertEquals(1, remote.reads);
        assertEquals("remote-model", local.disk.get(Config.KEY_MODEL));
    }

    @Test
    public void everyStoreAccessAndEditorMutationHoldsPreferenceCommitMonitor() {
        FakePrefs local = localBeforeImport();
        FakePrefs remote = remoteValues();
        local.requireMonitor = true;
        remote.requireMonitor = true;
        local.outcomes.add(true);
        assertTrue(ConfigurationSync.synchronize(local.prefs, remote.prefs).imported());
        remote.outcomes.add(true);
        assertTrue(ConfigurationSync.synchronize(local.prefs, remote.prefs).succeeded());
        local.outcomes.add(true);
        remote.outcomes.add(true);
        assertTrue(ConfigurationSync.save(local.prefs, remote.prefs, null).localCommitted);
    }

    private static FakePrefs localBeforeImport() {
        FakePrefs fake = new FakePrefs();
        fake.memory.put(Config.KEY_API_URL, "https://old.example/v1");
        fake.memory.put("unrelated_local", "untouched");
        fake.disk.putAll(fake.memory);
        return fake;
    }

    private static FakePrefs remoteValues() {
        FakePrefs fake = new FakePrefs();
        fake.memory.put(Config.KEY_MODEL, "remote-model");
        fake.memory.put(Config.KEY_PROTOCOL, Config.PROTOCOL_ANTHROPIC_MESSAGES);
        fake.memory.put(Config.KEY_ENABLED, false);
        fake.memory.put(Config.KEY_TIMEOUT_MS, 41000);
        fake.disk.putAll(fake.memory);
        return fake;
    }

    private static final class FakePrefs {
        final Map<String, Object> memory = new HashMap<>();
        final Map<String, Object> disk = new HashMap<>();
        final Deque<Object> outcomes = new ArrayDeque<>();
        int commits;
        int editors;
        int mutations;
        int reads;
        boolean throwOnRestore;
        boolean requireMonitor;
        Runnable onEdit;
        final SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    checkMonitor();
                    switch (method.getName()) {
                        case "getAll":
                            reads++;
                            return new HashMap<>(memory);
                        case "edit":
                            editors++;
                            if (onEdit != null) {
                                onEdit.run();
                            }
                            return editor();
                        default:
                            throw new AssertionError("Unexpected preferences method: " + method.getName());
                    }
                });

        void checkMonitor() {
            if (requireMonitor) {
                assertTrue(Thread.holdsLock(PreferenceCommit.class));
            }
        }

        SharedPreferences.Editor editor() {
            Map<String, Object> staged = new HashMap<>();
            Object removed = new Object();
            boolean[] committedOnce = {false};
            return (SharedPreferences.Editor) Proxy.newProxyInstance(
                    SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        checkMonitor();
                        String name = method.getName();
                        if (name.equals("commit")) {
                            commits++;
                            committedOnce[0] = true;
                            for (Map.Entry<String, Object> entry : staged.entrySet()) {
                                if (entry.getValue() == removed) {
                                    memory.remove(entry.getKey());
                                } else {
                                    memory.put(entry.getKey(), entry.getValue());
                                }
                            }
                            staged.clear();
                            Object outcome = outcomes.removeFirst();
                            if (outcome instanceof RuntimeException) {
                                throw (RuntimeException) outcome;
                            }
                            if (Boolean.TRUE.equals(outcome)) {
                                disk.clear();
                                disk.putAll(memory);
                            }
                            return outcome;
                        }
                        if (name.equals("remove") || name.startsWith("put")) {
                            if (throwOnRestore && committedOnce[0]) {
                                throw new IllegalStateException("Restoration staging failed");
                            }
                            mutations++;
                            staged.put((String) args[0], name.equals("remove") ? removed : args[1]);
                            return proxy;
                        }
                        throw new AssertionError("Unexpected editor method: " + name);
                    });
        }
    }
}
