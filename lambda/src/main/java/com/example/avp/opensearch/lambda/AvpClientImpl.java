package com.example.avp.opensearch.lambda;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import software.amazon.awssdk.services.verifiedpermissions.VerifiedPermissionsClient;
import software.amazon.awssdk.services.verifiedpermissions.model.ActionIdentifier;
import software.amazon.awssdk.services.verifiedpermissions.model.AttributeValue;
import software.amazon.awssdk.services.verifiedpermissions.model.BatchIsAuthorizedInputItem;
import software.amazon.awssdk.services.verifiedpermissions.model.BatchIsAuthorizedOutputItem;
import software.amazon.awssdk.services.verifiedpermissions.model.BatchIsAuthorizedRequest;
import software.amazon.awssdk.services.verifiedpermissions.model.BatchIsAuthorizedResponse;
import software.amazon.awssdk.services.verifiedpermissions.model.ContextDefinition;
import software.amazon.awssdk.services.verifiedpermissions.model.Decision;
import software.amazon.awssdk.services.verifiedpermissions.model.EntitiesDefinition;
import software.amazon.awssdk.services.verifiedpermissions.model.EntityIdentifier;
import software.amazon.awssdk.services.verifiedpermissions.model.EntityItem;
import software.amazon.awssdk.services.verifiedpermissions.model.VerifiedPermissionsException;

/**
 * Implementation of AvpClient that wraps the AVP SDK BatchIsAuthorized API.
 * Provides typed methods for Layer 1, Layer 3, and field-masking authorization calls
 * with configurable timeouts and fail-closed error handling.
 */
public class AvpClientImpl implements AvpClient {

    private static final String NAMESPACE = "HRPortal";
    private static final String USER_ENTITY_TYPE = NAMESPACE + "::User";
    private static final String ACTION_TYPE = NAMESPACE + "::Action";
    private static final String ENTITY_TYPE_RESOURCE = NAMESPACE + "::EntityType";
    private static final String FIELD_RESOURCE_TYPE = NAMESPACE + "::Field";

    private static final String ACTION_SEARCH = "Search";
    private static final String ACTION_VIEW = "View";
    private static final String ACTION_VIEW_FIELD = "ViewField";

    private static final Duration LAYER1_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration LAYER3_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration FIELD_MASKING_TIMEOUT = Duration.ofSeconds(10);

    private final VerifiedPermissionsClient avpClient;
    private final String policyStoreId;

    /**
     * Constructs an AvpClientImpl with the given AVP SDK client and policy store ID.
     *
     * @param avpClient     the AWS Verified Permissions SDK client
     * @param policyStoreId the policy store identifier
     */
    public AvpClientImpl(VerifiedPermissionsClient avpClient, String policyStoreId) {
        this.avpClient = avpClient;
        this.policyStoreId = policyStoreId;
    }

