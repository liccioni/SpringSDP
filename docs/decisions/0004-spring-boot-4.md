# 0004. Target Spring Boot 4.x instead of 3.x

Date: 2026-08-05

Status: Accepted

## Context

CLAUDE.md originally pinned "Spring Boot 3.x". By the time backend scaffolding started (issue #1), the entire 3.x line had reached open-source end-of-life (last release 3.5.16, EOL 2026-06-30) with no further security patches. Spring Boot 4.1.0 (released 2026-06-10) is the current supported release, with security patches through 2027-07-31, and its minimum Java baseline is still Java 21 — no conflict with the existing tech stack.

The main risk of moving to 4.x was Spring Boot's new default: if `spring-boot-starter-security` is on the classpath, all endpoints are secured with HTTP Basic auth out of the box. This mattered because MVP 0.1 explicitly requires "no authentication".

## Decision

Target Spring Boot 4.1.0 for the backend, not 3.x. The default-security behavior is a non-issue here because it only activates when `spring-boot-starter-security` is a dependency — this project doesn't add that starter for MVP 0.1, so no endpoints are secured by default and no extra permit-all configuration is needed.

Toolchain pinned alongside this decision:
* Gradle 9.6.1 (via the wrapper) — Spring Boot 4.1's Gradle plugin requires Gradle 8.14+ or 9.x.
* `org.springframework.boot` Gradle plugin 4.1.0, paired with `io.spring.dependency-management` 1.1.7.

## Consequences

* The backend starts on a currently-supported, patched Spring Boot line instead of an already end-of-life one.
* Spring Framework 7 (which 4.x brings in) has some WebFlux-facing revisions relative to Framework 6 — worth double-checking against the reference docs if a WebFlux API used later in the roadmap doesn't behave as expected.
* Adding `spring-boot-starter-security` in a future milestone (e.g. MVP 0.5's authentication work) will immediately secure all endpoints by default; that milestone's implementation needs to account for this rather than being caught by surprise.
* This supersedes the Spring Boot 3.x reference in the original Technology stack list.
