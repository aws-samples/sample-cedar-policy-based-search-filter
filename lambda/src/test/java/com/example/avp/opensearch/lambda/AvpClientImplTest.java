package com.example.avp.opensearch.lambda;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awssdk.services.verifiedpermissions.VerifiedPermissionsClient;
import software.amazon.awssdk.services.verifiedpermissions.model.BatchIsAuthorizedOutputItem;
import software.amazon.awssdk.services.verifiedpermissions.model.BatchIsAuthorizedRequest;
import software.amazon.awssdk.services.verifiedpermissions.model.BatchIsAuthorizedResponse;
import software.amazon.awssdk.services.verifiedpermissions.model.Decision;
import software.amazon.awssdk.services.verifiedpermissions.model.InternalServerException;
import software.amazon.awssdk.services.verifiedpermissions.model.ThrottlingException;

@ExtendWith(MockitoExtension.class)
class AvpClientImplTest {

    private static final String POLICY_STORE_ID = "test-policy-store-id";
    private static final String USER_ID = "alice";
    private static final String ROLE = "Employee";

    @Mock
    private VerifiedPermissionsClient avpSdkClient;

    private AvpClientImpl avpClient;

    @BeforeEach
    void setUp() {
        avpClient = new AvpClientImpl(avpSdkClient, POLICY_STORE_ID);
    }

    @Test
    void checkEntityTypeAccess_returnsAllowedEntityTypes() {
        // Given: AVP allows employees and teams, denies compensation and job-postings
        BatchIsAuthorizedResponse response = BatchIsAuthorizedResponse.builder()
            .results(List.of(
                BatchIsAuthorizedOutputItem.builder().decision(Decision.ALLOW).build(),
                BatchIsAuthorizedOutputItem.builder().decision(Decision.ALLOW).build(),
                BatchIsAuthorizedOutputItem.builder().decision(Decision.DENY).build(),
                BatchIsAuthorizedOutputItem.builder().decision(Decision.DENY).build()
            ))
            .build();
        when(avpSdkClient.batchIsAuthorized(any(BatchIsAuthorizedRequest.class))).thenReturn(response);

        // When
        Set<String> allowed = avpClient.checkEntityTypeAccess(
            USER_ID, ROLE, List.of("employees", "teams", "job-postings", "compensation"));

        // Then
        assertEquals(Set.of("employees", "teams"), allowed);
        verify(avpSdkClient, times(1)).batchIsAuthorized(any(BatchIsAuthorizedRequest.class));
    }

    @Test
    void checkEntityTypeAccess_returnsEmptySetWhenAllDenied() {
        BatchIsAuthorizedResponse response = BatchIsAuthorizedResponse.builder()
            .results(List.of(
                BatchIsAuthorizedOutputItem.builder().decision(Decision.DENY).build(),
                BatchIsAuthorizedOutputItem.builder().decision(Decision.DENY).build()
            ))
            .build();
        when(avpSdkClient.batchIsAuthorized(any(BatchIsAuthorizedRequest.class))).thenReturn(response);

        Set<String> allowed = avpClient.checkEntityTypeAccess(
            USER_ID, ROLE, List.of("employees", "teams"));

        assertTrue(allowed.isEmpty());
    }

    @Test
    void checkEntityTypeAccess_throwsOnSdkException() {
        when(avpSdkClient.batchIsAuthorized(any(BatchIsAuthorizedRequest.class)))
            .thenThrow(ThrottlingException.builder().message("Rate exceeded").build());

        assertThrows(AvpAuthorizationException.class, () ->
            avpClient.checkEntityTypeAccess(USER_ID, ROLE, List.of("employees")));
    }

    @Test
    void checkResultAccess_returnsAllowedResourceIds() {
        BatchIsAuthorizedResponse response = BatchIsAuthorizedResponse.builder()
            .results(List.of(
                BatchIsAuthorizedOutputItem.builder().decision(Decision.ALLOW).build(),
                BatchIsAuthorizedOutputItem.builder().decision(Decision.DENY).build(),
                BatchIsAuthorizedOutputItem.builder().decision(Decision.ALLOW).build()
            ))
            .build();
        when(avpSdkClient.batchIsAuthorized(any(BatchIsAuthorizedRequest.class))).thenReturn(response);

        Map<String, String> userContext = Map.of(
            "region", "US",
            "department", "Engineering"
        );

        List<Map<String, String>> resourceContexts = List.of(
            Map.of("resourceRegion", "US", "resourceDepartment", "Engineering"),
            Map.of("resourceRegion", "EU", "resourceDepartment", "HR"),
            Map.of("resourceRegion", "US", "resourceDepartment", "Engineering")
        );

        Set<String> allowed = avpClient.checkResultAccess(
            USER_ID, ROLE, userContext, "employees", List.of("emp-001", "emp-004", "emp-002"), resourceContexts);

        assertEquals(Set.of("emp-001", "emp-002"), allowed);
    }

