package com.example.avp.opensearch.lambda;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link OpenSearchClientImpl}.
 * Tests focus on query building, index path construction, and response parsing logic.
 */
class OpenSearchClientImplTest {

    private OpenSearchClientImpl client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        client = new OpenSearchClientImpl("https://search-test.us-east-1.es.amazonaws.com", "us-east-1");
        objectMapper = new ObjectMapper();
    }

    @Test
    void search_withEmptyPermittedEntityTypes_returnsEmptyMap() {
        Map<String, List<Map<String, Object>>> result = client.search("test", Collections.emptySet(), Map.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void search_withNullPermittedEntityTypes_returnsEmptyMap() {
        Map<String, List<Map<String, Object>>> result = client.search("test", null, Map.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void search_withNullQuery_returnsEmptyMap() {
        Map<String, List<Map<String, Object>>> result = client.search(null, Set.of("employees"), Map.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void search_withBlankQuery_returnsEmptyMap() {
        Map<String, List<Map<String, Object>>> result = client.search("   ", Set.of("employees"), Map.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void buildIndicesPath_singleEntityType_returnsCorrectPath() {
        String path = client.buildIndicesPath(Set.of("employees"));
        assertEquals("index-employees", path);
    }

    @Test
    void buildIndicesPath_multipleEntityTypes_returnsCommaSeparated() {
        Set<String> entityTypes = Set.of("employees", "teams");
        String path = client.buildIndicesPath(entityTypes);
        // Order is not guaranteed with Set, but both indices should be present
        assertTrue(path.contains("index-employees"));
        assertTrue(path.contains("index-teams"));
        assertTrue(path.contains(","));
        assertEquals(2, path.split(",").length);
    }

    @Test
    void buildIndicesPath_allEntityTypes_containsAllIndices() {
        Set<String> entityTypes = Set.of("employees", "teams", "job-postings", "compensation");
        String path = client.buildIndicesPath(entityTypes);
        assertTrue(path.contains("index-employees"));
        assertTrue(path.contains("index-teams"));
        assertTrue(path.contains("index-job-postings"));
        assertTrue(path.contains("index-compensation"));
        assertEquals(4, path.split(",").length);
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildQueryJson_withNoFilters_producesCorrectStructure() throws Exception {
        String json = client.buildQueryJson("John", Map.of());
        Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

        assertNotNull(parsed.get("query"));
        Map<String, Object> query = (Map<String, Object>) parsed.get("query");

        assertNotNull(query.get("bool"));
        Map<String, Object> boolQuery = (Map<String, Object>) query.get("bool");

        assertNotNull(boolQuery.get("must"));
        List<Map<String, Object>> must = (List<Map<String, Object>>) boolQuery.get("must");
        assertEquals(1, must.size());

        Map<String, Object> multiMatchWrapper = must.get(0);
        assertNotNull(multiMatchWrapper.get("multi_match"));
        Map<String, Object> multiMatch = (Map<String, Object>) multiMatchWrapper.get("multi_match");
        assertEquals("John", multiMatch.get("query"));
        assertEquals(List.of("*"), multiMatch.get("fields"));

        // No filter should be present
        assertNull(boolQuery.get("filter"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildQueryJson_withRegionFilter_producesCorrectStructure() throws Exception {
        Map<String, Object> filter = new HashMap<>();
        filter.put("region", List.of("US"));

        String json = client.buildQueryJson("test", filter);
        Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

        Map<String, Object> query = (Map<String, Object>) parsed.get("query");
        Map<String, Object> boolQuery = (Map<String, Object>) query.get("bool");

        assertNotNull(boolQuery.get("filter"));
        List<Map<String, Object>> filterClauses = (List<Map<String, Object>>) boolQuery.get("filter");
        assertEquals(1, filterClauses.size());

        Map<String, Object> termsClause = filterClauses.get(0);
        assertNotNull(termsClause.get("terms"));
        Map<String, Object> terms = (Map<String, Object>) termsClause.get("terms");
        assertEquals(List.of("US"), terms.get("region"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildQueryJson_withMultipleFilters_producesMultipleClauses() throws Exception {
        Map<String, Object> filter = new HashMap<>();
        filter.put("region", List.of("US", "EU"));
        filter.put("department", List.of("Engineering"));

        String json = client.buildQueryJson("test", filter);
        Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

        Map<String, Object> query = (Map<String, Object>) parsed.get("query");
        Map<String, Object> boolQuery = (Map<String, Object>) query.get("bool");

        List<Map<String, Object>> filterClauses = (List<Map<String, Object>>) boolQuery.get("filter");
        assertEquals(2, filterClauses.size());
    }

    @Test
    void parseResponse_withHits_groupsByEntityType() throws Exception {
        String responseJson = """
            {
              "took": 5,
              "timed_out": false,
              "hits": {
                "total": { "value": 3, "relation": "eq" },
                "hits": [
                  {
                    "_index": "index-employees",
                    "_id": "1",
                    "_source": { "name": "John Smith", "region": "US" }
                  },
                  {
                    "_index": "index-employees",
                    "_id": "2",
                    "_source": { "name": "Jane Doe", "region": "US" }
                  },
                  {
                    "_index": "index-teams",
                    "_id": "3",
                    "_source": { "name": "Platform Team", "region": "US" }
                  }
                ]
              }
            }
            """;

        Map<String, List<Map<String, Object>>> result = client.parseResponse(responseJson);

        assertEquals(2, result.size());
        assertTrue(result.containsKey("employees"));
        assertTrue(result.containsKey("teams"));
        assertEquals(2, result.get("employees").size());
        assertEquals(1, result.get("teams").size());
        assertEquals("John Smith", result.get("employees").get(0).get("name"));
        assertEquals("Platform Team", result.get("teams").get(0).get("name"));
    }

    @Test
    void parseResponse_withEmptyHits_returnsEmptyMap() throws Exception {
        String responseJson = """
            {
              "took": 1,
              "timed_out": false,
              "hits": {
                "total": { "value": 0, "relation": "eq" },
                "hits": []
              }
            }
            """;

        Map<String, List<Map<String, Object>>> result = client.parseResponse(responseJson);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseResponse_withNoHitsField_returnsEmptyMap() throws Exception {
        String responseJson = """
            {
              "took": 1,
              "timed_out": false
            }
            """;

        Map<String, List<Map<String, Object>>> result = client.parseResponse(responseJson);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseResponse_withMultipleIndices_groupsCorrectly() throws Exception {
        String responseJson = """
            {
              "hits": {
                "total": { "value": 4, "relation": "eq" },
                "hits": [
                  { "_index": "index-employees", "_id": "1", "_source": { "name": "Alice" } },
                  { "_index": "index-compensation", "_id": "2", "_source": { "salary": 150000 } },
                  { "_index": "index-job-postings", "_id": "3", "_source": { "title": "Dev" } },
                  { "_index": "index-teams", "_id": "4", "_source": { "name": "Team A" } }
                ]
              }
            }
            """;

        Map<String, List<Map<String, Object>>> result = client.parseResponse(responseJson);

        assertEquals(4, result.size());
        assertTrue(result.containsKey("employees"));
        assertTrue(result.containsKey("compensation"));
        assertTrue(result.containsKey("job-postings"));
        assertTrue(result.containsKey("teams"));
    }

    @Test
    void parseResponse_hitsWithNullSource_skipsThoseHits() throws Exception {
        String responseJson = """
            {
              "hits": {
                "total": { "value": 2, "relation": "eq" },
                "hits": [
                  { "_index": "index-employees", "_id": "1", "_source": { "name": "Alice" } },
                  { "_index": "index-employees", "_id": "2" }
                ]
              }
            }
            """;

        Map<String, List<Map<String, Object>>> result = client.parseResponse(responseJson);
        assertEquals(1, result.size());
        assertEquals(1, result.get("employees").size());
    }
}
