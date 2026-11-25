package com.estatevista.inquiry.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.estatevista.authorization.AccountAuthorizationService;
import com.estatevista.authorization.AuthenticatedAccount;
import com.estatevista.authorization.ResourceAuthorizationService;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.inquiry.dto.InquiryDetailResponse;
import com.estatevista.inquiry.dto.InquiryListItemResponse;
import com.estatevista.inquiry.dto.InquirySubmissionResponse;
import com.estatevista.inquiry.dto.InquirySubmitRequest;
import com.estatevista.inquiry.entity.Inquiry;
import com.estatevista.inquiry.repository.InquiryRepository;
import com.estatevista.property.dto.PageMeta;
import com.estatevista.property.dto.PagedResponse;
import com.estatevista.property.entity.Property;
import com.estatevista.property.entity.PropertyStatus;
import com.estatevista.property.repository.PropertyRepository;
import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.UserRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stage 6.4 inquiry business service.
 *
 * <p>Buyer identity is derived exclusively from the authenticated principal.
 * Assigned-agent identity is captured from the property's current assignment at
 * submission time and never rewritten by later reassignment. Duplicate active
 * inquiries are prevented by the V18 partial unique index as the final
 * concurrency authority; the native insert uses ON CONFLICT DO NOTHING on that
 * exact predicate to avoid rollback-only failures.</p>
 */
@Service
public class InquiryService {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;
    private static final String SORT_NEWEST = "newest";
    private static final String SORT_OLDEST = "oldest";
    private static final Set<String> ALLOWED_STATUSES =
            Set.of("NEW", "IN_PROGRESS", "RESOLVED", "CLOSED");
    private static final Set<String> ACTIVE_STATUSES = Set.of("NEW", "IN_PROGRESS");
    private static final String UNAVAILABLE_PROPERTY_TITLE = "Property no longer available";

    private final InquiryRepository inquiries;
    private final PropertyRepository properties;
    private final UserRepository users;
    private final AccountAuthorizationService accounts;
    private final ResourceAuthorizationService resources;

    public InquiryService(
            InquiryRepository inquiries,
            PropertyRepository properties,
            UserRepository users,
            AccountAuthorizationService accounts,
            ResourceAuthorizationService resources) {
        this.inquiries = inquiries;
        this.properties = properties;
        this.users = users;
        this.accounts = accounts;
        this.resources = resources;
    }

    @Transactional
    public InquirySubmissionResponse submit(
            Authentication authentication, UUID propertyId, InquirySubmitRequest request) {
        UUID buyerId = requireBuyerId(authentication);
        if (propertyId == null) {
            throw badRequest();
        }

        Property property = properties.findByIdForUpdate(propertyId)
                .orElseThrow(InquiryService::notFound);
        if (property.getStatus() != PropertyStatus.PUBLISHED) {
            throw notFound();
        }

        User assignedAgent = property.getPrimaryAgent();
        if (assignedAgent == null || assignedAgent.getId() == null) {
            throw notFound();
        }

        UUID agentId = assignedAgent.getId();
        lockUsersInOrder(buyerId, agentId);

        User buyer = users.findById(buyerId).orElseThrow(InquiryService::notFound);
        User agent = users.findById(agentId).orElseThrow(InquiryService::notFound);
        if (buyer.getAccountStatus() != AccountStatus.ACTIVE
                || !"BUYER".equals(buyer.getRole().getName())) {
            throw notFound();
        }
        if (agent.getAccountStatus() != AccountStatus.ACTIVE
                || !"AGENT".equals(agent.getRole().getName())) {
            throw notFound();
        }

        UUID inquiryId = UUID.randomUUID();
        int inserted = inquiries.insertNewIgnoringActiveDuplicate(
                inquiryId,
                buyerId,
                propertyId,
                agentId,
                request.getSubject(),
                request.getMessage());
        if (inserted == 1) {
            Inquiry saved = inquiries.findById(inquiryId)
                    .orElseThrow(InquiryService::notFound);
            return new InquirySubmissionResponse(
                    saved.getId(), propertyId, saved.getStatus(), saved.getCreatedAt());
        }

        // ON CONFLICT DO NOTHING on the exact partial unique active-inquiry index
        // returns 0 only for that conflict. Other constraint violations throw.
        if (inquiries.existsByBuyer_IdAndProperty_IdAndStatusIn(
                buyerId, propertyId, ACTIVE_STATUSES)) {
            throw conflict();
        }

        // Defensive fallback: no active row visible but insert did not succeed.
        // Re-attempt once to avoid a false 409 when the conflicting row changed
        // status concurrently; the DB index remains the final authority.
        UUID retryId = UUID.randomUUID();
        int retried = inquiries.insertNewIgnoringActiveDuplicate(
                retryId,
                buyerId,
                propertyId,
                agentId,
                request.getSubject(),
                request.getMessage());
        if (retried == 1) {
            Inquiry saved = inquiries.findById(retryId)
                    .orElseThrow(InquiryService::notFound);
            return new InquirySubmissionResponse(
                    saved.getId(), propertyId, saved.getStatus(), saved.getCreatedAt());
        }
        if (inquiries.existsByBuyer_IdAndProperty_IdAndStatusIn(
                buyerId, propertyId, ACTIVE_STATUSES)) {
            throw conflict();
        }
        throw notFound();
    }

