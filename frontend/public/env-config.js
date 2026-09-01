// Runtime configuration. In containers this file is regenerated at startup from
// environment variables by /docker-entrypoint.d/40-env-config.sh. The value below is
// only the local-development default (yarn start serves public/ as-is).
//
// OIDC_AUTHORITY/OIDC_CLIENT_ID are empty because web auth is presence-switched;
// local development runs without login unless both values are configured.
window._env_ = {
  API_BASE_URL: "http://localhost:8080",
  OIDC_AUTHORITY: "",
  OIDC_CLIENT_ID: "",
  OIDC_SCOPE: "",
};
