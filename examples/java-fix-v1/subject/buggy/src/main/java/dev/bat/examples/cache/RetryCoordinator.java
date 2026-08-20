package dev.bat.examples.cache;

public final class RetryCoordinator {
    public <V> CompletionDisposition complete(
            StreamingCache<V> cache,
            LoadAttempt attempt,
            CacheOutcome<V> outcome) {
        // BUG: the caller's attempt is discarded and reconstructed from current cache state.
        return cache.complete(cache.inferredActiveAttempt(), outcome);
    }
}
