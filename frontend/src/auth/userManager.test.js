import { User } from 'oidc-client-ts'

import { isWebAuthEnabled, userManager, getAccessToken } from './userManager'

// ────────────────────────────────────────────────────────────────────────────
// isWebAuthEnabled() — presence-switched on window._env_.OIDC_AUTHORITY AND
// OIDC_CLIENT_ID, mirroring the API_BASE_URL runtime-config idiom.
// ────────────────────────────────────────────────────────────────────────────

describe('isWebAuthEnabled()', () => {
  const originalEnv = window._env_

  afterEach(() => {
    window._env_ = originalEnv
  })

  test('true when both OIDC_AUTHORITY and OIDC_CLIENT_ID are present', () => {
    window._env_ = {
      OIDC_AUTHORITY: 'https://idp.example.com/realms/p2d',
      OIDC_CLIENT_ID: 'p2d-web',
    }

    expect(isWebAuthEnabled()).toBe(true)
  })

  test('false when OIDC_AUTHORITY is absent', () => {
    window._env_ = { OIDC_CLIENT_ID: 'p2d-web' }

    expect(isWebAuthEnabled()).toBe(false)
  })

  test('false when OIDC_CLIENT_ID is absent', () => {
    window._env_ = { OIDC_AUTHORITY: 'https://idp.example.com/realms/p2d' }

    expect(isWebAuthEnabled()).toBe(false)
  })

  test('false when both are blank strings (not merely absent)', () => {
    window._env_ = { OIDC_AUTHORITY: '   ', OIDC_CLIENT_ID: '' }

    expect(isWebAuthEnabled()).toBe(false)
  })

  test('false when window._env_ itself is absent', () => {
    delete window._env_

    expect(isWebAuthEnabled()).toBe(false)
  })

  test('false (not a throw) when window._env_ has neither key at all', () => {
    window._env_ = { API_BASE_URL: 'http://localhost:8080' }

    expect(isWebAuthEnabled()).toBe(false)
  })
})

// ────────────────────────────────────────────────────────────────────────────
// Access-token storage — the AC "access token is never written to localStorage
// or sessionStorage". userManager must be configured with an in-memory
// userStore (oidc-client-ts InMemoryWebStorage), so storing a signed-in User
// never touches real browser storage.
//
// NOTE (flagged nuance): the transient PKCE stateStore (code_verifier/state,
// used only across the full-page redirect handshake) may legitimately still
// use sessionStorage — that is a different concern from the access token and
// is explicitly NOT what these assertions check. Every assertion below is
// scoped to "does the access token string appear anywhere in storage", not
// "is storage empty".
// ────────────────────────────────────────────────────────────────────────────

const buildFakeUser = (accessToken) =>
  new User({
    access_token: accessToken,
    token_type: 'Bearer',
    profile: { sub: 'test-user' },
    expires_at: Math.floor(Date.now() / 1000) + 3600,
  })

describe('access token is never persisted (in-memory userStore)', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  test('after a simulated sign-in, the access token never appears anywhere in localStorage', async () => {
    await userManager.storeUser(buildFakeUser('super-secret-access-token'))

    const allLocalStorageValues = Object.keys(localStorage)
      .map((key) => localStorage.getItem(key))
      .join('\n')

    expect(allLocalStorageValues).not.toContain('super-secret-access-token')
  })

  test('after a simulated sign-in, the access token never appears anywhere in sessionStorage', async () => {
    // sessionStorage may still legitimately hold transient PKCE state keys — we assert only
    // that the ACCESS TOKEN STRING is absent, not that sessionStorage is empty.
    await userManager.storeUser(buildFakeUser('another-secret-access-token'))

    const allSessionStorageValues = Object.keys(sessionStorage)
      .map((key) => sessionStorage.getItem(key))
      .join('\n')

    expect(allSessionStorageValues).not.toContain('another-secret-access-token')
  })

  test('the signed-in user still round-trips via getUser() within the session (in-memory store retains it)', async () => {
    await userManager.storeUser(buildFakeUser('roundtrip-token'))

    const loaded = await userManager.getUser()

    expect(loaded?.access_token).toBe('roundtrip-token')
  })
})

// ────────────────────────────────────────────────────────────────────────────
// getAccessToken() — the seam the axios interceptor calls. Must resolve the
// current in-memory access token, or null when nobody is signed in — never
// throw (a throw would break every /api/v1 request while unauthenticated).
// ────────────────────────────────────────────────────────────────────────────

describe('getAccessToken()', () => {
  beforeEach(async () => {
    localStorage.clear()
    sessionStorage.clear()
    if (userManager) {
      await userManager.removeUser()
    }
  })

  test('resolves the current access token when a user is signed in', async () => {
    await userManager.storeUser(buildFakeUser('live-token'))

    await expect(getAccessToken()).resolves.toBe('live-token')
  })

  test('resolves null (does not throw) when no user is signed in', async () => {
    await expect(getAccessToken()).resolves.toBeNull()
  })
})
