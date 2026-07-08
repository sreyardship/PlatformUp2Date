// Runtime configuration. In containers this file is regenerated at startup from
// environment variables by /docker-entrypoint.d/40-env-config.sh. The value below is
// only the local-development default (yarn start serves public/ as-is).
//
// OIDC_AUTHORITY/OIDC_CLIENT_ID are left empty here on purpose — web auth (issue 03) is
// presence-switched, so an empty/absent value keeps local dev running with no login,
// unchanged from today's behavior.
window._env_ = {
  API_BASE_URL: "http://localhost:8080",
  OIDC_AUTHORITY: "",
  OIDC_CLIENT_ID: "",
  OIDC_SCOPE: "",
};
