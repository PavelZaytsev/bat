package dev.bat.examples.cache;

public final class StreamingCache<V> {
    private long activeGeneration;
    private CacheOutcome<V> visible = new CacheOutcome.Missing<>();

    public LoadAttempt beginRefresh() {
        return new LoadAttempt(++activeGeneration);
    }

    public CompletionDisposition complete(LoadAttempt attempt, CacheOutcome<V> outcome) {
        if (attempt.generation() != activeGeneration) {
            return CompletionDisposition.STALE;
        }
        visible = outcome;
        return CompletionDisposition.COMMITTED;
    }

    public CacheOutcome<V> visible() {
        return visible;
    }

    // F-0017 remains intentionally unchanged and out of scope.
    public int debugEntryCount() {
        return visible instanceof CacheOutcome.Found<?> ? 1 : 0;
    }
}
