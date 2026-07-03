package com.example.avp.opensearch.lambda;

import java.util.List;
import java.util.Map;

/**
 * Represents the search response returned to the HR Portal frontend.
 *
 * @param resultsByEntityType results grouped by entity type (e.g., "employees", "teams")
 * @param totalResults        the total number of results across all entity types
 * @param error               null on success; error message on failure
 */
public record SearchResponse(
    Map<String, List<Map<String, Object>>> resultsByEntityType,
    int totalResults,
    String error
) {}
