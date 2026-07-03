package com.example.avp.opensearch.lambda;

import java.util.List;
import java.util.Map;

/**
 * Field masking service.
 * Checks field-level visibility via AVP and replaces restricted field values with "****".
 * Uses a permit-then-forbid pattern in Cedar policies.
 */
public interface FieldMaskingService {

    /**
     * Masks fields that the user is not permitted to view.
     * Fields with a DENY decision are replaced with "****".
     * On error, masks all sensitive fields (fail-closed).
     *
     * @param userId     the authenticated user's identifier
     * @param role       the user's role
     * @param entityType the entity type of the results
     * @param results    the authorized results to apply field masking to
     * @return results with denied fields replaced by "****"
     */
    List<Map<String, Object>> maskFields(
        String userId,
        String role,
        String entityType,
        List<Map<String, Object>> results
    );
}
