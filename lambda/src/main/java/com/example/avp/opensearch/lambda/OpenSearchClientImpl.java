package com.example.avp.opensearch.lambda;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementation of {@link OpenSearchClient} that executes multi-index search
 * queries against an Amazon OpenSearch Service domain with SigV4 authentication.
 */
public class OpenSearchClientImpl implements OpenSearchClient {

    private static final String INDEX_PREFIX = "index-";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final String openSearchEndpoint;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Aws4Signer signer;
    private final Region region;
    private final DefaultCredentialsProvider credentialsProvider;

    /**
     * Creates an OpenSearchClientImpl using the OPENSEARCH_ENDPOINT environment variable
     * and AWS_REGION for SigV4 signing.
     */
    public OpenSearchClientImpl() {
        this(
            System.getenv("OPENSEARCH_ENDPOINT"),
            System.getenv().getOrDefault("AWS_REGION", "us-east-1")
        );
    }

    /**
     * Creates an OpenSearchClientImpl with explicit endpoint and region.
     *
     * @param endpoint the OpenSearch domain endpoint (e.g., https://search-domain.us-east-1.es.amazonaws.com)
     * @param regionStr the AWS region string
     */
    public OpenSearchClientImpl(String endpoint, String regionStr) {
        this.openSearchEndpoint = endpoint != null && endpoint.startsWith("https://")
            ? endpoint : "https://" + (endpoint != null ? endpoint : "");
        this.region = Region.of(regionStr);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();
        this.objectMapper = new ObjectMapper();
        this.signer = Aws4Signer.create();
        this.credentialsProvider = DefaultCredentialsProvider.create();
    }

    /**
     * Package-private constructor for testing with injected dependencies.
     */
    OpenSearchClientImpl(String endpoint, String regionStr, HttpClient httpClient, ObjectMapper objectMapper) {
        this.openSearchEndpoint = endpoint != null && endpoint.startsWith("https://")
            ? endpoint : "https://" + (endpoint != null ? endpoint : "");
        this.region = Region.of(regionStr);
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.signer = Aws4Signer.create();
        this.credentialsProvider = DefaultCredentialsProvider.create();
    }