    @Test
    void checkResultAccess_throwsOnSdkException() {
        when(avpSdkClient.batchIsAuthorized(any(BatchIsAuthorizedRequest.class)))
            .thenThrow(InternalServerException.builder().message("Internal error").build());

        assertThrows(AvpAuthorizationException.class, () ->
            avpClient.checkResultAccess(USER_ID, ROLE, Map.of(), "employees",
                List.of("emp-001"), List.of(Map.of("resourceRegion", "US"))));
    }

    @Test
    void checkFieldAccess_returnsAllowedFieldNames() {
        BatchIsAuthorizedResponse response = BatchIsAuthorizedResponse.builder()
            .results(List.of(
                BatchIsAuthorizedOutputItem.builder().decision(Decision.ALLOW).build(),
                BatchIsAuthorizedOutputItem.builder().decision(Decision.DENY).build(),
                BatchIsAuthorizedOutputItem.builder().decision(Decision.ALLOW).build()
            ))
            .build();
        when(avpSdkClient.batchIsAuthorized(any(BatchIsAuthorizedRequest.class))).thenReturn(response);

        Set<String> allowed = avpClient.checkFieldAccess(
            USER_ID, ROLE, "employees", List.of("name", "phoneNumber", "email"));

        assertEquals(Set.of("name", "email"), allowed);
    }

    @Test
    void checkFieldAccess_throwsOnSdkException() {
        when(avpSdkClient.batchIsAuthorized(any(BatchIsAuthorizedRequest.class)))
            .thenThrow(ThrottlingException.builder().message("Throttled").build());

        assertThrows(AvpAuthorizationException.class, () ->
            avpClient.checkFieldAccess(USER_ID, ROLE, "employees", List.of("name")));
    }

    @Test
    void checkFieldAccess_returnsAllFieldsWhenAllAllowed() {
        BatchIsAuthorizedResponse response = BatchIsAuthorizedResponse.builder()
            .results(List.of(
                BatchIsAuthorizedOutputItem.builder().decision(Decision.ALLOW).build(),
                BatchIsAuthorizedOutputItem.builder().decision(Decision.ALLOW).build(),
                BatchIsAuthorizedOutputItem.builder().decision(Decision.ALLOW).build()
            ))
            .build();
        when(avpSdkClient.batchIsAuthorized(any(BatchIsAuthorizedRequest.class))).thenReturn(response);

        Set<String> allowed = avpClient.checkFieldAccess(
            USER_ID, ROLE, "employees", List.of("name", "phoneNumber", "email"));

        assertEquals(Set.of("name", "phoneNumber", "email"), allowed);
    }

    @Test
    void timeoutConfigurations_areCorrect() {
        assertEquals(Duration.ofSeconds(5), avpClient.getLayer1Timeout());
        assertEquals(Duration.ofSeconds(10), avpClient.getLayer3Timeout());
        assertEquals(Duration.ofSeconds(10), avpClient.getFieldMaskingTimeout());
    }

    @Test
    void checkEntityTypeAccess_throwsOnUnexpectedException() {
        when(avpSdkClient.batchIsAuthorized(any(BatchIsAuthorizedRequest.class)))
            .thenThrow(new RuntimeException("Connection refused"));

        assertThrows(AvpAuthorizationException.class, () ->
            avpClient.checkEntityTypeAccess(USER_ID, ROLE, List.of("employees")));
    }

    @Test
    void checkResultAccess_withEmptyContext_succeeds() {
        BatchIsAuthorizedResponse response = BatchIsAuthorizedResponse.builder()
            .results(List.of(
                BatchIsAuthorizedOutputItem.builder().decision(Decision.ALLOW).build()
            ))
            .build();
        when(avpSdkClient.batchIsAuthorized(any(BatchIsAuthorizedRequest.class))).thenReturn(response);

        Set<String> allowed = avpClient.checkResultAccess(
            USER_ID, ROLE, Map.of(), "employees", List.of("emp-001"), List.of(Map.of()));

        assertEquals(Set.of("emp-001"), allowed);
    }
}
