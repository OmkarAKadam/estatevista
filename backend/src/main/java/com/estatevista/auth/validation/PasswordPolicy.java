package com.estatevista.auth.validation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.common.response.ApiErrorResponse.FieldErrorDetail;

/**
 * Context-word password rule from {@code SECURITY_ARCHITECTURE.md} §9.2.
 *
 * <p>The approved policy rejects a password equal to the user's email local
 * part, first name, last name, or the platform name. Comparison is
 * case-insensitive. It also rejects exact matches against the bundled offline
 * common/breached-password blocklist (OD-5). The blocklist is local only: no
 * password is transmitted to a third party. Because the list is finite, this is
 * defense in depth rather than a claim of complete breach coverage.</p>
 */
public final class PasswordPolicy {

    private static final String PLATFORM_NAME = "estatevista";
    private static final String BLOCKLIST_RESOURCE = "/security/common-passwords.txt";
    private static final Set<String> BREACHED_PASSWORDS = loadBlocklist();

    private PasswordPolicy() {
    }

    /**
     * Rejects passwords present in the bundled offline blocklist.
     *
     * @param password raw password; never logged or persisted by this check
     */
    public static void rejectBreachedPassword(String password) {
        if (isBreachedPassword(password)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    ErrorCode.VALIDATION_ERROR.defaultMessage(),
                    List.of(new FieldErrorDetail(
                            "password",
                            "must not be a commonly used or previously breached password")));
        }
    }

    public static boolean isBreachedPassword(String password) {
        return password != null && BREACHED_PASSWORDS.contains(password.toLowerCase(Locale.ROOT));
    }

    private static Set<String> loadBlocklist() {
        Set<String> values = new HashSet<>();
        try (InputStream stream = PasswordPolicy.class.getResourceAsStream(BLOCKLIST_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing password blocklist resource");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String value = line.trim().toLowerCase(Locale.ROOT);
                    if (!value.isEmpty() && !value.startsWith("#")) {
                        values.add(value);
                    }
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read password blocklist", exception);
        }
        return Collections.unmodifiableSet(values);
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
