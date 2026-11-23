package com.estatevista.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;

class SecureCsrfTokenRepositoryTest {

    private final SecureCsrfTokenRepository repository = new SecureCsrfTokenRepository();

    @Test
    void generatedTokenContainsAtLeast256BitsOfRandomData() {
        String token = repository.createNewToken();
        byte[] decoded = Base64.getUrlDecoder().decode(token);

        assertThat(decoded).hasSize(SecureCsrfTokenRepository.TOKEN_BYTES);
        assertThat(SecureCsrfTokenRepository.TOKEN_BYTES * 8).isGreaterThanOrEqualTo(256);
    }

    @Test
    void generatedTokenUsesUrlSafeBase64WithoutPadding() {
        String token = repository.createNewToken();

        assertThat(token).doesNotContain("+");
        assertThat(token).doesNotContain("/");
        assertThat(token).doesNotEndWith("=");
        assertThat(token).matches("^[A-Za-z0-9_-]+$");
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
    }

    @Test
    void repeatedGenerationsProduceIndependentNonFixedValues() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            tokens.add(repository.createNewToken());
        }

        assertThat(tokens).hasSize(64);
    }

    @Test
    void generatedCsrfTokenPreservesHeaderAndParameterNames() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        CsrfToken token = repository.generateToken(request);

        assertThat(token.getHeaderName()).isEqualTo("X-CSRF-TOKEN");
        assertThat(token.getParameterName()).isEqualTo("_csrf");
        assertThat(Base64.getUrlDecoder().decode(token.getToken())).hasSize(32);
    }

    @Test
    void customHeaderAndParameterNamesAreAppliedToGeneratedTokens() {
        SecureCsrfTokenRepository custom = new SecureCsrfTokenRepository();
        custom.setHeaderName("X-CUSTOM-CSRF");
        custom.setParameterName("_custom");

        CsrfToken token = custom.generateToken(new MockHttpServletRequest());

        assertThat(token.getHeaderName()).isEqualTo("X-CUSTOM-CSRF");
        assertThat(token.getParameterName()).isEqualTo("_custom");
        assertThat(Base64.getUrlDecoder().decode(token.getToken())).hasSize(32);
    }
}
