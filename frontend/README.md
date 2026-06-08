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

## Runtime configuration

The API base URL is provided at runtime via `public/env-config.js`, which sets
`window._env_`. In containers this file is regenerated from environment variables
by `docker-entrypoint.d/40-env-config.sh` before nginx starts, so the same image
can target different backends without rebuilding.
