#!/usr/bin/env bash
set -euo pipefail

# Build production bundle for React frontend
# Symlinks node_modules to a local tmpdir to avoid CephFS I/O contention
# (node_modules contains tens of thousands of small files).

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LOCAL_DIR="$(mktemp -d)"
trap 'rm -f "$PROJECT_ROOT/node_modules"; rm -rf "$LOCAL_DIR"' EXIT

mkdir "$LOCAL_DIR/node_modules"
ln -sfn "$LOCAL_DIR/node_modules" "$PROJECT_ROOT/node_modules"

echo "Installing dependencies..."
cd "$PROJECT_ROOT" && yarn install --frozen-lockfile

echo "Building production bundle..."
yarn build

echo "Build complete!"
echo "Output: $PROJECT_ROOT/build/"
