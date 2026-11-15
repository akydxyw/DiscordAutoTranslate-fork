package io.github.wxmyyds.discordautotranslate.core;

import java.util.Iterator;
import java.util.List;

/** One cleanup pass: keep failed owned handles reachable and attempt every other handle once. */
public final class HookCleanup {
    private HookCleanup() { }

    public interface Removal<T> {
        void remove(T handle) throws Throwable;
    }

    /** Mutates the sole ownership list; success removes ownership, failure retains it. */
    public static <T> int remove(List<T> owned, Removal<T> removal) {
        int failures = 0;
        Iterator<T> iterator = owned.iterator();
        while (iterator.hasNext()) {
            T handle = iterator.next();
            try {
                removal.remove(handle);
            } catch (Throwable failure) {
                failures++;
                continue;
            }
            iterator.remove();
        }
        return failures;
    }
}
