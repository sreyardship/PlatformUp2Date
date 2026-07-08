#!/usr/bin/env sh
# Regenerate the runtime config from environment variables before nginx starts.
# The nginx:alpine image automatically runs scripts in /docker-entrypoint.d/.
set -eu

cat > /usr/share/nginx/html/env-config.js <<EOF
window._env_ = {
  API_BASE_URL: "${API_BASE_URL:-}",
  OIDC_AUTHORITY: "${OIDC_AUTHORITY:-}",
  OIDC_CLIENT_ID: "${OIDC_CLIENT_ID:-}",
  OIDC_SCOPE: "${OIDC_SCOPE:-}",
};
EOF
