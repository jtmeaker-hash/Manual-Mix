#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# SetFlow Baseline Fast Verification
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BASE_DIR="$REPO_ROOT/base"

echo "Running fast baseline check for SetFlow..."
cd "$BASE_DIR"
./gradlew :automix:test :app:testDebugUnitTest --console=plain
echo "Baseline check PASSED."
