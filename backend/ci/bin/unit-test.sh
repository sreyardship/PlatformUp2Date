#!/usr/bin/env bash
set -euo pipefail

# Run unit tests using pre-compiled classes from a prior build step

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CLASSES_DIR="${1:?Usage: unit-test.sh <classes-directory>}"

if [ ! -d "$CLASSES_DIR" ]; then
    echo "Error: Classes directory does not exist: $CLASSES_DIR"
    exit 1
fi

# Need to make sure gradle opts is the same as the jvm opts
# in order to avoid starting the gradle daemon
export GRADLE_OPTS="-XX:MaxMetaspaceSize=384m -XX:+HeapDumpOnOutOfMemoryError -Xms256m -Xmx512m"

echo "Running unit tests using pre-built classes from $CLASSES_DIR..."
cd "$PROJECT_ROOT" && gradle --no-daemon test \
  -Dorg.gradle.jvmargs="-XX:MaxMetaspaceSize=384m -XX:+HeapDumpOnOutOfMemoryError -Xms256m -Xmx512m" \
  -PpreBuiltClasses="$CLASSES_DIR"

echo "✓ All unit tests passed!"
echo "Test results: $PROJECT_ROOT/build/test-results/test/"
