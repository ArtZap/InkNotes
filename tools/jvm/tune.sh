#!/usr/bin/env bash
# Tests with layout thresholds changed, to see what a threshold does before changing it:
#   tools/jvm/tune.sh pointLow=99,joinGap=0.3 MathPageTest RealPageTest
# (names: the fields of Tune in MathLayout.kt). Compile first with tools/jvm/test.sh.
set -euo pipefail
cd "$(dirname "$0")/../.."
KOTLINC=$(realpath -m "${KOTLINC:-build/kotlinc}")
OUT=$(realpath -m "${OUT:-build/jvm-test}")
cd app
SET=$1; shift
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT:$KOTLINC/lib/kotlin-stdlib.jar:src/test/resources" org.junit.Toggle "$SET" \
  $(for t in "$@"; do echo dev.inknotes.math.$t; done) 2>&1 | grep -v JAVA_TOOL
