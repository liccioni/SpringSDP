# 0023. Logout: OIDC RP-Initiated Logout, cookie-based CSRF

Date: 2026-08-12

Status: Accepted

## Context

MVP 0.6 (ADR 0020) shipped Keycloak login but no logout: deleting the app's
own `SESSION` cookie doesn't end anything on Keycloak's side, so a browser
that still holds Keycloak's SSO session cookie silently re-authenticates
through the OAuth2 redirect the moment `/ws` is hit again. Issue #102 (MVP
0.8) closes that gap - a real logout has to end both sessions, not just this
app's.

Two sub-decisions had genuine alternatives worth recording:

1. **How to end Keycloak's SSO session.** Spring Security ships
   `OidcClientInitiatedServerLogoutSuccessHandler`, which builds the
   end-session redirect for you - but only by resolving Keycloak's
   `end_session_endpoint` from `ClientRegistration`'s provider metadata,
   which is only populated via OIDC discovery (`issuer-uri`). ADR 0020
   already rejected `issuer-uri` for this app: the browser and this service
   reach Keycloak by different paths under Docker Compose
   (`localhost:8081` vs. `keycloak:8080`), and Spring Boot's issuer-uri
   shortcut asserts the fetched issuer string exactly matches the URL it was
   fetched from, which can't hold across two different paths.
2. **How the frontend proves the `POST /logout` request is really from its
   own page (CSRF).** The WebFlux default (`WebSessionServerCsrfTokenRepository`)
   stores the token server-side in the `WebSession`, invisible to the
   frontend's own JS - fine for a form rendered by the same server, useless
   for a hidden form built by a React SPA that needs to read a real token
   value into a hidden input before submitting.

## Decision

* **`AuditingLogoutSuccessHandler` builds the Keycloak end-session redirect
  by hand**, the same way `AuditingAuthenticationSuccessHandler`/
  `AuditingAuthenticationFailureHandler` already build their own redirects
  rather than delegating to framework defaults that assume issuer-uri
  discovery. A new `app.keycloak.end-session-uri` property (same shape and
  same reasoning as `authorization-uri`: browser-facing, so it keeps its
  host-published `localhost:8081` default under Docker Compose too, and is
  deliberately **not** overridden in `docker-compose.yml`) supplies the
  endpoint. The handler appends `id_token_hint` (from the session's
  `OidcUser.getIdToken()` - always present, since `oauth2Login`'s `openid`
  scope guarantees an OIDC, not just OAuth2, principal),
  `post_logout_redirect_uri` (`app.frontend-origin`, reusing the existing
  property), and `client_id`.
* **A new `LOGOUT` audit event**, published fire-and-forget over a
  `logout`/`logout-out-0` RabbitMQ fanout exchange exactly like
  `LOGIN_SUCCESS`/`LOGIN_ERROR` (ADR 0020/0022), consumed by the
  Backend/Trading Service's `AuditService.logoutConsumer()`. `sessionId` is
  `null`, the same convention `LOGIN_SUCCESS`/`LOGIN_ERROR` use: the
  security layer that fires this event has no access to the app's own
  connection-scoped `Session` (ADR 0017), only the HTTP-level
  `Authentication`.
* **CSRF moves to `CookieServerCsrfTokenRepository.withHttpOnlyFalse()`**, a
  readable `XSRF-TOKEN` cookie, paired with a small `CsrfCookieWebFilter`
  that forces the `CsrfToken` `Mono` exchange attribute to actually be
  subscribed to on every request (Spring Security's documented pattern for
  this - without it, nothing in this app's stack ever subscribes, so the
  repository never actually writes the cookie). **The token-request handler
  is explicitly the plain `ServerCsrfTokenRequestAttributeHandler`, not the
  default `XorServerCsrfTokenRequestAttributeHandler`.** The Xor default
  masks the token wherever it's exposed via the exchange attribute, for
  BREACH protection when a token is rendered into server-side HTML - this
  app has none of that; the frontend reads the cookie's raw value directly
  and submits that same raw value back as a hidden form field (`_csrf`), so
  the masking default would just make every request's token fail to match
  its own cookie.
* **The frontend submits a hidden, auto-submitting `<form method="POST">`**
  (`logout()` in `socket.ts`), not `fetch`/`XHR` - avoids needing any CORS
  configuration for a cross-origin (different port) request to the Gateway,
  the same reasoning `POST /logout` itself already gets for free from
  `oauth2Login`'s own redirect-based flow.
* **`keycloak/sdp-realm.json` registers `post.logout.redirect.uris`** for
  the frontend's origin (`http://localhost:5173`) - a Keycloak client
  `attributes` map entry, not a top-level array field like `redirectUris`.
* **Reconfirms ADR 0019's decision not to add a `SESSION_ENDED` event or a
  WebSocket-disconnect hook.** Issue #102 is a browser-driven HTTP logout,
  not a WebSocket lifecycle change - the WS connection tears down the same
  way a page reload always has, and ADR 0019 already reasoned through why
  hooking that moment isn't worth it yet.

## Consequences

* `SecurityConfig`'s filter chain now has three moving parts instead of one
  (`oauth2Login`, `logout`, `csrf`) where it used to have just
  `oauth2Login` - each with its own auditing handler wrapping a framework
  redirect, a pattern that's now fully consistent across login success,
  login failure, and logout.
* **Testing this needs a real `OidcUser` principal, not the plain
  `UsernamePasswordAuthenticationToken` `SdpWebSocketHandlerIT` seeds for
  login/WS tests** - `AuditingLogoutSuccessHandler` casts the principal to
  `OidcUser` directly (safe in production, since `openid` scope guarantees
  it) to read `getIdToken()`. `LogoutIT` builds a real `DefaultOidcUser` +
  `OidcIdToken` and seeds it into Redis the same way, rather than widening
  the handler with a defensive `instanceof` check for a case that can't
  happen outside a test's own fake setup.
* **No `WebTestClient` autoconfiguration module exists on this project's
  Spring Boot 4.1.0** (`spring-boot-test-autoconfigure` doesn't ship the
  reactive web-test-client slice this version) - `LogoutIT` uses a plain
  `WebClient` bound to `@LocalServerPort` instead, with no redirect-following
  configured, so the `302` itself comes back for assertion the same way a
  browser's non-following `WebSocket` upgrade already does in
  `SdpWebSocketHandlerIT`.
* A future service that needs its own CSRF-protected form endpoint can copy
  `CsrfCookieWebFilter` and the plain-handler config directly rather than
  rediscovering the Xor-masking pitfall.
* The actual Keycloak end-session round trip (browser redirected away and
  back with both cookies gone) is verified live, not by an automated test -
  same standard ADR 0020 set for the login side, for the same reason
  (automating a real OAuth2/OIDC browser flow in JUnit is disproportionate
  for this project's scale).
