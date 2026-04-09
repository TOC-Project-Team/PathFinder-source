#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

echo "🔨 Starting PathFinder fat jar build..."
echo "=========================================="

START_TIME=$(date +%s)

if [[ -z "${JAVA_HOME:-}" ]]; then
    echo "⚠️  JAVA_HOME is not set, attempting auto-detection..."
    if command -v java >/dev/null 2>&1; then
        JAVA_BIN="$(readlink -f "$(command -v java)")"
        JAVA_HOME="$(dirname "$(dirname "${JAVA_BIN}")")"
        export JAVA_HOME
        echo "✅ Automatically set JAVA_HOME: ${JAVA_HOME}"
    else
        echo "❌ Java was not found. Install JDK 21+ or set JAVA_HOME manually."
        exit 1
    fi
else
    echo "✅ JAVA_HOME is set: ${JAVA_HOME}"
fi

echo "🔍 Checking Java version..."
java -version 2>&1 | head -1

echo "🧹 Cleaning stray .class files..."
find "${PROJECT_ROOT}" -type f -name "*.class" \
    ! -path "${PROJECT_ROOT}/.gradle/*" \
    ! -path "${PROJECT_ROOT}/.gradle-build/*" \
    ! -path "${PROJECT_ROOT}/target/*" \
    -delete

echo
echo "☕ Running clean + shadowJar ..."
echo "=========================================="
./gradlew clean shadowJar

JAR_FILE="$(find "${PROJECT_ROOT}/.gradle-build/libs" -maxdepth 1 -type f -name '*-all.jar' | sort | tail -n 1)"
if [[ -z "${JAR_FILE}" ]]; then
    echo "❌ Fat jar artifact not found (expected in .gradle-build/libs)"
    exit 1
fi

echo
echo "✅ Fat jar build succeeded"
echo
echo "📊 Verifying build output..."
echo "=========================================="
echo "📦 Plugin file: ${JAR_FILE}"
ls -lh "${JAR_FILE}"

SIZE_BYTES="$(stat -c%s "${JAR_FILE}")"
CLASS_COUNT="$(jar tf "${JAR_FILE}" | grep -c '\.class$' || true)"
echo "✅ File size: $((SIZE_BYTES / 1024))KB"
echo "✅ Java class files: ${CLASS_COUNT}"

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))
echo "⏱️  Build duration: ${DURATION}s"

if [[ "${RELEASE_COPY:-0}" == "1" ]]; then
    RELEASE_DIR="${PROJECT_ROOT}/release"
    TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
    RELEASE_FILE="${RELEASE_DIR}/$(basename "${JAR_FILE%.jar}")-${TIMESTAMP}.jar"
    mkdir -p "${RELEASE_DIR}"
    cp "${JAR_FILE}" "${RELEASE_FILE}"
    echo "📁 Copied release artifact: ${RELEASE_FILE}"
fi

echo
echo "🎉 Build completed"
echo "=========================================="
echo "📦 Final fat jar: ${JAR_FILE}"
echo "💡 Next build command: ./scripts/build-fatjar.sh"
