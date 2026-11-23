package com.estatevista.config;

import java.security.SecureRandom;
import java.util.Base64;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.util.Assert;

/**
 * Session-bound CSRF token repository that satisfies SD-53's 256-bit entropy
 * requirement. Tokens are stored as an HTTP session attribute and generated
 * from 32 bytes (256 bits) of {@link SecureRandom} data, encoded as URL-safe
 * Base64 without padding. No cookie is written.
 */
public class SecureCsrfTokenRepository implements CsrfTokenRepository {

    static final int TOKEN_BYTES = 32;
    private static final String DEFAULT_CSRF_TOKEN_ATTR_NAME =
            "org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private String parameterName = "_csrf";
    private String headerName = "X-CSRF-TOKEN";
    private String sessionAttributeName = DEFAULT_CSRF_TOKEN_ATTR_NAME;

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        if (token == null) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.removeAttribute(this.sessionAttributeName);
            }
            return;
        }
        HttpSession session = request.getSession();
        session.setAttribute(this.sessionAttributeName, token);
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object token = session.getAttribute(this.sessionAttributeName);
        return token instanceof CsrfToken csrfToken ? csrfToken : null;
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return new DefaultCsrfToken(this.headerName, this.parameterName, createNewToken());
    }

    public void setParameterName(String parameterName) {
        Assert.hasLength(parameterName, "parameterName cannot be null or empty");
        this.parameterName = parameterName;
    }

    public void setHeaderName(String headerName) {
        Assert.hasLength(headerName, "headerName cannot be null or empty");
        this.headerName = headerName;
    }

    public void setSessionAttributeName(String sessionAttributeName) {
        Assert.hasLength(sessionAttributeName, "sessionAttributeName cannot be null or empty");
        this.sessionAttributeName = sessionAttributeName;
    }

    String createNewToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