    @Transactional(readOnly = true)
    public PagedResponse<InquiryListItemResponse> listOwn(
            Authentication authentication,
            int page,
            int size,
            String sort,
            String status) {
        UUID buyerId = requireBuyerId(authentication);
        String resolvedSort = normalizeSort(sort);
        validatePagination(page, size);
        String normalizedStatus = normalizeStatusFilter(status);

        Sort.Direction direction = SORT_NEWEST.equals(resolvedSort)
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(direction, "createdAt").and(Sort.by(direction, "id")));

        Page<UUID> ids = inquiries.findIdsByBuyer(buyerId, normalizedStatus, pageable);
        List<Inquiry> details = ids.getContent().isEmpty()
                ? List.of()
                : inquiries.findDetailsByIds(ids.getContent());
        Map<UUID, Inquiry> byId = details.stream()
                .collect(Collectors.toMap(Inquiry::getId, i -> i, (a, b) -> a, LinkedHashMap::new));
        List<InquiryListItemResponse> data = ids.getContent().stream()
                .map(byId::get)
                .filter(i -> i != null)
                .map(this::toListItem)
                .toList();

        return new PagedResponse<>(true, data, PageMeta.from(ids));
    }

    @Transactional(readOnly = true)
    public InquiryDetailResponse getDetail(Authentication authentication, UUID inquiryId) {
        AuthenticatedAccount account = accounts.requireActive(authentication);
        resources.requireInquiryAccess(account, inquiryId);
        Inquiry inquiry = inquiries.findDetailById(inquiryId)
                .orElseThrow(InquiryService::notFound);
        return toDetail(inquiry);
    }

    private InquiryListItemResponse toListItem(Inquiry inquiry) {
        return new InquiryListItemResponse(
                inquiry.getId(),
                inquiry.getProperty().getId(),
                propertyTitle(inquiry.getProperty()),
                inquiry.getBuyer().getId(),
                inquiry.getAssignedAgent().getId(),
                inquiry.getSubject(),
                inquiry.getStatus(),
                inquiry.getResolvedAt(),
                inquiry.getCreatedAt(),
                inquiry.getUpdatedAt());
    }

    private InquiryDetailResponse toDetail(Inquiry inquiry) {
        return new InquiryDetailResponse(
                inquiry.getId(),
                inquiry.getProperty().getId(),
                propertyTitle(inquiry.getProperty()),
                inquiry.getBuyer().getId(),
                inquiry.getAssignedAgent().getId(),
                inquiry.getSubject(),
                inquiry.getMessage(),
                inquiry.getStatus(),
                inquiry.getResolvedAt(),
                inquiry.getCreatedAt(),
                inquiry.getUpdatedAt());
    }

    private static String propertyTitle(Property property) {
        if (property == null || property.getStatus() != PropertyStatus.PUBLISHED) {
            return UNAVAILABLE_PROPERTY_TITLE;
        }
        return property.getTitle();
    }

    private void lockUsersInOrder(UUID first, UUID second) {
        // Lock in stable UUID order to prevent deadlocks when two transactions
        // touch the same buyer/agent pair in opposite roles.
        List<UUID> ordered = first.equals(second)
                ? List.of(first)
                : List.of(first, second).stream().sorted(Comparator.naturalOrder()).toList();
        for (UUID id : ordered) {
            users.findByIdForUpdate(id).orElseThrow(InquiryService::notFound);
        }
    }

    private UUID requireBuyerId(Authentication authentication) {
        AuthenticatedAccount account = accounts.requireActive(authentication);
        resources.requireBuyer(account);
        return account.id();
    }

    private static void validatePagination(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw badRequest();
        }
    }

    private static String normalizeSort(String sort) {
        String resolved = sort == null ? SORT_NEWEST : sort.trim().toLowerCase(Locale.ROOT);
        if (resolved.isEmpty() || (!SORT_NEWEST.equals(resolved) && !SORT_OLDEST.equals(resolved))) {
            throw badRequest();
        }
        return resolved;
    }

    private static String normalizeStatusFilter(String status) {
        if (status == null) {
            return null;
        }
        String trimmed = status.trim();
        if (trimmed.isEmpty()) {
            throw badRequest();
        }
        String normalized = trimmed.toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw badRequest();
        }
        return normalized;
    }

    private static ApiException badRequest() {
        return new ApiException(ErrorCode.BAD_REQUEST, ErrorCode.BAD_REQUEST.defaultMessage());
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.defaultMessage());
    }

    private static ApiException conflict() {
        return new ApiException(ErrorCode.CONFLICT, "An active inquiry already exists for this property");
    }
}
