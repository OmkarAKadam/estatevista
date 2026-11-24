package com.estatevista.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.estatevista.audit.AuditEventService;
import com.estatevista.common.exception.RateLimitExceededException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared authentication rate limiter backed by PostgreSQL (OD-6).
 *
 * <p>Counters are stored in {@code auth_rate_limit_events}; nothing is kept in
 * application memory, so all instances of the API observe the same attempts.
 * A transaction-scoped PostgreSQL advisory lock serializes concurrent checks
 * for the same key. Keys are SHA-256 digests of the scope and normalized
 * identity, so raw email addresses and client addresses are never persisted or
 * logged by this service.</p>
 */
@Service
public class RateLimitService {

    public static final String SCOPE_LOGIN = "login";
    public static final String SCOPE_REGISTRATION = "registration";
    public static final String SCOPE_IMAGE_UPLOAD = "image_upload";
    public static final String SCOPE_PUBLIC_SEARCH = "public_search";
    public static final String SCOPE_PROPERTY_DETAIL = "property_detail";
    public static final String SCOPE_AUTHENTICATED_API = "authenticated_api";
    public static final String SCOPE_ADMIN_MUTATION = "admin_mutation";
    public static final String SCOPE_FAVORITE_MUTATION = "favorite_mutation";

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private static final String ADVISORY_LOCK =
            "SELECT pg_advisory_xact_lock(hashtext(?)::bigint)";
    private static final String DELETE_EXPIRED =
            "DELETE FROM auth_rate_limit_events "
                    + "WHERE attempt_key = ? "
                    + "AND occurred_at <= now() - make_interval(secs => ?)";
    private static final String COUNT_ACTIVE =
            "SELECT count(*) FROM auth_rate_limit_events "
                    + "WHERE attempt_key = ? "
                    + "AND occurred_at > now() - make_interval(secs => ?)";
    private static final String INSERT_EVENT =
            "INSERT INTO auth_rate_limit_events (attempt_key, occurred_at) VALUES (?, now())";
    private static final String DELETE_ALL =
            "DELETE FROM auth_rate_limit_events WHERE attempt_key = ?";

    private final JdbcTemplate jdbcTemplate;
    private final RateLimitProperties properties;
    private final AuditEventService auditEventService;

