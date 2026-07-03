package com.example.avp.opensearch.lambda;

import java.util.List;
import java.util.Map;

/**
 * Layer 3: Fine-grained authorization service.
 * Individually authorizes each candidate search result against full Cedar policies,
 * batching up to 30 authorization requests per AVP API call.
 */
public interface FineGrainedAuthorizationService {

    /**
     * Filters candidate results to only those the user is authorized to view.
     * Results are partitioned into batches of at most 30 for AVP evaluation.
     * On error, returns an empty list (fail-closed).
     *
     * @param userId     the authenticated user's identifier
     * @param role       the user's role
     * @param context    the user's organizational context (region, department, managerId)
     * @param entityType the entity type of the candidate results
     * @param candidates the candidate results from OpenSearch
     * @return the filtered list containing only authorized results
     */
    List<Map<String, Object>> filterAuthorized(
        String userId,
        String role,
        OrganizationalContext context,
        String entityType,
        List<Map<String, Object>> candidates
    );
}
