package com.estatevista.property.dto;

import org.springframework.data.domain.Page;

/** Exact metadata fields required by the paged API envelope. */
public record PageMeta(
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious) {

    public static PageMeta from(Page<?> page) {
        return new PageMeta(
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext(),
                page.hasPrevious());
    }
}
