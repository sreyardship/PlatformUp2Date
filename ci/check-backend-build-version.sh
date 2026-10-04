#!/usr/bin/env bash
# Rebuild the packaged JVM app in the SAME output directory, without clean or
# --rerun-tasks, then observe its public endpoint. Labels/source text are not proof.
set -euo pipefail
cd "$(dirname "$0")/.."
root=$PWD
scratch=$(mktemp -d)
app_pid=''
cleanup() {
  if [[ -n $app_pid ]]; then kill "$app_pid" 2>/dev/null || true; wait "$app_pid" 2>/dev/null || true; fi
  rm -rf "$scratch"
}
trap cleanup EXIT

# A production configuration is required for boot, but no version source or
# Valkey needs to be reachable to serve build identity. No self-monitoring is
# added to the shipped defaults.
cat > "$scratch/platform-config.yaml" <<'YAML'
platform-config:
  scrape-interval: 1h
  apps:
    - name: unreachable-fixture
      current:
        type: http-json
        url: http://127.0.0.1:9/version
      latest:
        type: http-regex
        url: http://127.0.0.1:9/releases
        regex: '(\d+\.\d+\.\d+)'
YAML

build_dir='build/ci-version'
build=(gradle --no-daemon :backend:quarkusBuild "-PcustomBuildDir=$build_dir" -PbuildVersionRequired=true)
reject_build() {
  if "${build[@]}" "$@" >"$scratch/rejected.log" 2>&1; then
    printf 'required build identity unexpectedly accepted: %s\n' "$*" >&2; exit 1
  fi
  # Do not mistake an unrelated compiler/network failure for validation.
  if ! grep -Ei 'buildVersion|build version' "$scratch/rejected.log" >/dev/null; then
    cat "$scratch/rejected.log" >&2; exit 1
  fi
}
reject_build
reject_build -PbuildVersion=01.2.3

for version in "$(bash ci/resolve-build-version.sh release v1.2.3)" \
  "$(bash ci/resolve-build-version.sh release v1.2.3-rc.6+ci.1)" \
  "$(bash ci/resolve-build-version.sh edge)"; do
  "${build[@]}" "-PbuildVersion=$version"
  # Runtime attempts to replace the embedded identity must be ignored.
  BUILD_VERSION=9.9.9 QUARKUS_APPLICATION_VERSION=9.9.9 \
    java -Dquarkus.http.port=18080 -Dquarkus.redis.hosts=redis://127.0.0.1:9 \
      -Dquarkus.redis.devservices.enabled=false \
      -Dquarkus.config.locations="file:$scratch/platform-config.yaml" \
      -jar "$root/backend/$build_dir/quarkus-app/quarkus-run.jar" \
      >"$scratch/application.log" 2>&1 &
  app_pid=$!
  if ! python3 ci/check-version-response.py http://127.0.0.1:18080/api/version "$version"; then
    cat "$scratch/application.log" >&2; exit 1
  fi
  kill "$app_pid"
  wait "$app_pid" 2>/dev/null || true
  app_pid=''
done
printf 'backend build injection and regeneration: passed\n'
