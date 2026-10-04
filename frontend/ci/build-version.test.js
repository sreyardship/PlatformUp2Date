// @vitest-environment node
import { spawnSync } from 'node:child_process'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer, preview } from 'vite'
import { afterAll, beforeAll, expect, test } from 'vitest'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
let outDir

beforeAll(async () => {
  outDir = await mkdtemp(join(tmpdir(), 'frontend-build-version-'))
})
afterAll(async () => {
  await rm(outDir, { recursive: true, force: true })
})

function build(version, required) {
  const env = { ...process.env }
  delete env.BUILD_VERSION
  delete env.BUILD_VERSION_REQUIRED
  if (version !== undefined) env.BUILD_VERSION = version
  if (required !== undefined) env.BUILD_VERSION_REQUIRED = required
  return spawnSync(process.execPath, ['node_modules/vite/bin/vite.js', 'build', '--outDir', outDir], {
    cwd: root, env, encoding: 'utf8', timeout: 60_000,
  })
}

async function servedVersion() {
  const previous = process.env.BUILD_VERSION
  const previousRequired = process.env.BUILD_VERSION_REQUIRED
  process.env.BUILD_VERSION = 'invalid-runtime-override'
  process.env.BUILD_VERSION_REQUIRED = 'true'
  let server
  try {
    server = await preview({ root, build: { outDir }, preview: { host: '127.0.0.1', port: 0 } })
  } finally {
    if (previous === undefined) delete process.env.BUILD_VERSION
    else process.env.BUILD_VERSION = previous
    if (previousRequired === undefined) delete process.env.BUILD_VERSION_REQUIRED
    else process.env.BUILD_VERSION_REQUIRED = previousRequired
  }
  try {
    const { port } = server.httpServer.address()
    const response = await fetch(`http://127.0.0.1:${port}/version.json`)
    expect(response.status).toBe(200)
    expect(response.headers.get('content-type')).toMatch(/^application\/json\b/)
    expect(response.headers.get('cache-control')).toBe('no-store')
    return await response.json()
  } finally {
    await new Promise((resolve, reject) => server.httpServer.close(error => error ? reject(error) : resolve()))
  }
}

test('required build HTTP endpoint reports the injected release identity', async () => {
  const result = build('1.2.3', 'true')
  expect(result.status, result.stderr).toBe(0)
  expect(await servedVersion()).toEqual({ version: '1.2.3' })
}, 60_000)

test('rebuilding the same output serves RC, metadata, edge and local identities without stale output or runtime overrides', async () => {
  for (const [input, expected] of [
    ['1.2.3-rc.6+build.007', '1.2.3-rc.6+build.007'],
    ['dev-abcdef123456', 'dev-abcdef123456'],
    ['0.0.0-alpha.0+001', '0.0.0-alpha.0+001'],
    ['dev-ABCDEF', 'dev-ABCDEF'],
    ['dev', 'dev'],
    [undefined, 'dev'],
  ]) {
    const result = build(input, expected === 'dev' ? undefined : 'true')
    expect(result.status, result.stderr).toBe(0)
    expect(await servedVersion()).toEqual({ version: expected })
  }
}, 60_000)

test.each([undefined, 'dev', '1.2.3', '', 'invalid'])('dev HTTP endpoint ignores required injection despite BUILD_VERSION=%j', async version => {
  const previous = process.env.BUILD_VERSION
  const previousRequired = process.env.BUILD_VERSION_REQUIRED
  process.env.BUILD_VERSION_REQUIRED = 'true'
  if (version === undefined) delete process.env.BUILD_VERSION
  else process.env.BUILD_VERSION = version
  let server
  try {
    server = await createServer({ root, server: { host: '127.0.0.1', port: 0 } })
    await server.listen()
    const { port } = server.httpServer.address()
    const response = await fetch(`http://127.0.0.1:${port}/version.json?fresh=1`)
    expect(response.status).toBe(200)
    expect(response.headers.get('content-type')).toMatch(/^application\/json\b/)
    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(await response.json()).toEqual({ version: 'dev' })
  } finally {
    await server?.close()
    if (previous === undefined) delete process.env.BUILD_VERSION
    else process.env.BUILD_VERSION = previous
    if (previousRequired === undefined) delete process.env.BUILD_VERSION_REQUIRED
    else process.env.BUILD_VERSION_REQUIRED = previousRequired
  }
})

test.each([undefined, 'dev'])('required build rejects missing or local identity %j', version => {
  const result = build(version, 'true')
  expect(result.status).not.toBe(0)
  expect(result.stderr).toContain('BUILD_VERSION is required')
}, 60_000)

test.each(['', 'invalid'])('required build rejects invalid identity %j', version => {
  const result = build(version, 'true')
  expect(result.status).not.toBe(0)
  expect(result.stderr).toContain('Invalid BUILD_VERSION')
}, 60_000)

test.each(['false', 'TRUE', '1'])('only literal true requires injection, not %j', async required => {
  const result = build(undefined, required)
  expect(result.status, result.stderr).toBe(0)
  expect(await servedVersion()).toEqual({ version: 'dev' })
}, 60_000)

test.each([
  '', ' ', '1.2', 'v1.2.3', '01.2.3', '1.02.3', '1.2.03',
  '1.2.3-rc.01', '1.2.3-', '1.2.3+', '1.2.3+build..7',
  '1.2.3\n', ' 1.2.3', '1.2.3 ', 'dev-', 'dev-nothex', '1.0.0-SNAPSHOT!',
])('build rejects explicit invalid identity %j', version => {
  const result = build(version)
  expect(result.status).not.toBe(0)
  expect(result.stderr).toContain('Invalid BUILD_VERSION')
}, 60_000)
