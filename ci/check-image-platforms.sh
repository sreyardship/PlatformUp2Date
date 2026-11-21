#!/usr/bin/env bash
# Assert that each published image reference carries every given platform.
# Requires Docker Buildx and read access to the registry.
#
#   bash ci/check-image-platforms.sh linux/amd64,linux/arm64 \
#     ghcr.io/sreyardship/platformup2date/backend:edge \
#     ghcr.io/sreyardship/platformup2date/frontend:edge
set -euo pipefail

if (( $# < 2 )); then
  echo "usage: $0 <os/arch>[,<os/arch>...] <image-ref>..." >&2
  exit 2
fi
IFS=, read -r -a platforms <<<"$1"
shift

failed=0
for ref in "$@"; do
  # Platforms are read from the manifest list. A reference that is a plain
  # image manifest, not a list, therefore reports no platform and fails.
  published="$(docker buildx imagetools inspect "$ref" \
    --format '{{range .Manifest.Manifests}}{{.Platform.OS}}/{{.Platform.Architecture}}{{"\n"}}{{end}}')"
  missing=()
  for platform in "${platforms[@]}"; do
    grep -Fxq "$platform" <<<"$published" || missing+=("$platform")
  done
  if (( ${#missing[@]} > 0 )); then
    printf 'FAIL: %s lacks %s; it publishes:\n%s\n' "$ref" "${missing[*]}" "${published:-(no manifest list)}" >&2
    failed=1
  else
    echo "PASS: $ref publishes ${platforms[*]}"
  fi
done
exit "$failed"
