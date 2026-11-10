#!/usr/bin/env bash
# Formula recognition bench on CROHME handwritten formulas (per-symbol labels).
# Downloads the data (≈60 MB, CC BY-NC-SA, not kept in the repo) to build/crohme on first use,
# then runs FormulaBenchTest: the report is app/build/formula-report.json, and the test fails
# on a regression against app/src/test/resources/formula-baseline.json.
# Choosing layout thresholds: run dev.inknotes.math.LayoutTuner (a main in the unit tests)
# with build/crohme/CROHME_full_v2/CROHME2013_data/TrainINKML and check sets as arguments.
set -euo pipefail
cd "$(dirname "$0")/.."
DATA=build/crohme/CROHME_full_v2
if [ ! -d "$DATA" ]; then
  mkdir -p build/crohme
  curl -fL -o build/crohme/full.zip https://raw.githubusercontent.com/ThomasLech/CROHME_extractor/master/data/CROHME_full_v2.zip
  (cd build/crohme && unzip -q full.zip && rm full.zip)
fi
CROHME_DIR="$PWD/$DATA" ./gradlew :app:testDebugUnitTest --tests 'dev.inknotes.math.FormulaBenchTest' --rerun
