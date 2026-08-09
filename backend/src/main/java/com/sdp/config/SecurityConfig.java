package com.sdp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.session.data.redis.config.annotation.web.server.EnableRedisWebSession;

/**
 * Keycloak (authorization code grant) via oauth2Login - see ADR 0020,
 * superseding ADR 0016's hand-rolled auth. Only /ws requires authentication;
 * everything else (Spring Security's own oauth2Login endpoints) is open,
 * since this app has no other HTTP surface of its own left once POST /login
 * was retired. On success, redirects back to the frontend origin rather
 * than Spring Security's default (the originally-requested URL), since the
 * login flow is a top-level navigation initiated from the frontend, not a
 * request to a backend page worth returning to.
 *
 * @EnableRedisWebSession is explicit rather than property-triggered
 * auto-configuration: spring-session-data-redis ships no Spring Boot
 * auto-configuration of its own for the reactive (WebSession) case in this
 * version, only the servlet (HttpSession) one - see ADR 0020.
 */
@Configuration
@EnableWebFluxSecurity
@EnableRedisWebSession
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, @Value("${app.frontend-origin}") String frontendOrigin) {
        return http
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/ws").authenticated()
                        .anyExchange().permitAll())
                .oauth2Login(oauth2 -> oauth2.authenticationSuccessHandler(
                        new RedirectServerAuthenticationSuccessHandler(frontendOrigin)))
                .build();
    }
}
