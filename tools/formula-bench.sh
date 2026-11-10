#!/usr/bin/env bash
# Formula recognition bench on CROHME (handwritten formulas with per-symbol labels).
#   tools/formula-bench.sh            — report on the CROHME 2013 test set, compared with the baseline
#   tools/formula-bench.sh tune       — choose layout thresholds (Tune) on the 2013 training set
# Data (≈60 MB, CC BY-NC-SA, not kept in the repo) is downloaded to build/crohme on first use.
set -euo pipefail
cd "$(dirname "$0")/.."
DATA=build/crohme/CROHME_full_v2
if [ ! -d "$DATA" ]; then
  mkdir -p build/crohme
  curl -fL -o build/crohme/full.zip https://raw.githubusercontent.com/ThomasLech/CROHME_extractor/master/data/CROHME_full_v2.zip
  (cd build/crohme && unzip -q full.zip && rm full.zip)
fi
./gradlew -q :app:compileDebugUnitTestKotlin
CP="app/build/tmp/kotlin-classes/debugUnitTest:app/build/tmp/kotlin-classes/debug:$(find ~/.gradle -name 'kotlin-stdlib-2*.jar' | grep -v sources | head -1):$(find ~/.gradle -name 'junit-4.13.2.jar' | head -1):app/src/test/resources"
if [ "${1:-}" = tune ]; then
  java -cp "$CP" dev.inknotes.math.LayoutTuner "$DATA/CROHME2013_data/TrainINKML" "$DATA/CROHME2013_data/TestINKMLGT" "$DATA/CROHME2012_data/testDataGT"
else
  java -Dbench.fails=build/formula-fails.tsv -cp "$CP" dev.inknotes.math.FormulaBench "$DATA/CROHME2013_data/TestINKMLGT" build/formula-report.json app/src/test/resources/formula-baseline.json
fi
