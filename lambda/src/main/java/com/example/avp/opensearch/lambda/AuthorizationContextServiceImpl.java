package com.example.avp.opensearch.lambda;

import java.util.List;

/**
 * Implementation of AuthorizationContextService that derives organizational
 * memberships from the user's role (as provided in Cognito token claims).
 *
 * In this demo application, roles map to predefined organizational context:
 * - USHRManager → regions=["US"] (restricted to US region)
 * - Employee, GlobalHRAdmin, Recruiter → empty lists (no pre-filters)
 */
public class AuthorizationContextServiceImpl implements AuthorizationContextService {

    @Override
    public OrganizationalContext getOrganizationalContext(String userId, String role) {
        if (role == null) {
            throw new IllegalArgumentException("Role must not be null");
        }

        return switch (role) {
            case "USHRManager" -> new OrganizationalContext(
                List.of("US"),
                List.of(),
                List.of(),
                List.of()
            );
            case "Employee", "GlobalHRAdmin", "Recruiter" -> OrganizationalContext.empty();
            default -> OrganizationalContext.empty();
        };
    }
}
