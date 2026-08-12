package com.sdp.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

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
}
