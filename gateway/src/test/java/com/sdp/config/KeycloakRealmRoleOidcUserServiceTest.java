package com.sdp.config;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakRealmRoleOidcUserServiceTest {

    private final KeycloakRealmRoleOidcUserService service = new KeycloakRealmRoleOidcUserService();

    @Test
    void mapsRealmAccessRolesOntoGrantedAuthoritiesWithNoRolePrefix() {
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("trader")));

        Set<GrantedAuthority> authorities = service.realmRoleAuthorities(claims);

        assertThat(authorities).containsExactly(new SimpleGrantedAuthority("trader"));
    }

    @Test
    void mapsMultipleRealmRoles() {
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("trader", "viewer")));

        Set<GrantedAuthority> authorities = service.realmRoleAuthorities(claims);

        assertThat(authorities).containsExactlyInAnyOrder(
                new SimpleGrantedAuthority("trader"), new SimpleGrantedAuthority("viewer"));
    }

    @Test
    void returnsEmptySetWhenRealmAccessClaimIsMissing() {
        Set<GrantedAuthority> authorities = service.realmRoleAuthorities(Map.of());

        assertThat(authorities).isEmpty();
    }

    @Test
    void getNameResolvesToPreferredUsernameNotSubClaim() {
        OidcIdToken idToken = new OidcIdToken(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(60),
                Map.of(
                        "sub", "d90fe3a3-91df-4e1c-ad48-a1fbd7af178c",
                        "preferred_username", "trader1",
                        "realm_access", Map.of("roles", List.of("trader"))));
        OidcUser oidcUser = new DefaultOidcUser(Set.of(), idToken);

        OidcUser mapped = service.withRealmRolesAndUsername(oidcUser);

        assertThat(mapped.getName()).isEqualTo("trader1");
    }
}
