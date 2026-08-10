package com.sdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.session.data.redis.config.annotation.web.server.EnableRedisWebSession;

/**
 * Keycloak (authorization code grant) via oauth2Login - see ADR 0020,
 * superseding ADR 0016's hand-rolled auth. Only /ws requires authentication;
 * everything else (Spring Security's own oauth2Login endpoints) is open,
 * since this app has no other HTTP surface of its own left once POST /login
 * was retired. AuditingAuthenticationSuccessHandler/AuditingAuthentication-
 * FailureHandler record LOGIN_SUCCESS/LOGIN_ERROR (ADR 0019/0020, issue
 * #87) before performing the same redirects oauth2Login would otherwise do
 * inline (success, to the frontend origin) or by default (failure, to
 * /login?error).
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
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            AuditingAuthenticationSuccessHandler successHandler,
            AuditingAuthenticationFailureHandler failureHandler) {
        return http
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/ws").authenticated()
                        .anyExchange().permitAll())
                .oauth2Login(oauth2 -> oauth2
                        .authenticationSuccessHandler(successHandler)
                        .authenticationFailureHandler(failureHandler))
                .build();
    }
}
