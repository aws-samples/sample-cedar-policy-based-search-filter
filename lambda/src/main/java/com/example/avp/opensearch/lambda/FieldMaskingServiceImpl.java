package com.example.avp.opensearch.lambda;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of FieldMaskingService that calls AVP with action "ViewField"
 * and resource type "Field" to determine field-level visibility.
 *
 * Uses a permit-then-forbid pattern: by default all fields are visible;
 * only specific fields are forbidden for specific roles.
 *
 * Optimization: Since field access decisions depend only on (userId/role, entityType, fieldName)
 * and not on the specific result, checkFieldAccess is called once per entity type
 * and the same masking is applied to all results.
 */
public class FieldMaskingServiceImpl implements FieldMaskingService {

    private static final String MASK_VALUE = "****";
    private static final List<String> SENSITIVE_FIELDS = List.of("phoneNumber", "salary", "bonus");

    private final AvpClient avpClient;

    /**
     * Constructs a FieldMaskingServiceImpl with the given AVP client.
     *
     * @param avpClient the AVP client for field-level authorization checks
     */
    public FieldMaskingServiceImpl(AvpClient avpClient) {
        this.avpClient = avpClient;
    }

    @Override
    public List<Map<String, Object>> maskFields(
            String userId,
            String role,
            String entityType,
            List<Map<String, Object>> results) {

        if (results == null || results.isEmpty()) {
            return results;
        }

        Set<String> allowedFields;
        try {
            // Collect all unique field names across all results
            List<String> allFieldNames = collectFieldNames(results);

            if (allFieldNames.isEmpty()) {
                return results;
            }

            // Call AVP once per entity type (optimization: decision is same for all results)
            allowedFields = avpClient.checkFieldAccess(userId, role, entityType, allFieldNames);
        } catch (Exception e) {
            // Fail-closed: mask all sensitive fields on any error
            return maskSensitiveFields(results);
        }

        // Apply masking: replace DENY fields with "****"
        return applyMasking(results, allowedFields);
    }

    /**
     * Collects all unique field names from the results list.
     */
    private List<String> collectFieldNames(List<Map<String, Object>> results) {
        // Use a linked approach to maintain order and uniqueness
        Map<String, Boolean> seen = new HashMap<>();
        List<String> fieldNames = new ArrayList<>();

        for (Map<String, Object> result : results) {
            for (String key : result.keySet()) {
                if (!seen.containsKey(key)) {
                    seen.put(key, Boolean.TRUE);
                    fieldNames.add(key);
                }
            }
        }
        return fieldNames;
    }

    /**
     * Applies field masking based on the set of allowed fields.
     * Fields NOT in the allowed set have their values replaced with "****".
     */
    private List<Map<String, Object>> applyMasking(
            List<Map<String, Object>> results, Set<String> allowedFields) {

        List<Map<String, Object>> maskedResults = new ArrayList<>();

        for (Map<String, Object> result : results) {
            Map<String, Object> maskedResult = new HashMap<>();
            for (Map.Entry<String, Object> entry : result.entrySet()) {
                if (allowedFields.contains(entry.getKey())) {
                    maskedResult.put(entry.getKey(), entry.getValue());
                } else {
                    maskedResult.put(entry.getKey(), MASK_VALUE);
                }
            }
            maskedResults.add(maskedResult);
        }

        return maskedResults;
    }

    /**
     * Fail-closed behavior: masks all sensitive fields when AVP is unreachable or returns an error.
     * Sensitive fields: phoneNumber, salary, bonus.
     */
    private List<Map<String, Object>> maskSensitiveFields(List<Map<String, Object>> results) {
        List<Map<String, Object>> maskedResults = new ArrayList<>();

        for (Map<String, Object> result : results) {
            Map<String, Object> maskedResult = new HashMap<>(result);
            for (String sensitiveField : SENSITIVE_FIELDS) {
                if (maskedResult.containsKey(sensitiveField)) {
                    maskedResult.put(sensitiveField, MASK_VALUE);
                }
            }
            maskedResults.add(maskedResult);
        }

        return maskedResults;
    }
}
