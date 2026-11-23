#!/usr/bin/env bash
# The math unit tests (app/src/test/java/dev/inknotes/math) on the JVM, without Gradle or the
# Android SDK: kotlinc + a small stand-in for JUnit (junit/Shim.kt). Seconds, not minutes.
#   tools/jvm/test.sh                 all test classes of the math package
#   tools/jvm/test.sh MathPageTest    some of them
# With build/crohme present (tools/formula-bench.sh --fetch) the CROHME bench runs too.
set -euo pipefail
cd "$(dirname "$0")/../.."
KOTLINC=$(realpath -m "${KOTLINC:-build/kotlinc}")
OUT=$(realpath -m "${OUT:-build/jvm-test}")
M=app/src/main/java/dev/inknotes
T=app/src/test/java/dev/inknotes/math
[ -x "$KOTLINC/bin/kotlinc" ] || tools/jvm/setup.sh
if [ -d build/crohme/CROHME_full_v2 ]; then export CROHME_DIR="$PWD/build/crohme/CROHME_full_v2"; fi
export FORMULA_EXPORT=${FORMULA_EXPORT:-/tmp/formulas.tsv}
mkdir -p "$OUT"
# MathNotes needs ML Kit (Android): left out; LayoutTuner is a tool, not a test.
"$KOTLINC/bin/kotlinc" $(ls $M/math/*.kt | grep -v MathNotes) $M/data/Lang.kt $M/data/Cards.kt $M/editor/Tidy.kt $M/editor/Trim.kt $M/search/Spelling.kt \
  $(ls $T/*.kt | grep -v LayoutTuner) app/src/test/java/dev/inknotes/data/CardsTest.kt app/src/test/java/dev/inknotes/editor/TrimTest.kt app/src/sharedTest/java/dev/inknotes/math/*.kt \
  tools/jvm/junit/Shim.kt tools/jvm/junit/Toggle.kt -d "$OUT" 2>&1 | grep -E "error" | head -20 || true
if [ $# -gt 0 ]; then CLASSES="$*"; else CLASSES="$(grep -ho "^class [A-Za-z0-9]*Test\b" $T/*.kt | awk '{print $2}' | sort -u) data.CardsTest editor.TrimTest"; fi
cd app
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT:$KOTLINC/lib/kotlin-stdlib.jar:src/test/resources" org.junit.Runner \
  $(for t in $CLASSES; do case $t in *.*) echo dev.inknotes.$t ;; *) echo dev.inknotes.math.$t ;; esac; done) 2>&1 | grep -v JAVA_TOOL
