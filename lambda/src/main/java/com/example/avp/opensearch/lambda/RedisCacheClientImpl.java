package com.example.avp.opensearch.lambda;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisException;

/**
 * Redis-backed implementation of RedisCacheClient using Jedis.
 * Implements graceful fallback: on any Redis failure, operations are silently
 * bypassed (get returns empty, set is skipped) rather than throwing exceptions.
 */
public class RedisCacheClientImpl implements RedisCacheClient {

    private static final Logger LOGGER = Logger.getLogger(RedisCacheClientImpl.class.getName());
    private static final int CONNECTION_TIMEOUT_MS = 2000;
    private static final int DEFAULT_TTL_SECONDS = 300;

    private final JedisPool jedisPool;

    /**
     * Constructs a RedisCacheClientImpl using environment variables REDIS_ENDPOINT and REDIS_PORT.
     */
    public RedisCacheClientImpl() {
        this(System.getenv("REDIS_ENDPOINT"), parsePort(System.getenv("REDIS_PORT")));
    }

    /**
     * Constructs a RedisCacheClientImpl with the given endpoint and port.
     * Uses TLS for encryption in transit.
     *
     * @param endpoint the Redis host endpoint
     * @param port     the Redis port
     */
    public RedisCacheClientImpl(String endpoint, int port) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(4);
        poolConfig.setMinIdle(1);
        // Use TLS (ssl=true) for encryption in transit
        this.jedisPool = new JedisPool(poolConfig, endpoint, port, CONNECTION_TIMEOUT_MS, null, true);
    }

    /**
     * Constructs a RedisCacheClientImpl with a pre-configured JedisPool (for testing).
     *
     * @param jedisPool the Jedis connection pool
     */
    public RedisCacheClientImpl(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    @Override
    public Optional<String> get(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            String value = jedis.get(key);
            if (value != null) {
                return Optional.of(value);
            }
            return Optional.empty();
        } catch (JedisException e) {
            LOGGER.log(Level.WARNING, "Redis GET failed for key: " + key + ", bypassing cache", e);
            return Optional.empty();
        }
    }

    @Override
    public void set(String key, String value, int ttlSeconds) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setex(key, ttlSeconds, value);
        } catch (JedisException e) {
            LOGGER.log(Level.WARNING, "Redis SET failed for key: " + key + ", bypassing cache", e);
        }
    }

    /**
     * Builds a Layer 1 cache key for the given user ID.
     * Format: "l1:{userId}"
     *
     * @param userId the user's unique identifier
     * @return the Layer 1 cache key
     */
    public static String buildLayer1Key(String userId) {
        return "l1:" + userId;
    }

    /**
     * Builds a Layer 3 cache key for the given user, entity type, and resource ID.
     * Format: "l3:{userId}:{entityType}:{resourceId}"
     *
     * @param userId     the user's unique identifier
     * @param entityType the entity type (e.g., "employees", "teams")
     * @param resourceId the specific resource identifier
     * @return the Layer 3 cache key
     */
    public static String buildLayer3Key(String userId, String entityType, String resourceId) {
        return "l3:" + userId + ":" + entityType + ":" + resourceId;
    }

    /**
     * Returns the default TTL in seconds (300 = 5 minutes).
     *
     * @return the default TTL value
     */
    public static int getDefaultTtlSeconds() {
        return DEFAULT_TTL_SECONDS;
    }

    private static int parsePort(String portStr) {
        if (portStr == null || portStr.isBlank()) {
            return 6379; // Default Redis port
        }
        try {
            return Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            LOGGER.log(Level.WARNING, "Invalid REDIS_PORT value: " + portStr + ", using default 6379");
            return 6379;
        }
    }
}
