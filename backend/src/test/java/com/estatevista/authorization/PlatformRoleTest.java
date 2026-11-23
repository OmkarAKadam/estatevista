package com.estatevista.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the exhaustive, server-side role-to-authority mapping.
 *
 * <p>Only the three role names seeded by Flyway migration V2 are recognized.
 * Any other value yields no authority so that authorization fails closed.</p>
 */
class PlatformRoleTest {

    @ParameterizedTest
    @ValueSource(strings = {"BUYER", "AGENT", "ADMIN"})
    void mapsSeededRoleNamesExactly(String roleName) {
        PlatformRole role = PlatformRole.fromDatabaseName(roleName).orElseThrow();

        assertThat(role.name()).isEqualTo(roleName);
        assertThat(role.authority()).isEqualTo("ROLE_" + roleName);
    }

    @ParameterizedTest
    @ValueSource(strings = {"buyer", "Buyer", "ADMIN ", "GUEST", "SUPER_ADMIN", ""})
    void rejectsUnknownOrNonExactRoleNames(String roleName) {
        assertThat(PlatformRole.fromDatabaseName(roleName)).isEmpty();
    }

    @Test
    void rejectsNullRoleName() {
        assertThat(PlatformRole.fromDatabaseName(null)).isEmpty();
    }

    @Test
    void grantsNoAuthorityForAnUnknownRole() {
        assertThat(PlatformRole.fromDatabaseName("GUEST"))
                .as("unknown roles must not produce a security authority")
                .isEmpty();
    }
}
