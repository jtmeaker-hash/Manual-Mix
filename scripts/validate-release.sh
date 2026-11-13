#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# SetFlow Release Candidate Validation Script
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BASE_DIR="$REPO_ROOT/base"

echo "=================================================="
echo "SetFlow Release Validation & Quality Gate"
echo "=================================================="

cd "$BASE_DIR"

echo "==> Step 1: Running pure JVM and Android unit tests..."
./gradlew :automix:test :app:testDebugUnitTest --console=plain

echo "==> Step 2: Verifying Merged Manifest Permissions..."
./gradlew :app:processReleaseMainManifest --console=plain
MANIFEST="$BASE_DIR/app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml"

if [ ! -f "$MANIFEST" ]; then
    echo "ERROR: Merged manifest not found at $MANIFEST"
    exit 1
fi

if grep -q "android.permission.INTERNET" "$MANIFEST"; then
    echo "SECURITY GATE FAILED: android.permission.INTERNET found in release manifest!"
    exit 1
fi

if grep -q "android.permission.ACCESS_NETWORK_STATE" "$MANIFEST"; then
    echo "SECURITY GATE FAILED: android.permission.ACCESS_NETWORK_STATE found in release manifest!"
    exit 1
fi

if grep -q "android.permission.REQUEST_INSTALL_PACKAGES" "$MANIFEST"; then
    echo "SECURITY GATE FAILED: android.permission.REQUEST_INSTALL_PACKAGES found in release manifest!"
    exit 1
fi
echo "Manifest security gate PASSED: 100% offline, zero network permissions."

echo "==> Step 3: Assembling release candidate and collecting APKs..."
./gradlew assembleRelease :app:collectApks --console=plain

RELEASE_DIR="$BASE_DIR/app/build/release-apks"
if [ ! -d "$RELEASE_DIR" ]; then
    echo "ERROR: Release directory $RELEASE_DIR missing!"
    exit 1
fi

echo "==> Step 4: Verifying SHA256 Checksums..."
cd "$RELEASE_DIR"
if [ ! -f "SHA256SUMS.txt" ]; then
    echo "ERROR: SHA256SUMS.txt missing!"
    exit 1
fi
sha256sum -c SHA256SUMS.txt

echo "==> Step 5: Inspecting ARM64 release APK assets & native libraries..."
python3 -c "
import zipfile, glob, sys
apks = glob.glob('*ARMv8.apk')
if not apks:
    print('ERROR: No ARMv8 APK found!')
    sys.exit(1)
apk = apks[0]
with zipfile.ZipFile(apk) as z:
    names = set(z.namelist())
    required_assets = ['assets/automix/beat-this-small0-int8.onnx']
    required_libs = ['lib/arm64-v8a/libonnxruntime.so', 'lib/arm64-v8a/libonnxruntime4j_jni.so']
    for a in required_assets:
        if a not in names:
            print(f'ERROR: Missing required asset {a} in {apk}')
            sys.exit(1)
    for l in required_libs:
        if l not in names:
            print(f'ERROR: Missing required native lib {l} in {apk}')
            sys.exit(1)
print(f'Verified {apk}: ONNX model and native runtime libraries present.')
"

echo "=================================================="
echo "ALL GATES PASSED: SetFlow Release Candidate Verified!"
echo "=================================================="
