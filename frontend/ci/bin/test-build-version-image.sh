#!/usr/bin/env bash
# Shipped-image contract; run from any directory. Requires Docker, curl and Node.
set -euo pipefail

FRONTEND_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RUN_ID="p2d-version-$$-$(date +%s)"
NETWORK="$RUN_ID"
TMP_DIR="$(mktemp -d)"
containers=()
images=()

cleanup() {
  local status=$?
  if (( status != 0 )); then
    for container in "${containers[@]}"; do docker logs "$container" >&2 || true; done
  fi
  for container in "${containers[@]}"; do docker rm -f "$container" >/dev/null 2>&1 || true; done
  for image in "${images[@]}"; do docker image rm "$image" >/dev/null 2>&1 || true; done
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

docker network create "$NETWORK" >/dev/null

request() {
  curl --silent --show-error --max-time 40 -D "$TMP_DIR/headers" -o "$TMP_DIR/body" -w '%{http_code}' "$1"
}

assert_version() {
  local url=$1 expected=$2 status
  status="$(request "$url/version.json?fresh=1")"
  [[ "$status" == 200 ]] || { echo "Expected 200, got $status" >&2; return 1; }
  grep -Eiq '^Content-Type: application/json[[:space:]]*$' "$TMP_DIR/headers"
  grep -Eiq '^Cache-Control: no-store[[:space:]]*$' "$TMP_DIR/headers"
  node --input-type=module - "$TMP_DIR/body" "$expected" <<'JS'
import { readFileSync } from 'node:fs'
import assert from 'node:assert/strict'
assert.deepEqual(JSON.parse(readFileSync(process.argv[2], 'utf8')), { version: process.argv[3] })
JS
}

# Starts a frontend container and waits for nginx. Sets $container and $url.
start_frontend() {
  local port attempt
  container="$(docker run -d --network "$NETWORK" -p 127.0.0.1::8080 "$@")"
  containers+=("$container")
  port="$(docker inspect -f '{{(index (index .NetworkSettings.Ports "8080/tcp") 0).HostPort}}' "$container")"
  url="http://127.0.0.1:$port"
  for ((attempt = 0; attempt < 50; attempt++)); do
    if curl --silent --fail --max-time 1 "$url/" >/dev/null; then break; fi
    sleep 0.1
  done
}

stop_frontend() {
  docker rm -f "$container" >/dev/null
  containers=()
}

check_image() {
  local image=$1 version=$2 config container url status
  local -a config_args
  for config in nginx.conf nginx.quickstart.conf; do
    # Test the image's own default config, and the quickstart bind-mount variant.
    config_args=()
    if [[ "$config" == nginx.quickstart.conf ]]; then
      config_args=(-v "$FRONTEND_ROOT/$config:/etc/nginx/conf.d/default.conf:ro")
    fi
    # No backend is attached to this private network. Runtime variables must not
    # replace embedded identity, even with the existing UI runtime-config hook.
    start_frontend \
      -e BUILD_VERSION=9.9.9 -e BUILD_VERSION_REQUIRED=true -e API_BASE_URL=http://backend:8080 \
      "${config_args[@]}" "$image"
    assert_version "$url" "$version"
    [[ "$(request "$url/a-client-side-route")" == 200 ]]
    grep -qi '<html' "$TMP_DIR/body"
    if [[ "$config" == nginx.quickstart.conf ]]; then
      status="$(request "$url/api/v1/version")"
      [[ "$status" == 502 ]] || { echo "Expected unavailable backend (502), got $status" >&2; return 1; }
      assert_version "$url" "$version"
    fi
    # The image user cannot change the static files, so remove the file as root.
    docker exec -u 0 "$container" rm /usr/share/nginx/html/version.json
    [[ "$(request "$url/version.json")" == 404 ]]
    grep -Eiq '^Cache-Control: no-store[[:space:]]*$' "$TMP_DIR/headers"
    if grep -q '<div id="root"' "$TMP_DIR/body"; then
      echo 'Missing version file incorrectly served the SPA' >&2
      return 1
    fi
    stop_frontend
    echo "PASS: $config reports $version, ignores runtime override, and returns 404 when missing"
  done
}

# The limits of the frontend container in deploy/k8s/base: the image user is
# not root, the root filesystem is read-only, /tmp is the only writable path,
# and the container has no capabilities and no privilege escalation.
check_restricted_runtime() {
  local image=$1 user container url
  user="$(docker inspect -f '{{.Config.User}}' "$image")"
  # runAsNonRoot needs a numeric UID. The kubelet cannot verify a user name.
  [[ "$user" =~ ^[1-9][0-9]*$ ]] || { echo "Expected a numeric non-root image user, got '$user'" >&2; return 1; }
  start_frontend --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges \
    -e API_BASE_URL=https://api.example.test -e OIDC_CLIENT_ID=pu2d-spa "$image"
  [[ "$(request "$url/")" == 200 ]]
  grep -qi '<html' "$TMP_DIR/body"
  [[ "$(request "$url/env-config.js")" == 200 ]]
  grep -Fq 'API_BASE_URL: "https://api.example.test"' "$TMP_DIR/body"
  grep -Fq 'OIDC_CLIENT_ID: "pu2d-spa"' "$TMP_DIR/body"
  stop_frontend
  echo "PASS: runs as user $user on a read-only root filesystem and serves the runtime config"
}

# Reuse the same build context/cache while changing only build identity.
for version in default 1.2.3 1.2.3-rc.6+build.007 dev-abcdef123456; do
  image="$RUN_ID:${#images[@]}"
  images+=("$image")
  args=()
  expected="$version"
  if [[ "$version" == default ]]; then expected=dev
  else args+=(--build-arg "BUILD_VERSION=$version" --build-arg BUILD_VERSION_REQUIRED=true); fi
  docker build "${args[@]}" -t "$image" "$FRONTEND_ROOT"
  check_image "$image" "$expected"
done

# The images differ only in build identity, so one image is sufficient.
check_restricted_runtime "${images[0]}"

# Publishing builds must reject missing/default dev and invalid injection.
for version in missing dev '' invalid; do
  args=(--build-arg BUILD_VERSION_REQUIRED=true)
  error='Invalid BUILD_VERSION'
  if [[ "$version" == missing || "$version" == dev ]]; then error='BUILD_VERSION is required'; fi
  if [[ "$version" != missing ]]; then args+=(--build-arg "BUILD_VERSION=$version"); fi
  if docker build "${args[@]}" -t "$RUN_ID:invalid" "$FRONTEND_ROOT" >"$TMP_DIR/invalid-build.log" 2>&1; then
    images+=("$RUN_ID:invalid")
    echo "Required BUILD_VERSION '$version' unexpectedly built an image" >&2
    exit 1
  fi
  grep -q "$error" "$TMP_DIR/invalid-build.log"
  echo "PASS: required Docker build identity '$version' rejected"
done

# Explicitly blank must not behave like an omitted argument, even for local builds.
if docker build --build-arg BUILD_VERSION= -t "$RUN_ID:invalid" "$FRONTEND_ROOT" >"$TMP_DIR/invalid-build.log" 2>&1; then
  images+=("$RUN_ID:invalid")
  echo 'Blank BUILD_VERSION unexpectedly built an image' >&2
  exit 1
fi
grep -q 'Invalid BUILD_VERSION' "$TMP_DIR/invalid-build.log"
echo 'PASS: blank Docker build identity rejected'
