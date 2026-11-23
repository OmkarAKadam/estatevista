package com.estatevista.auth.validation;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Structural password validator for the approved policy
 * ({@code SECURITY_ARCHITECTURE.md} §9.1-§9.2).
 *
 * <p>Rules enforced here:</p>
 * <ul>
 *   <li>10 to 64 Unicode code points (not UTF-16 units);</li>
 *   <li>no more than 72 UTF-8 bytes, so BCrypt never silently truncates;</li>
 *   <li>no {@code U+0000};</li>
 *   <li>whitespace is significant and is never trimmed;</li>
 *   <li>no Unicode normalization is applied.</li>
 * </ul>
 *
 * <p>A {@code null} value is considered valid so {@code @NotEmpty} owns the
 * required-field message when the constraint is composed.</p>
 */
public class PasswordConstraintValidator implements ConstraintValidator<ValidPassword, String> {

    private static final int MIN_CODE_POINTS = 10;
    private static final int MAX_CODE_POINTS = 64;
    private static final int MAX_UTF8_BYTES = 72;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (value.indexOf('\u0000') >= 0) {
            return false;
        }
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints < MIN_CODE_POINTS || codePoints > MAX_CODE_POINTS) {
            return false;
        }
        return value.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
    }
}
