package com.estatevista.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    @Test
    void acceptsSafeInboundRequestIdAndClearsMdcAfterTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        request.addHeader(CorrelationIdFilter.HEADER, "stage36-request-7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) ->
                observed.set(MDC.get(CorrelationIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("stage36-request-7");
        assertThat(observed.get()).isEqualTo("stage36-request-7");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void replacesUnsafeRequestIdWithGeneratedUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        request.addHeader(CorrelationIdFilter.HEADER, "bad value with spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        String generated = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(generated).isNotEqualTo("bad value with spaces");
        assertThat(UUID.fromString(generated)).isNotNull();
    }

    @Test
    void generatesRequestIdWhenHeaderIsAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        assertThat(UUID.fromString(response.getHeader(CorrelationIdFilter.HEADER))).isNotNull();
    }
}
