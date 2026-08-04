package com.example.avp.opensearch.lambda;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FineGrainedAuthorizationServiceImplTest {

    private static final String USER_ID = "bob";
    private static final String ROLE = "USHRManager";
    private static final String ENTITY_TYPE = "employees";

    @Mock
    private AvpClient avpClient;

    @Mock
    private RedisCacheClient redisCacheClient;

    private FineGrainedAuthorizationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FineGrainedAuthorizationServiceImpl(avpClient, redisCacheClient);
    }

    @Test
    void filterAuthorized_emptyList_returnsEmpty() {
        OrganizationalContext context = new OrganizationalContext(
            List.of("US"), List.of("HR"), List.of("USHRManager"), List.of());

        List<Map<String, Object>> result = service.filterAuthorized(
            USER_ID, ROLE, context, ENTITY_TYPE, List.of());

        assertTrue(result.isEmpty());
    }

    @Test
    void filterAuthorized_nullList_returnsEmpty() {
        OrganizationalContext context = new OrganizationalContext(
            List.of("US"), List.of("HR"), List.of("USHRManager"), List.of());

        List<Map<String, Object>> result = service.filterAuthorized(
            USER_ID, ROLE, context, ENTITY_TYPE, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void filterAuthorized_cacheHitAllow_returnsWithoutAvpCall() {
        OrganizationalContext context = new OrganizationalContext(
            List.of("US"), List.of("HR"), List.of(), List.of());

        Map<String, Object> candidate = new HashMap<>();
        candidate.put("employeeId", "emp-001");
        candidate.put("name", "John Smith");
        candidate.put("region", "US");

        String cacheKey = "l3:bob:employees:emp-001";
        when(redisCacheClient.get(cacheKey)).thenReturn(Optional.of("ALLOW"));

        List<Map<String, Object>> result = service.filterAuthorized(
            USER_ID, ROLE, context, ENTITY_TYPE, List.of(candidate));

        assertEquals(1, result.size());
        assertEquals(candidate, result.get(0));
        verify(avpClient, never()).checkResultAccess(anyString(), anyString(), any(), anyString(), anyList(), anyList());
    }

    @Test
    void filterAuthorized_cacheHitDeny_excludesResult() {
        OrganizationalContext context = new OrganizationalContext(
            List.of("US"), List.of("HR"), List.of(), List.of());

        Map<String, Object> candidate = new HashMap<>();
        candidate.put("employeeId", "emp-004");
        candidate.put("name", "Anna Mueller");
        candidate.put("region", "EU");

        String cacheKey = "l3:bob:employees:emp-004";
        when(redisCacheClient.get(cacheKey)).thenReturn(Optional.of("DENY"));

        List<Map<String, Object>> result = service.filterAuthorized(
            USER_ID, ROLE, context, ENTITY_TYPE, List.of(candidate));

        assertTrue(result.isEmpty());
        verify(avpClient, never()).checkResultAccess(anyString(), anyString(), any(), anyString(), anyList(), anyList());
    }

    @Test
    void filterAuthorized_cacheMiss_callsAvpAndCachesResult() {
        OrganizationalContext context = new OrganizationalContext(
            List.of("US"), List.of("HR"), List.of(), List.of());

        Map<String, Object> candidate = new HashMap<>();
        candidate.put("employeeId", "emp-001");
        candidate.put("name", "John Smith");
        candidate.put("region", "US");
        candidate.put("department", "Engineering");

        String cacheKey = "l3:bob:employees:emp-001";
        when(redisCacheClient.get(cacheKey)).thenReturn(Optional.empty());
        when(avpClient.checkResultAccess(eq(USER_ID), eq(ROLE), any(), eq(ENTITY_TYPE), eq(List.of("emp-001")), anyList()))
            .thenReturn(Set.of("emp-001"));

        List<Map<String, Object>> result = service.filterAuthorized(
            USER_ID, ROLE, context, ENTITY_TYPE, List.of(candidate));

        assertEquals(1, result.size());
        verify(redisCacheClient).set(eq(cacheKey), eq("ALLOW"), eq(300));
    }

    @Test
    void filterAuthorized_cacheMiss_avpDenies_cachesAndExcludes() {
        OrganizationalContext context = new OrganizationalContext(
            List.of("US"), List.of("HR"), List.of(), List.of());

        Map<String, Object> candidate = new HashMap<>();
        candidate.put("employeeId", "emp-004");
        candidate.put("name", "Anna Mueller");
        candidate.put("region", "EU");

        String cacheKey = "l3:bob:employees:emp-004";
        when(redisCacheClient.get(cacheKey)).thenReturn(Optional.empty());
        when(avpClient.checkResultAccess(eq(USER_ID), eq(ROLE), any(), eq(ENTITY_TYPE), eq(List.of("emp-004")), anyList()))
            .thenReturn(Set.of()); // DENY

        List<Map<String, Object>> result = service.filterAuthorized(
            USER_ID, ROLE, context, ENTITY_TYPE, List.of(candidate));

        assertTrue(result.isEmpty());
        verify(redisCacheClient).set(eq(cacheKey), eq("DENY"), eq(300));
    }

    @Test
    void filterAuthorized_mixedCacheAndUncached() {
        OrganizationalContext context = new OrganizationalContext(
            List.of("US"), List.of("HR"), List.of(), List.of());

        Map<String, Object> cachedAllowed = new HashMap<>();
        cachedAllowed.put("employeeId", "emp-001");
        cachedAllowed.put("region", "US");

        Map<String, Object> cachedDenied = new HashMap<>();
        cachedDenied.put("employeeId", "emp-004");
        cachedDenied.put("region", "EU");

        Map<String, Object> uncached = new HashMap<>();
        uncached.put("employeeId", "emp-002");
        uncached.put("region", "US");

        when(redisCacheClient.get("l3:bob:employees:emp-001")).thenReturn(Optional.of("ALLOW"));
        when(redisCacheClient.get("l3:bob:employees:emp-004")).thenReturn(Optional.of("DENY"));
        when(redisCacheClient.get("l3:bob:employees:emp-002")).thenReturn(Optional.empty());
        when(avpClient.checkResultAccess(eq(USER_ID), eq(ROLE), any(), eq(ENTITY_TYPE), eq(List.of("emp-002")), anyList()))
            .thenReturn(Set.of("emp-002"));

        List<Map<String, Object>> candidates = List.of(cachedAllowed, cachedDenied, uncached);
        List<Map<String, Object>> result = service.filterAuthorized(
            USER_ID, ROLE, context, ENTITY_TYPE, candidates);

        assertEquals(2, result.size());
        assertTrue(result.contains(cachedAllowed));
        assertTrue(result.contains(uncached));
    }

    @Test
    void filterAuthorized_avpError_returnsEmptyFailClosed() {
        OrganizationalContext context = new OrganizationalContext(
            List.of("US"), List.of("HR"), List.of(), List.of());

        Map<String, Object> candidate = new HashMap<>();
        candidate.put("employeeId", "emp-001");
        candidate.put("region", "US");

        when(redisCacheClient.get("l3:bob:employees:emp-001")).thenReturn(Optional.empty());
        when(avpClient.checkResultAccess(anyString(), anyString(), any(), anyString(), anyList(), anyList()))
            .thenThrow(new AvpAuthorizationException("AVP unreachable"));

        List<Map<String, Object>> result = service.filterAuthorized(
            USER_ID, ROLE, context, ENTITY_TYPE, List.of(candidate));

        assertTrue(result.isEmpty());
    }

    @Test
    void filterAuthorized_batchesCorrectly_moreThan30() {
        OrganizationalContext context = new OrganizationalContext(
            List.of("US"), List.of("HR"), List.of(), List.of());

        // Create 35 uncached candidates
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (int i = 1; i <= 35; i++) {
            Map<String, Object> candidate = new HashMap<>();
            candidate.put("employeeId", "emp-" + String.format("%03d", i));
            candidate.put("region", "US");
            candidates.add(candidate);
        }

        // All cache misses
        for (int i = 1; i <= 35; i++) {
            String key = "l3:bob:employees:emp-" + String.format("%03d", i);
            when(redisCacheClient.get(key)).thenReturn(Optional.empty());
        }

        // First batch (30 items) - all allowed
        List<String> firstBatchIds = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            firstBatchIds.add("emp-" + String.format("%03d", i));
        }
        when(avpClient.checkResultAccess(eq(USER_ID), eq(ROLE), any(), eq(ENTITY_TYPE), eq(firstBatchIds), anyList()))
            .thenReturn(Set.copyOf(firstBatchIds));

        // Second batch (5 items) - all allowed
        List<String> secondBatchIds = new ArrayList<>();
        for (int i = 31; i <= 35; i++) {
            secondBatchIds.add("emp-" + String.format("%03d", i));
        }
        when(avpClient.checkResultAccess(eq(USER_ID), eq(ROLE), any(), eq(ENTITY_TYPE), eq(secondBatchIds), anyList()))
            .thenReturn(Set.copyOf(secondBatchIds));

        List<Map<String, Object>> result = service.filterAuthorized(
            USER_ID, ROLE, context, ENTITY_TYPE, candidates);

        assertEquals(35, result.size());
    }

    @Test
    void filterAuthorized_extractsTeamId() {
        OrganizationalContext context = new OrganizationalContext(
            List.of("US"), List.of("Engineering"), List.of(), List.of());

        Map<String, Object> candidate = new HashMap<>();
        candidate.put("teamId", "team-001");
        candidate.put("name", "Platform Team");
        candidate.put("region", "US");

        when(redisCacheClient.get("l3:bob:teams:team-001")).thenReturn(Optional.of("ALLOW"));

        List<Map<String, Object>> result = service.filterAuthorized(
            USER_ID, ROLE, context, "teams", List.of(candidate));

        assertEquals(1, result.size());
    }

    @Test
    void filterAuthorized_extractsPostingId() {
        OrganizationalContext context = new OrganizationalContext(
            List.of(), List.of(), List.of(), List.of());

        Map<String, Object> candidate = new HashMap<>();
        candidate.put("postingId", "job-001");
        candidate.put("title", "Senior Dev");
        candidate.put("region", "US");

        when(redisCacheClient.get("l3:bob:job-postings:job-001")).thenReturn(Optional.of("ALLOW"));

        List<Map<String, Object>> result = service.filterAuthorized(
            USER_ID, ROLE, context, "job-postings", List.of(candidate));

        assertEquals(1, result.size());
    }

    @Test
    void filterAuthorized_extractsCompensationId() {
        OrganizationalContext context = new OrganizationalContext(
            List.of("US"), List.of(), List.of(), List.of());

        Map<String, Object> candidate = new HashMap<>();
        candidate.put("compensationId", "comp-001");
        candidate.put("region", "US");
        candidate.put("salary", 150000);

        when(redisCacheClient.get("l3:bob:compensation:comp-001")).thenReturn(Optional.of("ALLOW"));

        List<Map<String, Object>> result = service.filterAuthorized(
            USER_ID, ROLE, context, "compensation", List.of(candidate));

        assertEquals(1, result.size());
    }

    @Test
    void filterAuthorized_contextMapIncludesUserAttributes() {
        OrganizationalContext context = new OrganizationalContext(
            List.of("US"), List.of("HR"), List.of("USHRManager"), List.of());

        Map<String, Object> candidate = new HashMap<>();
        candidate.put("employeeId", "emp-001");
        candidate.put("region", "US");
        candidate.put("department", "Engineering");
        candidate.put("managerId", "mgr-001");

        when(redisCacheClient.get("l3:bob:employees:emp-001")).thenReturn(Optional.empty());
        when(avpClient.checkResultAccess(eq(USER_ID), eq(ROLE), any(), eq(ENTITY_TYPE), eq(List.of("emp-001")), anyList()))
            .thenAnswer(invocation -> {
                Map<String, String> userContext = invocation.getArgument(2);
                // Verify user context attributes
                assertEquals("US", userContext.get("region"));
                assertEquals("HR", userContext.get("department"));

                // Verify per-record resource context attributes
                List<Map<String, String>> resourceContexts = invocation.getArgument(5);
                assertEquals(1, resourceContexts.size());
                Map<String, String> resourceContext = resourceContexts.get(0);
                assertEquals("US", resourceContext.get("resourceRegion"));
                assertEquals("Engineering", resourceContext.get("resourceDepartment"));
                assertEquals("mgr-001", resourceContext.get("resourceManagerId"));
                return Set.of("emp-001");
            });

        List<Map<String, Object>> result = service.filterAuthorized(
            USER_ID, ROLE, context, ENTITY_TYPE, List.of(candidate));

        assertEquals(1, result.size());
    }
}
