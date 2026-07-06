# Contributing to PlatformUp2Date

Thanks for taking a look. This document covers the things a drive-by
contributor can't guess from the code alone: how to get a working
toolchain, how to build and test each half of the stack, what the merge
gate expects, and the conventions the codebase follows.

## Dev environment

The recommended way to get a toolchain is the Nix flake in
`project-environment/`:

```bash
nix develop ./project-environment
```

If you use [direnv](https://direnv.net/), a `.envrc` is checked in that
loads the same flake — run `direnv allow` once and the shell activates
automatically whenever you enter the repo.

One command gets you everything the project needs, pinned to the same
versions CI uses: Gradle 8.14.4 on Java 21, the Quarkus CLI, GraalVM CE
(with `GRAALVM_HOME` exported) for native builds, `yarn` and Node.js for
the frontend, and a native `valkey` binary so integration tests can run
without Docker.

If you'd rather not use Nix, install the toolchain yourself: Gradle
**8.14.4** on Java 21, yarn + Node.js, and — only if you're doing native
builds — GraalVM CE with `GRAALVM_HOME` set. Match the CI versions in
`.github/workflows/pr.yml` if you want your local runs to behave the same
as the pipeline. Note there is no Gradle wrapper checked in.

## Backend (Quarkus + Gradle, Java 21)

The backend is a Quarkus 3.33.2 application on Java 21.

```bash
cd backend
gradle quarkusDev   # dev mode with live reload, localhost:8080
gradle test         # unit + JVM integration tests
gradle build        # build the JAR
```

`gradle test` includes integration tests (e.g. `ValkeyScrapeStateStoreIT`)
that lean on Quarkus Dev Services to provide a throwaway Valkey instance.
With Docker available, Dev Services spins up a container automatically;
inside the Nix dev shell you also have a native `valkey` binary, so the
tests can run against a locally started Valkey without Docker (this is how
CI runs them).

### Native build

The shipped image is a GraalVM native binary, not the JVM jar. Inside the
dev shell, GraalVM and `GRAALVM_HOME` are already set up:

```bash
cd backend
gradle build quarkusIntTest \
  -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false \
  -x test
```

Outside the dev shell, install GraalVM CE and set `GRAALVM_HOME` yourself;
the command above is otherwise unchanged. See `.github/workflows/pr.yml`
for the exact toolchain versions and flags CI uses — keep local native
builds consistent with it if something doesn't match.

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

Note: CONTEXT.md and ADRs are mostly here so AI-agents can easily generate
pretty decent code right of the bat. The fact that we humans also get 
these things documented are a bonus.

## Getting started

Read the [README](README.md) for the quick start and an overview of what
the project does, and [`ARCHITECTURE.md`](ARCHITECTURE.md) for the design
principles behind the version-source model. Then enter the dev shell,
build from source (above), make your change, and open a pull request.

## AI usage
AI is fine, AI slop is not. 

It just comes down to the fact that you need to understand the code the AI generates. I judge it as any other code, but if the PR is too large it might take a while before I get around to it. 

Lets pray this project stays small enough that PRs can stay open. (Most likely it will, so I'm most likely writing to myself here)

## Testing
All features should be tested. 

- Unit tests
- Integration tests
- No need for 100% code coverage
- No testing private methods by making them public (I rather not even have the test then in the end, okay to use while you develop).

If you're worried about where you should put the tests, reference the testing pyramid... and the code.

If I'm having a good day I might even engage in a philosophical testing discussion.
