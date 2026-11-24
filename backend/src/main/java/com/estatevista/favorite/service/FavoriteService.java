package com.estatevista.favorite.service;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.estatevista.authorization.AccountAuthorizationService;
import com.estatevista.authorization.AuthenticatedAccount;
import com.estatevista.authorization.ResourceAuthorizationService;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.favorite.dto.BatchFavoriteStatusResponse;
import com.estatevista.favorite.dto.FavoriteListResponse;
import com.estatevista.favorite.dto.FavoriteMutationResponse;
import com.estatevista.favorite.dto.FavoriteStatusResponse;
import com.estatevista.favorite.dto.RestrictedFavoritePropertyResponse;
import com.estatevista.favorite.entity.Favorite;
import com.estatevista.favorite.repository.FavoriteRepository;
import com.estatevista.property.PropertySummaryMapper;
import com.estatevista.property.dto.PageMeta;
import com.estatevista.property.dto.PagedResponse;
import com.estatevista.property.dto.PropertySummaryResponse;
import com.estatevista.property.entity.Property;
import com.estatevista.property.entity.PropertyStatus;
import com.estatevista.property.repository.PropertyRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Buyer-scoped favorites business service (Stage 6 decision register §5.1).
 *
 * <p>Buyer identity comes only from the revalidated session principal. Mutation
 * queries are scoped by that buyer, publication is checked while the property
 * row is locked, and concurrent duplicate adds are absorbed by the preserved
 * database unique constraint via an {@code ON CONFLICT DO NOTHING} insert.</p>
 */