    @Override
    public Map<String, List<Map<String, Object>>> search(
            String query,
            Set<String> permittedEntityTypes,
            Map<String, Object> filter) {

        if (permittedEntityTypes == null || permittedEntityTypes.isEmpty()) {
            return Collections.emptyMap();
        }

        if (query == null || query.isBlank()) {
            return Collections.emptyMap();
        }

        try {
            String requestBody = buildQueryJson(query, filter);
            String indicesPath = buildIndicesPath(permittedEntityTypes);
            System.out.println("[OpenSearchClient] Endpoint: " + openSearchEndpoint);
            System.out.println("[OpenSearchClient] Indices path: " + indicesPath);
            System.out.println("[OpenSearchClient] Request body: " + requestBody);
            String responseBody = executeSignedRequest(indicesPath, requestBody);
            System.out.println("[OpenSearchClient] Response (first 500 chars): " + (responseBody != null ? responseBody.substring(0, Math.min(500, responseBody.length())) : "null"));
            return parseResponse(responseBody);
        } catch (Exception e) {
            System.out.println("[OpenSearchClient] ERROR: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            // Fail-closed: return empty results on error
            return Collections.emptyMap();
        }
    }

    /**
     * Builds the OpenSearch query JSON with multi_match and optional bool filters.
     */
    String buildQueryJson(String query, Map<String, Object> filter) throws Exception {
        Map<String, Object> multiMatch = new HashMap<>();
        multiMatch.put("query", query);
        multiMatch.put("fields", List.of("*"));

        Map<String, Object> multiMatchWrapper = new HashMap<>();
        multiMatchWrapper.put("multi_match", multiMatch);

        Map<String, Object> boolQuery = new HashMap<>();
        boolQuery.put("must", List.of(multiMatchWrapper));

        // Apply filter clauses from PreFilterService if present
        if (filter != null && !filter.isEmpty()) {
            List<Map<String, Object>> filterClauses = buildFilterClauses(filter);
            if (!filterClauses.isEmpty()) {
                boolQuery.put("filter", filterClauses);
            }
        }

        Map<String, Object> queryWrapper = new HashMap<>();
        queryWrapper.put("bool", boolQuery);

        Map<String, Object> body = new HashMap<>();
        body.put("query", queryWrapper);

        return objectMapper.writeValueAsString(body);
    }

    /**
     * Converts the filter map from PreFilterService into a list of OpenSearch filter clauses.
     * Each entry in the filter map becomes a terms filter clause.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildFilterClauses(Map<String, Object> filter) {
        List<Map<String, Object>> clauses = new ArrayList<>();

        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String field = entry.getKey();
            Object value = entry.getValue();

            Map<String, Object> terms = new HashMap<>();
            if (value instanceof List) {
                terms.put(field, value);
            } else {
                terms.put(field, List.of(value));
            }

            Map<String, Object> termsWrapper = new HashMap<>();
            termsWrapper.put("terms", terms);
            clauses.add(termsWrapper);
        }

        return clauses;
    }

    /**
     * Builds the comma-separated indices path from permitted entity types.
     * Entity types are mapped to index names using the pattern: index-{entityType}
     */
    String buildIndicesPath(Set<String> permittedEntityTypes) {
        return permittedEntityTypes.stream()
            .map(entityType -> INDEX_PREFIX + entityType)
            .collect(Collectors.joining(","));
    }

    /**
     * Executes a SigV4-signed POST request to the OpenSearch domain.
     */
    private String executeSignedRequest(String indicesPath, String requestBody) throws Exception {
        URI uri = URI.create(openSearchEndpoint + "/" + indicesPath + "/_search");

        byte[] bodyBytes = requestBody.getBytes(StandardCharsets.UTF_8);

        // Build the SDK HTTP request for signing
        SdkHttpFullRequest sdkRequest = SdkHttpFullRequest.builder()
            .uri(uri)
            .method(SdkHttpMethod.POST)
            .putHeader("Content-Type", "application/json")
            .putHeader("Host", uri.getHost())
            .contentStreamProvider(() -> new ByteArrayInputStream(bodyBytes))
            .build();

        // Sign the request with SigV4
        Aws4SignerParams signerParams = Aws4SignerParams.builder()
            .awsCredentials(credentialsProvider.resolveCredentials())
            .signingName("es")
            .signingRegion(region)
            .build();

        SdkHttpFullRequest signedRequest = signer.sign(sdkRequest, signerParams);

        // Build Java HttpClient request from signed SDK request
        HttpRequest.Builder httpRequestBuilder = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes));

        // Copy signed headers to the Java HTTP request
        signedRequest.headers().forEach((name, values) -> {
            for (String value : values) {
                // Skip restricted headers that Java HttpClient handles internally
                if (!name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("content-length")) {
                    httpRequestBuilder.header(name, value);
                }
            }
        });

        HttpRequest httpRequest = httpRequestBuilder.build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return response.body();
        }

        throw new RuntimeException("OpenSearch returned status " + response.statusCode() + ": " + response.body());
    }

    /**
     * Parses the OpenSearch response and groups hits by entity type.
     * Index names follow the pattern index-{entityType}, so the entity type
     * is extracted by stripping the "index-" prefix.
     */
    @SuppressWarnings("unchecked")
    Map<String, List<Map<String, Object>>> parseResponse(String responseBody) throws Exception {
        Map<String, Object> response = objectMapper.readValue(
            responseBody, new TypeReference<Map<String, Object>>() {});

        Map<String, Object> hits = (Map<String, Object>) response.get("hits");
        if (hits == null) {
            return Collections.emptyMap();
        }

        List<Map<String, Object>> hitsList = (List<Map<String, Object>>) hits.get("hits");
        if (hitsList == null || hitsList.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, List<Map<String, Object>>> resultsByEntityType = new HashMap<>();

        for (Map<String, Object> hit : hitsList) {
            String index = (String) hit.get("_index");
            Map<String, Object> source = (Map<String, Object>) hit.get("_source");

            if (index == null || source == null) {
                continue;
            }

            // Strip "index-" prefix to get entity type
            String entityType = index.startsWith(INDEX_PREFIX)
                ? index.substring(INDEX_PREFIX.length())
                : index;

            resultsByEntityType.computeIfAbsent(entityType, k -> new ArrayList<>()).add(source);
        }

        return resultsByEntityType;
    }
}
