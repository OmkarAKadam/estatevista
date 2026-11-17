package com.estatevista.common.logging;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Attaches a request-scoped correlation identifier to logs, responses, and
 * audit rows (D-11).
 *
 * <p>An inbound {@code X-Request-Id} is accepted only when it is a safe
 * single-token value; otherwise a fresh UUID is generated. The value is
 * exposed back to the caller in the same header, placed in SLF4J MDC as
 * {@code requestId}, and cleared in a {@code finally} block so servlet
 * container thread reuse cannot leak one request's identifier into another.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    private static final int MAX_LENGTH = 100;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolve(String supplied) {
        if (supplied == null || supplied.isBlank() || supplied.length() > MAX_LENGTH) {
            return UUID.randomUUID().toString();
        }
        for (int index = 0; index < supplied.length(); index++) {
            char character = supplied.charAt(index);
            boolean safe = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '-' || character == '_' || character == '.';
            if (!safe) {
                return UUID.randomUUID().toString();
            }
        }
        return supplied;
    }
}
