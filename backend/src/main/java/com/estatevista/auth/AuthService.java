package com.estatevista.auth;

import java.util.Locale;

import com.estatevista.auth.dto.RegisterRequest;
import com.estatevista.auth.dto.RegisteredUserResponse;
import com.estatevista.auth.validation.PasswordPolicy;
import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.Role;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.RoleRepository;
import com.estatevista.user.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Buyer self-registration (SECURITY_ARCHITECTURE.md §8.8.1).
 *
 * <p>Registration is the only public account-creation path. The role is always
 * {@code BUYER} and the initial status is always {@code ACTIVE}; neither value
 * is derived from the request. No session is created and the response never
 * contains the password or its hash.</p>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String BUYER_ROLE = "BUYER";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public RegisteredUserResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());
        String firstName = request.getFirstName().trim();
        String lastName = request.getLastName().trim();
        String phone = trimToNull(request.getPhone());

        PasswordPolicy.rejectContextWords(request.getPassword(), email, firstName, lastName);

        // Fast, friendly conflict response. The functional unique index remains
        // the authoritative guard for concurrent registrations.
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw emailConflict();
        }

        Role buyerRole = roleRepository.findByName(BUYER_ROLE)
                .orElseThrow(() -> new IllegalStateException(
                        "Required role is missing from the roles reference table"));

        User user = new User();
        user.setRole(buyerRole);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEmail(email);
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setAccountStatus(AccountStatus.ACTIVE);

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            // The only user-level unique constraint is uk_users_email. The raw
            // exception may contain SQL and is deliberately not logged.
            log.debug("Registration rejected by a database constraint");
            throw emailConflict();
        }

        log.info("Registered buyer account id={}", user.getId());
        return new RegisteredUserResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                buyerRole.getName(),
                user.getAccountStatus().name());
    }

    static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ApiException emailConflict() {
        return new ApiException(
                ErrorCode.CONFLICT,
                "An account with that email address already exists");
    }
}
