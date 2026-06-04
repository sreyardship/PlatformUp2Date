#!/usr/bin/env sh
# Regenerate the runtime config from environment variables before nginx starts.
# The nginx:alpine image automatically runs scripts in /docker-entrypoint.d/.
set -eu

cat > /usr/share/nginx/html/env-config.js <<EOF
window._env_ = {
  API_BASE_URL: "${API_BASE_URL:-}",
};
EOF
