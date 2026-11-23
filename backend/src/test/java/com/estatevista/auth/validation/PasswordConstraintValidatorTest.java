package com.estatevista.auth.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordConstraintValidatorTest {

    private final PasswordConstraintValidator validator = new PasswordConstraintValidator();

    @Test
    void treatsNullAsValidSoRequiredFieldValidationOwnsTheMessage() {
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    void acceptsTenCharacters() {
        assertThat(validator.isValid("abcdefghij", null)).isTrue();
    }

    @Test
    void rejectsNineCharacters() {
        assertThat(validator.isValid("abcdefghi", null)).isFalse();
    }

    @Test
    void acceptsSixtyFourCharacters() {
        assertThat(validator.isValid("a".repeat(64), null)).isTrue();
    }

    @Test
    void rejectsSixtyFiveCharacters() {
        assertThat(validator.isValid("a".repeat(65), null)).isFalse();
    }

    @Test
    void acceptsExactlySeventyTwoUtf8Bytes() {
        String eighteenEmoji = "\uD83D\uDE00".repeat(18);

        assertThat(eighteenEmoji.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(72);
        assertThat(validator.isValid(eighteenEmoji, null)).isTrue();
    }

    @Test
    void rejectsMoreThanSeventyTwoUtf8BytesEvenWhenTheCharacterCountIsAllowed() {
        String nineteenEmoji = "\uD83D\uDE00".repeat(19);

        assertThat(nineteenEmoji.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(76);
        assertThat(validator.isValid(nineteenEmoji, null)).isFalse();
    }

    @Test
    void countsUnicodeCodePointsRatherThanUtf16Units() {
        String tenEmoji = "\uD83D\uDE00".repeat(10);

        assertThat(tenEmoji.length()).isEqualTo(20);
        assertThat(validator.isValid(tenEmoji, null)).isTrue();
    }

    @Test
    void acceptsSpacesAsIntentionalCharacters() {
        assertThat(validator.isValid("  pass phrase  ", null)).isTrue();
    }

    @Test
    void rejectsNullCharacters() {
        assertThat(validator.isValid("abcdefghij\u0000", null)).isFalse();
    }
}
