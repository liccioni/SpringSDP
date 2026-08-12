package com.sdp.config;

import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Forces the CsrfToken Mono exchange attribute to actually be subscribed
 * to on every request, so CookieServerCsrfTokenRepository writes the
 * XSRF-TOKEN cookie. Without this, WebFlux's CsrfWebFilter only generates/
 * persists a token when something subscribes to that Mono - which nothing
 * else in this app's stack does, since there's no server-rendered page.
 * This is the standard pattern from Spring Security's own WebFlux CSRF
 * docs for an SPA reading the token cookie itself (issue #102's hidden
 * logout form needs a real token value to put in its hidden field).
 */
class CsrfCookieWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        exchange.getResponse().beforeCommit(() -> {
            Mono<CsrfToken> csrfToken = exchange.getAttribute(CsrfToken.class.getName());
            return csrfToken != null ? csrfToken.then() : Mono.empty();
        });
        return chain.filter(exchange);
    }
}
