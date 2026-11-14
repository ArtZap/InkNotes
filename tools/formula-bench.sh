#!/usr/bin/env bash
# Formula recognition bench on CROHME handwritten formulas (per-symbol labels).
# Downloads the data (≈60 MB, CC BY-NC-SA, not kept in the repo) to build/crohme on first use,
# then runs FormulaBenchTest: the report is app/build/formula-report.json, and the test fails
# formula by formula against app/src/test/resources/formula-outcomes.tsv.
# Choosing layout thresholds: run dev.inknotes.math.LayoutTuner (a main in the unit tests)
# with build/crohme/CROHME_full_v2/CROHME2013_data/TrainINKML and check sets as arguments.
# --fetch: only download and check the data (CI does this, then runs all unit tests with
# CROHME_DIR set). --update: after the run, its outcomes become the baseline
# (app/src/test/resources/formula-outcomes.tsv) — for an intended change.
set -euo pipefail
cd "$(dirname "$0")/.."
DATA=build/crohme/CROHME_full_v2
if [ ! -d "$DATA" ]; then
  mkdir -p build/crohme
  # A fixed commit, and the file checked against its git blob id there: a changed or
  # tampered archive is not unpacked.
  curl -fL -o build/crohme/full.zip https://raw.githubusercontent.com/ThomasLech/CROHME_extractor/ca2d9328b8ad4dabec94587d8f98f94851ff3a49/data/CROHME_full_v2.zip
  if [ "$(git hash-object build/crohme/full.zip)" != "e5fa1924a89558b97086f15dde2ac4c2420e86d1" ]; then
    rm -f build/crohme/full.zip
    echo "CROHME_full_v2.zip не совпадает с ожидаемым (git blob e5fa192…), не распаковываю" >&2
    exit 1
  fi
  (cd build/crohme && unzip -q full.zip && rm full.zip)
fi
if [ "${1:-}" = "--fetch" ]; then exit 0; fi
CROHME_DIR="$PWD/$DATA" ./gradlew :app:testDebugUnitTest --tests 'dev.inknotes.math.FormulaBenchTest' --rerun || [ "${1:-}" = "--update" ]
cat app/build/formula-report.txt
if [ "${1:-}" = "--update" ]; then cp app/build/formula-outcomes.tsv app/src/test/resources/formula-outcomes.tsv; fi
