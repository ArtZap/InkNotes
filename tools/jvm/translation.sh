#!/usr/bin/env bash
# TranslationTest on the JVM: every Russian string of the app is the first argument of tr().
set -euo pipefail
cd "$(dirname "$0")/../.."
KOTLINC=$(realpath -m "${KOTLINC:-build/kotlinc}")
[ -x "$KOTLINC/bin/kotlinc" ] || tools/jvm/setup.sh
mkdir -p build/jvm-tr
"$KOTLINC/bin/kotlinc" app/src/test/java/dev/inknotes/data/TranslationTest.kt $(grep -rln "object KotlinStrings" app/src/test) tools/jvm/junit/Shim.kt -d build/jvm-tr 2>&1 | grep error || true
cd app && java -Dstdout.encoding=UTF-8 -cp "../build/jvm-tr:$KOTLINC/lib/kotlin-stdlib.jar" org.junit.Runner dev.inknotes.data.TranslationTest 2>&1 | grep -v JAVA_TOOL
