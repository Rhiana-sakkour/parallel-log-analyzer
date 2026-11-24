#!/usr/bin/env bash
# Starts the desktop GUI. (Under WSL2 with WSLg the window appears on the Windows desktop; otherwise run it on Windows/Linux/macOS directly.)
set -euo pipefail
cd "$(dirname "$0")/.."
[ -d out ] || scripts/build.sh
java ${JAVA_OPTS:-} -cp out logan.ui.LogAnalyzerApp
