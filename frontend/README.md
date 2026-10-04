# PlatformUp2Date Frontend

React + Material-UI frontend, built and served with [Vite](https://vite.dev/).
Tests run on [Vitest](https://vitest.dev/).

## Available Scripts

In the project directory, you can run:

### `yarn start` (alias: `yarn dev`)

Runs the app in development mode with hot module reloading.\
Open [http://localhost:3000](http://localhost:3000) to view it in your browser.

### `yarn test`

Runs the test suite once (`vitest run`). Use `yarn test:watch` for watch mode.

### `yarn build`

Builds the app for production to the `build` folder. The output is minified and
asset filenames include content hashes.

### `yarn preview`

Serves the production build locally from `build/` on port 3000 for a final check
before deployment.

## Build identity

`GET /version.json` reports the frontend artifact's embedded identity. Builds
accept `BUILD_VERSION` as strict SemVer (including prerelease/build metadata),
`dev-<hexcommit>` for edge builds, or `dev` for local builds. Omitted identity
defaults to `dev`; explicitly blank or invalid values fail the build.

Publishing builds set `BUILD_VERSION_REQUIRED=true`, which rejects missing
identity and explicit `dev`. Only the literal `true` enables this build-only
check. The Docker build arguments default to `BUILD_VERSION=dev` and
`BUILD_VERSION_REQUIRED=false` for local builds:

```bash
docker build --build-arg BUILD_VERSION=1.2.3 \
  --build-arg BUILD_VERSION_REQUIRED=true -t p2d-frontend .
```

Development always serves `dev`. Preview and nginx serve the embedded file;
runtime environment variables cannot override it. Build identity is separate
from the runtime UI settings below.

Contract checks (from `frontend/`):

```bash
yarn test ci/build-version.test.js
bash ci/bin/test-build-version-image.sh # requires Docker, curl, and Node
```

The image check builds local and required-injection release/edge variants,
rejects missing/dev/invalid required injection, and checks both shipped nginx
configurations without publishing images.

## Runtime configuration

Runtime settings come from `public/env-config.js`, which sets `window._env_`.
In containers, `docker-entrypoint.d/40-env-config.sh` regenerates that file from
these environment variables before nginx starts:

| Variable | Default | Purpose |
|---|---|---|
| `API_BASE_URL` | empty (same origin) | Base URL for backend requests. Local development uses `http://localhost:8080`. |
| `OIDC_AUTHORITY` | empty | OIDC issuer URL for web login. |
| `OIDC_CLIENT_ID` | empty | Public client ID registered for the SPA. |
| `OIDC_SCOPE` | `openid profile` | Space-separated scopes sent with the authorization request. |

Web login is enabled only when both `OIDC_AUTHORITY` and `OIDC_CLIENT_ID` are
non-blank. The settings are injected at container startup, so changing them does
not require rebuilding the image. See
[`docs/configuration.md`](../docs/configuration.md#frontend-runtime-configuration)
for the full contract.
