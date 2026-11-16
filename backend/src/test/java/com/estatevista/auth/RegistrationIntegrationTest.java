package com.estatevista.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import com.estatevista.auth.security.CredentialVerificationService;
import com.estatevista.user.entity.AccountStatus;
import com.estatevista.user.entity.User;
import com.estatevista.user.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-PostgreSQL verification of Stage 3.2 registration and self-service
 * profile endpoints.
 *
 * <p>Runs only when {@code TEST_DB_URL} points at an isolated PostgreSQL
 * database, so a database-free build never silently claims integration
 * coverage. Every test participates in a transaction that is rolled back, so
 * the Flyway-seeded reference data and role rows are never mutated.</p>
 *
 * <p>The authenticated profile tests supply the approved principal directly
 * because login does not exist yet (Stage 3.3/3.4). The security filter chain,
 * CSRF enforcement, real repositories, constraints, and BCrypt encoder are all
 * exercised; only session establishment is simulated.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@Transactional
class RegistrationIntegrationTest {

    private static final String ORIGIN = "http://localhost:5500";
    private static final String RAW_PASSWORD = "Example-Password-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CredentialVerificationService credentialVerificationService;

    @Test
    void registersABuyerWithNormalizedEmailAndAnIrreversibleHash() throws Exception {
        mockMvc.perform(register("  Jane.Doe@Example.COM  "))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("jane.doe@example.com"))
                .andExpect(jsonPath("$.data.role").value("BUYER"))
                .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(content().string(not(containsString(RAW_PASSWORD))));

        User saved = userRepository.findByEmailIgnoreCase("jane.doe@example.com").orElseThrow();

        assertThat(saved.getRole().getName()).isEqualTo("BUYER");
        assertThat(saved.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(saved.getEmail()).isEqualTo("jane.doe@example.com");
        assertThat(saved.getPasswordHash()).isNotEqualTo(RAW_PASSWORD);
        assertThat(saved.getPasswordHash()).contains("{bcrypt}");
        assertThat(passwordEncoder.matches(RAW_PASSWORD, saved.getPasswordHash())).isTrue();
    }

