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
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes append-only, redacted audit rows for security-relevant authentication
 * events (D-11).
 *
 * <p>Only outcomes, identifiers, correlation ids, and client addresses are
 * recorded. Passwords, password hashes, CSRF tokens, session identifiers, and
 * bearer credentials are never accepted by this API and therefore cannot be
 * persisted. Each write uses a new transaction so a failed login or a caller's
 * read-only transaction cannot suppress the audit record.</p>
 */
@Service
public class AuditEventService {

    public static final String ACTION_REGISTER = "AUTH_REGISTER";
    public static final String ACTION_LOGIN_SUCCESS = "AUTH_LOGIN_SUCCESS";
    public static final String ACTION_LOGIN_FAILURE = "AUTH_LOGIN_FAILURE";
    public static final String ACTION_LOGOUT = "AUTH_LOGOUT";

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public AuditEventService(
            AuditLogRepository auditLogRepository, UserRepository userRepository) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRegistration(UUID userId) {
        write(ACTION_REGISTER, "USER", userId, userId, "{\"outcome\":\"REGISTERED\"}");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginSuccess(UUID userId) {
        write(ACTION_LOGIN_SUCCESS, "USER", userId, userId, "{\"outcome\":\"SUCCESS\"}");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginFailure() {
        write(ACTION_LOGIN_FAILURE, "AUTHENTICATION", null, null,
                "{\"outcome\":\"INVALID_CREDENTIALS\"}");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLogout(UUID userId) {
        write(ACTION_LOGOUT, "USER", userId, userId, "{\"outcome\":\"LOGOUT\"}");
    }

    private void write(
            String action, String entityType, UUID entityId, UUID actorId, String afterData) {
        AuditLog entry = new AuditLog();
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setAfterData(afterData);
        entry.setRequestId(requestId());
        entry.setIpAddress(clientAddress());
        if (actorId != null) {
            User actor = userRepository.findById(actorId).orElse(null);
            entry.setActor(actor);
        }
        auditLogRepository.save(entry);
    }

    private static String requestId() {
        String value = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (value != null && !value.isBlank()) {
            return value.length() > 100 ? value.substring(0, 100) : value;
        }
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String header = request.getHeader(CorrelationIdFilter.HEADER);
        if (header == null || header.length() <= 100) {
            return header;
        }
        return header.substring(0, 100);
    }

    private static InetAddress clientAddress() {
        HttpServletRequest request = currentRequest();
        if (request == null || request.getRemoteAddr() == null) {
            return null;
        }
        try {
            return InetAddress.getByName(request.getRemoteAddr());
        } catch (UnknownHostException exception) {
            // Never persist an unparsable address or log the raw value.
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
