package com.estatevista.common.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import com.estatevista.common.exception.ErrorCode;
import com.estatevista.common.response.ApiErrorResponse.FieldErrorDetail;

import org.junit.jupiter.api.Test;

class ApiErrorResponseTest {

    @Test
    void nullOrBlankMessageFallsBackToTheCodeDefaultMessage() {
        ApiErrorResponse nullMessage = ApiErrorResponse.of(
                ErrorCode.BAD_REQUEST, null, List.of(), "/api/v1/probe");
        ApiErrorResponse blankMessage = ApiErrorResponse.of(
                ErrorCode.BAD_REQUEST, "   ", List.of(), "/api/v1/probe");

        assertThat(nullMessage.error().message())
                .isEqualTo(ErrorCode.BAD_REQUEST.defaultMessage());
        assertThat(blankMessage.error().message())
                .isEqualTo(ErrorCode.BAD_REQUEST.defaultMessage());
    }

    @Test
    void explicitMessageIsRetainedAndCodeComesFromTheEnumName() {
        ApiErrorResponse response = ApiErrorResponse.of(
                ErrorCode.NOT_FOUND, "Property not found", List.of(), "/api/v1/properties/1");

        assertThat(response.success()).isFalse();
        assertThat(response.error().code()).isEqualTo("NOT_FOUND");
        assertThat(response.error().message()).isEqualTo("Property not found");
        assertThat(response.path()).isEqualTo("/api/v1/properties/1");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    void nullDetailsBecomeAnEmptyImmutableList() {
        ApiErrorResponse response = ApiErrorResponse.of(
                ErrorCode.BAD_REQUEST, null, null, "/api/v1/probe");

        assertThat(response.error().details()).isEmpty();
        assertThatThrownBy(() -> response.error().details().add(
                new FieldErrorDetail("field", "message")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void suppliedDetailsAreDefensivelyCopied() {
        List<FieldErrorDetail> mutableDetails = new ArrayList<>();
        mutableDetails.add(new FieldErrorDetail("email", "must not be blank"));

        ApiErrorResponse response = ApiErrorResponse.of(
                ErrorCode.VALIDATION_ERROR, null, mutableDetails, "/api/v1/probe");
        mutableDetails.add(new FieldErrorDetail("password", "must not be blank"));

        assertThat(response.error().details())
                .containsExactly(new FieldErrorDetail("email", "must not be blank"));
    }
}
