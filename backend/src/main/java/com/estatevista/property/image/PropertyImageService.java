package com.estatevista.property.image;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.estatevista.authorization.AccountAuthorizationService;
import com.estatevista.authorization.AuthenticatedAccount;
import com.estatevista.authorization.PlatformRole;
import com.estatevista.authorization.ResourceAuthorizationService;
import com.estatevista.audit.AuditEventService;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.common.response.ApiErrorResponse.FieldErrorDetail;
import com.estatevista.property.dto.ImageMetadataUpdateRequest;
import com.estatevista.property.dto.PropertyImageResponse;
import com.estatevista.property.entity.Property;
import com.estatevista.property.entity.PropertyImage;
import com.estatevista.property.entity.PropertyImageDeletionOutbox;
import com.estatevista.property.entity.PropertyStatus;
import com.estatevista.property.repository.PropertyImageDeletionOutboxRepository;
import com.estatevista.property.repository.PropertyImageRepository;
import com.estatevista.property.repository.PropertyRepository;
import com.estatevista.security.RateLimitService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Backend-mediated property image management (Stage 4.5).
 *
 * <p>The storage adapter is optional by design: no production provider is
 * configured until the separate D-08/G-11 approval is granted. Mutations fail
 * closed with a safe 503 when no adapter is available; integration tests supply
 * a deterministic fake adapter.</p>
 *
 * <p>Image deletion follows the approved durable-outbox ADR: active metadata and
 * a cleanup task commit atomically, then provider deletion occurs after commit.
 * Provider cleanup is eventual, retried, and idempotent; the API never deletes
 * provider bytes before the metadata transaction commits.</p>
 */
@Service
@Transactional(readOnly = true)
public class PropertyImageService {

    private static final Logger log = LoggerFactory.getLogger(PropertyImageService.class);
    private static final int MAX_IMAGES_PER_PROPERTY = 10;

    private final PropertyRepository propertyRepository;
    private final PropertyImageRepository imageRepository;
    private final PropertyImageDeletionOutboxRepository outboxRepository;
    private final AccountAuthorizationService accounts;
    private final ResourceAuthorizationService resources;
    private final AuditEventService audit;
    private final RateLimitService rateLimitService;
    private final PropertyImageProcessor processor;
    private final ObjectProvider<ImageStorage> storageProvider;
    private final ApplicationEventPublisher events;

    public PropertyImageService(
            PropertyRepository propertyRepository,
            PropertyImageRepository imageRepository,
            PropertyImageDeletionOutboxRepository outboxRepository,
            AccountAuthorizationService accounts,
            ResourceAuthorizationService resources,
            AuditEventService audit,
            RateLimitService rateLimitService,
            PropertyImageProcessor processor,
            ObjectProvider<ImageStorage> storageProvider,
            ApplicationEventPublisher events) {
        this.propertyRepository = propertyRepository;
        this.imageRepository = imageRepository;
        this.outboxRepository = outboxRepository;
        this.accounts = accounts;
        this.resources = resources;
        this.audit = audit;
        this.rateLimitService = rateLimitService;
        this.processor = processor;
        this.storageProvider = storageProvider;
        this.events = events;
    }

    @Transactional
    public PropertyImageResponse upload(
            UUID propertyId,
            Authentication authentication,
            MultipartFile file,
            String altText,
            Boolean isPrimary,
            Integer sortOrder) {
        AuthenticatedAccount actor = accounts.requireActive(authentication);
        Property property = requireLockedProperty(propertyId);
        resources.requirePropertyManagement(actor, propertyId);
        rejectArchived(actor, property);
        rateLimitService.acquire(RateLimitService.SCOPE_IMAGE_UPLOAD, actor.id().toString());

        if (imageRepository.countByProperty_Id(propertyId) >= MAX_IMAGES_PER_PROPERTY) {
            throw conflict("A property can have at most 10 images");
        }
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (altText != null && altText.length() > 255) {
            errors.add(fieldError("altText", "must be at most 255 characters"));
        }
        if (sortOrder != null && sortOrder < 0) {
            errors.add(fieldError("sortOrder", "must be zero or greater"));
        }
        throwIfInvalid(errors);

        SanitizedImage sanitized = processor.sanitize(file);
        ImageStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            audit.recordImageFailure(
                    propertyId, null, actor.id(), "UPLOAD", "PROVIDER_NOT_CONFIGURED");
            throw serviceUnavailable();
        }

