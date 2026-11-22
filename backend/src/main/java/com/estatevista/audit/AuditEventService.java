package com.estatevista.audit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

import com.estatevista.audit.entity.AuditLog;
import com.estatevista.audit.repository.AuditLogRepository;
import com.estatevista.common.logging.CorrelationIdFilter;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.UserRepository;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes append-only, redacted audit rows for security-relevant events (D-11).
 *
 * <p>Only outcomes, safe identifiers, correlation IDs, and client addresses are
 * recorded. Passwords, password hashes, CSRF tokens, session identifiers,
 * cookies, authorization headers, bearer credentials, and private content are
 * never accepted by this API and therefore cannot be persisted.</p>
 *
 * <p>Each write uses a new transaction so a failed login, a rolled-back
 * rate-limit check, or a caller's read-only transaction cannot suppress the
 * audit record. Audit-write failures are logged with the action and correlation
 * ID but never change the primary request outcome.</p>
 */
@Service
public class AuditEventService {

    public static final String ACTION_REGISTER = "AUTH_REGISTER";
    public static final String ACTION_LOGIN_SUCCESS = "AUTH_LOGIN_SUCCESS";
    public static final String ACTION_LOGIN_FAILURE = "AUTH_LOGIN_FAILURE";
    public static final String ACTION_LOGOUT = "AUTH_LOGOUT";
    public static final String ACTION_RATE_LIMIT_REJECTED = "SECURITY_RATE_LIMIT_TRIGGERED";
    public static final String ACTION_CSRF_FAILURE = "SECURITY_CSRF_FAILURE";
    public static final String ACTION_ACCESS_DENIED = "SECURITY_ACCESS_DENIED";
    public static final String ACTION_WS_AUTH_FAILURE = "SECURITY_WS_AUTH_FAILURE";
    public static final String ACTION_PROPERTY_CREATE = "PROPERTY_CREATE";
    public static final String ACTION_PROPERTY_UPDATE = "PROPERTY_UPDATE";
    public static final String ACTION_PROPERTY_ARCHIVE = "PROPERTY_ARCHIVE";
    public static final String ACTION_PROPERTY_ASSIGNMENT = "PROPERTY_ASSIGNMENT";
    public static final String ACTION_IMAGE_UPLOAD = "PROPERTY_IMAGE_UPLOAD";
    public static final String ACTION_IMAGE_METADATA = "PROPERTY_IMAGE_METADATA";
    public static final String ACTION_IMAGE_PRIMARY = "PROPERTY_IMAGE_PRIMARY";
    public static final String ACTION_IMAGE_DELETE = "PROPERTY_IMAGE_DELETE";
    public static final String ACTION_IMAGE_FAILURE = "PROPERTY_IMAGE_FAILURE";

    private static final Logger log = LoggerFactory.getLogger(AuditEventService.class);
    private static final String AUDITED_ATTRIBUTE_PREFIX =
            AuditEventService.class.getName() + ".audited.";

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public AuditEventService(
            AuditLogRepository auditLogRepository,
            UserRepository userRepository,
            PlatformTransactionManager transactionManager) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void recordRegistration(UUID userId) {
        write(ACTION_REGISTER, "USER", userId, userId, "{\"outcome\":\"REGISTERED\"}", null);
    }

    public void recordLoginSuccess(UUID userId) {
        write(ACTION_LOGIN_SUCCESS, "USER", userId, userId, "{\"outcome\":\"SUCCESS\"}", null);
    }

    public void recordLoginFailure() {
        write(ACTION_LOGIN_FAILURE, "AUTHENTICATION", null, null,
                "{\"outcome\":\"INVALID_CREDENTIALS\"}", null);
    }

    public void recordLogout(UUID userId) {
        write(ACTION_LOGOUT, "USER", userId, userId, "{\"outcome\":\"LOGOUT\"}", null);
    }

    /** Records a successful property creation using only server-owned identifiers. */
    public void recordPropertyCreated(UUID propertyId, UUID actorId) {
        write(ACTION_PROPERTY_CREATE, "PROPERTY", propertyId, actorId,
                "{\"outcome\":\"SUCCESS\",\"status\":\"DRAFT\"}", null);
    }

    /** Records a successful managed property update. */
    public void recordPropertyUpdated(UUID propertyId, UUID actorId) {
        write(ACTION_PROPERTY_UPDATE, "PROPERTY", propertyId, actorId,
                "{\"outcome\":\"SUCCESS\"}", null);
    }

