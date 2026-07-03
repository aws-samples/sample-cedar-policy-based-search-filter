package com.example.avp.opensearch.lambda;

/**
 * Represents an incoming search request from the HR Portal frontend.
 *
 * @param query the search term submitted by the user
 */
public record SearchRequest(String query) {}