        String storagePublicId = UUID.randomUUID().toString();
        StoredImage stored;
        try {
            stored = storage.upload(propertyId, storagePublicId, sanitized);
            validateStored(stored);
        } catch (ApiException exception) {
            audit.recordImageFailure(
                    propertyId, null, actor.id(), "UPLOAD", "PROVIDER_FAILURE");
            throw exception;
        } catch (RuntimeException exception) {
            audit.recordImageFailure(
                    propertyId, null, actor.id(), "UPLOAD", "PROVIDER_FAILURE");
            throw serviceUnavailable();
        }

        UUID previousPrimaryId = null;
        if (Boolean.TRUE.equals(isPrimary)) {
            List<PropertyImage> primaries =
                    imageRepository.findByProperty_IdAndIsPrimaryTrue(propertyId);
            previousPrimaryId = primaries.isEmpty() ? null : primaries.get(0).getId();
            primaries.forEach(image -> image.setIsPrimary(false));
            imageRepository.saveAll(primaries);
            imageRepository.flush();
        }

        PropertyImage image = new PropertyImage();
        image.setProperty(property);
        image.setStorageProvider(stored.storageProvider());
        image.setStoragePublicId(stored.storagePublicId());
        image.setImageUrl(stored.imageUrl());
        image.setAltText(trimToNull(altText));
        image.setSortOrder(sortOrder == null
                ? imageRepository.countByProperty_Id(propertyId)
                : sortOrder);
        image.setIsPrimary(Boolean.TRUE.equals(isPrimary));

        PropertyImage saved;
        try {
            saved = imageRepository.saveAndFlush(image);
            if (sortOrder != null) {
                applyRequestedOrder(propertyId, saved.getId(), sortOrder);
            }
        } catch (DataIntegrityViolationException exception) {
            safeDelete(storage, stored, propertyId, actor.id(), "PRIMARY_CONFLICT");
            throw conflict("The requested primary image conflicts with the current state");
        } catch (RuntimeException exception) {
            safeDelete(storage, stored, propertyId, actor.id(), "DATABASE_FAILURE");
            audit.recordImageFailure(
                    propertyId, null, actor.id(), "UPLOAD", "DATABASE_FAILURE");
            throw new ApiException(
                    ErrorCode.INTERNAL_ERROR,
                    "The image could not be recorded.");
        }

