package io.github.wxmyyds.discordautotranslate.ui;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** One process-level service registration, with independent weak Activity subscriptions. */
final class XposedServiceConnectionHolder implements XposedServiceHelper.OnServiceListener {

    interface Listener {
        void onServiceConnected(@NonNull XposedService service);
        void onServiceDied(@NonNull XposedService service);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final XposedServiceConnectionHolder INSTANCE =
            new XposedServiceConnectionHolder();
    private static boolean registered;
    private final Subscriptions<Listener> subscriptions = new Subscriptions<>();
    @Nullable
    private XposedService currentService;
    private long connectionGeneration;

    private XposedServiceConnectionHolder() {}

    /** Main-thread identity bind. Only a newly added subscription receives current replay. */
    static void bind(@NonNull Listener listener) {
        dispatchToMain(() -> {
            // Register before subscribing: synchronous helper replay must not deliver twice.
            ensureRegistered();
            if (INSTANCE.subscriptions.add(listener) && INSTANCE.currentService != null) {
                listener.onServiceConnected(INSTANCE.currentService);
            }
        });
    }

    static void unbind(@NonNull Listener listener) {
        dispatchToMain(() -> INSTANCE.subscriptions.remove(listener));
    }

    /** Reconcile a stopped Activity's cached service before it subscribes again. */
    @Nullable
    static XposedService currentService() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Service snapshot requires the main thread");
        }
        return INSTANCE.currentService;
    }

    static long connectionGeneration() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Service generation requires the main thread");
        }
        return INSTANCE.connectionGeneration;
    }

    private static void ensureRegistered() {
        if (!registered) {
            registered = true;
            // The helper has no unregister API; never retain an Activity in its listener.
            XposedServiceHelper.registerListener(INSTANCE);
        }
    }

    @Override
    public void onServiceBind(@NonNull XposedService service) {
        dispatchToMain(() -> {
            if (currentService == service) {
                return;
            }
            currentService = service;
            final long generation = ++connectionGeneration;
            for (WeakReference<Listener> subscription : subscriptions.dispatchSnapshot()) {
                if (generation != connectionGeneration) {
                    return;
                }
                Listener listener = subscription.get();
                if (listener != null && subscriptions.isBound(subscription)) {
                    listener.onServiceConnected(service);
                }
            }
        });
    }

    @Override
    public void onServiceDied(@NonNull XposedService service) {
        dispatchToMain(() -> {
            // A stale binder death cannot erase a newer service.
            if (currentService != service) {
                return;
            }
            currentService = null;
            final long generation = ++connectionGeneration;
            for (WeakReference<Listener> subscription : subscriptions.dispatchSnapshot()) {
                if (generation != connectionGeneration) {
                    return;
                }
                Listener listener = subscription.get();
                if (listener != null && subscriptions.isBound(subscription)) {
                    listener.onServiceDied(service);
                }
            }
        });
    }

    private static void dispatchToMain(@NonNull Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            MAIN.post(action);
        }
    }

    /** Pure identity/weak-subscription policy; confined to the dispatch thread. */
    static final class Subscriptions<L> {
        private final List<WeakReference<L>> listeners = new ArrayList<>();

        boolean add(L listener) {
            if (contains(listener)) {
                return false;
            }
            listeners.add(new WeakReference<>(listener));
            return true;
        }

        void remove(L listener) {
            Iterator<WeakReference<L>> iterator = listeners.iterator();
            while (iterator.hasNext()) {
                L existing = iterator.next().get();
                if (existing == null || existing == listener) {
                    iterator.remove();
                }
            }
        }

        boolean contains(L listener) {
            boolean found = false;
            Iterator<WeakReference<L>> iterator = listeners.iterator();
            while (iterator.hasNext()) {
                L existing = iterator.next().get();
                if (existing == null) {
                    iterator.remove();
                } else if (existing == listener) {
                    found = true;
                }
            }
            return found;
        }

        List<WeakReference<L>> dispatchSnapshot() {
            snapshot(); // Prune cleared entries before capturing subscription identities.
            return new ArrayList<>(listeners);
        }

        boolean isBound(WeakReference<L> subscription) {
            return listeners.contains(subscription);
        }

        List<L> snapshot() {
            List<L> result = new ArrayList<>();
            Iterator<WeakReference<L>> iterator = listeners.iterator();
            while (iterator.hasNext()) {
                L listener = iterator.next().get();
                if (listener == null) {
                    iterator.remove();
                } else {
                    result.add(listener);
                }
            }
            return result;
        }
    }
}
