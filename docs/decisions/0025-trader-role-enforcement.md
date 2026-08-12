# 0025. Enforce trader/viewer roles on trade creation

Date: 2026-08-12

Status: Accepted

## Context

Keycloak has defined `trader` ("full access: subscribe, create/confirm/
cancel trades, view the blotter") and `viewer` ("read-only: prices and
blotter, no trading") realm roles since MVP 0.6 (`keycloak/sdp-realm.json`),
with a demo user (`trader2`) assigned `viewer`. No code path anywhere ever
checked either role: `oauth2Login` never mapped `realm_access.roles` into
Spring Security authorities, `Session` (ADR 0017) carried no role field,
neither `SdpWebSocketHandler` nor `trading-service`'s `TradeService`
performed any authorization check, and the frontend gated the buy/sell
buttons only on `pendingTrade !== null` - a UX lock, not a permission check.
The `viewer` role has been silently equivalent to `trader` for trading
purposes since it was introduced (issue #117).

## Decision

* **Enforcement lives in `trading-service`, not the gateway.** The
  architecture doc's "WebSocket handling should not contain business logic"
  rule applies here: whether a role permits trading is a trading-domain
  rule, not transport plumbing. The gateway's `SdpWebSocketHandler`/
  `TradeService` stay a pure relay, unchanged in this respect.
* **Keycloak realm roles are mapped onto Spring Security authorities at the
  gateway** via a new `KeycloakRealmRoleOidcUserService`
  (`ReactiveOAuth2UserService<OidcUserRequest, OidcUser>`), reading the ID
  token's `realm_access.roles` claim and producing plain `GrantedAuthority`s
  with no `ROLE_` prefix - the authorization check downstream is a manual
  `Set.contains`, not `hasRole()`/`@PreAuthorize`, so there's no reason to
  carry Spring's usual role-prefix convention across the wire. Reactive
  `ServerHttpSecurity.OAuth2LoginSpec` has no `userInfoEndpoint()` DSL
  (unlike servlet Spring Security); it looks up a
  `ReactiveOAuth2UserService<OidcUserRequest, OidcUser>` bean from the
  application context on its own, so no explicit wiring is needed in
  `SecurityConfig` beyond the bean existing.
* **The role travels across the wire.** `Session` (gateway) gains a
  `Set<String> roles` field, populated from the WebSocket handshake's
  `Authentication`. `contracts.TradeCommand` gains a matching `Set<String>
  roles` field, populated from `Session.roles()` wherever gateway's
  `TradeService` builds a command. This is a defense-in-depth choice over a
  gateway-only check: trading-service is the actual authority on whether a
  trade may be created, and gateway has no independent way to enforce that
  without duplicating trading-domain logic.
* **`trading-service.TradeService.validate()` gains a first check**: `if
  (!roles.contains("trader")) return Optional.of("role does not permit
  trading")`, ahead of the existing quantity/symbol checks. A role
  rejection reuses the exact existing broadcast `TRADE_REJECTED` path
  (`reject()`, the `tradeRejected-out-0` binding, the same `TradeRejected`
  contract shape) - no new message type, just a new `reason` string.

## Consequences

* `Session`'s and `TradeCommand`'s constructors both changed shape (a new
  required field, not an optional/defaulted one) - every existing call site
  in both production and test code needed updating in the same change, per
  this project's preference for direct changes over backwards-compatibility
  shims.
* `CONFIRM_TRADE`/`CANCEL_TRADE` are not separately role-checked: a
  `viewer` session can never hold a pending trade id in the first place
  (its `CREATE_TRADE` is always rejected before a `PendingTrade` exists), so
  there is nothing for it to confirm or cancel. Revisit this only if a
  future change lets a session reference another session's pending trade.
* A `viewer`'s rejected `CREATE_TRADE` is still broadcast to every
  connected session via `TRADE_REJECTED`, exactly like any other invalid
  request - `docs/protocol.md` already flags this broadcast-vs-targeted
  question as open for a later issue; this ADR doesn't resolve it, just
  keeps role rejections consistent with today's other rejections.
* Frontend enforcement (hiding/disabling buy/sell for a `viewer`) is
  explicitly out of scope here - the fix is the authorization boundary, not
  the UX. A `viewer` can still click Buy/Sell and will see a
  `TRADE_REJECTED` come back; a follow-up UI-only issue can hide the
  controls entirely if that experience turns out to matter.
