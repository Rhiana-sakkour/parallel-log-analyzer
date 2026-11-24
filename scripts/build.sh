#!/usr/bin/env bash
# Compile everything (main + tests) into ./out . Requires a JDK 17+ (javac on PATH).
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf out && mkdir -p out
javac -d out $(find src/main src/test -name '*.java')
echo "Build OK -> out/"
