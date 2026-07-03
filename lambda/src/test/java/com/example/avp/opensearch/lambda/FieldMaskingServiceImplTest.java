package com.example.avp.opensearch.lambda;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FieldMaskingServiceImplTest {

    private static final String USER_ID = "dave";
    private static final String ROLE = "Recruiter";
    private static final String ENTITY_TYPE = "employees";

    @Mock
    private AvpClient avpClient;

    private FieldMaskingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FieldMaskingServiceImpl(avpClient);
    }

    @Test
    void maskFields_masksFieldsWithDenyDecision() {
        // Given: AVP allows name and email, denies phoneNumber
        List<Map<String, Object>> results = List.of(
            Map.of("name", "John Smith", "phoneNumber", "+1-555-0101", "email", "john@example.com")
        );
        when(avpClient.checkFieldAccess(eq(USER_ID), eq(ROLE), eq(ENTITY_TYPE), anyList()))
            .thenReturn(Set.of("name", "email"));

        // When
        List<Map<String, Object>> masked = service.maskFields(USER_ID, ROLE, ENTITY_TYPE, results);

        // Then
        assertEquals(1, masked.size());
        assertEquals("John Smith", masked.get(0).get("name"));
        assertEquals("john@example.com", masked.get(0).get("email"));
        assertEquals("****", masked.get(0).get("phoneNumber"));
    }

    @Test
    void maskFields_keepsAllFieldsWhenAllAllowed() {
        // Given: AVP allows all fields
        List<Map<String, Object>> results = List.of(
            Map.of("name", "Jane Doe", "phoneNumber", "+1-555-0102", "email", "jane@example.com")
        );
        when(avpClient.checkFieldAccess(eq(USER_ID), eq(ROLE), eq(ENTITY_TYPE), anyList()))
            .thenReturn(Set.of("name", "phoneNumber", "email"));

        // When
        List<Map<String, Object>> masked = service.maskFields(USER_ID, ROLE, ENTITY_TYPE, results);

        // Then
        assertEquals(1, masked.size());
        assertEquals("Jane Doe", masked.get(0).get("name"));
        assertEquals("+1-555-0102", masked.get(0).get("phoneNumber"));
        assertEquals("jane@example.com", masked.get(0).get("email"));
    }

    @Test
    void maskFields_masksSensitiveFieldsOnError() {
        // Given: AVP throws an exception
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Object> result = new HashMap<>();
        result.put("name", "John Smith");
        result.put("phoneNumber", "+1-555-0101");
        result.put("salary", 150000);
        result.put("bonus", 15000);
        result.put("email", "john@example.com");
        results.add(result);

        when(avpClient.checkFieldAccess(eq(USER_ID), eq(ROLE), eq(ENTITY_TYPE), anyList()))
            .thenThrow(new AvpAuthorizationException("AVP unreachable"));

        // When
        List<Map<String, Object>> masked = service.maskFields(USER_ID, ROLE, ENTITY_TYPE, results);

        // Then: sensitive fields are masked, non-sensitive fields remain
        assertEquals(1, masked.size());
        assertEquals("John Smith", masked.get(0).get("name"));
        assertEquals("john@example.com", masked.get(0).get("email"));
        assertEquals("****", masked.get(0).get("phoneNumber"));
        assertEquals("****", masked.get(0).get("salary"));
        assertEquals("****", masked.get(0).get("bonus"));
    }

    @Test
    void maskFields_returnsEmptyListForEmptyInput() {
        // Given: empty results
        List<Map<String, Object>> results = List.of();

        // When
        List<Map<String, Object>> masked = service.maskFields(USER_ID, ROLE, ENTITY_TYPE, results);

        // Then
        assertTrue(masked.isEmpty());
        verify(avpClient, never()).checkFieldAccess(eq(USER_ID), eq(ROLE), eq(ENTITY_TYPE), anyList());
    }

    @Test
    void maskFields_returnsNullForNullInput() {
        // When
        List<Map<String, Object>> masked = service.maskFields(USER_ID, ROLE, ENTITY_TYPE, null);

        // Then
        assertNull(masked);
        verify(avpClient, never()).checkFieldAccess(eq(USER_ID), eq(ROLE), eq(ENTITY_TYPE), anyList());
    }

    @Test
    void maskFields_appliesSameMaskingToAllResults() {
        // Given: Multiple results, AVP denies phoneNumber for all
        List<Map<String, Object>> results = List.of(
            Map.of("name", "John Smith", "phoneNumber", "+1-555-0101"),
            Map.of("name", "Jane Doe", "phoneNumber", "+1-555-0102"),
            Map.of("name", "Mike Johnson", "phoneNumber", "+1-555-0103")
        );
        when(avpClient.checkFieldAccess(eq(USER_ID), eq(ROLE), eq(ENTITY_TYPE), anyList()))
            .thenReturn(Set.of("name"));

        // When
        List<Map<String, Object>> masked = service.maskFields(USER_ID, ROLE, ENTITY_TYPE, results);

        // Then: all results have phoneNumber masked
        assertEquals(3, masked.size());
        for (Map<String, Object> maskedResult : masked) {
            assertEquals("****", maskedResult.get("phoneNumber"));
        }
        assertEquals("John Smith", masked.get(0).get("name"));
        assertEquals("Jane Doe", masked.get(1).get("name"));
        assertEquals("Mike Johnson", masked.get(2).get("name"));
    }

    @Test
    void maskFields_callsAvpOnceForAllResults() {
        // Given: Multiple results with same fields
        List<Map<String, Object>> results = List.of(
            Map.of("name", "John", "phoneNumber", "+1-555-0101"),
            Map.of("name", "Jane", "phoneNumber", "+1-555-0102")
        );
        when(avpClient.checkFieldAccess(eq(USER_ID), eq(ROLE), eq(ENTITY_TYPE), anyList()))
            .thenReturn(Set.of("name", "phoneNumber"));

        // When
        service.maskFields(USER_ID, ROLE, ENTITY_TYPE, results);

        // Then: AVP is called exactly once (optimization)
        verify(avpClient).checkFieldAccess(eq(USER_ID), eq(ROLE), eq(ENTITY_TYPE), anyList());
    }

    @Test
    void maskFields_handlesRuntimeExceptionWithFailClosed() {
        // Given: AVP throws a RuntimeException
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Object> result = new HashMap<>();
        result.put("name", "John Smith");
        result.put("phoneNumber", "+1-555-0101");
        results.add(result);

        when(avpClient.checkFieldAccess(eq(USER_ID), eq(ROLE), eq(ENTITY_TYPE), anyList()))
            .thenThrow(new RuntimeException("Connection timeout"));

        // When
        List<Map<String, Object>> masked = service.maskFields(USER_ID, ROLE, ENTITY_TYPE, results);

        // Then: sensitive fields are masked (fail-closed)
        assertEquals(1, masked.size());
        assertEquals("John Smith", masked.get(0).get("name"));
        assertEquals("****", masked.get(0).get("phoneNumber"));
    }

    @Test
    void maskFields_onlyMasksSensitiveFieldsOnError_nonSensitiveFieldsRetained() {
        // Given: AVP throws, result contains mix of sensitive and non-sensitive fields
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Object> result = new HashMap<>();
        result.put("employeeId", "emp-001");
        result.put("name", "John Smith");
        result.put("region", "US");
        result.put("department", "Engineering");
        result.put("phoneNumber", "+1-555-0101");
        result.put("salary", 150000);
        result.put("bonus", 15000);
        results.add(result);

        when(avpClient.checkFieldAccess(eq(USER_ID), eq(ROLE), eq(ENTITY_TYPE), anyList()))
            .thenThrow(new AvpAuthorizationException("Service unavailable"));

        // When
        List<Map<String, Object>> masked = service.maskFields(USER_ID, ROLE, ENTITY_TYPE, results);

        // Then: non-sensitive fields are unmodified
        assertEquals("emp-001", masked.get(0).get("employeeId"));
        assertEquals("John Smith", masked.get(0).get("name"));
        assertEquals("US", masked.get(0).get("region"));
        assertEquals("Engineering", masked.get(0).get("department"));
        // Sensitive fields are masked
        assertEquals("****", masked.get(0).get("phoneNumber"));
        assertEquals("****", masked.get(0).get("salary"));
        assertEquals("****", masked.get(0).get("bonus"));
    }
}
