package com.estatevista.property;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.estatevista.common.response.ApiResponse;
import com.estatevista.common.validation.UnknownFieldGuard;
import com.estatevista.property.dto.CreatePropertyRequest;
import com.estatevista.property.dto.PagedResponse;
import com.estatevista.property.dto.PropertyAssignmentRequest;
import com.estatevista.property.dto.PropertyDetailResponse;
import com.estatevista.property.dto.PropertySummaryResponse;
import com.estatevista.property.dto.PropertyUpdateRequest;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Stage 4.2 property CRUD and discovery endpoints.
 *
 * <p>Only the approved routes are mapped here. Explicitly deferred routes
 * (featured, status changes, reference lookups, and images) remain unmapped and
 * denied by the security filter chain.</p>
 */
@RestController
public class PropertyController {

    private static final String PUBLIC_DEFAULT_SIZE = "12";
    private static final String MANAGEMENT_DEFAULT_SIZE = "20";

    private final PropertyService service;

    public PropertyController(PropertyService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/properties")
    public ResponseEntity<ApiResponse<PropertyDetailResponse>> create(
            Authentication authentication,
            @Valid @RequestBody CreatePropertyRequest request) {
        rejectUnknownFields(request);
        PropertyDetailResponse created = service.create(authentication, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success("Property created", created));
    }

    @GetMapping("/api/v1/properties/{propertyId}")
    public ResponseEntity<ApiResponse<PropertyDetailResponse>> get(
            @PathVariable UUID propertyId, Authentication authentication) {
        return noStore(ApiResponse.success(
                service.get(propertyId, authentication)));
    }

    @GetMapping("/api/v1/properties")
    public ResponseEntity<PagedResponse<PropertySummaryResponse>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID subtypeId,
            @RequestParam(required = false) String listingType,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String locality,
            @RequestParam(required = false) Integer minBedrooms,
            @RequestParam(required = false) Integer maxBedrooms,
            @RequestParam(required = false) List<UUID> amenityIds,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = PUBLIC_DEFAULT_SIZE) int size) {
        return noStore(service.search(keyword, categoryId, subtypeId, listingType,
                minPrice, maxPrice, city, locality, minBedrooms, maxBedrooms,
                amenityIds, sort, page, size));
    }

    @GetMapping("/api/v1/properties/recent")
    public ResponseEntity<PagedResponse<PropertySummaryResponse>> recent(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = PUBLIC_DEFAULT_SIZE) int size) {
        return noStore(service.recent(page, size));
    }

    @GetMapping("/api/v1/properties/{propertyId}/related")
    public ResponseEntity<PagedResponse<PropertySummaryResponse>> related(
            @PathVariable UUID propertyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = PUBLIC_DEFAULT_SIZE) int size) {
        return noStore(service.related(propertyId, page, size));
    }

    @GetMapping("/api/v1/agents/me/properties")
    public ResponseEntity<PagedResponse<PropertySummaryResponse>> agentProperties(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = MANAGEMENT_DEFAULT_SIZE) int size,
            @RequestParam(required = false) String status) {
        return noStore(service.listAgent(authentication, page, size, status));
    }

    @GetMapping("/api/v1/admin/properties")
    public ResponseEntity<PagedResponse<PropertySummaryResponse>> adminProperties(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = MANAGEMENT_DEFAULT_SIZE) int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID primaryAgentId,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String keyword) {
        return noStore(service.listAdmin(authentication, page, size, status,
                primaryAgentId, categoryId, city, keyword));
    }

    @PatchMapping("/api/v1/properties/{propertyId}")
    public ResponseEntity<ApiResponse<PropertyDetailResponse>> patch(
            @PathVariable UUID propertyId,
            Authentication authentication,
            @Valid @RequestBody PropertyUpdateRequest request) {
        rejectUnknownFields(request);
        return noStore(ApiResponse.success(
                service.update(propertyId, authentication, request, false)));
    }

    @PutMapping("/api/v1/properties/{propertyId}")
    public ResponseEntity<ApiResponse<PropertyDetailResponse>> put(
            @PathVariable UUID propertyId,
            Authentication authentication,
            @Valid @RequestBody PropertyUpdateRequest request) {
        rejectUnknownFields(request);
        return noStore(ApiResponse.success(
                service.update(propertyId, authentication, request, true)));
    }

    @DeleteMapping("/api/v1/properties/{propertyId}")
    public ResponseEntity<ApiResponse<PropertyDetailResponse>> archive(
            @PathVariable UUID propertyId, Authentication authentication) {
        return noStore(ApiResponse.success(
                service.archive(propertyId, authentication)));
    }

    @PatchMapping("/api/v1/admin/properties/{propertyId}/assignment")
    public ResponseEntity<ApiResponse<PropertyDetailResponse>> reassign(
            @PathVariable UUID propertyId,
            Authentication authentication,
            @Valid @RequestBody PropertyAssignmentRequest request) {
        UnknownFieldGuard.reject(request.unknownFields());
        return noStore(ApiResponse.success(
                service.reassign(propertyId, authentication, request)));
    }

    private static void rejectUnknownFields(CreatePropertyRequest request) {
        UnknownFieldGuard.reject(request.unknownFields());
        if (request.getLocation() != null) {
            UnknownFieldGuard.reject(request.getLocation().unknownFields());
        }
        if (request.getResidentialDetails() != null) {
            UnknownFieldGuard.reject(request.getResidentialDetails().unknownFields());
        }
        if (request.getCommercialDetails() != null) {
            UnknownFieldGuard.reject(request.getCommercialDetails().unknownFields());
        }
    }

    private static void rejectUnknownFields(PropertyUpdateRequest request) {
        UnknownFieldGuard.reject(request.unknownFields());
        if (request.getLocation() != null) {
            UnknownFieldGuard.reject(request.getLocation().unknownFields());
        }
        if (request.getResidentialDetails() != null) {
            UnknownFieldGuard.reject(request.getResidentialDetails().unknownFields());
        }
        if (request.getCommercialDetails() != null) {
            UnknownFieldGuard.reject(request.getCommercialDetails().unknownFields());
        }
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
