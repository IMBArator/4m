#!/usr/bin/env bash
#
# Builds and tests :core without Gradle.
#
# The full Gradle build needs ForgeGradle/NeoGradle and a Minecraft toolchain, none of which :core
# uses — it is plain Java. So while the pipeline is the thing under construction, this gives a fast
# loop that needs nothing but a JDK 17 and two downloaded jars.
#
#   ./tools/build-core.sh              compile and run the tests
#   ./tools/build-core.sh probe <url> [seconds] [out-file]
#                                      run the pipeline against a real station
#
# Set JAVA_HOME to a JDK 17 (Debian 13 ships no openjdk-17-jdk; see the README for how to get one
# without root). Downloads land in .build/ and are cached.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD="$ROOT/.build"
JUNIT_VERSION="1.10.2"
JUNIT_JAR="$BUILD/junit-platform-console-standalone-${JUNIT_VERSION}.jar"

if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVAC="$JAVA_HOME/bin/javac"
    JAVA="$JAVA_HOME/bin/java"
else
    JAVAC="$(command -v javac || true)"
    JAVA="$(command -v java || true)"
fi

if [[ -z "$JAVAC" || ! -x "$JAVAC" ]]; then
    echo "error: no javac found. Set JAVA_HOME to a JDK 17 — see the README." >&2
    exit 1
fi

VERSION="$("$JAVAC" -version 2>&1 | grep -oE '[0-9]+' | head -1)"
if [[ "$VERSION" -lt 17 ]]; then
    echo "error: javac $VERSION found, but Minecraft 1.20.1 targets Java 17." >&2
    exit 1
fi

mkdir -p "$BUILD/main" "$BUILD/test"

compile_main() {
    find "$ROOT/core/src/main/java" -name '*.java' > "$BUILD/main-sources.txt"
    echo ">> compiling $(wc -l < "$BUILD/main-sources.txt" | tr -d ' ') main sources"
    "$JAVAC" -Xlint:all -Werror -d "$BUILD/main" @"$BUILD/main-sources.txt"
}

fetch_junit() {
    [[ -f "$JUNIT_JAR" ]] && return
    echo ">> fetching JUnit $JUNIT_VERSION"
    curl -sSL -o "$JUNIT_JAR" \
        "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/${JUNIT_VERSION}/junit-platform-console-standalone-${JUNIT_VERSION}.jar"
}

case "${1:-test}" in
    probe)
        shift
        [[ $# -ge 1 ]] || { echo "usage: $0 probe <url> [seconds] [out-file]" >&2; exit 2; }
        compile_main
        exec "$JAVA" -cp "$BUILD/main" mmmm.core.tools.StreamProbe "$@"
        ;;
    compile)
        compile_main
        ;;
    test)
        compile_main
        fetch_junit
        find "$ROOT/core/src/test/java" -name '*.java' > "$BUILD/test-sources.txt"
        echo ">> compiling $(wc -l < "$BUILD/test-sources.txt" | tr -d ' ') test sources"
        "$JAVAC" -Xlint:all -cp "$BUILD/main:$JUNIT_JAR" -d "$BUILD/test" @"$BUILD/test-sources.txt"
        echo ">> running tests"
        exec "$JAVA" -jar "$JUNIT_JAR" execute \
            --class-path "$BUILD/main:$BUILD/test" \
            --scan-class-path --details=summary --disable-ansi-colors
        ;;
    *)
        echo "usage: $0 [test|compile|probe <url> [seconds] [out-file]]" >&2
        exit 2
        ;;
esac
