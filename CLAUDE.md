# Agent guide

## Context pointers

Load the reference for the branch being changed:

- **Domain:** read [`CONTEXT.md`](CONTEXT.md) when changing terminology or behavior shared by REST, MCP, metrics, and the UI. Preserve its canonical distinctions across code, tests, commits, and docs.
- **Version sources:** read [`ARCHITECTURE.md`](ARCHITECTURE.md) when changing current/latest discovery or scrape behavior.
- **Configuration:** read [`docs/configuration.md`](docs/configuration.md) when changing monitoring config, version schemes, changelog templates, or Surface authentication.
- **Deployment:** read [`docs/deployment.md`](docs/deployment.md) when changing Kubernetes manifests, Valkey, metrics, Grafana, or production authentication.
- **Workflow:** read [`CONTRIBUTING.md`](CONTRIBUTING.md) for build and test guidance; use the build files and [PR workflow](.github/workflows/pr.yml) as the executable source of truth.
- **Decisions:** search [`docs/adr/`](docs/adr/) before changing non-obvious behavior. Add an ADR when a new trade-off would otherwise have to be rediscovered from the diff.

## Invariants

- **Hexagon:** treat [`HexagonalArchitectureTests`](backend/src/test/java/org/yardship/unit/architecture/HexagonalArchitectureTests.java) as the exact dependency rule. Route cross-layer collaboration through `core/ports`; keep adapters isolated and the core independent of adapters.
- **Build roots:** run Gradle from the repository root against `:backend`; no Gradle wrapper is checked in. Run frontend scripts from `frontend/`.
- **Code-first API:** JAX-RS controllers and DTOs are the contract; SmallRye generates `/q/openapi`. REST shape changes update controller tests and the hand-written client under `frontend/src/api/`, not an authored OpenAPI file. See [ADR-0020](docs/adr/0020-api-is-code-first.md).
- **Configuration ownership:** production monitoring config is mounted at runtime. Local defaults live under `%dev` in `backend/src/main/resources/application.yml`; tests supply their own config. Keep `docs/configuration.md` and `deploy/k8s/base/platform-config.yaml` aligned with configuration code.
- **Completion:** cover changed behavior at the narrowest useful level and run every directly affected suite. Preserve native integration coverage for GraalVM reachability, packaged native resources, and shipped-image behavior. Verify every affected Surface and source-of-truth document agrees with the code.
