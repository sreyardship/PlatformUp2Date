# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PlatformUp2Date is a version monitoring application that tracks deployed platform applications against their latest upstream releases. It shows whether each app is up-to-date (green) or outdated (red).

## Build & Run Commands

### Full stack (Docker Compose)
```bash
# Build backend first (required before compose)
gradle :backend:build
docker compose up -d
# Frontend: localhost:3000, Backend: localhost:8080
```

### Backend (Quarkus + Gradle)
The Gradle root is the repo root (multi-module build: `:domain` + `:backend`); run these from the repo root, not `backend/`.
```bash
gradle :backend:quarkusDev          # Dev mode with live reload (localhost:8080)
gradle :backend:build                # Build JAR
gradle :backend:test                 # Run all backend tests
gradle :backend:test --tests '*VersionTests'  # Run a single test class
gradle :domain:test                  # Run :domain's own unit tests
```

### Frontend (Vite + Yarn)
```bash
cd frontend
yarn install
yarn dev      # Dev server on localhost:3000
yarn test     # Run tests (Vitest)
yarn build    # Production build
```

## Architecture

### Code-First API
The API is code-first: the JAX-RS controllers (e.g., `VersionController`) are the source of truth. The `quarkus-smallrye-openapi` extension generates the OpenAPI spec from them at runtime, served at `/q/openapi` (with Swagger UI available in dev mode). See `docs/adr/0020-api-is-code-first.md` for the rationale.

### Backend — Hexagonal Architecture
The backend is a Quarkus 3.33.2 (Java 21) application structured as ports & adapters:

- **`core/ports/in/`** — Use case interfaces (e.g., `ApplicationVersionPort`)
- **`core/ports/out/`** — Repository interfaces (e.g., `VersionRepository`)
- **`core/services/`** — Business logic (`ApplicationVersionService`)
- **`core/domain/`** — Domain primitives (`Version` wraps semver validation, `VersionApplication`)
- **`adapters/in/`** — REST controller (`VersionController` at `/api/v1/version`)
- **`adapters/out/`** — Repository implementation using Quarkus REST clients to fetch current versions from deployed apps and latest versions from GitHub Releases API

Application monitoring targets are configured in `backend/src/main/resources/application.yml`.

### Frontend — React
Vite + React with Material-UI. Component flow: `App` (data fetching) → `Display` (container) → `VersionList` → `Version` (card with color-coded status). API calls go through a centralized Axios client in `src/api/` configured via the runtime `window._env_.API_BASE_URL` value.

## Dev Environment

A Nix flake in `project-environment/` provides a reproducible dev shell with `quarkus`, `gradle`, and GraalVM CE pre-installed.
