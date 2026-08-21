package com.sdp.config;

import java.net.URI;

import com.sdp.contracts.Logout;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import reactor.core.publisher.Mono;

/**
 * Records a LOGOUT audit event, then redirects to Keycloak's end-session
 * endpoint with id_token_hint/post_logout_redirect_uri/client_id (OIDC
 * RP-Initiated Logout, issue #102), replacing Spring Security's default
 * post-logout redirect (SecurityConfig had none configured, so it would
 * otherwise fall back to /login?logout). Built explicitly rather than via
 * Spring's OidcClientInitiatedServerLogoutSuccessHandler, since that needs
 * OIDC discovery (issuer-uri) - deliberately not configured, same reasoning
 * as ADR 0020 - see ADR 0023.
 *
 * The principal is an OidcUser for a real, still-valid OIDC login -
 * oauth2Login's scope includes "openid" (application.yml), so Spring
 * Security normally resolves it via OidcReactiveOAuth2UserService rather
 * than a plain OAuth2User. But if the session backing the request has
 * already expired or was otherwise invalidated (issue #126), Spring
 * Security falls back to an anonymous Authentication with a plain String
 * principal instead - there's no id_token_hint to give Keycloak in that
 * case, so logout just redirects to the frontend origin directly.
 */
@Component
public class AuditingLogoutSuccessHandler implements ServerLogoutSuccessHandler {

    private static final String LOGOUT_BINDING = "logout-out-0";

    private final StreamBridge streamBridge;
    private final String endSessionUri;
    private final String frontendOrigin;
    private final String clientId;

    public AuditingLogoutSuccessHandler(
            StreamBridge streamBridge,
            @Value("${app.keycloak.end-session-uri}") String endSessionUri,
            @Value("${app.frontend-origin}") String frontendOrigin,
            @Value("${spring.security.oauth2.client.registration.keycloak.client-id}") String clientId) {
        this.streamBridge = streamBridge;
        this.endSessionUri = endSessionUri;
        this.frontendOrigin = frontendOrigin;
        this.clientId = clientId;
    }

    @Override
    public Mono<Void> onLogoutSuccess(WebFilterExchange webFilterExchange, Authentication authentication) {
        return Mono.fromRunnable(() -> streamBridge.send(LOGOUT_BINDING, new Logout(authentication.getName())))
                .then(Mono.defer(() -> {
                    ServerHttpResponse response = webFilterExchange.getExchange().getResponse();
                    response.setStatusCode(HttpStatus.FOUND);
                    response.getHeaders().setLocation(redirectUri(authentication));
                    return response.setComplete();
                }));
    }

    private URI redirectUri(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof OidcUser oidcUser)) {
            return URI.create(frontendOrigin);
        }
        return UriComponentsBuilder.fromUriString(endSessionUri)
                .queryParam("id_token_hint", oidcUser.getIdToken().getTokenValue())
                .queryParam("post_logout_redirect_uri", frontendOrigin)
                .queryParam("client_id", clientId)
                .build()
                .toUri();
    }
}
