#!/usr/bin/env bash
set -euo pipefail

# Run frontend tests in non-interactive CI mode
# Symlinks node_modules to a local tmpdir to avoid CephFS I/O contention.

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LOCAL_DIR="$(mktemp -d)"
trap 'rm -f "$PROJECT_ROOT/node_modules"; rm -rf "$LOCAL_DIR"' EXIT

mkdir "$LOCAL_DIR/node_modules"
ln -sfn "$LOCAL_DIR/node_modules" "$PROJECT_ROOT/node_modules"

echo "Installing dependencies..."
cd "$PROJECT_ROOT" && yarn install --frozen-lockfile

echo "Running frontend tests..."
yarn test --watchAll=false --ci --passWithNoTests

echo "All frontend tests passed!"
