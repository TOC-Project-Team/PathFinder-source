#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

echo "⚡ Fast PathFinder fat jar build using existing caches..."
START_TIME=$(date +%s)

find "${PROJECT_ROOT}" -type f -name "*.class" \
    ! -path "${PROJECT_ROOT}/.gradle/*" \
    ! -path "${PROJECT_ROOT}/.gradle-build/*" \
    ! -path "${PROJECT_ROOT}/target/*" \
    -delete

./gradlew shadowJar

JAR_FILE="$(find "${PROJECT_ROOT}/.gradle-build/libs" -maxdepth 1 -type f -name '*-all.jar' | sort | tail -n 1)"
if [[ -z "${JAR_FILE}" ]]; then
    echo "❌ Fat jar artifact not found"
    exit 1
fi

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))
CLASS_COUNT="$(jar tf "${JAR_FILE}" | grep -c '\.class$' || true)"

echo
echo "✅ Fast build succeeded (${DURATION}s)"
echo "📦 Plugin file: ${JAR_FILE}"
ls -lh "${JAR_FILE}"
echo "✅ Java class files: ${CLASS_COUNT}"
