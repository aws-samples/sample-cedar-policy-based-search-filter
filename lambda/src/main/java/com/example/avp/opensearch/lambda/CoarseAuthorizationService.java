package com.example.avp.opensearch.lambda;

import java.util.Set;

/**
 * Layer 1: Coarse-grained authorization service.
 * Determines which entity types (OpenSearch indices) a user is permitted to search.
 */
public interface CoarseAuthorizationService {

    /**
     * Returns the set of entity types the given user is permitted to search.
     * On error or timeout, returns an empty set (fail-closed).
     *
     * @param userId the authenticated user's identifier
     * @param role   the user's role from Cognito claims
     * @return set of permitted entity type names (e.g., "employees", "teams")
     */
    Set<String> getPermittedEntityTypes(String userId, String role);
}
