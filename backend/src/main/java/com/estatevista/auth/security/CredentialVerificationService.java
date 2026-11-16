package com.estatevista.auth.security;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reusable credential verification for the upcoming login implementation
 * (SECURITY_ARCHITECTURE.md §8.8.2).
 *
 * <p>Verification compares a supplied raw password with a stored encoded hash
 * through the configured {@link PasswordEncoder}. Raw passwords are never
 * compared directly with stored hashes, and neither raw passwords nor encoded
 * hashes are logged or exposed.</p>
 *
 * <p>Missing accounts, wrong passwords, null/blank inputs, and
 * malformed/unsupported stored hashes all produce the same empty result so the
 * public contract does not reveal whether an email address exists. A dummy hash
 * comparison is performed when no account is found to avoid an obvious timing
 * difference between the two failure cases.</p>
 *
 * <p>This component is independent of HTTP controllers and does not create or
 * mutate authenticated sessions.</p>
 */
@Service
public class CredentialVerificationService {

    private static final Logger log = LoggerFactory.getLogger(CredentialVerificationService.class);

    /**
     * A valid, encoder-produced hash used only to keep the missing-account path
     * on the same password-comparison work as the present-account path. The
     * plaintext is irrelevant and never compared by callers.
     */
    private static final String DUMMY_RAW_PASSWORD = "estatevista-dummy-verification-password";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String dummyEncodedPassword;

    public CredentialVerificationService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.dummyEncodedPassword = passwordEncoder.encode(DUMMY_RAW_PASSWORD);
    }

    /**
     * Verifies credentials for the account identified by {@code email}.
     *
     * @param email       the supplied email address; normalized the same way as
     *                    registration (trim + lower-case, root locale)
     * @param rawPassword the supplied raw password
     * @return a safe view of the account when the credentials are valid;
     *         {@link Optional#empty()} for every other case
     */
    @Transactional(readOnly = true)
    public Optional<VerifiedAccount> verify(String email, String rawPassword) {
        String normalizedEmail = normalizeEmail(email);
        Optional<User> user = normalizedEmail == null
                ? Optional.empty()
                : userRepository.findByEmailIgnoreCase(normalizedEmail);

        String encodedHash = user.map(User::getPasswordHash).orElse(dummyEncodedPassword);
        boolean matches = matchesSafely(rawPassword, encodedHash);

        if (user.isEmpty() || !matches) {
            return Optional.empty();
        }

        return Optional.of(toVerifiedAccount(user.get()));
    }

    /**
     * Compares a raw password with an encoded hash through the configured
     * encoder, treating null inputs and malformed/unsupported hashes as a
     * non-match rather than a distinguishable failure.
     */
    boolean matchesSafely(String rawPassword, String encodedHash) {
        if (rawPassword == null || encodedHash == null) {
            return false;
        }
        try {
            return passwordEncoder.matches(rawPassword, encodedHash);
        } catch (IllegalArgumentException exception) {
            // Thrown by delegating/algorithm encoders for unknown ids or malformed
            // encoded values. Logged without the hash or password; treated as an
            // authentication failure.
            log.debug("Credential verification rejected an unsupported or malformed password hash");
            return false;
        }
    }

    private static VerifiedAccount toVerifiedAccount(User user) {
        UUID id = user.getId();
        String email = user.getEmail();
        String roleName = user.getRole() == null ? null : user.getRole().getName();
        AccountStatus accountStatus = user.getAccountStatus();
        return new VerifiedAccount(id, email, roleName, accountStatus);
    }

    static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * Safe verification result. It deliberately excludes the password hash and
     * every other credential or secret.
     */
    public record VerifiedAccount(
            UUID id,
            String email,
            String roleName,
            AccountStatus accountStatus) {
    }
}
