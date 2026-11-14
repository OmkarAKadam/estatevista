package com.estatevista.property.entity;

/**
 * Approved property status values (DATABASE_DESIGN.md §6.3), persisted as text
 * to match the database CHECK constraint.
 *
 * <p>Public discovery is restricted to {@link #PUBLISHED} (§6.3, C-10/D-08).
 * That filtering rule belongs to the property discovery implementation, not to
 * this persistence mapping.</p>
 */
public enum PropertyStatus {
    DRAFT,
    PUBLISHED,
    UNAVAILABLE,
    SOLD,
    RENTED,
    ARCHIVED
}
