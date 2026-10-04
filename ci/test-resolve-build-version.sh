#!/usr/bin/env bash
# Black-box tests of the identity CLI; no sourcing or mocked Git executable.
set -euo pipefail
resolver=$(cd "$(dirname "$0")" && pwd)/resolve-build-version.sh
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
checks=0

expect() {
  local wanted=$1 actual
  shift
  actual=$(bash "$resolver" "$@")
  [[ $actual == "$wanted" ]] || { printf 'expected <%s>, got <%s>\n' "$wanted" "$actual" >&2; exit 1; }
  checks=$((checks + 1))
}
reject() {
  if bash "$resolver" "$@" >"$scratch/stdout" 2>"$scratch/stderr"; then
    printf 'unexpected success: %s\n' "$*" >&2; exit 1
  fi
  [[ ! -s $scratch/stdout && -s $scratch/stderr ]]
  checks=$((checks + 1))
}

expect 1.2.3 release v1.2.3
expect 0.0.0 release v0.0.0
expect 1.2.3-rc.6 release v1.2.3-rc.6
expect 1.2.3-rc.6+build.001 release v1.2.3-rc.6+build.001
expect 1.2.3+001.sha-ABC release v1.2.3+001.sha-ABC
expect 1.2.3-0.0a.01a.1-2 release v1.2.3-0.0a.01a.1-2
for tag in '' v 1.2.3 vv1.2.3 V1.2.3 v1.2 v01.2.3 v1.02.3 v1.2.03 \
  v1.2.3-01 v1.2.3-rc.01 v1.2.3- v1.2.3-rc..1 v1.2.3+ v1.2.3+meta..1 \
  v1.2.3_rc v1.2.3+bad_meta ' v1.2.3' 'v1.2.3 ' $'v1.2.3\n' 'v1.2.3;echo bad'; do
  reject release "$tag"
done
reject
reject unknown
reject release
reject release v1.2.3 extra
reject edge extra

# This commit is deliberately unrelated to the caller's GITHUB_SHA (PRs can
# check out a merge commit). A detached checkout and a changed HEAD must work.
git init -q "$scratch/repo"
cd "$scratch/repo"
git -c user.name=CI -c user.email=ci@example.invalid commit -qm first --allow-empty
export GITHUB_SHA=not-the-checked-out-commit
first=$(git rev-parse HEAD)
expect "dev-$first" edge
git -c user.name=CI -c user.email=ci@example.invalid commit -qm second --allow-empty
second=$(git rev-parse HEAD)
[[ $first != "$second" ]]
expect "dev-$second" edge
git checkout -q --detach "$first"
expect "dev-$first" edge
cd "$scratch"
reject edge
printf 'identity CLI: %d checks passed\n' "$checks"
