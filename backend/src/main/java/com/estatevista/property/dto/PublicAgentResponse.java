package com.estatevista.property.dto;

import java.util.UUID;

/**
 * Public-safe primary-agent display fields.
 *
 * <p>Email, phone, account status, role internals, password hash, and audit
 * fields are intentionally excluded.</p>
 */
public record PublicAgentResponse(UUID id, String firstName, String lastName) {
}
