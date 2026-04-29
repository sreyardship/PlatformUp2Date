#!/usr/bin/env bash
set -euo pipefail

# Build native executable using GraalVM
# This produces a standalone native binary with no JVM required

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

# Need to make sure gradle opts is the same as the jvm opts
# in order to avoid starting the gradle daemon
export GRADLE_OPTS="-XX:MaxMetaspaceSize=384m -XX:+HeapDumpOnOutOfMemoryError -Xms256m -Xmx512m"

NATIVE_BUILD_DIR="$PROJECT_ROOT/build-native"

echo "Building native executable (this may take several minutes)..."
cd "$PROJECT_ROOT" && gradle --no-daemon --project-cache-dir "$PROJECT_ROOT/.gradle/native" build \
  -Dorg.gradle.jvmargs="-XX:MaxMetaspaceSize=384m -XX:+HeapDumpOnOutOfMemoryError -Xms256m -Xmx512m" \
  -PcustomBuildDir="$NATIVE_BUILD_DIR" \
  -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false \
  -x test

echo "✓ Native build complete!"
echo "Binary location: $NATIVE_BUILD_DIR/*-runner"
