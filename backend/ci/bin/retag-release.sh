#!/usr/bin/env bash
set -euo pipefail

# Retags the latest prerelease image to a stable release version.
#
# Usage:
#   retag-release.sh --registry <host> --image <name> --branch <name>
#
# Required arguments:
#   --registry      Container registry hostname
#   --image         Image name within the registry (e.g. platformup2date/backend)
#   --branch        Sanitized branch name of the merged PR (e.g. feature-cool-thing)
#
# Authentication:
#   Reads REGISTRY_USER and REGISTRY_PASSWORD from env vars or from files
#   at /etc/env-secret/. Generates a docker auth config and passes it to
#   skopeo via --authfile.
#
# Checks the merge commit message for [minor] or [major] to override the
# default patch bump.

usage() {
    echo "Usage: $0 --registry <host> --image <name> --branch <name>" >&2
    exit 1
}

REGISTRY=""
IMAGE_NAME=""
BRANCH_NAME=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --registry)   REGISTRY="$2";           shift 2 ;;
        --image)      IMAGE_NAME="$2";         shift 2 ;;
        --branch)     BRANCH_NAME="$2";        shift 2 ;;
        *) echo "Unknown option: $1" >&2; usage ;;
    esac
done

[[ -z "$REGISTRY" ]]   && { echo "Error: --registry is required" >&2; usage; }
[[ -z "$IMAGE_NAME" ]] && { echo "Error: --image is required" >&2; usage; }
[[ -z "$BRANCH_NAME" ]] && { echo "Error: --branch is required" >&2; usage; }

ENV_SECRET_DIR="/etc/env-secret"
REGISTRY_USER="${REGISTRY_USER:-}"
REGISTRY_PASSWORD="${REGISTRY_PASSWORD:-}"
[[ -z "$REGISTRY_USER" ]] && [[ -f "$ENV_SECRET_DIR/REGISTRY_USER" ]] && REGISTRY_USER="$(cat "$ENV_SECRET_DIR/REGISTRY_USER")"
[[ -z "$REGISTRY_PASSWORD" ]] && [[ -f "$ENV_SECRET_DIR/REGISTRY_PASSWORD" ]] && REGISTRY_PASSWORD="$(cat "$ENV_SECRET_DIR/REGISTRY_PASSWORD")"

AUTH_FILE=""
if [[ -n "$REGISTRY_USER" ]] && [[ -n "$REGISTRY_PASSWORD" ]]; then
    echo "Generating docker auth config from credentials..."
    AUTH_FILE="$(mktemp)"
    AUTH=$(printf '%s:%s' "$REGISTRY_USER" "$REGISTRY_PASSWORD" | base64 -w0)
    printf '{"auths":{"%s":{"auth":"%s"}}}' "$REGISTRY" "$AUTH" > "$AUTH_FILE"
fi

SKOPEO_AUTH_ARGS=""
if [[ -n "$AUTH_FILE" ]]; then
    SKOPEO_AUTH_ARGS="--authfile $AUTH_FILE"
fi

get_registry_tags() {
    skopeo list-tags $SKOPEO_AUTH_ARGS "docker://$REGISTRY/$IMAGE_NAME" 2>/dev/null \
        | jq -r '.Tags[]' 2>/dev/null || true
}

get_latest_stable_version() {
    echo "$1" | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -n 1 || true
}

ALL_TAGS="$(get_registry_tags)"
LATEST_STABLE="$(get_latest_stable_version "$ALL_TAGS")"

if [[ -z "$LATEST_STABLE" ]]; then
    LATEST_STABLE="0.0.0"
    echo "No existing stable tags found, starting from 0.0.0"
else
    echo "Latest stable tag: $LATEST_STABLE"
fi

MAJOR="$(echo "$LATEST_STABLE" | cut -d. -f1)"
MINOR="$(echo "$LATEST_STABLE" | cut -d. -f2)"
PATCH="$(echo "$LATEST_STABLE" | cut -d. -f3)"

# Check merge commit message for version bump directives
COMMIT_MSG="$(git log -1 --format='%s%n%b' HEAD)"

if echo "$COMMIT_MSG" | grep -qi '\[major\]'; then
    MAJOR=$((MAJOR + 1))
    MINOR=0
    PATCH=0
    echo "Detected [major] bump directive"
elif echo "$COMMIT_MSG" | grep -qi '\[minor\]'; then
    MINOR=$((MINOR + 1))
    PATCH=0
    echo "Detected [minor] bump directive"
else
    PATCH=$((PATCH + 1))
fi

NEXT_VERSION="${MAJOR}.${MINOR}.${PATCH}"
echo "Next stable version: $NEXT_VERSION"

# Find the prerelease tag that matches NEXT_VERSION-<branch>.N (highest N)
# Prerelease tags follow the pattern: X.Y.Z-<label>.N
PRERELEASE_PATTERN="^${NEXT_VERSION}-${BRANCH_NAME}\.[0-9]+$"
PRERELEASE_TAG="$({ echo "$ALL_TAGS" | grep -E "$PRERELEASE_PATTERN" || true; } | sort -V | tail -n 1)"

if [[ -z "$PRERELEASE_TAG" ]]; then
    echo "Error: no prerelease tag found matching pattern $PRERELEASE_PATTERN"
    echo "Available tags:"
    echo "$ALL_TAGS" | head -20
    exit 1
fi

echo "Found prerelease tag: $PRERELEASE_TAG"

SRC="docker://$REGISTRY/$IMAGE_NAME:$PRERELEASE_TAG"
DST="docker://$REGISTRY/$IMAGE_NAME:$NEXT_VERSION"

echo "Retagging: $SRC -> $DST"
skopeo copy $SKOPEO_AUTH_ARGS "$SRC" "$DST"

echo "Successfully published $REGISTRY/$IMAGE_NAME:$NEXT_VERSION"

[[ -n "$AUTH_FILE" ]] && rm -f "$AUTH_FILE"
