package com.estatevista.property.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Paginated success envelope with {@code data} and {@code meta} as siblings.
 *
 * <p>This is deliberately not {@code ApiResponse<T>}: the approved contract
 * forbids nesting pagination metadata inside {@code data}.</p>
 */
public record PagedResponse<T>(boolean success, List<T> data, PageMeta meta) {

    public static <T> PagedResponse<T> of(Page<T> page) {
        return new PagedResponse<>(true, page.getContent(), PageMeta.from(page));
    }
}
