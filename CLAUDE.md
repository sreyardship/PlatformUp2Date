# ci-bump
# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PlatformUp2Date is a version monitoring application that tracks deployed platform applications against their latest upstream releases. It shows whether each app is up-to-date (green) or outdated (red).

## Build & Run Commands

### Full stack (Docker Compose)
```bash
# Build backend first (required before compose)
cd backend && gradle build && cd ..
docker compose up -d
# Frontend: localhost:3000, Backend: localhost:8080
```

### Backend (Quarkus + Gradle)
```bash
cd backend
gradle quarkusDev          # Dev mode with live reload (localhost:8080)
gradle build                # Build JAR
gradle test                 # Run all tests
gradle test --tests '*VersionTests'  # Run a single test class
```

### Frontend (React + Yarn)
```bash
cd frontend
yarn install
yarn start    # Dev server on localhost:3000
yarn test     # Run tests (Jest)
yarn build    # Production build
```

### API Design (Apicurio Studio)
```bash
docker compose -f "compose.apicurio.yml" up -d
# Studio UI: localhost:8888 — schemas don't persist, download before teardown
```

## Architecture

### Contract-First API
The API is defined in an OpenAPI 3.0.2 spec at `backend/src/main/resources/openapi/platform-up-2-date.yaml`. Code is generated for both frontend and backend using OpenAPI Generator. The generated files are treated as immutable. The `quarkus-openapi-generator-server` Quarkus extension handles backend code generation. Apicurio Studio is used for visual schema editing.

### Backend — Hexagonal Architecture
The backend is a Quarkus 3.10.1 (Java 21) application structured as ports & adapters:

- **`core/ports/in/`** — Use case interfaces (e.g., `ApplicationVersionPort`)
- **`core/ports/out/`** — Repository interfaces (e.g., `VersionRepository`)
- **`core/services/`** — Business logic (`ApplicationVersionService`)
- **`core/domain/`** — Domain primitives (`Version` wraps semver validation, `VersionApplication`)
- **`adapters/in/`** — REST controller (`VersionController` at `/api/v1/version`)
- **`adapters/out/`** — Repository implementation using Quarkus REST clients to fetch current versions from deployed apps and latest versions from GitHub Releases API

Application monitoring targets are configured in `backend/src/main/resources/application.yml`.

### Frontend — React
Create React App with Material-UI. Component flow: `App` (data fetching) → `Display` (container) → `VersionList` → `Version` (card with color-coded status). API calls go through a centralized Axios client in `src/api/` configured via `REACT_APP_BASE_URL` env var.

## Dev Environment

A Nix flake in `project-environment/` provides a reproducible dev shell with `quarkus`, `gradle`, and GraalVM CE pre-installed.
