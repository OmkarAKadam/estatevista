package com.estatevista.auth.dto;

/**
 * CSRF bootstrap response for the Vanilla JavaScript client.
 *
 * <p>Only the token value and the header name required to echo it are exposed.
 * The token must never be logged or treated as a session identifier.</p>
 */
public record CsrfTokenResponse(String headerName, String token) {
}
