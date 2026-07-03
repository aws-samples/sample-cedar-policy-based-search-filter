package com.example.avp.opensearch.lambda;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for PreFilterServiceImpl.
 * Verifies OpenSearch filter construction from organizational context.
 */
class PreFilterServiceImplTest {

    private PreFilterServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PreFilterServiceImpl();
    }

    @Test
    void emptyContextProducesNoFilters() {
        OrganizationalContext context = OrganizationalContext.empty();

        Map<String, Object> filters = service.buildFilter(context);

        assertTrue(filters.isEmpty());
    }

    @Test
    void nullContextProducesNoFilters() {
        Map<String, Object> filters = service.buildFilter(null);

        assertTrue(filters.isEmpty());
    }

    @Test
    void singleRegionProducesRegionFilter() {
        OrganizationalContext context = new OrganizationalContext(
            List.of("US"), List.of(), List.of(), List.of()
        );

        Map<String, Object> filters = service.buildFilter(context);

        assertEquals(1, filters.size());
        assertEquals(List.of("US"), filters.get("region"));
    }

    @Test
    void multipleRegionsProducesRegionFilter() {
        OrganizationalContext context = new OrganizationalContext(
            List.of("US", "EU"), List.of(), List.of(), List.of()
        );

        Map<String, Object> filters = service.buildFilter(context);

        assertEquals(1, filters.size());
        assertEquals(List.of("US", "EU"), filters.get("region"));
    }

    @Test
    void singleDepartmentProducesDepartmentFilter() {
        OrganizationalContext context = new OrganizationalContext(
            List.of(), List.of("Engineering"), List.of(), List.of()
        );

        Map<String, Object> filters = service.buildFilter(context);

        assertEquals(1, filters.size());
        assertEquals(List.of("Engineering"), filters.get("department"));
    }

    @Test
    void regionAndDepartmentProducesBothFilters() {
        OrganizationalContext context = new OrganizationalContext(
            List.of("US"), List.of("HR", "Engineering"), List.of(), List.of()
        );

        Map<String, Object> filters = service.buildFilter(context);

        assertEquals(2, filters.size());
        assertEquals(List.of("US"), filters.get("region"));
        assertEquals(List.of("HR", "Engineering"), filters.get("department"));
    }

    @Test
    void rolesAndTeamsDoNotProduceFilters() {
        // roles and teams are part of OrganizationalContext but are not mapped to filter fields
        OrganizationalContext context = new OrganizationalContext(
            List.of(), List.of(), List.of("Admin"), List.of("team-001")
        );

        Map<String, Object> filters = service.buildFilter(context);

        assertTrue(filters.isEmpty());
    }

    @Test
    void bobContextProducesUsRegionFilter() {
        // Simulate Bob (USHRManager) whose context has regions=["US"]
        OrganizationalContext bobContext = new OrganizationalContext(
            List.of("US"), List.of(), List.of(), List.of()
        );

        Map<String, Object> filters = service.buildFilter(bobContext);

        assertEquals(1, filters.size());
        assertEquals(List.of("US"), filters.get("region"));
        assertNull(filters.get("department"));
    }
}
