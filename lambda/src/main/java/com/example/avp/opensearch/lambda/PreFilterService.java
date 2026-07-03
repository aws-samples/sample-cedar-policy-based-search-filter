package com.example.avp.opensearch.lambda;

import java.util.Map;

/**
 * Layer 2: Pre-filter service.
 * Builds OpenSearch filter clauses from the user's organizational context
 * to reduce the result set before fine-grained evaluation.
 */
public interface PreFilterService {

    /**
     * Builds an OpenSearch bool filter from the user's organizational context.
     * Non-empty membership lists produce terms filter clauses combined with AND logic.
     * Empty membership lists produce no filter clause for that dimension.
     *
     * @param context the user's organizational memberships
     * @return a map representing the OpenSearch filter structure, or an empty map if no filters apply
     */
    Map<String, Object> buildFilter(OrganizationalContext context);
}
