package com.estatevista.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorCodeTest {

    @Test
    void everyCodeHasANonBlankMessageAndHttpStatus() {
        assertThat(ErrorCode.values()).allSatisfy(code -> {
            assertThat(code.status()).isNotNull();
            assertThat(code.defaultMessage()).isNotBlank();
        });
    }

    @Test
    void approvedHttpStatusesMapToTheirTransportErrorCode() {
        assertThat(ErrorCode.fromStatus(400)).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(ErrorCode.fromStatus(401)).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(ErrorCode.fromStatus(403)).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(ErrorCode.fromStatus(404)).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ErrorCode.fromStatus(405)).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED);
        assertThat(ErrorCode.fromStatus(409)).isEqualTo(ErrorCode.CONFLICT);
        assertThat(ErrorCode.fromStatus(413)).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        assertThat(ErrorCode.fromStatus(415)).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        assertThat(ErrorCode.fromStatus(422)).isEqualTo(ErrorCode.UNPROCESSABLE_ENTITY);
        assertThat(ErrorCode.fromStatus(429)).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        assertThat(ErrorCode.fromStatus(503)).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void unknownHttpStatusesFallBackToTheSafeInternalErrorCode() {
        assertThat(ErrorCode.fromStatus(418)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ErrorCode.fromStatus(500)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ErrorCode.fromStatus(502)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ErrorCode.fromStatus(0)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ErrorCode.fromStatus(599)).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void declaredStatusesMatchTheApprovedHttpContract() {
        assertThat(ErrorCode.VALIDATION_ERROR.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.PAYLOAD_TOO_LARGE.status()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(ErrorCode.UNPROCESSABLE_ENTITY.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.INTERNAL_ERROR.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