    @Test
    void rejectsACaseInsensitiveDuplicateEmail() throws Exception {
        mockMvc.perform(register("duplicate@example.com")).andExpect(status().isCreated());

        mockMvc.perform(register("  DUPLICATE@Example.COM  "))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    void rejectsAClientSuppliedRoleAndCreatesNoAccount() throws Exception {
        String body = """
                {
                  "firstName": "Eve",
                  "lastName": "Attacker",
                  "email": "eve.role@example.com",
                  "password": "Example-Password-123",
                  "role": "ADMIN"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[?(@.field == 'role')]").exists());

        assertThat(userRepository.findByEmailIgnoreCase("eve.role@example.com")).isEmpty();
    }

    @Test
    void rejectsClientSuppliedStatusAndPasswordHash() throws Exception {
        String body = """
                {
                  "firstName": "Eve",
                  "lastName": "Attacker",
                  "email": "eve.status@example.com",
                  "password": "Example-Password-123",
                  "accountStatus": "SUSPENDED",
                  "passwordHash": "{bcrypt}$2a$12$attacker-supplied"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[?(@.field == 'accountStatus')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field == 'passwordHash')]").exists());

        assertThat(userRepository.findByEmailIgnoreCase("eve.status@example.com")).isEmpty();
    }

    @Test
    void rejectsAPasswordContainingTheEmailLocalPart() throws Exception {
        String body = """
                {
                  "firstName": "Jane",
                  "lastName": "Doe",
                  "email": "jane.context@example.com",
                  "password": "jane.context"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[?(@.field == 'password')]").exists());

        assertThat(userRepository.findByEmailIgnoreCase("jane.context@example.com")).isEmpty();
    }

    @Test
    void registrationWithoutAnAllowedOriginIsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody("no.origin@example.com")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(userRepository.findByEmailIgnoreCase("no.origin@example.com")).isEmpty();
    }

    @Test
    void profileReadRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void authenticatedBuyerReadsOnlyTheirOwnProfile() throws Exception {
        User user = registerAndLoad("owner@example.com");

        mockMvc.perform(get("/api/v1/users/me").with(user(user.getId().toString())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.data.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.data.email").value("owner@example.com"))
                .andExpect(jsonPath("$.data.role").value("BUYER"))
                .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(content().string(not(containsString("passwordHash"))));
    }

    @Test
    void unknownPrincipalIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(user(UUID.randomUUID().toString())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void profileUpdateRequiresCsrf() throws Exception {
        User user = registerAndLoad("csrf@example.com");

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(user(user.getId().toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Changed\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        User unchanged = userRepository.findById(user.getId()).orElseThrow();
        assertThat(unchanged.getFirstName()).isEqualTo("Jane");
    }

    @Test
    void profileUpdateChangesOnlyPermittedFieldsAndKeepsProtectedValues() throws Exception {
        User user = registerAndLoad("patch@example.com");
        String originalHash = user.getPasswordHash();

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(user(user.getId().toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "  Janet  ",
                                  "phone": " +441234567890 "
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.firstName").value("Janet"))
                .andExpect(jsonPath("$.data.lastName").value("Doe"))
                .andExpect(jsonPath("$.data.phone").value("+441234567890"))
                .andExpect(jsonPath("$.data.email").value("patch@example.com"))
                .andExpect(jsonPath("$.data.role").value("BUYER"))
                .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

        User updated = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updated.getFirstName()).isEqualTo("Janet");
        assertThat(updated.getPhone()).isEqualTo("+441234567890");
        assertThat(updated.getEmail()).isEqualTo("patch@example.com");
        assertThat(updated.getRole().getName()).isEqualTo("BUYER");
        assertThat(updated.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(updated.getPasswordHash()).isEqualTo(originalHash);
    }

    @Test
    void profileUpdateRejectsProtectedFieldsAndChangesNothing() throws Exception {
        User user = registerAndLoad("protected@example.com");

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(user(user.getId().toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\",\"accountStatus\":\"SUSPENDED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[?(@.field == 'role')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field == 'accountStatus')]").exists());

        User unchanged = userRepository.findById(user.getId()).orElseThrow();
        assertThat(unchanged.getRole().getName()).isEqualTo("BUYER");
        assertThat(unchanged.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void stage32BcryptHashesRemainCompatibleWithCredentialVerification() throws Exception {
        User user = registerAndLoad("verify.compatible@example.com");

        var verified = credentialVerificationService.verify("  VERIFY.COMPATIBLE@example.com  ", RAW_PASSWORD);

        assertThat(verified).isPresent();
        assertThat(verified.get().id()).isEqualTo(user.getId());
        assertThat(verified.get().email()).isEqualTo("verify.compatible@example.com");
        assertThat(verified.get().roleName()).isEqualTo("BUYER");
        assertThat(verified.get().accountStatus()).isEqualTo(AccountStatus.ACTIVE);

        assertThat(credentialVerificationService.verify("verify.compatible@example.com", "wrong-password"))
                .isEmpty();
        assertThat(credentialVerificationService.verify("nobody@example.com", RAW_PASSWORD)).isEmpty();
    }

    private org.springframework.test.web.servlet.RequestBuilder register(String email)
            throws Exception {
        return post("/api/v1/auth/register")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(registrationBody(email));
    }

    private User registerAndLoad(String email) throws Exception {
        mockMvc.perform(register(email)).andExpect(status().isCreated());
        Optional<User> user = userRepository.findByEmailIgnoreCase(email);
        assertThat(user).isPresent();
        return user.orElseThrow();
    }

    private static String registrationBody(String email) {
        return """
                {
                  "firstName": "Jane",
                  "lastName": "Doe",
                  "email": "%s",
                  "password": "%s",
                  "phone": "+919876543210"
                }
                """.formatted(email, RAW_PASSWORD);
    }
}