    /** Records a successful logical archive (never a physical deletion). */
    public void recordPropertyArchived(UUID propertyId, UUID actorId) {
        write(ACTION_PROPERTY_ARCHIVE, "PROPERTY", propertyId, actorId,
                "{\"outcome\":\"SUCCESS\",\"status\":\"ARCHIVED\"}", null);
    }

    /**
     * Records a successful administrator reassignment using only server-owned
     * identifiers. No private or credential data is included in the snapshots.
     */
    public void recordPropertyAssignment(
            UUID propertyId, UUID actorId, UUID previousAgentId, UUID newAgentId) {
        String before = "{\"outcome\":\"SUCCESS\",\"primaryAgentId\":"
                + jsonUuid(previousAgentId) + "}";
        String after = "{\"outcome\":\"SUCCESS\",\"primaryAgentId\":"
                + jsonUuid(newAgentId) + "}";
        write(ACTION_PROPERTY_ASSIGNMENT, "PROPERTY", propertyId, actorId,
                before, after, null);
    }

    /** Records a successful backend-mediated image upload. */
    public void recordImageUpload(
            UUID propertyId, UUID imageId, UUID actorId, String storageProvider) {
        write(ACTION_IMAGE_UPLOAD, "PROPERTY_IMAGE", imageId, actorId,
                "{\"outcome\":\"SUCCESS\",\"propertyId\":" + jsonUuid(propertyId)
                        + ",\"storageProvider\":" + jsonToken(storageProvider) + "}",
                null);
    }

    /** Records a metadata-only image update; no bytes or provider ID is logged. */
    public void recordImageMetadata(UUID propertyId, UUID imageId, UUID actorId) {
        write(ACTION_IMAGE_METADATA, "PROPERTY_IMAGE", imageId, actorId,
                "{\"outcome\":\"SUCCESS\",\"propertyId\":" + jsonUuid(propertyId) + "}",
                null);
    }

    /** Records an explicit primary-image change, including the previous image ID. */
    public void recordImagePrimary(
            UUID propertyId, UUID imageId, UUID previousImageId, UUID actorId) {
        write(ACTION_IMAGE_PRIMARY, "PROPERTY_IMAGE", imageId, actorId,
                "{\"outcome\":\"SUCCESS\",\"propertyId\":" + jsonUuid(propertyId)
                        + ",\"previousImageId\":" + jsonUuid(previousImageId) + "}",
                null);
    }

    /** Records an explicit image deletion with no automatic replacement. */
    public void recordImageDelete(UUID propertyId, UUID imageId, UUID actorId) {
        write(ACTION_IMAGE_DELETE, "PROPERTY_IMAGE", imageId, actorId,
                "{\"outcome\":\"SUCCESS\",\"propertyId\":" + jsonUuid(propertyId)
                        + ",\"replacement\":\"NONE\"}",
                null);
    }

    /** Records a safe image-operation failure reason without provider details. */
    public void recordImageFailure(
            UUID propertyId, UUID imageId, UUID actorId, String action, String reason) {
        write(ACTION_IMAGE_FAILURE, "PROPERTY_IMAGE", imageId, actorId,
                "{\"outcome\":\"FAILURE\",\"action\":" + jsonToken(action)
                        + ",\"propertyId\":" + jsonUuid(propertyId)
                        + ",\"reason\":" + jsonToken(reason) + "}",
                null);
    }

    /**
     * Records one authentication rate-limit rejection. The raw identity is
     * deliberately not passed here; only the internal scope is stored.
     */
    public void recordRateLimitRejected(String scope) {
        if (!markAudited(currentRequest(), "RATE_LIMIT")) {
            return;
        }
        write(ACTION_RATE_LIMIT_REJECTED, "AUTHENTICATION", null, currentActorId(),
                securityPayload("REJECTED", "RATE_LIMIT", safeScope(scope)), null);
    }

    /** Records a single CSRF validation failure for the current request. */
    public void recordCsrfFailure(HttpServletRequest request) {
        if (!markAudited(request, "CSRF")) {
            return;
        }
        write(ACTION_CSRF_FAILURE, "SECURITY", null, currentActorId(),
                securityPayload("DENIED", "INVALID_CSRF", null), request);
    }

    /**
     * Records a rejected Origin/Referer check on the CSRF-exempt registration
     * endpoint. It shares the CSRF request guard with token failures so one
     * request can never produce two CSRF-family audit rows.
     */
    public void recordOriginRejected(HttpServletRequest request) {
        if (!markAudited(request, "CSRF")) {
            return;
        }
        write(ACTION_CSRF_FAILURE, "SECURITY", null, currentActorId(),
                securityPayload("DENIED", "INVALID_ORIGIN", null), request);
    }

