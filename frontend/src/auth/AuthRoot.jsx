// Issue 03 (SPA becomes an OIDC client) — composition root gating.
//
// When web auth is disabled, renders <App/> directly: no <AuthProvider>, no useAuth() call, no
// redirect — exactly today's behavior. When enabled, wraps <App/> in react-oidc-context's
// <AuthProvider> (configured from the same in-memory UserManager settings as src/auth/
// userManager.js, so the interceptor and the provider share one token source) and, inside it,
// redirects an unauthenticated visitor to the IdP and renders the board once authenticated.

import { AuthProvider, useAuth } from 'react-oidc-context'
import { useEffect } from 'react'
import App from '../App'
import { isWebAuthEnabled, userManager } from './userManager'

const onSigninCallback = () => {
  window.history.replaceState({}, document.title, window.location.pathname)
}

const AuthGate = () => {
  const { isAuthenticated, isLoading, signinRedirect } = useAuth()

  useEffect(() => {
    if (!isLoading && !isAuthenticated) {
      signinRedirect()
    }
  }, [isLoading, isAuthenticated, signinRedirect])

  if (isAuthenticated) {
    return <App />
  }

  return null
}

const AuthRoot = () => {
  if (!isWebAuthEnabled()) {
    return <App />
  }

  return (
    <AuthProvider userManager={userManager} onSigninCallback={onSigninCallback}>
      <AuthGate />
    </AuthProvider>
  )
}

export default AuthRoot
