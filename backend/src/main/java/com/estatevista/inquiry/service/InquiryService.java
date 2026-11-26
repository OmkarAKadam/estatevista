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

import com.estatevista.audit.AuditEventService;
import com.estatevista.authorization.AccountAuthorizationService;
import com.estatevista.authorization.AuthenticatedAccount;
import com.estatevista.authorization.PlatformRole;
import com.estatevista.authorization.ResourceAuthorizationService;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.inquiry.dto.InquiryDetailResponse;
import com.estatevista.inquiry.dto.InquiryListItemResponse;
import com.estatevista.inquiry.dto.InquiryStatusPatchRequest;
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

import jakarta.persistence.EntityManager;

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
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "NEW", Set.of("IN_PROGRESS", "CLOSED"),
            "IN_PROGRESS", Set.of("RESOLVED", "CLOSED"),
            "RESOLVED", Set.of("CLOSED"),
            "CLOSED", Set.of());

    private final InquiryRepository inquiries;
    private final PropertyRepository properties;
    private final UserRepository users;
    private final AccountAuthorizationService accounts;
    private final ResourceAuthorizationService resources;
    private final AuditEventService audit;
    private final EntityManager entityManager;

    public InquiryService(
            InquiryRepository inquiries,
            PropertyRepository properties,
            UserRepository users,
            AccountAuthorizationService accounts,
            ResourceAuthorizationService resources,
            AuditEventService audit,
            EntityManager entityManager) {
        this.inquiries = inquiries;
        this.properties = properties;
        this.users = users;
        this.accounts = accounts;
        this.resources = resources;
        this.audit = audit;
        this.entityManager = entityManager;
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
        // requireBuyerId() may already have placed the buyer in this persistence
        // context. Refresh both locked rows so a deactivation committed while the
        // lock was held cannot be masked by a stale first-level cache entry.
        entityManager.refresh(buyer);
        entityManager.refresh(agent);
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

        return listFromIds(inquiries.findIdsByBuyer(
                buyerId, normalizedStatus, pageable(page, size, resolvedSort)));
    }

    @Transactional(readOnly = true)
    public InquiryDetailResponse getDetail(Authentication authentication, UUID inquiryId) {
        AuthenticatedAccount account = accounts.requireActive(authentication);
        resources.requireInquiryAccess(account, inquiryId);
        Inquiry inquiry = inquiries.findDetailById(inquiryId)
                .orElseThrow(InquiryService::notFound);
        return toDetail(inquiry);
    }

    /** Stage 6.5: historically assigned-agent inquiry collection. */
    @Transactional(readOnly = true)
    public PagedResponse<InquiryListItemResponse> listAssigned(
            Authentication authentication,
            int page,
            int size,
            String sort,
            String status) {
        AuthenticatedAccount account = requireManager(authentication, PlatformRole.AGENT);
        return listFromIds(inquiries.findIdsByAssignedAgent(
                account.id(), normalizeStatusFilter(status), pageable(page, size, sort)));
    }

    /** Stage 6.5: administrator inquiry collection (metadata only in lists). */
    @Transactional(readOnly = true)
    public PagedResponse<InquiryListItemResponse> listForAdmin(
            Authentication authentication,
            int page,
            int size,
            String sort,
            String status) {
        requireManager(authentication, PlatformRole.ADMIN);
        return listFromIds(inquiries.findIdsForAdmin(
                normalizeStatusFilter(status), pageable(page, size, sort)));
    }

    /**
     * Stage 6.5: authorized, transactional lifecycle transition. The inquiry row
     * and the actor account row are locked for the whole decision so no caller
     * can validate against a stale status or deactivation result. The status
     * update and the single audit event commit or roll back together.
     */
    @Transactional
    public InquiryDetailResponse changeStatus(
            Authentication authentication, UUID inquiryId, InquiryStatusPatchRequest request) {
        AuthenticatedAccount account = requireManagerAny(authentication);
        String target = normalizeStatusInput(request == null ? null : request.getStatus());
        if (inquiryId == null) {
            throw badRequest();
        }

        // Revalidate the actor under a row lock so deactivation cannot race with
        // an already-authorized decision.
        User lockedActor = users.findByIdForUpdate(account.id()).orElseThrow(
                InquiryService::unauthorized);
        // requireActive already loaded this entity in the same persistence context.
        // Refresh after the row lock so a deactivation that committed while the
        // lock was held cannot be masked by the first-level cache.
        entityManager.refresh(lockedActor);
        if (lockedActor.getAccountStatus() != AccountStatus.ACTIVE
                || lockedActor.getRole() == null
                || !account.role().name().equals(lockedActor.getRole().getName())) {
            throw unauthorized();
        }

        Inquiry inquiry = inquiries.findByIdForUpdate(inquiryId)
                .orElseThrow(InquiryService::notFound);

        // Resource authorization precedes any lifecycle-conflict evaluation and
        // missing/unauthorized inquiries are indistinguishable.
        if (account.role() == PlatformRole.AGENT
                && !account.id().equals(ownerId(inquiry.getAssignedAgent()))) {
            throw notFound();
        }

        String current = inquiry.getStatus();
        if (current.equals(target)) {
            // Idempotent success: no update, no timestamp change, no audit event.
            return toDetail(inquiry);
        }
        if (!ALLOWED_TRANSITIONS.getOrDefault(current, Set.of()).contains(target)) {
            throw conflictTransition();
        }

        Instant now = Instant.now();
        inquiry.setStatus(target);
        inquiry.setUpdatedAt(now);
        if ("RESOLVED".equals(target)) {
            inquiry.setResolvedAt(now);
        }
        audit.recordInquiryStatusChangeInTransaction(
                inquiry.getId(),
                account.id(),
                propertyId(inquiry.getProperty()),
                ownerId(inquiry.getBuyer()),
                ownerId(inquiry.getAssignedAgent()),
                current,
                target,
                now);
        Inquiry saved = inquiries.saveAndFlush(inquiry);
        return toDetail(saved);
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

    private PagedResponse<InquiryListItemResponse> listFromIds(Page<UUID> ids) {
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

    private static Pageable pageable(int page, int size, String sort) {
        validatePagination(page, size);
        Sort.Direction direction = SORT_NEWEST.equals(normalizeSort(sort))
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        return PageRequest.of(page, size,
                Sort.by(direction, "createdAt").and(Sort.by(direction, "id")));
    }

    private AuthenticatedAccount requireManager(
            Authentication authentication, PlatformRole required) {
        AuthenticatedAccount account = accounts.requireActive(authentication);
        accounts.requireRole(account, required);
        return account;
    }

    private AuthenticatedAccount requireManagerAny(Authentication authentication) {
        AuthenticatedAccount account = accounts.requireActive(authentication);
        if (account.role() != PlatformRole.AGENT && account.role() != PlatformRole.ADMIN) {
            throw forbidden();
        }
        return account;
    }

    private static String normalizeStatusInput(String status) {
        if (status == null || !ALLOWED_STATUSES.contains(status)) {
            throw badRequest();
        }
        return status;
    }

    private static UUID ownerId(User user) {
        return user == null ? null : user.getId();
    }

    private static UUID propertyId(Property property) {
        return property == null ? null : property.getId();
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

    private static ApiException conflictTransition() {
        return new ApiException(ErrorCode.CONFLICT, "The requested inquiry transition is not allowed");
    }

    private static ApiException unauthorized() {
        return new ApiException(
                ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.defaultMessage());
    }

    private static ApiException forbidden() {
        return new ApiException(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage());
    }
}
