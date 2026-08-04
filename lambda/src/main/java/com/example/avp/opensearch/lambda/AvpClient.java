package com.example.avp.opensearch.lambda;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wrapper around the AVP SDK BatchIsAuthorized API.
 * Provides typed methods for Layer 1, Layer 3, and field-masking authorization calls.
 */
public interface AvpClient {

    /**
     * Layer 1: Checks which entity types the user is permitted to search.
     * Timeout: 5 seconds.
     *
     * @param userId     the user's identifier
     * @param role       the user's role
     * @param entityTypes the entity types to check (e.g., "employees", "teams", "job-postings", "compensation")
     * @return the set of entity types for which AVP returned ALLOW
     */
    Set<String> checkEntityTypeAccess(String userId, String role, List<String> entityTypes);

    /**
     * Layer 3: Checks fine-grained access for individual results.
     * Each resource is evaluated with its own per-record context attributes,
     * enabling truly per-record authorization decisions.
     * Timeout: 10 seconds.
     *
     * @param userId          the user's identifier
     * @param role            the user's role
     * @param userContext     user-level context attributes (region, department, managerId)
     * @param entityType      the entity type of the results
     * @param resourceIds     the resource identifiers to check
     * @param resourceContexts per-record context maps (one per resourceId, same order)
     * @return the set of resource IDs for which AVP returned ALLOW
     */
    Set<String> checkResultAccess(
        String userId,
        String role,
        Map<String, String> userContext,
        String entityType,
        List<String> resourceIds,
        List<Map<String, String>> resourceContexts
    );

    /**
     * Field masking: Checks field-level visibility for a user and entity type.
     * Timeout: 10 seconds.
     *
     * @param userId     the user's identifier
     * @param role       the user's role
     * @param entityType the parent entity type
     * @param fieldNames the field names to check
     * @return the set of field names for which AVP returned ALLOW
     */
    Set<String> checkFieldAccess(String userId, String role, String entityType, List<String> fieldNames);
}
