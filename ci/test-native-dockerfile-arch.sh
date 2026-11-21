#!/usr/bin/env bash
# Contract of the shipped native Dockerfile's patcher stage: the target platform
# selects the ELF loader, and a devshell-built binary loses its /nix/store paths.
# Requires Docker (BuildKit) and readelf. No emulation and no native build is
# needed: only the patcher stage is built, and it runs on the build platform.
set -euo pipefail
cd "$(dirname "$0")/.."

dockerfile=backend/src/main/docker/Dockerfile.native-ci
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT

# Fixture: a dynamically linked ELF that looks like a Nix devshell build, with
# its interpreter and RUNPATH in /nix/store. patchelf edits ELF headers only, so
# the architecture of the fixture does not have to match the target.
context="$scratch/context"
mkdir -p "$context"
docker build --quiet --output "type=local,dest=$context" - >/dev/null <<'DOCKERFILE'
FROM alpine:3 AS fixture
RUN apk add --no-cache patchelf
RUN cp /bin/busybox /fixture-runner \
    && patchelf \
        --set-interpreter /nix/store/fixture-glibc/lib/ld-linux.so \
        --set-rpath /nix/store/fixture-glibc/lib \
        /fixture-runner
FROM scratch
COPY --from=fixture /fixture-runner /build-native/fixture-runner
DOCKERFILE

interpreter() {
  readelf --program-headers --wide "$1" | sed -n 's/.*Requesting program interpreter: \(.*\)\]$/\1/p'
}
search_paths() {
  readelf --dynamic --wide "$1" | grep -E '\((RPATH|RUNPATH)\)' || true
}

[[ "$(interpreter "$context/build-native/fixture-runner")" == /nix/store/* ]] \
  || { echo 'fixture has no /nix/store interpreter' >&2; exit 1; }
[[ -n "$(search_paths "$context/build-native/fixture-runner")" ]] \
  || { echo 'fixture has no RUNPATH' >&2; exit 1; }

patch_for() {
  local platform=$1 out=$2
  docker build --quiet --file "$dockerfile" --target patcher --platform "$platform" \
    --output "type=local,dest=$out" "$context" >/dev/null
}

assert_patched() {
  local platform=$1 expected=$2 out actual
  out="$scratch/out-${platform//\//-}"
  patch_for "$platform" "$out"
  actual="$(interpreter "$out/work/application")"
  [[ "$actual" == "$expected" ]] \
    || { echo "$platform: expected loader $expected, got $actual" >&2; exit 1; }
  [[ -z "$(search_paths "$out/work/application")" ]] \
    || { echo "$platform: RPATH/RUNPATH was not removed" >&2; exit 1; }
  echo "PASS: $platform uses $expected and has no RPATH"
}

assert_patched linux/amd64 /lib64/ld-linux-x86-64.so.2
assert_patched linux/arm64 /lib/ld-linux-aarch64.so.1

# ppc64le is a valid Docker platform that this Dockerfile has no loader for.
# Do not mistake an unrelated build failure for the Dockerfile's own guard.
if docker build --file "$dockerfile" --target patcher --platform linux/ppc64le \
    --output "type=local,dest=$scratch/out-unsupported" "$context" >"$scratch/unsupported.log" 2>&1; then
  echo 'linux/ppc64le unexpectedly built an image' >&2; exit 1
fi
grep -q 'unsupported target architecture: ppc64le' "$scratch/unsupported.log" \
  || { cat "$scratch/unsupported.log" >&2; exit 1; }
echo 'PASS: linux/ppc64le rejected as an unsupported target architecture'
