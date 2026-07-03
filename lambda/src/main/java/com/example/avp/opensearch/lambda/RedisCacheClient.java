package com.example.avp.opensearch.lambda;

import java.util.Optional;

/**
 * Client for caching authorization decisions in Redis.
 * Implements graceful fallback when Redis is unreachable (bypass cache).
 */
public interface RedisCacheClient {

    /**
     * Retrieves a cached value by key.
     * Returns empty if the key does not exist or Redis is unreachable.
     *
     * @param key the cache key
     * @return the cached value, or empty if not found or unavailable
     */
    Optional<String> get(String key);

    /**
     * Stores a value in the cache with the specified TTL.
     * Silently fails if Redis is unreachable.
     *
     * @param key          the cache key
     * @param value        the value to store
     * @param ttlSeconds   the time-to-live in seconds
     */
    void set(String key, String value, int ttlSeconds);
}