@Service
public class FavoriteService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_BATCH_IDS = 100;
    private static final String SORT_NEWEST = "newest";
    private static final String SORT_OLDEST = "oldest";

    private final FavoriteRepository favorites;
    private final PropertyRepository properties;
    private final PropertySummaryMapper summaryMapper;
    private final AccountAuthorizationService accounts;
    private final ResourceAuthorizationService resources;

    public FavoriteService(
            FavoriteRepository favorites,
            PropertyRepository properties,
            PropertySummaryMapper summaryMapper,
            AccountAuthorizationService accounts,
            ResourceAuthorizationService resources) {
        this.favorites = favorites;
        this.properties = properties;
        this.summaryMapper = summaryMapper;
        this.accounts = accounts;
        this.resources = resources;
    }

    /** Result of an add request; {@code created} selects HTTP 201 versus 200. */
    public record AddResult(FavoriteMutationResponse data, boolean created) {
    }

    @Transactional
    public AddResult add(Authentication authentication, UUID propertyId) {
        UUID buyerId = requireBuyerId(authentication);
        requirePropertyId(propertyId);

        // The row lock serializes this decision against property lifecycle
        // changes. A concurrent add cannot pass the publication check after a
        // competing unpublish commits, and vice versa.
        Property property = properties.findByIdForUpdate(propertyId)
                .orElseThrow(FavoriteService::notFound);
        if (property.getStatus() != PropertyStatus.PUBLISHED) {
            throw notFound();
        }

        int inserted = favorites.insertIgnoringDuplicate(
                UUID.randomUUID(), buyerId, propertyId);
        if (inserted == 1) {
            return new AddResult(new FavoriteMutationResponse(propertyId, true), true);
        }

        // Zero rows means the unique constraint absorbed a concurrent insert,
        // or a concurrent removal committed between the insert and lookup.
        // Re-read once within the same transaction before deciding 200/201.
        if (favorites.existsByBuyer_IdAndProperty_Id(buyerId, propertyId)) {
            return new AddResult(new FavoriteMutationResponse(propertyId, true), false);
        }
        inserted = favorites.insertIgnoringDuplicate(
                UUID.randomUUID(), buyerId, propertyId);
        if (inserted == 1) {
            return new AddResult(new FavoriteMutationResponse(propertyId, true), true);
        }
        if (favorites.existsByBuyer_IdAndProperty_Id(buyerId, propertyId)) {
            return new AddResult(new FavoriteMutationResponse(propertyId, true), false);
        }
        throw notFound();
    }

    @Transactional
    public void remove(Authentication authentication, UUID propertyId) {
        UUID buyerId = requireBuyerId(authentication);
        requirePropertyId(propertyId);
        favorites.deleteByBuyer_IdAndProperty_Id(buyerId, propertyId);
    }

    @Transactional(readOnly = true)
    public PagedResponse<FavoriteListResponse> list(
            Authentication authentication, int page, int size, String sort) {
        UUID buyerId = requireBuyerId(authentication);
        // An absent parameter uses the approved default; an explicitly supplied
        // blank value is not one of the two approved sort modes.
        String resolvedSort = sort == null ? SORT_NEWEST : sort;
        validatePagination(page, size, resolvedSort);

        Sort.Direction direction = SORT_NEWEST.equals(resolvedSort)
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(direction, "createdAt").and(Sort.by(direction, "id")));
        Page<Favorite> stored = favorites.findPageByBuyer(buyerId, pageable);

        List<Property> published = stored.getContent().stream()
                .map(Favorite::getProperty)
                .filter(property -> property != null
                        && property.getStatus() == PropertyStatus.PUBLISHED)
                .toList();
        Map<UUID, PropertySummaryResponse> summaries = summaryMapper.mapById(published);
        Map<UUID, Object> views = new LinkedHashMap<>();
        summaries.forEach((id, summary) -> views.put(id, summary));

        Page<FavoriteListResponse> mapped = stored.map(favorite -> {
            Property property = favorite.getProperty();
            Object view = property != null && property.getStatus() == PropertyStatus.PUBLISHED
                    ? views.get(property.getId())
                    : null;
            return new FavoriteListResponse(
                    property == null ? null : property.getId(),
                    favorite.getCreatedAt(),
                    view == null ? RestrictedFavoritePropertyResponse.unavailable() : view);
        });
        return new PagedResponse<>(true, mapped.getContent(), PageMeta.from(mapped));
    }

    @Transactional(readOnly = true)
    public FavoriteStatusResponse status(Authentication authentication, UUID propertyId) {
        UUID buyerId = requireBuyerId(authentication);
        requirePropertyId(propertyId);
        boolean saved = favorites.existsByBuyer_IdAndProperty_IdAndProperty_Status(
                buyerId, propertyId, PropertyStatus.PUBLISHED);
        return new FavoriteStatusResponse(propertyId, saved);
    }

    @Transactional(readOnly = true)
    public BatchFavoriteStatusResponse batchStatus(
            Authentication authentication, String propertyIds) {
        UUID buyerId = requireBuyerId(authentication);
        List<UUID> requested = parseBatch(propertyIds);
        if (requested.isEmpty()) {
            throw badRequest();
        }
        List<UUID> saved = favorites.findPublishedPropertyIds(
                buyerId, PropertyStatus.PUBLISHED, requested);
        Set<UUID> savedIds = saved == null ? Set.of() : new HashSet<>(saved);
        List<FavoriteStatusResponse> results = requested.stream()
                .map(id -> new FavoriteStatusResponse(id, savedIds.contains(id)))
                .toList();
        return new BatchFavoriteStatusResponse(results);
    }

    private UUID requireBuyerId(Authentication authentication) {
        AuthenticatedAccount account = accounts.requireActive(authentication);
        resources.requireBuyer(account);
        return account.id();
    }

    private static void requirePropertyId(UUID propertyId) {
        if (propertyId == null) {
            throw badRequest();
        }
    }

    private static void validatePagination(int page, int size, String sort) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE
                || !(SORT_NEWEST.equals(sort) || SORT_OLDEST.equals(sort))) {
            throw badRequest();
        }
    }

    private static List<UUID> parseBatch(String raw) {
        if (raw == null || raw.isBlank()) {
            throw badRequest();
        }
        String[] supplied = raw.split(",", -1);
        if (supplied.length > MAX_BATCH_IDS) {
            throw badRequest();
        }
        LinkedHashSet<UUID> unique = new LinkedHashSet<>();
        for (String value : supplied) {
            unique.add(parseCanonicalLowercase(value));
        }
        return List.copyOf(unique);
    }

    private static UUID parseCanonicalLowercase(String value) {
        if (value == null || value.isBlank()
                || !value.equals(value.toLowerCase(Locale.ROOT))) {
            throw badRequest();
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw badRequest();
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw badRequest();
        }
    }

    private static ApiException badRequest() {
        return new ApiException(
                ErrorCode.BAD_REQUEST, ErrorCode.BAD_REQUEST.defaultMessage());
    }

    private static ApiException notFound() {
        return new ApiException(
                ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.defaultMessage());
    }
}
