package com.example.avp.opensearch.lambda;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Layer 1: Coarse-grained authorization service implementation.
 * Determines which entity types (OpenSearch indices) a user is permitted to search.
 *
 * Checks Redis cache first; on cache miss, calls AVP BatchIsAuthorized for each
 * entity type and caches the result with a 300-second TTL.
 *
 * On ANY error (AVP exception, JSON parsing, cache failure, etc.), returns an
 * empty set (fail-closed behavior).
 */
public class CoarseAuthorizationServiceImpl implements CoarseAuthorizationService {

    private static final Logger LOGGER = Logger.getLogger(CoarseAuthorizationServiceImpl.class.getName());

    private static final List<String> ENTITY_TYPES = List.of(
        "employees", "teams", "job-postings", "compensation"
    );

    private final AvpClient avpClient;
    private final RedisCacheClient redisCacheClient;
    private final ObjectMapper objectMapper;

    /**
     * Constructs a CoarseAuthorizationServiceImpl with the given AVP client and Redis cache client.
     *
     * @param avpClient        the AVP client for BatchIsAuthorized calls
     * @param redisCacheClient the Redis cache client for caching authorization decisions
     */
    public CoarseAuthorizationServiceImpl(AvpClient avpClient, RedisCacheClient redisCacheClient) {
        this(avpClient, redisCacheClient, new ObjectMapper());
    }

    /**
     * Constructs a CoarseAuthorizationServiceImpl with the given dependencies (for testing).
     *
     * @param avpClient        the AVP client for BatchIsAuthorized calls
     * @param redisCacheClient the Redis cache client for caching authorization decisions
     * @param objectMapper     the Jackson ObjectMapper for JSON serialization
     */
    public CoarseAuthorizationServiceImpl(AvpClient avpClient, RedisCacheClient redisCacheClient, ObjectMapper objectMapper) {
        this.avpClient = avpClient;
        this.redisCacheClient = redisCacheClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> getPermittedEntityTypes(String userId, String role) {
        try {
            // Check Redis cache first
            String cacheKey = RedisCacheClientImpl.buildLayer1Key(userId);
            Optional<String> cachedValue = redisCacheClient.get(cacheKey);

            if (cachedValue.isPresent()) {
                return parseCachedEntityTypes(cachedValue.get());
            }

            // Cache miss: call AVP for each entity type
            Set<String> permitted = avpClient.checkEntityTypeAccess(userId, role, ENTITY_TYPES);

            // Cache the result
            cacheEntityTypes(cacheKey, permitted);

            return permitted;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during coarse authorization for user " + userId + ", returning empty set (fail-closed)", e);
            return Collections.emptySet();
        }
    }

    /**
     * Parses a cached JSON array of entity type strings into a Set.
     *
     * @param json the cached JSON string (e.g., ["employees","teams"])
     * @return the set of entity type strings
     * @throws JsonProcessingException if the JSON is malformed
     */
    private Set<String> parseCachedEntityTypes(String json) throws JsonProcessingException {
        List<String> entityTypes = objectMapper.readValue(json, new TypeReference<List<String>>() {});
        return new HashSet<>(entityTypes);
    }

    /**
     * Caches the permitted entity types as a JSON array string with the default TTL.
     *
     * @param cacheKey  the Redis cache key
     * @param permitted the set of permitted entity types
     */
    private void cacheEntityTypes(String cacheKey, Set<String> permitted) {
        try {
            String json = objectMapper.writeValueAsString(List.copyOf(permitted));
            redisCacheClient.set(cacheKey, json, RedisCacheClientImpl.getDefaultTtlSeconds());
        } catch (JsonProcessingException e) {
            LOGGER.log(Level.WARNING, "Failed to serialize entity types for caching", e);
            // Non-fatal: we still have the result from AVP
        }
    }
}
