package dev.bat.examples.cache;

import java.util.NoSuchElementException;

public final class StreamingCache<V> {
    private long activeGeneration;
    private CacheOutcome<V> visible = new CacheOutcome.Missing<>();

    public LoadAttempt beginRefresh() {
        return new LoadAttempt(++activeGeneration);
    }

    LoadAttempt inferredActiveAttempt() {
        return new LoadAttempt(activeGeneration);
    }

    // BUG: the reconstructed attempt is still ignored; arrival is treated as authority.
    public CompletionDisposition complete(LoadAttempt attempt, CacheOutcome<V> outcome) {
        if (outcome instanceof CacheOutcome.Missing<?>) {
            throw new NoSuchElementException("backend miss");
        }
        visible = outcome;
        return CompletionDisposition.COMMITTED;
    }

    public CacheOutcome<V> visible() {
        return visible;
    }

    // Deliberately unrelated F-0017: observe it, but do not repair it for the root issue.
    public int debugEntryCount() {
        return visible instanceof CacheOutcome.Found<?> ? 1 : 0;
    }
}
