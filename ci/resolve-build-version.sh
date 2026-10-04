#!/usr/bin/env bash
# Print one build identity. Call after checkout; edge always observes this worktree.
set -euo pipefail
export LC_ALL=C

fail() { printf 'build identity: %s\n' "$*" >&2; exit 1; }

case "${1-}" in
  release)
    [[ $# == 2 ]] || fail 'usage: resolve-build-version.sh release v<semver>'
    # SemVer 2.0: no leading zeros in core/numeric prerelease identifiers;
    # alphanumeric prerelease identifiers must contain a letter or hyphen.
    number='(0|[1-9][0-9]*)'
    prerelease="($number|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
    semver="^v$number\\.$number\\.$number(-$prerelease(\\.$prerelease)*)?(\\+[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?$"
    [[ $2 =~ $semver ]] || fail "invalid release tag: $2 (expected v<strict semver>)"
    printf '%s\n' "${2#v}"
    ;;
  edge)
    [[ $# == 1 ]] || fail 'usage: resolve-build-version.sh edge'
    revision=$(git rev-parse --verify HEAD) || fail 'edge requires a checked-out commit'
    [[ $revision =~ ^([0-9a-f]{40}|[0-9a-f]{64})$ ]] || fail 'HEAD is not a full Git object ID'
    printf 'dev-%s\n' "$revision"
    ;;
  *) fail 'usage: resolve-build-version.sh {release v<semver>|edge}' ;;
esac
