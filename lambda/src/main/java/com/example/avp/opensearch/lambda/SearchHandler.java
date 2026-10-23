package com.example.avp.opensearch.lambda;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.verifiedpermissions.VerifiedPermissionsClient;

/**
 * Lambda entry point for the HR Portal search service.
 * Orchestrates the three-layer authorization pipeline:
 * Layer 1 (Coarse) → Layer 2 (Pre-Filter) → OpenSearch query → Layer 3 (Fine-Grained) → Field Masking.
 *
 * Implements fail-closed behavior: on any unhandled error, returns empty results or a 500 error.
 */
public class SearchHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Logger LOGGER = Logger.getLogger(SearchHandler.class.getName());
    private static final Map<String, String> CORS_HEADERS = Map.of(
        "Content-Type", "application/json",
        "Access-Control-Allow-Origin", "*",
        "Access-Control-Allow-Headers", "Content-Type,Authorization",
        "Access-Control-Allow-Methods", "POST,OPTIONS"
    );
    /**
     * Roles recognized by the authorization model. Requests carrying any other
     * role value (or none) are denied. Keep in sync with the Cedar policies and
     * the users provisioned in AuthConstruct.
     */
    private static final Set<String> KNOWN_ROLES =
        Set.of("Employee", "USHRManager", "GlobalHRAdmin", "Recruiter");
    private final ObjectMapper objectMapper;
    private final CoarseAuthorizationService coarseAuthorizationService;
    private final AuthorizationContextService authorizationContextService;
    private final PreFilterService preFilterService;
    private final OpenSearchClient openSearchClient;
    private final FineGrainedAuthorizationService fineGrainedAuthorizationService;
    private final FieldMaskingService fieldMaskingService;

    /**
     * Default constructor used by AWS Lambda runtime.
     * Instantiates all service dependencies from environment variables.
     */
    public SearchHandler() {
        this.objectMapper = new ObjectMapper();

        String policyStoreId = System.getenv("POLICY_STORE_ID");
        VerifiedPermissionsClient vpClient = VerifiedPermissionsClient.create();
        AvpClient avpClient = new AvpClientImpl(vpClient, policyStoreId);
        RedisCacheClient redisCacheClient = new RedisCacheClientImpl();

        this.coarseAuthorizationService = new CoarseAuthorizationServiceImpl(avpClient, redisCacheClient);
        this.authorizationContextService = new AuthorizationContextServiceImpl();
        this.preFilterService = new PreFilterServiceImpl();
        this.openSearchClient = new OpenSearchClientImpl();
        this.fineGrainedAuthorizationService = new FineGrainedAuthorizationServiceImpl(avpClient, redisCacheClient);
        this.fieldMaskingService = new FieldMaskingServiceImpl(avpClient);
    }

    /**
     * Constructor for testing with injected dependencies.
     */
    public SearchHandler(
            ObjectMapper objectMapper,
            CoarseAuthorizationService coarseAuthorizationService,
            AuthorizationContextService authorizationContextService,
            PreFilterService preFilterService,
            OpenSearchClient openSearchClient,
            FineGrainedAuthorizationService fineGrainedAuthorizationService,
            FieldMaskingService fieldMaskingService) {
        this.objectMapper = objectMapper;
        this.coarseAuthorizationService = coarseAuthorizationService;
        this.authorizationContextService = authorizationContextService;
        this.preFilterService = preFilterService;
        this.openSearchClient = openSearchClient;
        this.fineGrainedAuthorizationService = fineGrainedAuthorizationService;
        this.fieldMaskingService = fieldMaskingService;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            // 1. Parse request body as SearchRequest
            SearchRequest searchRequest = parseSearchRequest(event.getBody());
            String query = searchRequest.query();

            if (query == null || query.isBlank()) {
                return buildResponse(200, new SearchResponse(Collections.emptyMap(), 0, null));
            }

            // 2. Extract user identity from Cognito claims
            String userId = extractUserId(event);
            String role = extractRole(event);
            // Fail closed on missing identity or unrecognized role
            if (userId == null || role == null || !KNOWN_ROLES.contains(role)) {
                LOGGER.warning("Rejecting search request with missing identity or unrecognized role");
                return buildResponse(200, new SearchResponse(Collections.emptyMap(), 0, null));
            }
            LOGGER.info(() -> "Processing search request for role=" + role);

            // 3. Layer 1: Coarse-grained authorization - get permitted entity types
            Set<String> permittedEntityTypes = coarseAuthorizationService.getPermittedEntityTypes(userId, role);
            LOGGER.fine(() -> "Permitted entity types: " + permittedEntityTypes);

            // 4. If empty, return empty results
            if (permittedEntityTypes.isEmpty()) {
                return buildResponse(200, new SearchResponse(Collections.emptyMap(), 0, null));
            }

            // 5. Get organizational context (fail-closed on error)
            OrganizationalContext orgContext;
            try {
                orgContext = authorizationContextService.getOrganizationalContext(userId, role);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "AuthorizationContextService error, failing closed", e);
                return buildResponse(200, new SearchResponse(Collections.emptyMap(), 0, null));
            }

            // 6. Layer 2: Build pre-filter from organizational context
            Map<String, Object> filter = preFilterService.buildFilter(orgContext);

            // 7. Execute OpenSearch query with pre-filters
            Map<String, List<Map<String, Object>>> searchResults = openSearchClient.search(query, permittedEntityTypes, filter);
            LOGGER.fine(() -> "OpenSearch returned " + searchResults.size() + " entity types");

            // 8. Layer 3: Fine-grained authorization per entity type
            Map<String, List<Map<String, Object>>> filteredResults = new HashMap<>();
            for (Map.Entry<String, List<Map<String, Object>>> entry : searchResults.entrySet()) {
                String entityType = entry.getKey();
                List<Map<String, Object>> candidates = entry.getValue();

                List<Map<String, Object>> authorized = fineGrainedAuthorizationService.filterAuthorized(
                    userId, role, orgContext, entityType, candidates);

                if (!authorized.isEmpty()) {
                    filteredResults.put(entityType, authorized);
                }
            }

            // 9. Field masking per entity type
            Map<String, List<Map<String, Object>>> maskedResults = new HashMap<>();
            for (Map.Entry<String, List<Map<String, Object>>> entry : filteredResults.entrySet()) {
                String entityType = entry.getKey();
                List<Map<String, Object>> results = entry.getValue();

                List<Map<String, Object>> masked = fieldMaskingService.maskFields(userId, role, entityType, results);
                maskedResults.put(entityType, masked);
            }

            // 10. Build response with total count
            int totalResults = maskedResults.values().stream()
                .mapToInt(List::size)
                .sum();

            SearchResponse searchResponse = new SearchResponse(maskedResults, totalResults, null);

            // 11. Return 200 with JSON body and CORS headers
            return buildResponse(200, searchResponse);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unhandled exception in SearchHandler", e);
            SearchResponse errorResponse = new SearchResponse(Collections.emptyMap(), 0, e.getMessage());
            return buildResponse(500, errorResponse);
        }
    }

    /**
     * Parses the request body into a SearchRequest.
     */
    private SearchRequest parseSearchRequest(String body) throws Exception {
        if (body == null || body.isBlank()) {
            return new SearchRequest("");
        }
        return objectMapper.readValue(body, SearchRequest.class);
    }

    /**
     * Extracts the user ID from Cognito claims in the request context.
     * Looks for "sub" or "cognito:username" in the authorizer claims.
     * 
     * @return the user ID, or null if no identity claim is present
     */
    @SuppressWarnings("unchecked")
    private String extractUserId(APIGatewayProxyRequestEvent event) {
        Map<String, Object> claims = getCognitoClaims(event);
        if (claims == null) {
            return null;
        }

        Object sub = claims.get("sub");
        if (sub != null  && !sub.toString().isBlank()) {
            return sub.toString();
        }

        Object username = claims.get("cognito:username");
        if (username != null && !username.toString().isBlank()) {
            return username.toString();
        }

        return null;
    }

    /**
     * Extracts the user role from Cognito claims in the request context.
     * Looks for "custom:role" in the authorizer claims.
     */
    @SuppressWarnings("unchecked")
    private String extractRole(APIGatewayProxyRequestEvent event) {
        Map<String, Object> claims = getCognitoClaims(event);
        if (claims == null) {
            return null;
        }

        Object role = claims.get("custom:role");
        return role != null ? role.toString() : null;
    }

    /**
     * Retrieves the Cognito claims map from the API Gateway request context.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getCognitoClaims(APIGatewayProxyRequestEvent event) {
        if (event.getRequestContext() == null) {
            return null;
        }

        Map<String, Object> authorizer = event.getRequestContext().getAuthorizer();
        if (authorizer == null) {
            return null;
        }

        Object claims = authorizer.get("claims");
        if (claims instanceof Map) {
            return (Map<String, Object>) claims;
        }

        return null;
    }

    /**
     * Builds an API Gateway proxy response with the given status code and body.
     */
    private APIGatewayProxyResponseEvent buildResponse(int statusCode, SearchResponse searchResponse) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        response.setHeaders(CORS_HEADERS);

        try {
            response.setBody(objectMapper.writeValueAsString(searchResponse));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to serialize response", e);
            response.setBody("{\"resultsByEntityType\":{},\"totalResults\":0,\"error\":\"Internal serialization error\"}");
        }

        return response;
    }
}
