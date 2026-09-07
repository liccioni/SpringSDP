# 0031. Reverse proxy unifying browser-facing origins (local dev, no TLS)

Date: 2026-09-07

Status: Accepted

## Context

Under Docker Compose, the browser has always talked to two separate origins:
the frontend's plain-nginx static container (`localhost:5173`) and the
Gateway's WebSocket/OAuth2 endpoints (`localhost:8080`). This only works
because browser cookies aren't port-scoped. Issue #103 (filed alongside the
MVP 0.8 logout work, #102) flagged this as fragile and proposed a reverse
proxy unifying both origins — but was deliberately deferred at the time:
there was no near-term TLS/production-deployment need, and building it
before the MVP 0.7 service split (#89-#94) settled would have meant
rewriting proxy routes as each endpoint migrated one at a time.

That trigger still hasn't arrived — issue #121 (cloud provider research) is
unscheduled and no production/TLS target exists yet. This ADR picks the
issue up anyway, deliberately scoped narrower than a "correct eventual
answer" reading of the original issue text would suggest: **local
Docker-Compose dev unification only, no TLS, Keycloak left out.** It
delivers the concrete, real win available today (retiring the
cookie-port-scoping fragility, one origin for the whole app) without
building ahead of a production need that hasn't materialized. Left
undecided, whichever future issue picks up TLS/production deployment
(#121/#120) would have to invent this topology from scratch anyway, so
recording the local-only version now is still useful groundwork rather than
wasted motion.

## Decision

* **A new `proxy` nginx container becomes the sole browser-facing origin,
  reusing port 8080** — the Gateway's own former default. `gateway` and
  `frontend` both stop publishing host ports and become reachable only from
  `proxy` over the existing `public` Docker network (ADR 0021). `proxy`
  routes `/ws`, `/oauth2/*`, `/login/*`, `/logout` to `gateway:8080`, and
  everything else to `frontend:80`. Reusing 8080 rather than picking a new
  port means `frontend/src/services/socket.ts`'s `DEFAULT_WS_URL`/
  `DEFAULT_LOGIN_URL`/`DEFAULT_LOGOUT_URL` constants, `frontend/Dockerfile`'s
  `VITE_WS_URL` build arg, and Keycloak's registered `redirectUris` all
  needed zero changes — only `app.frontend-origin` and Keycloak's
  `post.logout.redirect.uris` (both previously `:5173`) moved to `:8080`.
* **`proxy` proxy_passes to the existing `frontend` container** rather than
  absorbing static-serving into the proxy image itself. Matches this
  repo's established one-container-per-concern pattern (ADR 0022: gateway,
  market-data-service, trading-service are already split this way), avoids
  duplicating the npm build and nginx directives (cache-control, error
  pages) into a second Dockerfile, and keeps `proxy/nginx.conf` a pure
  routing layer — the simplest shape for a later TLS pass to add to.
* **`proxy_set_header Host $http_host;` on every gateway-routed location** is
  the one load-bearing correctness fix. nginx's `proxy_pass` does not
  forward the client's original `Host` header by default — it sends
  `Host: gateway:8080` unless told otherwise. Spring Security's
  `oauth2Login` redirect-uri template
  (`"{baseUrl}/login/oauth2/code/{registrationId}"`) derives `{baseUrl}`
  from the *incoming request's* Host header at runtime — the one place in
  this codebase with request-derived origin construction, as opposed to a
  configured property. Without forwarding the real Host, the `redirect_uri`
  sent to Keycloak stops matching the registered
  `http://localhost:8080/login/oauth2/code/keycloak`, and login breaks with
  a Keycloak-side "invalid redirect_uri" error that doesn't obviously point
  back at the proxy. **Caught live during verification**: an initial
  `$host` (nginx's variant that strips the port from the Host header,
  a documented behavior, not a bug) silently dropped `:8080`, producing
  `redirect_uri=http://localhost/login/oauth2/code/keycloak` — same failure
  class as not forwarding Host at all. `$http_host` (the Host header taken
  verbatim, port included) is the correct variable.
* **`server.forward-headers-strategy` deliberately stays unset** in
  `gateway/application.yml`. No scheme or externally-visible-port
  translation happens anywhere in this scope (no TLS, same port
  in/out), so there's nothing for forwarded-header handling to reconcile —
  setting it now would be a no-op. Revisit once a later TLS pass (#121/#120)
  terminates TLS at the proxy: that introduces a real scheme mismatch
  (`X-Forwarded-Proto: https` vs. the Gateway's own plain-HTTP view of
  itself) that this property exists to fix.
* **WebSocket upgrade headers and a raised read timeout on `/ws`.** nginx
  doesn't upgrade a proxied connection by default; `proxy_http_version 1.1`
  plus `proxy_set_header Upgrade $http_upgrade` / `Connection
  $connection_upgrade` (the standard `map $http_upgrade $connection_upgrade
  {...}` idiom) are required for the price-tick WebSocket to work at all
  through the proxy. `proxy_read_timeout` is raised to `3600s` — nginx's 60s
  default would silently kill the long-lived price-tick connection on any
  quiet gap past a minute.
* **Keycloak stays on its own separate origin (`localhost:8081`),
  deliberately not routed through `proxy`.** The issue's own wording
  ("unifying everything") reads naturally as frontend+gateway, not the
  identity provider — routing Keycloak through the proxy too would touch
  `KC_HOSTNAME`'s existing dual-path issuer-claim reconciliation (ADR 0020)
  and the admin console, neither of which this pass needs to disturb.
* **`post_logout_redirect_uri` and `app.frontend-origin` stay absolute, not
  relative** — correcting the original issue's own claim that unifying
  origins would let this become relative. `post_logout_redirect_uri` is a
  query parameter sent to Keycloak's end-session endpoint at a genuinely
  different origin (`localhost:8081`); per OIDC RP-Initiated Logout and
  Keycloak's own validation, it has no basis to resolve a relative path and
  must stay absolute. `app.frontend-origin` feeds both that use and the
  plain post-login browser redirect (now same-origin, and so *could*
  become relative) — kept as one absolute property rather than split into
  two, since the same-origin use case is a rarely-hit fallback branch not
  worth the extra property for.
* **The native "Without Docker" dev path is explicitly unchanged.** It runs
  `gradlew bootRun` (gateway, `:8080`) and `npm run dev` (Vite's own dev
  server, `:5173`) as bare host processes and never starts the `proxy`
  container — it keeps today's two-origin topology exactly as before, since
  it's about running services as native processes rather than reproducing
  the containerized topology.
* **`gateway` gets a new healthcheck (curl against `/actuator/health`), and
  `proxy`'s (and `frontend`'s) `depends_on: gateway` moves to
  `condition: service_healthy`.** Caught live, on a real cold start: a plain
  `depends_on` only waits for the container to start, not for the Spring
  Boot app inside it to actually be listening - `gateway`'s Netty server
  takes roughly 9 seconds to come up after the container starts, and
  without this fix `proxy` (whose own nginx starts in milliseconds) began
  forwarding requests to it immediately, producing `502 Bad Gateway` -
  `connect() failed (111: Connection refused)` - for that entire window on
  every fresh `docker compose up`. Same shape as this file's own existing
  Keycloak healthcheck (`condition: service_healthy` before `gateway`
  itself starts), just using `curl` directly rather than the `/dev/tcp`
  shell trick Keycloak's minimal base image needed - `gateway`'s
  `eclipse-temurin:21-jre` base image has `curl` available.
* **Accepted risk: Docker's embedded DNS is resolved once at nginx startup
  and cached indefinitely** for the bare-hostname `proxy_pass` targets
  (`gateway:8080`, `frontend:80`). A known nginx-in-Docker gotcha (a
  container restart changing the target's IP without a full `proxy` restart
  can leave it pointing at a stale address) — accepted here as a
  single-host, dev-only risk rather than adding a `resolver`+variable
  workaround, consistent with this project's "start simple" philosophy.

## Verified

Live-verified end to end after implementation, including a real cold
`docker compose up` (not just a rebuild of one already-warm container) -
that cold start is exactly what surfaced the startup-race bug above, which
a warm restart during initial verification had masked.

## Consequences

* One browser-facing origin (`http://localhost:8080`) for the whole local
  dev stack, retiring the cookie-port-scoping fragility issue #103
  originally flagged. `docker-compose.yml`'s `public` network now exposes
  only `proxy` and Keycloak to the host; `gateway` and `frontend` keep
  `public` membership (so `proxy` can reach them) but no host-published
  port of their own.
* `docs/protocol.md`'s Endpoint section and `README.md` both needed
  correcting: the Gateway is no longer directly reachable on its own
  published port under Docker Compose, and `startUpDocker.sh` now waits on
  and opens a single URL instead of two.
* Five files hardcoded the old `localhost:5173`/`localhost:8080` literals as
  both fixture input and assertion target
  (`LogoutIT`, `AuditingLogoutSuccessHandlerTest`,
  `AuditingAuthenticationSuccessHandlerTest`, `socket.test.ts`,
  `App.integration.test.tsx`); the three Java tests needed their `:5173`
  literals updated to `:8080`, the two frontend tests needed no change
  (already `:8080`).
* This intentionally does **not** cover TLS termination or non-localhost
  production deployment — that stays issues #121 (cloud provider research,
  still unscheduled) and #120 (Kubernetes manifests, still unscheduled)'s
  job, per `docs/roadmap.md`'s backlog execution strategy. `proxy/nginx.conf`
  is kept deliberately simple (a pure routing layer, no TLS directives) so
  that pass is additive rather than a rewrite.
* A future service added behind this proxy (or a TLS-terminating rewrite of
  it) can copy the `Host`-forwarding and WebSocket-upgrade pattern directly
  rather than rediscovering both gotchas.
