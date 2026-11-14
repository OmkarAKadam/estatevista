package com.estatevista.property.entity;

/**
 * Approved listing types (DATABASE_DESIGN.md §6.3), persisted as text to match
 * the database CHECK constraint.
 */
public enum ListingType {
    SALE,
    RENT
}
