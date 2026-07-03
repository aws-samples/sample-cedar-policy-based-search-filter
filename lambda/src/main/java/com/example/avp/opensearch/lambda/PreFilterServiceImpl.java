package com.example.avp.opensearch.lambda;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of PreFilterService that builds OpenSearch bool filter clauses
 * from a user's organizational context.
 *
 * For each non-empty membership list in the context, a corresponding filter entry
 * is added to the result map. Multiple filters are combined with AND logic
 * (each becomes a separate terms clause in the OpenSearch bool filter).
 *
 * Mapping:
 * - regions → "region" filter
 * - departments → "department" filter
 *
 * Empty membership lists produce no filter entry for that dimension.
 * If all lists are empty, returns an empty map (no filters apply).
 */
public class PreFilterServiceImpl implements PreFilterService {

    @Override
    public Map<String, Object> buildFilter(OrganizationalContext context) {
        if (context == null) {
            return Map.of();
        }

        Map<String, Object> filters = new LinkedHashMap<>();

        List<String> regions = context.regions();
        if (regions != null && !regions.isEmpty()) {
            filters.put("region", List.copyOf(regions));
        }

        List<String> departments = context.departments();
        if (departments != null && !departments.isEmpty()) {
            filters.put("department", List.copyOf(departments));
        }

        return filters;
    }
}
