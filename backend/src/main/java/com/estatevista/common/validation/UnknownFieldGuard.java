package com.estatevista.common.validation;

import java.util.List;
import java.util.Map;

import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.common.response.ApiErrorResponse.FieldErrorDetail;

/**
 * Rejects request properties that are not explicitly allowed by a request DTO.
 *
 * <p>This is the mass-assignment guard required by
 * {@code SECURITY_ARCHITECTURE.md} §11.7 (T-15): a client cannot smuggle
 * {@code role}, {@code accountStatus}, {@code passwordHash}, {@code id}, or any
 * other protected field into a request. Rejection names the offending field but
 * never echoes its value.</p>
 */
public final class UnknownFieldGuard {

    private UnknownFieldGuard() {
    }

    public static void reject(Map<String, Object> unknownFields) {
        if (unknownFields == null || unknownFields.isEmpty()) {
            return;
        }
        List<FieldErrorDetail> details = unknownFields.keySet().stream()
                .map(field -> new FieldErrorDetail(field, "is not an allowed field"))
                .toList();
        throw new ApiException(
                ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.defaultMessage(),
                details);
    }
}
