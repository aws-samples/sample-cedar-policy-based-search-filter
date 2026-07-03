package com.example.avp.opensearch.lambda;

import java.util.List;

/**
 * Represents a user's organizational memberships used for pre-filtering
 * and context-based authorization decisions.
 */
public record OrganizationalContext(
    List<String> regions,
    List<String> departments,
    List<String> roles,
    List<String> teams
) {

    /**
     * Creates an empty organizational context with no memberships.
     */
    public static OrganizationalContext empty() {
        return new OrganizationalContext(List.of(), List.of(), List.of(), List.of());
    }
}
