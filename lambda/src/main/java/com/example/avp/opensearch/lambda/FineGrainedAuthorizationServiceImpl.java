package com.example.avp.opensearch.lambda;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Layer 3: Fine-grained authorization service implementation.
 * Individually authorizes each candidate search result against full Cedar policies,
 * batching up to 30 authorization requests per AVP API call.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>For each candidate, extract a resource ID (employeeId, teamId, postingId, compensationId, or _id)</li>
 *   <li>Check Redis cache: cached "ALLOW" → include directly; cached "DENY" → skip</li>
 *   <li>Collect uncached results into batches of ≤30</li>
 *   <li>Call AvpClient.checkResultAccess() for each batch with context attributes</li>
 *   <li>Cache each decision with 300s TTL</li>
 *   <li>Return only results with ALLOW decisions</li>
 * </ol>
 *
 * <p>On any error, returns an empty list (fail-closed behavior).
 */
public class FineGrainedAuthorizationServiceImpl implements FineGrainedAuthorizationService {

    private static final Logger LOGGER = Logger.getLogger(FineGrainedAuthorizationServiceImpl.class.getName());
    private static final int BATCH_SIZE = 30;
    private static final int CACHE_TTL_SECONDS = 300;
    private static final String ALLOW = "ALLOW";
    private static final String DENY = "DENY";

    private final AvpClient avpClient;
    private final RedisCacheClient cacheClient;

    /**
     * Constructs a FineGrainedAuthorizationServiceImpl with the given AVP and cache clients.
     *
     * @param avpClient   the AVP client for authorization checks
     * @param cacheClient the Redis cache client for caching decisions
     */
    public FineGrainedAuthorizationServiceImpl(AvpClient avpClient, RedisCacheClient cacheClient) {
        this.avpClient = avpClient;
        this.cacheClient = cacheClient;
    }

    @Override
    public List<Map<String, Object>> filterAuthorized(
            String userId,
            String role,
            OrganizationalContext context,
            String entityType,
            List<Map<String, Object>> candidates) {

        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        try {
            List<Map<String, Object>> allowedResults = new ArrayList<>();
            List<Map<String, Object>> uncachedCandidates = new ArrayList<>();

            // Step 1 & 2: Check cache for each candidate
            for (Map<String, Object> candidate : candidates) {
                String resourceId = extractResourceId(candidate);
                String cacheKey = RedisCacheClientImpl.buildLayer3Key(userId, entityType, resourceId);

                Optional<String> cachedDecision = cacheClient.get(cacheKey);
                if (cachedDecision.isPresent()) {
                    if (ALLOW.equals(cachedDecision.get())) {
                        allowedResults.add(candidate);
                    }
                    // If DENY, skip (do not add to allowed or uncached)
                } else {
                    uncachedCandidates.add(candidate);
                }
            }

            // Step 3 & 4: Process uncached candidates in batches of ≤30
            List<List<Map<String, Object>>> batches = partitionIntoBatches(uncachedCandidates, BATCH_SIZE);

            for (List<Map<String, Object>> batch : batches) {
                List<String> resourceIds = new ArrayList<>();
                for (Map<String, Object> candidate : batch) {
                    resourceIds.add(extractResourceId(candidate));
                }

                // Build context map with user and resource context attributes
                Map<String, String> contextMap = buildContextMap(context, batch);

                Set<String> allowedIds = avpClient.checkResultAccess(
                    userId, role, contextMap, entityType, resourceIds);

                // Step 5 & 6: Cache each decision and collect allowed results
                for (int i = 0; i < batch.size(); i++) {
                    Map<String, Object> candidate = batch.get(i);
                    String resourceId = resourceIds.get(i);
                    String cacheKey = RedisCacheClientImpl.buildLayer3Key(userId, entityType, resourceId);

                    if (allowedIds.contains(resourceId)) {
                        cacheClient.set(cacheKey, ALLOW, CACHE_TTL_SECONDS);
                        allowedResults.add(candidate);
                    } else {
                        cacheClient.set(cacheKey, DENY, CACHE_TTL_SECONDS);
                    }
                }
            }

            return allowedResults;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Fine-grained authorization failed, returning empty set (fail-closed)", e);
            return List.of();
        }
    }

    /**
     * Extracts a resource ID from a candidate result map.
     * Checks for entity-specific ID fields in priority order, falling back to _id.
     *
     * @param candidate the candidate result map
     * @return the resource identifier string
     */
    private String extractResourceId(Map<String, Object> candidate) {
        String[] idFields = {"employeeId", "teamId", "postingId", "compensationId", "_id"};
        for (String field : idFields) {
            Object value = candidate.get(field);
            if (value != null) {
                return value.toString();
            }
        }
        return String.valueOf(candidate.hashCode());
    }

    /**
     * Builds the context map for AVP authorization requests.
     * Includes:
     * - "region": user's region (from OrganizationalContext)
     * - "department": user's department (from OrganizationalContext)
     * - "managerId": user's managerId (from OrganizationalContext roles, if available)
     * - "resourceRegion": result's region value
     * - "resourceDepartment": result's department value
     * - "resourceManagerId": result's managerId value
     *
     * @param context the user's organizational context
     * @param batch   the batch of candidate results
     * @return the context map for AVP calls
     */
    private Map<String, String> buildContextMap(OrganizationalContext context, List<Map<String, Object>> batch) {
        Map<String, String> contextMap = new HashMap<>();

        // User context attributes from OrganizationalContext
        if (context.regions() != null && !context.regions().isEmpty()) {
            contextMap.put("region", context.regions().get(0));
        }
        if (context.departments() != null && !context.departments().isEmpty()) {
            contextMap.put("department", context.departments().get(0));
        }
        // managerId is not a direct field on OrganizationalContext; pass empty if not available
        contextMap.put("managerId", "");

        // Resource context attributes from the batch results
        if (!batch.isEmpty()) {
            Map<String, Object> representative = batch.get(0);
            Object resourceRegion = representative.get("region");
            if (resourceRegion != null) {
                contextMap.put("resourceRegion", resourceRegion.toString());
            }
            Object resourceDepartment = representative.get("department");
            if (resourceDepartment != null) {
                contextMap.put("resourceDepartment", resourceDepartment.toString());
            }
            Object resourceManagerId = representative.get("managerId");
            if (resourceManagerId != null) {
                contextMap.put("resourceManagerId", resourceManagerId.toString());
            }
        }

        return contextMap;
    }

    /**
     * Partitions a list into sublists of at most the given batch size.
     *
     * @param items     the items to partition
     * @param batchSize the maximum size of each batch
     * @param <T>       the type of items
     * @return a list of batches, each containing at most batchSize items
     */
    private <T> List<List<T>> partitionIntoBatches(List<T> items, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += batchSize) {
            int end = Math.min(i + batchSize, items.size());
            batches.add(items.subList(i, end));
        }
        return batches;
    }
}
