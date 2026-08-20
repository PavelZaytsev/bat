package dev.bat.examples.cache;

public final class RetryCoordinator {
    public <V> CompletionDisposition complete(
            StreamingCache<V> cache,
            LoadAttempt attempt,
            CacheOutcome<V> outcome) {
        return cache.complete(attempt, outcome);
    }
}