    @Override
    public Set<String> checkEntityTypeAccess(String userId, String role, List<String> entityTypes) {
        List<BatchIsAuthorizedInputItem> requests = new ArrayList<>();

        for (String entityType : entityTypes) {
            BatchIsAuthorizedInputItem input = BatchIsAuthorizedInputItem.builder()
                .principal(EntityIdentifier.builder()
                    .entityType(USER_ENTITY_TYPE)
                    .entityId(userId)
                    .build())
                .action(ActionIdentifier.builder()
                    .actionType(ACTION_TYPE)
                    .actionId(ACTION_SEARCH)
                    .build())
                .resource(EntityIdentifier.builder()
                    .entityType(ENTITY_TYPE_RESOURCE)
                    .entityId(entityType)
                    .build())
                .build();
            requests.add(input);
        }

        EntityItem userEntity = buildUserEntity(userId, role, null, null, null);

        BatchIsAuthorizedResponse response = callBatchIsAuthorized(requests, List.of(userEntity), LAYER1_TIMEOUT);

        Set<String> allowed = new HashSet<>();
        List<BatchIsAuthorizedOutputItem> results = response.results();
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).decision() == Decision.ALLOW) {
                allowed.add(entityTypes.get(i));
            }
        }
        return allowed;
    }

    @Override
    public Set<String> checkResultAccess(
            String userId,
            String role,
            Map<String, String> userContext,
            String entityType,
            List<String> resourceIds,
            List<Map<String, String>> resourceContexts) {

        List<BatchIsAuthorizedInputItem> requests = new ArrayList<>();

        for (int i = 0; i < resourceIds.size(); i++) {
            Map<String, String> perRecordContext = resourceContexts.get(i);

            Map<String, AttributeValue> requestContext = new HashMap<>();
            if (perRecordContext.containsKey("resourceRegion")) {
                requestContext.put("resourceRegion", AttributeValue.builder()
                    .string(perRecordContext.get("resourceRegion"))
                    .build());
            }
            if (perRecordContext.containsKey("resourceDepartment")) {
                requestContext.put("resourceDepartment", AttributeValue.builder()
                    .string(perRecordContext.get("resourceDepartment"))
                    .build());
            }
            if (perRecordContext.containsKey("resourceManagerId")) {
                requestContext.put("resourceManagerId", AttributeValue.builder()
                    .string(perRecordContext.get("resourceManagerId"))
                    .build());
            }

            BatchIsAuthorizedInputItem.Builder inputBuilder = BatchIsAuthorizedInputItem.builder()
                .principal(EntityIdentifier.builder()
                    .entityType(USER_ENTITY_TYPE)
                    .entityId(userId)
                    .build())
                .action(ActionIdentifier.builder()
                    .actionType(ACTION_TYPE)
                    .actionId(ACTION_VIEW)
                    .build())
                .resource(EntityIdentifier.builder()
                    .entityType(ENTITY_TYPE_RESOURCE)
                    .entityId(entityType)
                    .build());

            if (!requestContext.isEmpty()) {
                inputBuilder.context(ContextDefinition.builder()
                    .contextMap(requestContext)
                    .build());
            }

            requests.add(inputBuilder.build());
        }

        String userRegion = userContext.getOrDefault("region", "");
        String userDepartment = userContext.getOrDefault("department", "");
        String userManagerId = userContext.getOrDefault("managerId", "");
        EntityItem userEntity = buildUserEntity(userId, role, userRegion, userDepartment, userManagerId);

        BatchIsAuthorizedResponse response = callBatchIsAuthorized(requests, List.of(userEntity), LAYER3_TIMEOUT);

        Set<String> allowed = new HashSet<>();
        List<BatchIsAuthorizedOutputItem> results = response.results();
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).decision() == Decision.ALLOW) {
                allowed.add(resourceIds.get(i));
            }
        }
        return allowed;
    }

    @Override
    public Set<String> checkFieldAccess(String userId, String role, String entityType, List<String> fieldNames) {
        List<BatchIsAuthorizedInputItem> requests = new ArrayList<>();

        for (String fieldName : fieldNames) {
            BatchIsAuthorizedInputItem input = BatchIsAuthorizedInputItem.builder()
                .principal(EntityIdentifier.builder()
                    .entityType(USER_ENTITY_TYPE)
                    .entityId(userId)
                    .build())
                .action(ActionIdentifier.builder()
                    .actionType(ACTION_TYPE)
                    .actionId(ACTION_VIEW_FIELD)
                    .build())
                .resource(EntityIdentifier.builder()
                    .entityType(FIELD_RESOURCE_TYPE)
                    .entityId(fieldName)
                    .build())
                .build();
            requests.add(input);
        }

        EntityItem userEntity = buildUserEntity(userId, role, null, null, null);

        BatchIsAuthorizedResponse response = callBatchIsAuthorized(requests, List.of(userEntity), FIELD_MASKING_TIMEOUT);

        Set<String> allowed = new HashSet<>();
        List<BatchIsAuthorizedOutputItem> results = response.results();
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).decision() == Decision.ALLOW) {
                allowed.add(fieldNames.get(i));
            }
        }
        return allowed;
    }

    /**
     * Calls AVP BatchIsAuthorized with the given requests and timeout.
     * On SDK exceptions or timeout, throws a RuntimeException for fail-closed handling by callers.
     */
    private BatchIsAuthorizedResponse callBatchIsAuthorized(
            List<BatchIsAuthorizedInputItem> requests,
            List<EntityItem> entities,
            Duration timeout) {

        try {
            BatchIsAuthorizedRequest.Builder requestBuilder = BatchIsAuthorizedRequest.builder()
                .policyStoreId(policyStoreId)
                .requests(requests);

            if (entities != null && !entities.isEmpty()) {
                requestBuilder.entities(EntitiesDefinition.builder()
                    .entityList(entities)
                    .build());
            }

            return avpClient.batchIsAuthorized(requestBuilder.build());
        } catch (VerifiedPermissionsException e) {
            throw new AvpAuthorizationException(
                "AVP BatchIsAuthorized call failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new AvpAuthorizationException(
                "Unexpected error during AVP BatchIsAuthorized call: " + e.getMessage(), e);
        }
    }

    /**
     * Builds an EntityItem representing a user with their attributes.
     * All attributes are required by the Cedar schema, so empty strings are used as defaults.
     */
    private EntityItem buildUserEntity(
            String userId, String role, String region, String department, String managerId) {

        Map<String, AttributeValue> attributes = new HashMap<>();
        attributes.put("role", AttributeValue.builder().string(role != null ? role : "").build());
        attributes.put("region", AttributeValue.builder().string(region != null ? region : "").build());
        attributes.put("department", AttributeValue.builder().string(department != null ? department : "").build());
        attributes.put("managerId", AttributeValue.builder().string(managerId != null ? managerId : "").build());

        return EntityItem.builder()
            .identifier(EntityIdentifier.builder()
                .entityType(USER_ENTITY_TYPE)
                .entityId(userId)
                .build())
            .attributes(attributes)
            .build();
    }

    /**
     * Returns the configured Layer 1 timeout.
     */
    public Duration getLayer1Timeout() {
        return LAYER1_TIMEOUT;
    }

    /**
     * Returns the configured Layer 3 timeout.
     */
    public Duration getLayer3Timeout() {
        return LAYER3_TIMEOUT;
    }

    /**
     * Returns the configured field masking timeout.
     */
    public Duration getFieldMaskingTimeout() {
        return FIELD_MASKING_TIMEOUT;
    }
}
