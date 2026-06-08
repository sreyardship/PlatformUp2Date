/// <reference types="vitest/config" />
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// CRA replacement. Notes:
// - dev server stays on port 3000 (compose / Dockerfile.dev expect it)
// - build output goes to build/ (the Dockerfile copies /app/build)
// - public/ is served at the root, so /env-config.js still provides the
//   runtime window._env_ config injected by the Docker entrypoint.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    host: true,
  },
  preview: {
    port: 3000,
    host: true,
  },
  build: {
    outDir: 'build',
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/setupTests.js',
    css: true,
    server: {
      deps: {
        // MUI v9 ships ESM (.mjs) that uses a directory import of
        // react-transition-group, which Node's native ESM resolver rejects.
        // Inlining lets Vite transform/resolve it the same way the app build does.
        inline: [/@mui\//, 'react-transition-group'],
      },
    },
  },
})
