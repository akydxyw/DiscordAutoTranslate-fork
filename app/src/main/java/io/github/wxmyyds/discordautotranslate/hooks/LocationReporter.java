package io.github.wxmyyds.discordautotranslate.hooks;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import io.github.wxmyyds.discordautotranslate.core.ProjectLog;
import io.github.wxmyyds.discordautotranslate.core.RecentLocations;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Bounded, throttled observation transport; failed batches are reported, never retried or redirected. */
public final class LocationReporter {
    private static final Map<String, MessageLocation> PENDING = new LinkedHashMap<>();
    private static final Map<String, Long> SENT = new LinkedHashMap<>();
    private static ScheduledExecutorService worker;
    private static Context context;
    private static boolean scheduled;

    private LocationReporter() {}

    static synchronized void observe(MessageLocation location, Context host) {
        if (worker == null) {
            context = host.getApplicationContext();
            worker = Executors.newSingleThreadScheduledExecutor();
        }
        if (worker.isShutdown() || context == null) throw new IllegalStateException("Location reporter unavailable");
        long now = android.os.SystemClock.elapsedRealtime();
        Long last = SENT.get(location.channelId);
        if (last != null && now - last < 60000L) return;
        if (PENDING.size() == RecentLocations.LIMIT && !PENDING.containsKey(location.channelId)) {
            ProjectLog.warnOnce("scope.directory.full", "scope directory observation queue is full");
            return;
        }
        PENDING.put(location.channelId, location);
        if (!scheduled) {
            scheduled = true;
            worker.schedule(LocationReporter::sendNext, 250L, TimeUnit.MILLISECONDS);
        }
    }

    private static void sendNext() {
        MessageLocation location;
        Context target;
        synchronized (LocationReporter.class) {
            if (PENDING.isEmpty() || context == null) { scheduled = false; return; }
            String channel = PENDING.keySet().iterator().next();
            location = PENDING.remove(channel);
            target = context;
            // Throttle attempts, not only successes; failure never creates an automatic retry loop.
            SENT.put(channel, android.os.SystemClock.elapsedRealtime());
            if (SENT.size() > RecentLocations.LIMIT) SENT.remove(SENT.keySet().iterator().next());
        }
        try {
            Bundle extras = new Bundle();
            extras.putString("guildId", location.guildId);
            extras.putString("channelId", location.channelId);
            Bundle response = target.getContentResolver().call(
                    Uri.parse("content://" + RecentLocations.AUTHORITY), RecentLocations.METHOD, null, extras);
            if (response == null || !response.getBoolean("stored", false))
                throw new IllegalStateException("Directory observation was not acknowledged");
        } catch (RuntimeException failure) {
            ProjectLog.warnOnce("scope.directory.failed." + failure.getClass().getSimpleName(),
                    "scope directory observation failed exception=" + failure.getClass().getSimpleName());
        }
        synchronized (LocationReporter.class) {
            if (worker != null && !worker.isShutdown() && !PENDING.isEmpty()) {
                worker.schedule(LocationReporter::sendNext, 250L, TimeUnit.MILLISECONDS);
            } else {
                scheduled = false;
            }
        }
    }

    public static boolean detachAndAwait(long timeoutMs) {
        ScheduledExecutorService previous;
        synchronized (LocationReporter.class) {
            previous = worker;
            if (previous != null) {
                try { previous.shutdownNow(); } catch (RuntimeException failure) {
                    throw new IllegalStateException("Location worker shutdown failed", failure);
                }
            }
            context = null;
            PENDING.clear();
            SENT.clear();
            scheduled = false;
        }
        if (previous == null) return true;
        try {
            if (!previous.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) return false;
            synchronized (LocationReporter.class) { if (worker == previous) worker = null; }
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
