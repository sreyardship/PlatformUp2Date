#!/usr/bin/env sh
# Regenerate the runtime config from environment variables before nginx starts.
# The nginx image automatically runs scripts in /docker-entrypoint.d/.
#
# In the image, this path is a link to /tmp/env-config.js (see the Dockerfile).
# Thus, the write also works on a read-only root filesystem with a writable
# /tmp.
set -eu

cat > /usr/share/nginx/html/env-config.js <<EOF
window._env_ = {
  API_BASE_URL: "${API_BASE_URL:-}",
  OIDC_AUTHORITY: "${OIDC_AUTHORITY:-}",
  OIDC_CLIENT_ID: "${OIDC_CLIENT_ID:-}",
  OIDC_SCOPE: "${OIDC_SCOPE:-}",
};
EOF
