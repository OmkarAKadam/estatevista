package com.estatevista.favorite.controller;

import java.util.UUID;

import com.estatevista.common.response.ApiResponse;
import com.estatevista.favorite.dto.BatchFavoriteStatusResponse;
import com.estatevista.favorite.dto.FavoriteListResponse;
import com.estatevista.favorite.dto.FavoriteMutationResponse;
import com.estatevista.favorite.dto.FavoriteStatusResponse;
import com.estatevista.favorite.service.FavoriteService;
import com.estatevista.favorite.service.FavoriteService.AddResult;
import com.estatevista.property.dto.PagedResponse;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Thin REST layer for the five approved favorite routes (API §9). */
@RestController
@RequestMapping("/api/v1/favorites")
public class FavoriteController {

    private static final String ADD_MESSAGE = "Property added to favorites";

    private final FavoriteService service;

    public FavoriteController(FavoriteService service) {
        this.service = service;
    }

    @PostMapping("/{propertyId}")
    public ResponseEntity<ApiResponse<FavoriteMutationResponse>> add(
            Authentication authentication, @PathVariable UUID propertyId) {
        AddResult result = service.add(authentication, propertyId);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(ADD_MESSAGE, result.data()));
    }

    @DeleteMapping("/{propertyId}")
    public ResponseEntity<Void> remove(
            Authentication authentication, @PathVariable UUID propertyId) {
        service.remove(authentication, propertyId);
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @GetMapping
    public ResponseEntity<PagedResponse<FavoriteListResponse>> list(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(name = "sort", required = false) String sort) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.list(authentication, page, size, sort));
    }

    /** Literal route is registered before the variable-status route. */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<BatchFavoriteStatusResponse>> batchStatus(
            Authentication authentication,
            @RequestParam(name = "propertyIds", required = false) String propertyIds) {
        return noStore(ApiResponse.success(service.batchStatus(authentication, propertyIds)));
    }

    @GetMapping("/{propertyId}/status")
    public ResponseEntity<ApiResponse<FavoriteStatusResponse>> status(
            Authentication authentication, @PathVariable UUID propertyId) {
        return noStore(ApiResponse.success(service.status(authentication, propertyId)));
    }

    private static <T> ResponseEntity<ApiResponse<T>> noStore(ApiResponse<T> body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
