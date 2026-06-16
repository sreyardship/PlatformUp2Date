#!/usr/bin/env bash
set -euo pipefail

# Build native executable using GraalVM
# This produces a standalone native binary with no JVM required

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

# Need to make sure gradle opts is the same as the jvm opts
# in order to avoid starting the gradle daemon
export GRADLE_OPTS="-XX:MaxMetaspaceSize=384m -XX:+HeapDumpOnOutOfMemoryError -Xms256m -Xmx512m"

NATIVE_BUILD_DIR="$PROJECT_ROOT/build-native"

# `quarkusIntTest` launches the native binary, which needs Valkey to boot.
# CI has no Docker, so Quarkus Dev Services can't start a throwaway container.
# Run Valkey natively from the devshell instead and point the tests at it;
# setting QUARKUS_REDIS_HOSTS also keeps Dev Services from reaching for Docker.
valkey-server --port 6379 --daemonize yes --save '' --appendonly no
export QUARKUS_REDIS_HOSTS=redis://localhost:6379
for _ in $(seq 1 50); do
  valkey-cli -p 6379 ping >/dev/null 2>&1 && break
  sleep 0.2
done

echo "Building native executable (this may take several minutes)..."
# `quarkusIntTest` runs the @QuarkusIntegrationTest suite against the freshly built
# native binary. This is the only place native-image regressions (e.g. a REST-client
# provider that can't be reflectively instantiated in native) get caught before the
# image is published — JVM-mode unit tests cannot see them. `-x test` skips the unit
# tests here; those run in the separate `unit-test` pipeline task.
cd "$PROJECT_ROOT" && gradle --no-daemon --project-cache-dir "$PROJECT_ROOT/.gradle/native" build quarkusIntTest \
  -Dorg.gradle.jvmargs="-XX:MaxMetaspaceSize=384m -XX:+HeapDumpOnOutOfMemoryError -Xms256m -Xmx512m" \
  -PcustomBuildDir="$NATIVE_BUILD_DIR" \
  -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false \
  -x test

echo "✓ Native build + integration tests complete!"
echo "Binary location: $NATIVE_BUILD_DIR/*-runner"
