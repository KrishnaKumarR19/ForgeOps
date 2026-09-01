package com.forgeops.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.forgeops.identity.application.AuthenticatedUser;
import com.forgeops.identity.domain.Role;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

/**
 * Unit tests for {@link AuthenticatedUserAuthentication}: mapping the authenticated
 * principal's roles to Spring Security authorities (Phase 4.2 Slice 5). Verifies the
 * canonical single {@code ROLE_} prefix (so {@code hasRole("ADMIN")} matches), correct
 * per-role mapping, multi-role preservation, and that no doubled prefix is produced.
 * Synthetic data; no Spring context.
 */
class AuthenticatedUserAuthenticationTests {

    private static final UUID USER_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000aa");

    private static Set<String> authorities(Set<Role> roles) {
        var auth = new AuthenticatedUserAuthentication(new AuthenticatedUser(USER_ID, roles));
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void adminRoleMapsToRoleAdminAuthority() {
        assertThat(authorities(EnumSet.of(Role.ADMIN))).containsExactly("ROLE_ADMIN");
    }

    @Test
    void engineerRoleMapsToRoleEngineerAuthority() {
        assertThat(authorities(EnumSet.of(Role.ENGINEER))).containsExactly("ROLE_ENGINEER");
    }

    @Test
    void incidentManagerRoleMapsToRoleIncidentManagerAuthority() {
        assertThat(authorities(EnumSet.of(Role.INCIDENT_MANAGER)))
                .containsExactly("ROLE_INCIDENT_MANAGER");
    }

    @Test
    void viewerRoleMapsToRoleViewerAuthority() {
        assertThat(authorities(EnumSet.of(Role.VIEWER))).containsExactly("ROLE_VIEWER");
    }

    @Test
    void multipleRolesAreAllMappedAndPreserved() {
        assertThat(authorities(EnumSet.of(Role.ADMIN, Role.ENGINEER, Role.VIEWER)))
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_ENGINEER", "ROLE_VIEWER");
    }

    @Test
    void doesNotProduceDoubledRolePrefix() {
        Set<String> all = authorities(EnumSet.allOf(Role.class));

        assertThat(all).allSatisfy(a -> {
            assertThat(a).startsWith("ROLE_");
            assertThat(a).doesNotContain("ROLE_ROLE_");
        });
    }

    @Test
    void isAuthenticatedAndExposesPrincipal() {
        var principal = new AuthenticatedUser(USER_ID, EnumSet.of(Role.ADMIN));
        var auth = new AuthenticatedUserAuthentication(principal);

        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getPrincipal()).isEqualTo(principal);
        assertThat(auth.principal()).isEqualTo(principal);
        assertThat(auth.getCredentials()).isNull();
        assertThat(auth.getName()).isEqualTo(USER_ID.toString());
    }
}
