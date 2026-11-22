package com.estatevista.property.image;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.common.response.ApiErrorResponse.FieldErrorDetail;
import com.estatevista.common.response.ApiResponse;
import com.estatevista.common.validation.UnknownFieldGuard;
import com.estatevista.property.dto.ImageMetadataUpdateRequest;
import com.estatevista.property.dto.PropertyImageResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/**
 * The five approved Stage 4.5 property image endpoints.
 *
 * <p>No provider-specific route, direct browser upload, image-processing route,
 * or client-selected storage field is exposed.</p>
 */
@RestController
@RequestMapping("/api/v1/properties/{propertyId}/images")
public class PropertyImageController {

    private static final Set<String> ALLOWED_FORM_FIELDS =
            Set.of("file", "altText", "isPrimary", "sortOrder");

    private final PropertyImageService service;

    public PropertyImageController(PropertyImageService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<PropertyImageResponse>> upload(
            @PathVariable UUID propertyId,
            Authentication authentication,
            HttpServletRequest request,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String altText,
            @RequestParam(required = false) Boolean isPrimary,
            @RequestParam(required = false) Integer sortOrder) {
        rejectUnknownFormFields(request);
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(
                        service.upload(
                                propertyId, authentication, file,
                                altText, isPrimary, sortOrder)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<PropertyImageResponse>>> list(
            @PathVariable UUID propertyId, Authentication authentication) {
        return noStore(ApiResponse.success(service.list(propertyId, authentication)));
    }

    @PatchMapping(value = "/{imageId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<PropertyImageResponse>> updateMetadata(
            @PathVariable UUID propertyId,
            @PathVariable UUID imageId,
            Authentication authentication,
            @Valid @RequestBody ImageMetadataUpdateRequest request) {
        UnknownFieldGuard.reject(request.unknownFields());
        return noStore(ApiResponse.success(
                service.updateMetadata(propertyId, imageId, authentication, request)));
    }

    @PatchMapping("/{imageId}/primary")
    public ResponseEntity<ApiResponse<List<PropertyImageResponse>>> setPrimary(
            @PathVariable UUID propertyId,
            @PathVariable UUID imageId,
            Authentication authentication) {
        return noStore(ApiResponse.success(
                service.setPrimary(propertyId, imageId, authentication)));
    }

    @DeleteMapping("/{imageId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID propertyId,
            @PathVariable UUID imageId,
            Authentication authentication) {
        service.delete(propertyId, imageId, authentication);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static void rejectUnknownFormFields(HttpServletRequest request) {
        request.getParameterMap().keySet().stream()
                .filter(field -> !ALLOWED_FORM_FIELDS.contains(field))
                .findFirst()
                .ifPresent(PropertyImageController::throwUnknownField);
        if (request instanceof MultipartHttpServletRequest multipart) {
            multipart.getFileNames().forEachRemaining(field -> {
                if (!ALLOWED_FORM_FIELDS.contains(field)) {
                    throwUnknownField(field);
                }
            });
        }
    }

    private static void throwUnknownField(String field) {
        throw new ApiException(
                ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.defaultMessage(),
                List.of(new FieldErrorDetail(field, "is not an allowed field")));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
