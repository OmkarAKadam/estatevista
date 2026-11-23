package com.estatevista.property.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;

/**
 * Property subtype within a category ({@code property_subtypes},
 * DATABASE_DESIGN.md §6.2).
 *
 * <p>The database enforces both {@code (category_id, name)} uniqueness and the
 * composite target {@code (category_id, id)} required by the properties
 * foreign key (§23.1 A).</p>
 */
@Entity
@Table(name = "property_subtypes")
public class PropertySubtype {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private PropertyCategory category;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public PropertySubtype() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public PropertyCategory getCategory() {
        return category;
    }

    public void setCategory(PropertyCategory category) {
        this.category = category;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
