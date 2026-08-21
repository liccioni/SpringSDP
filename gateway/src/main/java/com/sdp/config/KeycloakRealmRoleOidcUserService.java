package com.sdp.config;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.ReactiveOAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * Maps Keycloak's {@code realm_access.roles} ID token claim (the
 * {@code trader}/{@code viewer} roles defined in
 * {@code keycloak/sdp-realm.json}) onto plain {@link GrantedAuthority}s -
 * see ADR 0025. No {@code ROLE_} prefix: the resulting authorities feed
 * {@code Session.roles()} (com.sdp.session), which trading-service checks
 * with a manual {@code Set.contains}, not {@code hasRole()}/{@code
 * @PreAuthorize}. Spring Security's default {@link OidcReactiveOAuth2UserService}
 * only ever produces the generic {@code OIDC_USER} authority - it has no
 * idea Keycloak's realm roles exist unless told to look.
 */
@Component
public class KeycloakRealmRoleOidcUserService implements ReactiveOAuth2UserService<OidcUserRequest, OidcUser> {

    private final OidcReactiveOAuth2UserService delegate = new OidcReactiveOAuth2UserService();

    @Override
    public Mono<OidcUser> loadUser(OidcUserRequest userRequest) {
        return delegate.loadUser(userRequest).map(this::withRealmRolesAndUsername);
    }

    /**
     * {@link DefaultOidcUser}'s no-name-attribute-key constructor defaults
     * {@code getName()} to the {@code sub} claim, silently ignoring this
     * app's {@code user-name-attribute: preferred_username} config (that
     * property only applies to the plain OAuth2 {@code DefaultOAuth2User}
     * path, not OIDC's {@code DefaultOidcUser}) - see issue #127.
     */
    OidcUser withRealmRolesAndUsername(OidcUser oidcUser) {
        return new DefaultOidcUser(
                realmRoleAuthorities(oidcUser.getIdToken().getClaims()),
                oidcUser.getIdToken(),
                oidcUser.getUserInfo(),
                "preferred_username");
    }

    @SuppressWarnings("unchecked")
    Set<GrantedAuthority> realmRoleAuthorities(Map<String, Object> idTokenClaims) {
        Object realmAccess = idTokenClaims.get("realm_access");
        if (!(realmAccess instanceof Map<?, ?> realmAccessMap)) {
            return Set.of();
        }
        Object roles = realmAccessMap.get("roles");
        if (!(roles instanceof List<?> roleList)) {
            return Set.of();
        }
        return roleList.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority((String) role))
                .collect(Collectors.toSet());
    }
}