    /** Records a single authorization denial for the current request. */
    public void recordAccessDenied(HttpServletRequest request) {
        if (!markAudited(request, "ACCESS")) {
            return;
        }
        write(ACTION_ACCESS_DENIED, "SECURITY", null, currentActorId(),
                securityPayload("DENIED", "FORBIDDEN", null), request);
    }

    /**
     * Records a STOMP authorization failure. STOMP frames execute outside a
     * servlet request, so request ID and IP are unavailable; the actor is taken
     * only from the trusted handshake principal name.
     */
    public void recordWebSocketAuthFailure(String principalName, String destination) {
        write(ACTION_WS_AUTH_FAILURE, "SECURITY", null, parseActorId(principalName),
                securityPayload("DENIED", "FORBIDDEN", safeDestination(destination)), null);
    }

    private void write(
            String action,
            String entityType,
            UUID entityId,
            UUID actorId,
            String afterData,
            HttpServletRequest request) {
        write(action, entityType, entityId, actorId, null, afterData, request);
    }

    private void write(
            String action,
            String entityType,
            UUID entityId,
            UUID actorId,
            String beforeData,
            String afterData,
            HttpServletRequest request) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                HttpServletRequest effectiveRequest =
                        request == null ? currentRequest() : request;
                AuditLog entry = new AuditLog();
                entry.setAction(action);
                entry.setEntityType(entityType);
                entry.setEntityId(entityId);
                entry.setBeforeData(beforeData);
                entry.setAfterData(afterData);
                entry.setRequestId(requestId(effectiveRequest));
                entry.setIpAddress(clientAddress(effectiveRequest));
                if (actorId != null) {
                    User actor = userRepository.findById(actorId).orElse(null);
                    entry.setActor(actor);
                }
                auditLogRepository.save(entry);
            });
        } catch (RuntimeException exception) {
            // Do not turn a valid operation or a genuine security rejection into
            // a different response merely because the audit sink failed. The
            // warning keeps the missing record non-silent and operator-visible.
            log.warn("Audit write failed action={} requestId={} error={}",
                    action, requestIdForLog(request), exception.getClass().getSimpleName());
        }
    }

    private static String jsonUuid(UUID value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static String jsonToken(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,40}")) {
            return "\"UNKNOWN\"";
        }
        return "\"" + value + "\"";
    }

    private static boolean markAudited(HttpServletRequest request, String event) {
        if (request == null) {
            return true;
        }
        String attribute = AUDITED_ATTRIBUTE_PREFIX + event;
        if (request.getAttribute(attribute) != null) {
            return false;
        }
        request.setAttribute(attribute, Boolean.TRUE);
        return true;
    }

    private static UUID currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return parseActorId(authentication.getName());
    }

    private static UUID parseActorId(String name) {
        if (name == null || name.isBlank() || "anonymousUser".equals(name)) {
            return null;
        }
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String securityPayload(String outcome, String reason, String scope) {
        String actor = currentActorId() == null ? "ANONYMOUS" : "USER";
        StringBuilder payload = new StringBuilder(96)
                .append("{\"outcome\":\"").append(outcome)
                .append("\",\"reason\":\"").append(reason)
                .append("\",\"actor\":\"").append(actor).append('"');
        if (scope != null) {
            payload.append(",\"scope\":\"").append(scope).append('"');
        }
        return payload.append('}').toString();
    }

    private static String safeScope(String scope) {
        return scope != null && scope.matches("[a-z_]+") ? scope : "unknown";
    }

    private static String safeDestination(String destination) {
        return destination != null && destination.matches("/[A-Za-z0-9/_-]{1,80}")
                ? destination
                : "unknown";
    }

    private static String requestId(HttpServletRequest request) {
        String value = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (value == null || value.isBlank()) {
            value = request == null ? null : request.getHeader(CorrelationIdFilter.HEADER);
        }
        return truncate(value);
    }

    private static String requestIdForLog(HttpServletRequest request) {
        String value = requestId(request);
        return value == null ? "-" : value;
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() > 100 ? value.substring(0, 100) : value;
    }

    private static InetAddress clientAddress(HttpServletRequest request) {
        if (request == null || request.getRemoteAddr() == null) {
            return null;
        }
        try {
            return InetAddress.getByName(request.getRemoteAddr());
        } catch (UnknownHostException exception) {
            // Never persist or log an unparsable raw address.
            return null;
        }
    }

    private static HttpServletRequest currentRequest() {
        if (!(RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        return attrs.getRequest();
    }
}
