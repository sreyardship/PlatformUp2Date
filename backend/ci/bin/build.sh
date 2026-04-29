#!/usr/bin/env bash
set -euo pipefail

# Build JAR artifact for Quarkus backend
# This builds a fast-JAR package suitable for JVM deployment and testing

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

# Need to make sure gradle opts is the same as the jvm opts
# in order to avoid starting the gradle daemon
export GRADLE_OPTS="-XX:MaxMetaspaceSize=384m -XX:+HeapDumpOnOutOfMemoryError -Xms256m -Xmx512m"

echo "Building JAR artifact..."
cd "$PROJECT_ROOT" && gradle --no-daemon build \
  -Dorg.gradle.jvmargs="-XX:MaxMetaspaceSize=384m -XX:+HeapDumpOnOutOfMemoryError -Xms256m -Xmx512m" \
  -Dquarkus.package.jar.type=fast-jar \
  -x test

echo "✓ Build complete!"
echo "JAR location: $PROJECT_ROOT/build/quarkus-app/"
