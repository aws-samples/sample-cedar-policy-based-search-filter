package com.example.avp.opensearch.lambda;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class CoarseAuthorizationServiceImplTest {

    private static final String USER_ID = "alice";
    private static final String ROLE = "Employee";
    private static final List<String> ALL_ENTITY_TYPES = List.of(
        "employees", "teams", "job-postings", "compensation"
    );

    @Mock
    private AvpClient avpClient;

    @Mock
    private RedisCacheClient redisCacheClient;

    private ObjectMapper objectMapper;
    private CoarseAuthorizationServiceImpl service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new CoarseAuthorizationServiceImpl(avpClient, redisCacheClient, objectMapper);
    }

    @Test
    void getPermittedEntityTypes_cacheHit_returnsFromCache() throws Exception {
        // Given: cached value exists
        String cachedJson = "[\"employees\",\"teams\"]";
        when(redisCacheClient.get("l1:alice")).thenReturn(Optional.of(cachedJson));

        // When
        Set<String> result = service.getPermittedEntityTypes(USER_ID, ROLE);

        // Then: returns cached entity types without calling AVP
        assertEquals(Set.of("employees", "teams"), result);
        verify(avpClient, never()).checkEntityTypeAccess(anyString(), anyString(), any());
    }

    @Test
    void getPermittedEntityTypes_cacheMiss_callsAvpAndCaches() throws Exception {
        // Given: cache miss
        when(redisCacheClient.get("l1:alice")).thenReturn(Optional.empty());
        when(avpClient.checkEntityTypeAccess(USER_ID, ROLE, ALL_ENTITY_TYPES))
            .thenReturn(Set.of("employees", "teams"));

        // When
        Set<String> result = service.getPermittedEntityTypes(USER_ID, ROLE);

        // Then: returns AVP result and caches it
        assertEquals(Set.of("employees", "teams"), result);
        verify(redisCacheClient).set(eq("l1:alice"), anyString(), eq(300));
    }

    @Test
    void getPermittedEntityTypes_avpReturnsAllTypes() throws Exception {
        // Given: cache miss, AVP allows all
        when(redisCacheClient.get("l1:carol")).thenReturn(Optional.empty());
        when(avpClient.checkEntityTypeAccess("carol", "GlobalHRAdmin", ALL_ENTITY_TYPES))
            .thenReturn(Set.of("employees", "teams", "job-postings", "compensation"));

        // When
        Set<String> result = service.getPermittedEntityTypes("carol", "GlobalHRAdmin");

        // Then
        assertEquals(Set.of("employees", "teams", "job-postings", "compensation"), result);
    }

    @Test
    void getPermittedEntityTypes_avpReturnsEmpty() throws Exception {
        // Given: cache miss, AVP denies all
        when(redisCacheClient.get("l1:alice")).thenReturn(Optional.empty());
        when(avpClient.checkEntityTypeAccess(USER_ID, ROLE, ALL_ENTITY_TYPES))
            .thenReturn(Collections.emptySet());

        // When
        Set<String> result = service.getPermittedEntityTypes(USER_ID, ROLE);

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void getPermittedEntityTypes_avpThrowsException_returnsEmptySet() {
        // Given: cache miss, AVP throws
        when(redisCacheClient.get("l1:alice")).thenReturn(Optional.empty());
        when(avpClient.checkEntityTypeAccess(USER_ID, ROLE, ALL_ENTITY_TYPES))
            .thenThrow(new AvpAuthorizationException("AVP unreachable", new RuntimeException()));

        // When
        Set<String> result = service.getPermittedEntityTypes(USER_ID, ROLE);

        // Then: fail-closed returns empty set
        assertTrue(result.isEmpty());
    }

    @Test
    void getPermittedEntityTypes_cacheGetThrowsException_returnsEmptySet() {
        // Given: Redis throws on get
        when(redisCacheClient.get("l1:alice")).thenThrow(new RuntimeException("Redis connection failed"));

        // When
        Set<String> result = service.getPermittedEntityTypes(USER_ID, ROLE);

        // Then: fail-closed returns empty set
        assertTrue(result.isEmpty());
    }

    @Test
    void getPermittedEntityTypes_invalidCacheJson_returnsEmptySet() {
        // Given: cached value is malformed JSON
        when(redisCacheClient.get("l1:alice")).thenReturn(Optional.of("not-valid-json"));

        // When
        Set<String> result = service.getPermittedEntityTypes(USER_ID, ROLE);

        // Then: fail-closed returns empty set
        assertTrue(result.isEmpty());
    }

    @Test
    void getPermittedEntityTypes_cacheSetFails_stillReturnsAvpResult() {
        // Given: cache miss, AVP succeeds, but cache set throws (RedisCacheClient swallows errors internally)
        when(redisCacheClient.get("l1:alice")).thenReturn(Optional.empty());
        when(avpClient.checkEntityTypeAccess(USER_ID, ROLE, ALL_ENTITY_TYPES))
            .thenReturn(Set.of("employees", "teams"));

        // When
        Set<String> result = service.getPermittedEntityTypes(USER_ID, ROLE);

        // Then: still returns AVP result
        assertEquals(Set.of("employees", "teams"), result);
    }

    @Test
    void getPermittedEntityTypes_cachesWithCorrectTtl() throws Exception {
        // Given: cache miss
        when(redisCacheClient.get("l1:alice")).thenReturn(Optional.empty());
        when(avpClient.checkEntityTypeAccess(USER_ID, ROLE, ALL_ENTITY_TYPES))
            .thenReturn(Set.of("employees"));

        // When
        service.getPermittedEntityTypes(USER_ID, ROLE);

        // Then: verifies TTL of 300 seconds is used
        verify(redisCacheClient).set(eq("l1:alice"), anyString(), eq(300));
    }

    @Test
    void getPermittedEntityTypes_cacheKeyFormat() {
        // Given: cache hit for a specific user
        when(redisCacheClient.get("l1:bob")).thenReturn(Optional.of("[\"employees\",\"compensation\"]"));

        // When
        Set<String> result = service.getPermittedEntityTypes("bob", "USHRManager");

        // Then: correct cache key was used
        verify(redisCacheClient).get("l1:bob");
        assertEquals(Set.of("employees", "compensation"), result);
    }
}
