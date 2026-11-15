package io.github.wxmyyds.discordautotranslate.ui;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import io.github.wxmyyds.discordautotranslate.core.ConfigurationSync;

/** Main-thread lifecycle delivery and background snapshot loading for settings persistence. */
final class SettingsPersistence {
    private final Executor executor;
    private final Consumer<Runnable> dispatch;
    private final List<Runnable> deferred = new ArrayList<>();
    private boolean started;
    private boolean destroyed;
    private boolean loading;

    SettingsPersistence(Executor executor, Consumer<Runnable> dispatch) {
        this.executor = executor;
        this.dispatch = dispatch;
    }

    void start() {
        if (destroyed) {
            return;
        }
        started = true;
        List<Runnable> completions = new ArrayList<>(deferred);
        deferred.clear();
        for (Runnable completion : completions) {
            deliver(completion);
        }
    }

    void stop() {
        started = false;
    }

    void destroy() {
        destroyed = true;
        loading = false;
        deferred.clear();
    }

    boolean isLoading() {
        return loading;
    }

    /** Called by workers; dispatch itself must enqueue onto the main thread. */
    void post(Runnable completion) {
        dispatch.accept(() -> deliver(completion));
    }

    private void deliver(Runnable completion) {
        if (destroyed) {
            return;
        }
        if (started) {
            completion.run();
        } else {
            deferred.add(completion);
        }
    }

    /**
     * One pending load, including delivery deferred while stopped. The replacement flag
     * protects drafts/edits; callers must still consume invalid/poisoned state when false.
     */
    void load(SharedPreferences prefs, LongSupplier formGeneration, BooleanSupplier dirty,
              BiConsumer<ConfigurationSync.LocalState, Boolean> completion) {
        if (destroyed || loading) {
            return;
        }
        final long readFormGeneration = formGeneration.getAsLong();
        loading = true;
        try {
            executor.execute(() -> {
                ConfigurationSync.LocalState state = ConfigurationSync.loadLocal(prefs);
                post(() -> {
                    loading = false;
                    // Evaluate when consumed, after draft restoration or stopped edits.
                    completion.accept(state,
                            !dirty.getAsBoolean() && readFormGeneration == formGeneration.getAsLong());
                });
            });
        } catch (RuntimeException failure) {
            loading = false;
            throw failure;
        }
    }
}
