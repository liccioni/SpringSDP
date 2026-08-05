# 0005. Use Jib instead of a hand-written Dockerfile for the backend image

Date: 2026-08-05

Status: Accepted

## Context

ADR 0002 originally called for a hand-written multi-stage `backend/Dockerfile`. Before that was implemented, we evaluated a few Gradle plugins for the backend build, including `com.google.cloud.tools.jib`. Jib builds container images directly from a Gradle/Maven build, with no Dockerfile and no local Docker daemon required to build (it can push straight to a registry, or load into a local daemon for `jibDockerBuild`). It's actively maintained (v3.5.4, July 2026).

The natural alternative is Spring Boot's own built-in `bootBuildImage` task (Cloud Native Buildpacks), which needs no extra plugin at all — but it requires a local Docker daemon to run the buildpack build. Since Jib needs no Dockerfile to write or maintain and produces smaller, layer-cached, more reproducible images by design, it's a better fit here even though the "no Docker daemon needed" advantage matters less for us (Docker is already required locally for Docker Compose and, from MVP 0.4, Testcontainers).

## Decision

The backend uses the `com.google.cloud.tools.jib` Gradle plugin instead of a hand-written Dockerfile. Configuration lives directly in `backend/build.gradle`:

* Base image pinned by digest (`eclipse-temurin@sha256:...`, currently the `21-jre` image) rather than a floating tag, for reproducible builds — Jib warns explicitly if the base image isn't pinned.
* Container port `8080`, matching the WebSocket endpoint convention (ADR 0003).
* `./gradlew jibDockerBuild` loads the image into the local Docker daemon for local use (e.g. Docker Compose); `./gradlew jib` would push directly to a registry once one is configured — not needed yet.

This does not change ADR 0002's Docker Compose or frontend-Dockerfile decisions — the frontend still gets a hand-written Dockerfile, since Jib is Java-specific.

We also adopted `com.adarshr.test-logger` (v4.0.0) alongside this for readable console test output — a low-risk, purely presentational addition with no interaction with the existing JaCoCo/CI setup.

We evaluated and deferred two other options: `com.github.jmongard.git-semver-plugin` (no release/tagging process exists yet to justify automatic semantic versioning) and adding the `jitpack.io` repository (no dependency currently requires it, and it's a weaker reproducibility model than Maven Central).

## Consequences

* No `backend/Dockerfile` to write or maintain; the image build is declarative Gradle config instead.
* The base image digest pin needs a manual bump when we want a newer `eclipse-temurin:21-jre` patch — there's no automation for this yet.
* `docker-compose.yml` (a separate MVP 0.1 issue) will reference the `jibDockerBuild`-produced `sdp-backend` image rather than a `docker build` of a Dockerfile.
* Revisit `git-semver-plugin`/`axion-release-plugin` once there's an actual release or image-tagging process; revisit `jitpack.io` if a dependency ever requires it.