    public RateLimitService(
            JdbcTemplate jdbcTemplate,
            RateLimitProperties properties,
            AuditEventService auditEventService) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.auditEventService = auditEventService;
    }

    /**
     * Registers one attempt and rejects the caller when the configured threshold
     * has already been reached inside the active window.
     *
     * @param scope one of the declared {@code SCOPE_*} constants; property
     *              scopes use the approved SECURITY_ARCHITECTURE.md §11.9 baselines
     * @param identity normalized email/client identity; never logged or stored raw
     */
    @Transactional
    public void acquire(String scope, String identity) {
        acquire(List.of(scope), identity);
    }

    /**
     * Applies every applicable bucket together. All keys are locked in a stable
     * order before any counter is consumed, and a rejection reports the longest
     * blocking window among the triggered buckets.
     */
    @Transactional
    public void acquire(Collection<String> scopes, String identity) {
        if (!properties.isEnabled() || scopes == null || scopes.isEmpty()) {
            return;
        }
        LinkedHashMap<String, String> keysByScope = new LinkedHashMap<>();
        for (String scope : scopes) {
            if (scope != null && !scope.isBlank()) {
                keysByScope.putIfAbsent(scope, key(scope, identity));
            }
        }
        if (keysByScope.isEmpty()) {
            return;
        }

        List<Map.Entry<String, String>> lockedOrder = new ArrayList<>(keysByScope.entrySet());
        lockedOrder.sort(Comparator.comparing(Map.Entry::getValue));
        for (Map.Entry<String, String> entry : lockedOrder) {
            lock(entry.getValue());
        }

        boolean blocked = false;
        long longestWindow = 0L;
        String firstBlockedScope = null;
        for (Map.Entry<String, String> entry : keysByScope.entrySet()) {
            String scope = entry.getKey();
            String key = entry.getValue();
            int windowSeconds = window(scope);
            jdbcTemplate.update(DELETE_EXPIRED, key, windowSeconds);
            Long active = jdbcTemplate.queryForObject(
                    COUNT_ACTIVE, Long.class, key, windowSeconds);
            if (active != null && active >= threshold(scope)) {
                blocked = true;
                longestWindow = Math.max(longestWindow, windowSeconds);
                if (firstBlockedScope == null) {
                    firstBlockedScope = scope;
                }
            }
        }
        if (blocked) {
            log.warn("Rate limit rejected an attempt scopes={}", keysByScope.keySet());
            auditEventService.recordRateLimitRejected(
                    firstBlockedScope == null ? "unknown" : firstBlockedScope);
            throw new RateLimitExceededException(longestWindow);
        }
        for (String key : keysByScope.values()) {
            jdbcTemplate.update(INSERT_EVENT, key);
        }
    }

    /**
     * Clears the counter after a successful login. Registration attempts are
     * deliberately never cleared so that successful registrations cannot be
     * used to reset the per-address limit.
     */
    @Transactional
    public void reset(String scope, String identity) {
        if (!properties.isEnabled()) {
            return;
        }
        lock(key(scope, identity));
        jdbcTemplate.update(DELETE_ALL, key(scope, identity));
    }

    private void lock(String key) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(ADVISORY_LOCK)) {
                statement.setString(1, key);
                statement.execute();
            }
            return null;
        });
    }

    private int threshold(String scope) {
        if (SCOPE_REGISTRATION.equals(scope)) {
            return Math.max(1, properties.getRegistration().getMaxAttempts());
        }
        if (SCOPE_IMAGE_UPLOAD.equals(scope)) {
            return Math.max(1, properties.getImageUpload().getMaxAttempts());
        }
        if (SCOPE_PUBLIC_SEARCH.equals(scope)) {
            return Math.max(1, properties.getPublicSearch().getMaxAttempts());
        }
        if (SCOPE_PROPERTY_DETAIL.equals(scope)) {
            return Math.max(1, properties.getPropertyDetail().getMaxAttempts());
        }
        if (SCOPE_AUTHENTICATED_API.equals(scope)) {
            return Math.max(1, properties.getAuthenticatedApi().getMaxAttempts());
        }
        if (SCOPE_ADMIN_MUTATION.equals(scope)) {
            return Math.max(1, properties.getAdminMutation().getMaxAttempts());
        }
        if (SCOPE_FAVORITE_MUTATION.equals(scope)) {
            return Math.max(1, properties.getFavoriteMutation().getMaxAttempts());
        }
        return Math.max(1, properties.getLogin().getMaxAttempts());
    }

    private int window(String scope) {
        if (SCOPE_REGISTRATION.equals(scope)) {
            return Math.max(1, properties.getRegistration().getWindowSeconds());
        }
        if (SCOPE_IMAGE_UPLOAD.equals(scope)) {
            return Math.max(1, properties.getImageUpload().getWindowSeconds());
        }
        if (SCOPE_PUBLIC_SEARCH.equals(scope)) {
            return Math.max(1, properties.getPublicSearch().getWindowSeconds());
        }
        if (SCOPE_PROPERTY_DETAIL.equals(scope)) {
            return Math.max(1, properties.getPropertyDetail().getWindowSeconds());
        }
        if (SCOPE_AUTHENTICATED_API.equals(scope)) {
            return Math.max(1, properties.getAuthenticatedApi().getWindowSeconds());
        }
        if (SCOPE_ADMIN_MUTATION.equals(scope)) {
            return Math.max(1, properties.getAdminMutation().getWindowSeconds());
        }
        if (SCOPE_FAVORITE_MUTATION.equals(scope)) {
            return Math.max(1, properties.getFavoriteMutation().getWindowSeconds());
        }
        return Math.max(1, properties.getLogin().getWindowSeconds());
    }

    private static String key(String scope, String identity) {
        String material = scope + "|" + (identity == null ? "" : identity.toLowerCase());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for rate-limit keys", exception);
        }
    }
}
