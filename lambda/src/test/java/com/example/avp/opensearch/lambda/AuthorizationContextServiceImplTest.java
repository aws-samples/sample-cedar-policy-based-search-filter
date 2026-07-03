package com.example.avp.opensearch.lambda;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AuthorizationContextServiceImpl.
 * Verifies role-based organizational context derivation.
 */
class AuthorizationContextServiceImplTest {

    private AuthorizationContextServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthorizationContextServiceImpl();
    }

    @Test
    void usHrManagerReturnsUsRegion() {
        OrganizationalContext context = service.getOrganizationalContext("bob", "USHRManager");

        assertEquals(List.of("US"), context.regions());
        assertTrue(context.departments().isEmpty());
        assertTrue(context.roles().isEmpty());
        assertTrue(context.teams().isEmpty());
    }

    @Test
    void employeeReturnsEmptyContext() {
        OrganizationalContext context = service.getOrganizationalContext("alice", "Employee");

        assertTrue(context.regions().isEmpty());
        assertTrue(context.departments().isEmpty());
        assertTrue(context.roles().isEmpty());
        assertTrue(context.teams().isEmpty());
    }

    @Test
    void globalHrAdminReturnsEmptyContext() {
        OrganizationalContext context = service.getOrganizationalContext("carol", "GlobalHRAdmin");

        assertTrue(context.regions().isEmpty());
        assertTrue(context.departments().isEmpty());
        assertTrue(context.roles().isEmpty());
        assertTrue(context.teams().isEmpty());
    }

    @Test
    void recruiterReturnsEmptyContext() {
        OrganizationalContext context = service.getOrganizationalContext("dave", "Recruiter");

        assertTrue(context.regions().isEmpty());
        assertTrue(context.departments().isEmpty());
        assertTrue(context.roles().isEmpty());
        assertTrue(context.teams().isEmpty());
    }

    @Test
    void unknownRoleReturnsEmptyContext() {
        OrganizationalContext context = service.getOrganizationalContext("unknown", "SomeOtherRole");

        assertTrue(context.regions().isEmpty());
        assertTrue(context.departments().isEmpty());
        assertTrue(context.roles().isEmpty());
        assertTrue(context.teams().isEmpty());
    }

    @Test
    void nullRoleThrowsException() {
        assertThrows(IllegalArgumentException.class,
            () -> service.getOrganizationalContext("user1", null));
    }
}