        audit.recordImageUpload(
                propertyId, saved.getId(), actor.id(), stored.storageProvider());
        return toResponse(saved);
    }

    public List<PropertyImageResponse> list(
            UUID propertyId, Authentication authentication) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(PropertyImageService::notFound);
        if (property.getStatus() == PropertyStatus.PUBLISHED) {
            return responses(imageRepository.findByProperty_IdOrderBySortOrderAsc(propertyId));
        }

        var account = accounts.resolve(authentication);
        if (account.isEmpty()
                || !mayManageVisibleNonPublished(property, account.get())) {
            throw notFound();
        }
        return responses(imageRepository.findByProperty_IdOrderBySortOrderAsc(propertyId));
    }

    @Transactional
    public PropertyImageResponse updateMetadata(
            UUID propertyId,
            UUID imageId,
            Authentication authentication,
            ImageMetadataUpdateRequest request) {
        AuthenticatedAccount actor = accounts.requireActive(authentication);
        Property property = requireLockedProperty(propertyId);
        resources.requirePropertyManagement(actor, propertyId);
        rejectArchived(actor, property);
        PropertyImage image = requireImage(imageId, propertyId);

        List<FieldErrorDetail> errors = new ArrayList<>();
        if (request.getAltText() != null && request.getAltText().length() > 255) {
            errors.add(fieldError("altText", "must be at most 255 characters"));
        }
        if (request.getSortOrder() != null && request.getSortOrder() < 0) {
            errors.add(fieldError("sortOrder", "must be zero or greater"));
        }
        if (request.hasField("sortOrder") && request.getSortOrder() == null) {
            errors.add(fieldError("sortOrder", "must be zero or greater when supplied"));
        }
        throwIfInvalid(errors);

        boolean orderChanged = false;
        if (request.hasField("altText")) {
            image.setAltText(trimToNull(request.getAltText()));
        }
        if (request.hasField("sortOrder")) {
            orderChanged = true;
        }

        try {
            if (orderChanged) {
                applyRequestedOrder(propertyId, imageId, request.getSortOrder());
            } else {
                imageRepository.saveAndFlush(image);
            }
        } catch (DataIntegrityViolationException exception) {
            audit.recordImageFailure(
                    propertyId, imageId, actor.id(), "METADATA", "ORDER_CONFLICT");
            throw conflict("The requested image order conflicts with the current state");
        } catch (RuntimeException exception) {
            audit.recordImageFailure(
                    propertyId, imageId, actor.id(), "METADATA", "DATABASE_FAILURE");
            throw new ApiException(
                    ErrorCode.INTERNAL_ERROR,
                    "The image metadata could not be saved.");
        }

        audit.recordImageMetadata(propertyId, imageId, actor.id());
        return toResponse(imageRepository.findByIdAndProperty_Id(imageId, propertyId)
                .orElseThrow(PropertyImageService::notFound));
    }

    @Transactional
    public List<PropertyImageResponse> setPrimary(
            UUID propertyId, UUID imageId, Authentication authentication) {
        AuthenticatedAccount actor = accounts.requireActive(authentication);
        Property property = requireLockedProperty(propertyId);
        resources.requirePropertyManagement(actor, propertyId);
        rejectArchived(actor, property);
        requireImage(imageId, propertyId);

        UUID previousPrimaryId = null;
        try {
            List<PropertyImage> primaries =
                    imageRepository.findByProperty_IdAndIsPrimaryTrue(propertyId);
            for (PropertyImage primary : primaries) {
                if (!primary.getId().equals(imageId)) {
                    previousPrimaryId = primary.getId();
                    primary.setIsPrimary(false);
                }
            }
            imageRepository.saveAll(primaries);
            imageRepository.flush();

            PropertyImage target = imageRepository
                    .findByIdAndProperty_Id(imageId, propertyId)
                    .orElseThrow(PropertyImageService::notFound);
            target.setIsPrimary(true);
            imageRepository.saveAndFlush(target);
        } catch (DataIntegrityViolationException exception) {
            audit.recordImageFailure(
                    propertyId, imageId, actor.id(), "PRIMARY", "PRIMARY_CONFLICT");
            throw conflict("The primary image changed concurrently");
        }

        audit.recordImagePrimary(propertyId, imageId, previousPrimaryId, actor.id());
        return responses(imageRepository.findByProperty_IdOrderBySortOrderAsc(propertyId));
    }

    @Transactional
    public void delete(
            UUID propertyId, UUID imageId, Authentication authentication) {
        AuthenticatedAccount actor = accounts.requireActive(authentication);
        Property property = requireLockedProperty(propertyId);
        resources.requirePropertyManagement(actor, propertyId);
        rejectArchived(actor, property);
        PropertyImage image = requireImage(imageId, propertyId);

        if (storageProvider.getIfAvailable() == null) {
            audit.recordImageFailure(
                    propertyId, imageId, actor.id(), "DELETE", "PROVIDER_NOT_CONFIGURED");
            throw serviceUnavailable();
        }

        Instant now = Instant.now();
        UUID taskId = UUID.randomUUID();
        PropertyImageDeletionOutbox cleanupTask = new PropertyImageDeletionOutbox(
                taskId,
                propertyId,
                imageId,
                image.getStorageProvider(),
                image.getStoragePublicId(),
                actor.id(),
                now);

        try {
            imageRepository.delete(image);
            imageRepository.flush();
            outboxRepository.saveAndFlush(cleanupTask);
            audit.recordImageDeleteInTransaction(propertyId, imageId, actor.id());
        } catch (RuntimeException exception) {
            audit.recordImageFailure(
                    propertyId, imageId, actor.id(), "DELETE", "DATABASE_FAILURE");
            throw new ApiException(
                    ErrorCode.INTERNAL_ERROR,
                    "The image could not be removed from metadata.");
        }

        events.publishEvent(new ImageDeletionRequested(
                taskId, propertyId, imageId, actor.id()));
    }

    private void applyRequestedOrder(UUID propertyId, UUID imageId, int requestedOrder) {
        List<PropertyImage> images = new ArrayList<>(
                imageRepository.findByProperty_IdOrderBySortOrderAsc(propertyId));
        PropertyImage target = images.stream()
                .filter(image -> image.getId().equals(imageId))
                .findFirst()
                .orElseThrow(PropertyImageService::notFound);
        images.remove(target);
        int destination = Math.max(0, Math.min(requestedOrder, images.size()));
        images.add(destination, target);
        for (int index = 0; index < images.size(); index++) {
            images.get(index).setSortOrder(index);
        }
        imageRepository.saveAll(images);
        imageRepository.flush();
    }

    private Property requireLockedProperty(UUID propertyId) {
        return propertyRepository.findByIdForUpdate(propertyId)
                .orElseThrow(PropertyImageService::notFound);
    }

    private PropertyImage requireImage(UUID imageId, UUID propertyId) {
        return imageRepository.findByIdAndProperty_Id(imageId, propertyId)
                .orElseThrow(PropertyImageService::notFound);
    }

    private static boolean mayManageVisibleNonPublished(
            Property property, AuthenticatedAccount actor) {
        if (actor.role() == PlatformRole.ADMIN) {
            return true;
        }
        return actor.role() == PlatformRole.AGENT
                && property.getPrimaryAgent() != null
                && Objects.equals(property.getPrimaryAgent().getId(), actor.id());
    }

    private static void rejectArchived(AuthenticatedAccount actor, Property property) {
        if (property.getStatus() != PropertyStatus.ARCHIVED) {
            return;
        }
        if (actor.role() == PlatformRole.AGENT) {
            throw new ApiException(
                    ErrorCode.FORBIDDEN,
                    "Archived properties cannot be modified by agents");
        }
        throw conflict("Archived properties cannot be modified");
    }

    private static void validateStored(StoredImage stored) {
        try {
            URI uri = URI.create(stored.imageUrl());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("Scheme");
            }
        } catch (IllegalArgumentException exception) {
            throw new ApiException(
                    ErrorCode.SERVICE_UNAVAILABLE,
                    "Image storage is temporarily unavailable.");
        }
    }

    private void safeDelete(
            ImageStorage storage, StoredImage stored,
            UUID propertyId, UUID actorId, String reason) {
        try {
            storage.delete(stored.storageProvider(), stored.storagePublicId());
        } catch (RuntimeException cleanupFailure) {
            log.warn(
                    "Image cleanup failed propertyId={} reason={} error={}",
                    propertyId,
                    reason,
                    cleanupFailure.getClass().getSimpleName());
            audit.recordImageFailure(propertyId, null, actorId, "CLEANUP", reason);
        }
    }

    private static List<PropertyImageResponse> responses(List<PropertyImage> images) {
        return images.stream().map(PropertyImageService::toResponse).toList();
    }

    private static PropertyImageResponse toResponse(PropertyImage image) {
        return new PropertyImageResponse(
                image.getId(),
                image.getImageUrl(),
                image.getAltText(),
                image.getSortOrder(),
                image.getIsPrimary());
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static void throwIfInvalid(List<FieldErrorDetail> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    ErrorCode.VALIDATION_ERROR.defaultMessage(),
                    errors);
        }
    }

    private static FieldErrorDetail fieldError(String field, String message) {
        return new FieldErrorDetail(field, message);
    }

    private static ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, message);
    }

    private static ApiException serviceUnavailable() {
        return new ApiException(
                ErrorCode.SERVICE_UNAVAILABLE,
                "Image storage is temporarily unavailable.");
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND);
    }
}
