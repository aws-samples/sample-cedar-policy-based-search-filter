package com.example.avp.opensearch.lambda;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client for executing search queries against the OpenSearch domain.
 */
public interface OpenSearchClient {

    /**
     * Executes a multi-index search query with optional filters.
     * Targets only the indices specified by the permitted entity types.
     *
     * @param query               the search term
     * @param permittedEntityTypes the set of entity types (indices) to search
     * @param filter              the pre-filter from Layer 2 (may be empty)
     * @return a map of entity type to list of matching documents
     */
    Map<String, List<Map<String, Object>>> search(
        String query,
        Set<String> permittedEntityTypes,
        Map<String, Object> filter
    );
}
