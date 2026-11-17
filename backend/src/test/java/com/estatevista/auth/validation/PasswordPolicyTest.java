package com.estatevista.auth.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void rejectsBundledCommonPasswordsCaseInsensitively() {
        assertThat(PasswordPolicy.isBreachedPassword("Password123!")).isTrue();
        assertThat(PasswordPolicy.isBreachedPassword("qwerty123")).isTrue();
        assertThat(PasswordPolicy.isBreachedPassword("Example-Password-123")).isFalse();

        assertThatThrownBy(() -> PasswordPolicy.rejectBreachedPassword("ADMIN@123"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void acceptsNullAndDoesNotBreakContextWordRule() {
        assertThatCode(() -> PasswordPolicy.rejectBreachedPassword(null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> PasswordPolicy.rejectContextWords(
                "jane", "jane@example.com", "Jane", "Doe"))
                .isInstanceOf(ApiException.class);
    }
}
