package dev.bat.examples.cache;

public final class StreamingCacheRootProof {
    public static void main(String[] args) {
        var cache = new StreamingCache<String>();
        var coordinator = new RetryCoordinator();
        var oldAttempt = cache.beginRefresh();
        var activeAttempt = cache.beginRefresh();

        var stale = coordinator.complete(cache, oldAttempt, new CacheOutcome.Found<>("old"));
        var staleRejected = stale == CompletionDisposition.STALE
                && cache.visible() instanceof CacheOutcome.Missing<?>;
        var missRemainedAValue = false;
        try {
            var committed = coordinator.complete(cache, activeAttempt, new CacheOutcome.Missing<>());
            missRemainedAValue = committed == CompletionDisposition.COMMITTED
                    && cache.visible() instanceof CacheOutcome.Missing<?>;
        } catch (java.util.NoSuchElementException expectedDomainFailureWasThrown) {
            missRemainedAValue = false;
        }

        if (!staleRejected) {
            fail("stale completion must be rejected without changing the visible result");
        }
        if (!missRemainedAValue) {
            fail("expected backend miss must remain a domain value");
        }
    }

    private static void fail(String fingerprint) {
        System.err.println("ROOT_PROOF_FAILURE: " + fingerprint);
        throw new AssertionError(fingerprint);
    }
}
