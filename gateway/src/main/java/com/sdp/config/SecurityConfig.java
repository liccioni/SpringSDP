package com.sdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.session.data.redis.config.annotation.web.server.EnableRedisWebSession;

/**
 * Keycloak (authorization code grant) via oauth2Login - see ADR 0020,
 * superseding ADR 0016's hand-rolled auth. Only /ws requires authentication;
 * everything else (Spring Security's own oauth2Login/logout endpoints) is
 * open, since this app has no other HTTP surface of its own left once POST
 * /login was retired. AuditingAuthenticationSuccessHandler/Auditing-
 * AuthenticationFailureHandler/AuditingLogoutSuccessHandler publish
 * LOGIN_SUCCESS/LOGIN_ERROR/LOGOUT over RabbitMQ for the Backend/Trading
 * Service to audit (ADR 0019/0020/0023, issues #87/#102) before performing
 * the redirects oauth2Login/logout would otherwise do by default (success,
 * to the frontend origin; login failure, to /login?error; logout, to
 * /login?logout).
 *
 * CSRF uses a readable cookie (CookieServerCsrfTokenRepository) rather than
 * the WebFlux default (token stored in the WebSession, unreadable by the
 * frontend's own JS) so the frontend's hidden logout form (issue #102) can
 * read a real token value into its hidden field - paired with
 * CsrfCookieWebFilter, which forces that cookie to actually get written on
 * every request (see that class's javadoc for why it's needed at all).
 * ServerCsrfTokenRequestAttributeHandler (plain, not the Xor-masking
 * default) is used explicitly: the default masks the token whenever it's
 * exposed via the exchange attribute, for BREACH protection when a token is
 * rendered into server-side HTML - not this app's case, since the frontend
 * reads the cookie's raw value directly and submits that same raw value
 * back, which the masking default would reject as a mismatch.
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
            AuditingAuthenticationFailureHandler failureHandler,
            AuditingLogoutSuccessHandler logoutSuccessHandler) {
        return http
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/ws").authenticated()
                        .anyExchange().permitAll())
                .oauth2Login(oauth2 -> oauth2
                        .authenticationSuccessHandler(successHandler)
                        .authenticationFailureHandler(failureHandler))
                .logout(logout -> logout.logoutSuccessHandler(logoutSuccessHandler))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieWebFilter(), SecurityWebFiltersOrder.CSRF)
                .build();
    }
}
