package io.github.wxmyyds.discordautotranslate.core;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** Sole cross-process observation endpoint. Does not expose settings or the observed directory. */
public final class RecentLocationsProvider extends ContentProvider {
    private long lastAcceptedAt;

    @Override public boolean onCreate() { return true; }

    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        String caller = getCallingPackage(); // Android verifies package-to-Binder-UID ownership.
        if (!"com.discord".equals(caller)) throw new SecurityException("Caller not allowed");
        String[] packages = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages == null || !java.util.Arrays.asList(packages).contains(caller))
            throw new SecurityException("Caller UID mismatch");
        if (!RecentLocations.METHOD.equals(method) || arg != null || extras == null
                || extras.size() != 2 || !extras.containsKey("guildId") || !extras.containsKey("channelId"))
            throw new IllegalArgumentException("Invalid observation request");
        Object guild = extras.get("guildId");
        Object channel = extras.get("channelId");
        if (!(channel instanceof String) || guild != null && !(guild instanceof String))
            throw new IllegalArgumentException("Invalid observation types");
        RecentLocations.Entry entry = new RecentLocations.Entry((String) guild, (String) channel);
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastAcceptedAt != 0 && now - lastAcceptedAt < 200L)
            throw new IllegalStateException("Observation rate limit exceeded");
        SharedPreferences prefs = getContext().getSharedPreferences(RecentLocations.PREFS, 0);
        java.util.List<RecentLocations.Entry> current = RecentLocations.parse(prefs.getString(RecentLocations.KEY, "[]"));
        if (!prefs.edit().putString(RecentLocations.KEY,
                RecentLocations.toJson(RecentLocations.observe(current, entry))).commit())
            throw new IllegalStateException("Directory commit failed");
        lastAcceptedAt = now;
        Bundle result = new Bundle();
        result.putBoolean("stored", true);
        return result;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        throw new UnsupportedOperationException("Only observations are accepted");
    }
    @Override public String getType(Uri uri) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
