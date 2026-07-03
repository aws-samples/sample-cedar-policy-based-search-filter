package com.example.avp.opensearch.lambda;

/**
 * Provides the user's organizational memberships for pre-filtering and
 * context-based authorization decisions.
 */
public interface AuthorizationContextService {

    /**
     * Returns the user's organizational memberships including regions,
     * departments, roles, and team associations.
     * On error, implementations should throw an exception so the caller
     * can apply fail-closed behavior.
     *
     * @param userId the authenticated user's identifier
     * @param role   the user's role from Cognito claims
     * @return the user's organizational context
     */
    OrganizationalContext getOrganizationalContext(String userId, String role);
}
