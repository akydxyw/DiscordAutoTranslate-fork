package io.github.wxmyyds.discordautotranslate.hooks;

import io.github.wxmyyds.discordautotranslate.core.StateStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** One display choice and current membership per Poll or Embed card; entries own translations. */
final class CardState {
    private static final Map<String, Card> CARDS = new LinkedHashMap<String, Card>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Card> eldest) {
            return size() > 512;
        }
    };

    static final class Card {
        final String titleKey;
        final List<String> keys;
        private volatile boolean showingOriginal;
        private boolean active = true;

        Card(String titleKey, List<String> keys, boolean showingOriginal) {
            this.titleKey = titleKey;
            this.keys = Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(keys)));
            this.showingOriginal = showingOriginal;
        }

        boolean showingOriginal() { return showingOriginal; }

        StateStore.Status status() {
            boolean failed = false;
            boolean translated = false;
            for (String key : keys) {
                StateStore.Entry entry = StateStore.get().peek(key);
                if (entry == null) continue;
                if (entry.status == StateStore.Status.TRANSLATING) return StateStore.Status.TRANSLATING;
                failed |= entry.status == StateStore.Status.FAILED;
                translated |= entry.status == StateStore.Status.TRANSLATED;
            }
            return failed ? StateStore.Status.FAILED
                    : translated ? StateStore.Status.TRANSLATED : StateStore.Status.IDLE;
        }
    }

    private CardState() {}

    static synchronized Card bind(String row, String titleKey, List<String> keys) {
        Card previous = CARDS.get(row);
        Card current = new Card(titleKey, keys, previous != null && previous.showingOriginal);
        CARDS.put(row, current);
        return current;
    }

    static synchronized Card forKey(String key) {
        if (!HookBridge.isCardKey(key)) return null;
        Card card = CARDS.get(HookBridge.cardIdentityOf(key));
        return card != null && card.active && card.keys.contains(key) ? card : null;
    }

    /** The current view key and current card membership are the only positional replay authority. */
    static synchronized boolean canReplay(String incomingKey, String boundKey) {
        return incomingKey != null && incomingKey.equals(boundKey) && forKey(incomingKey) != null;
    }

    /** Invalidate old membership before parsing a real root bind, retaining only its choice. */
    static synchronized void invalidate(String row) {
        Card card = CARDS.get(row);
        if (card != null) card.active = false;
    }

    /** Only the current control segment can switch the card. */
    static synchronized Card toggle(String titleKey) {
        Card card = forKey(titleKey);
        if (card == null || !titleKey.equals(card.titleKey)) return null;
        card.showingOriginal = !card.showingOriginal;
        return card;
    }

    static synchronized void clear() { CARDS.clear(); }
}
