package dev.bat.examples.cache;

public sealed interface CacheOutcome<V>
        permits CacheOutcome.Found, CacheOutcome.Missing {
    record Found<V>(V value) implements CacheOutcome<V> {}
    record Missing<V>() implements CacheOutcome<V> {}
}
