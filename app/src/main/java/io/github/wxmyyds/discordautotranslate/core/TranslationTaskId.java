package io.github.wxmyyds.discordautotranslate.core;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Immutable identity of one translation attempt, shared by state and scheduling. */
public final class TranslationTaskId {
    private static final AtomicLong NEXT_ATTEMPT = new AtomicLong();

    public final String messageId;
    public final String contentHash;
    public final int generation;
    public final long attempt;

    private TranslationTaskId(String messageId, String contentHash, int generation, long attempt) {
        this.messageId = messageId;
        this.contentHash = contentHash;
        this.generation = generation;
        this.attempt = attempt;
    }

    public static TranslationTaskId create(String messageId, String contentHash, int generation) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(contentHash, "contentHash");
        long attempt = NEXT_ATTEMPT.updateAndGet(previous -> {
            if (previous == Long.MAX_VALUE) {
                throw new IllegalStateException("Translation attempt identities exhausted");
            }
            return previous + 1L;
        });
        return new TranslationTaskId(messageId, contentHash, generation, attempt);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TranslationTaskId)) return false;
        TranslationTaskId that = (TranslationTaskId) other;
        return generation == that.generation && attempt == that.attempt
                && messageId.equals(that.messageId) && contentHash.equals(that.contentHash);
    }

    @Override
    public int hashCode() {
        int result = messageId.hashCode();
        result = 31 * result + contentHash.hashCode();
        result = 31 * result + generation;
        result = 31 * result + (int) (attempt ^ (attempt >>> 32));
        return result;
    }
}
