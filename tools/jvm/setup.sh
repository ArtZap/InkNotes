#!/usr/bin/env bash
# Kotlin compiler for the JVM checks without the Android SDK (see tools/jvm/README.md).
# Downloads kotlinc into build/kotlinc (not in the repository) once.
set -euo pipefail
cd "$(dirname "$0")/../.."
VERSION=${KOTLIN_VERSION:-2.2.21}
if [ -x build/kotlinc/bin/kotlinc ]; then echo "build/kotlinc есть"; exit 0; fi
mkdir -p build
curl -fL -o build/kotlinc.zip "https://github.com/JetBrains/kotlin/releases/download/v$VERSION/kotlin-compiler-$VERSION.zip"
(cd build && unzip -q kotlinc.zip && rm kotlinc.zip)
build/kotlinc/bin/kotlinc -version
