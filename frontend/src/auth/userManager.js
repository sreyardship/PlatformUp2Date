// Issue 03 (SPA becomes an OIDC client) — SEAM CONTRACT, implemented per the tester's RED-phase
// stub (see the header comment this file used to carry, still true of the shape below).
//
// This is the non-React bridge between the axios request interceptor (a module-level singleton,
// src/api/axiosClient.js) and the React OIDC context (react-oidc-context's <AuthProvider>, wired
// at the composition root, src/auth/AuthRoot.jsx / src/index.jsx). Both sides share this SAME
// oidc-client-ts UserManager instance/settings so the access token the AuthProvider obtains via
// the redirect/silent-renew flow is the exact token the interceptor attaches to /api/v1 requests.

import { UserManager, WebStorageStateStore, InMemoryWebStorage } from 'oidc-client-ts'

const readEnv = () => (typeof window !== 'undefined' && window._env_) || {}

const nonBlank = (value) => typeof value === 'string' && value.trim().length > 0

export const isWebAuthEnabled = () => {
  const env = readEnv()
  return nonBlank(env.OIDC_AUTHORITY) && nonBlank(env.OIDC_CLIENT_ID)
}

// Shared settings builder — used both by this module's unconditionally-constructed UserManager
// and by AuthRoot.jsx's <AuthProvider>, so both sides talk to the same in-memory user store and
// the same client/authority/scope configuration. Safe to build even when auth is disabled
// (authority/client_id fall back to empty strings) — no network/discovery call happens until
// something actually calls signinRedirect/signinSilent, and disabled mode never does.
export const buildUserManagerSettings = () => {
  const env = readEnv()

  return {
    authority: env.OIDC_AUTHORITY || '',
    client_id: env.OIDC_CLIENT_ID || '',
    redirect_uri: typeof window !== 'undefined' ? `${window.location.origin}/` : '',
    response_type: 'code',
    scope: env.OIDC_SCOPE || 'openid profile',
    automaticSilentRenew: true,
    // Access token (and the rest of the signed-in User) held IN MEMORY ONLY — never
    // localStorage/sessionStorage. The transient PKCE stateStore stays on oidc-client-ts's
    // default (its default persistent store) so code_verifier/state survive the full-page redirect.
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  }
}

export const userManager = new UserManager(buildUserManagerSettings())

export const getAccessToken = async () => {
  try {
    const user = await userManager.getUser()
    return user?.access_token ?? null
  } catch {
    return null
  }
}
