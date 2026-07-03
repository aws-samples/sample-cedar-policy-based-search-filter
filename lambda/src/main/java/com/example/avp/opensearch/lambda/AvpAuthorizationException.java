package com.example.avp.opensearch.lambda;

/**
 * Exception thrown when an AVP authorization call fails due to SDK errors,
 * timeouts, or other unexpected failures. Callers should apply fail-closed
 * behavior when this exception is caught.
 */
public class AvpAuthorizationException extends RuntimeException {

    public AvpAuthorizationException(String message) {
        super(message);
    }

    public AvpAuthorizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
