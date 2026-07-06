# Contributing to PlatformUp2Date

Thanks for taking a look. This document covers the things a drive-by
contributor can't guess from the code alone: how to build and test each
half of the stack, what the merge gate expects, and the conventions the
codebase follows.

## Backend (Quarkus + Gradle, Java 21)

The backend is a Quarkus 3.33.2 application on Java 21. There is no Gradle
wrapper checked in — install Gradle yourself (CI uses **8.14.4**; match that
if you want your local runs to behave the same as the pipeline).

```bash
cd backend
gradle quarkusDev   # dev mode with live reload, localhost:8080
gradle test         # unit + JVM integration tests
gradle build        # build the JAR
```

`gradle test` includes integration tests (e.g. `ValkeyScrapeStateStoreIT`)
that lean on Quarkus Dev Services — they need Docker on your machine to spin
up a throwaway Valkey container automatically; no manual service setup
required.

### Native build

The shipped image is a GraalVM native binary, not the JVM jar. To build and
exercise it locally you need a GraalVM install — the easiest path is the Nix
flake in `project-environment/`, which pins GraalVM CE 25 (JDK 25) and
exports `GRAALVM_HOME` for you:

```bash
nix develop ./project-environment
cd backend
gradle build quarkusIntTest \
  -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false \
  -x test
```

If you'd rather not use Nix, install GraalVM CE 25 directly and set
`GRAALVM_HOME` yourself; the command above is otherwise unchanged. See
`.github/workflows/pr.yml` for the exact toolchain versions and flags CI
uses — keep local native builds consistent with it if something doesn't
match.

## Frontend (Vite + Vitest)

The frontend is a Vite app tested with Vitest — not Create React App or
Jest.

```bash
cd frontend
yarn install
yarn dev     # dev server, localhost:3000
yarn test    # Vitest test run
yarn build   # production build
```

## The merge gate

Every pull request must pass **both** required checks before it can merge:

- **`fast`** — backend unit/integration tests (`gradle test`) plus the
  frontend test suite. A few minutes.
- **`native`** — a full GraalVM native build plus the native integration
  test suite run against the built binary. This is the slow one: expect
  roughly **20–30 minutes**. It exists because native-image behavior can
  diverge from JVM-mode behavior in ways the fast job cannot see (see
  `docs/adr/0025-ssh-os-release-native-image-reachability.md` for a
  concrete example), so it is not optional or skippable — plan your PR
  timeline around it.

Both jobs are defined in `.github/workflows/pr.yml` if you want to see
exactly what runs.

## Conventions

- **Commit messages** follow [Conventional Commits](https://www.conventionalcommits.org/)
  (`feat:`, `fix:`, `docs:`, `refactor:`, etc.).
- **`CONTEXT.md`** is the project's canonical vocabulary — terms like
  Application, current/latest, drift, version scheme, and fleet clock are
  defined there precisely. Use those terms consistently in code, commit
  messages, and PR descriptions rather than inventing synonyms.
- **Architecture decision records** live in `docs/adr/`. A change that
  settles a non-obvious design question — a new version-source type, a
  change to how state is stored or reconciled, a tradeoff that later
  contributors would otherwise have to rediscover from the diff — should
  come with a short ADR explaining the decision and its alternatives.
  Small, self-explanatory changes don't need one; skim a few existing ADRs
  under `docs/adr/` to get a feel for the right level of detail.
- **Hexagonal architecture**: the backend is structured as ports & adapters
  (`core/ports/in`, `core/ports/out`, `core/services`, `core/domain`,
  `adapters/in`, `adapters/out`). `core/` must never import from
  `adapters/` — dependencies point inward. New version sources, repository
  implementations, or controllers belong under `adapters/`, driven through
  a port interface defined in `core/`.

## Getting started

Read the [README](README.md) for the quick start and an overview of what
the project does, and [`ARCHITECTURE.md`](ARCHITECTURE.md) for the design
principles behind the version-source model. Then build from source (above),
make your change, and open a pull request.
