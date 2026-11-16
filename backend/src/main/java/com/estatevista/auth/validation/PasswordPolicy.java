package com.estatevista.auth.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.common.response.ApiErrorResponse.FieldErrorDetail;

/**
 * Context-word password rule from {@code SECURITY_ARCHITECTURE.md} §9.2.
 *
 * <p>The approved policy rejects a password equal to the user's email local
 * part, first name, last name, or the platform name. Comparison is
 * case-insensitive. "Obvious variants" and the common/breached-password list
 * depend on the unresolved blocklist decision (OD-5) and are therefore not
 * implemented here.</p>
 */
public final class PasswordPolicy {

    private static final String PLATFORM_NAME = "estatevista";

    private PasswordPolicy() {
    }

    public static void rejectContextWords(
            String password, String email, String firstName, String lastName) {
        if (password == null) {
            return;
        }
        String candidate = password.toLowerCase(Locale.ROOT);
        List<String> forbidden = new ArrayList<>();
        forbidden.add(localPart(email));
        forbidden.add(firstName);
        forbidden.add(lastName);
        forbidden.add(PLATFORM_NAME);

        boolean matchesContext = forbidden.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(candidate::equals);
        if (matchesContext) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    ErrorCode.VALIDATION_ERROR.defaultMessage(),
                    List.of(new FieldErrorDetail(
                            "password",
                            "must not match your name, email address, or the platform name")));
        }
    }

    private static String localPart(String email) {
        if (email == null) {
            return null;
        }
        int separator = email.indexOf('@');
        return separator > 0 ? email.substring(0, separator) : email;
    }
}
