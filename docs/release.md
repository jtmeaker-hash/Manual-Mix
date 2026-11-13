# SetFlow Release Management & Signing Policy

## Release Philosophy

SetFlow releases are fully reproducible, open-source, and verifiable. Binary releases are distributed alongside complete corresponding source code under the GNU General Public License v3.0.

## Versioning Policy

SetFlow adheres to strict Semantic Versioning (`vMAJOR.MINOR.PATCH[-beta.N]`):
- **Development & Stage Milestones**: e.g., `0.14.0-stage14` (versionCode `14`).
- **Beta Releases**: e.g., `v0.14.0-beta.1` (versionCode `1000 + CI_BUILD_NUMBER`).
- **Stable Releases**: e.g., `v1.0.0` (versionCode `1000 + CI_BUILD_NUMBER`).

Tag verification is enforced in CI via `tools/check_tag.py` to prevent version regressions.

## Signing Architecture

### 1. Isolated Signing Keys
- **No Secrets in Git**: Keystore files and signing credentials are never committed to version control.
- **CI Secret Store**: GitHub Actions release workflows inject release signing credentials from GitHub Encrypted Secrets:
  - `SETFLOW_KEYSTORE_BASE64`: Base64-encoded release `.jks` keystore.
  - `SETFLOW_KEYSTORE_PASSWORD`: Keystore master password.
  - `SETFLOW_KEY_ALIAS`: Private key alias.
  - `SETFLOW_KEY_PASSWORD`: Private key password.
- **Local Fallback**: Local developer builds and pull requests automatically sign using the standard debug keystore, ensuring all tests and release APK packaging tasks execute cleanly without external dependencies.

### 2. ABI Split Builds
SetFlow uses Android Gradle Plugin ABI splits to deliver optimized APKs without unnecessary architecture bloat:
- **`SetFlow-<version>-ARMv8.apk`**: 64-bit ARM (`arm64-v8a`), matching over 95% of active Android devices (~26MB).
- **`SetFlow-<version>-ARMv7.apk`**: 32-bit legacy ARM (`armeabi-v7a`) (~21MB).
- **`SetFlow-<version>-universal.apk`**: Universal multi-architecture APK (~40MB).
- **Excluded**: x86 and x86_64 emulator binaries are excluded from release distribution packaging.

## Release Gate Checklist

Before publishing any release:
- [ ] Working directory is clean (`git status --porcelain` is empty).
- [ ] All unit and automix tests pass: `./gradlew :automix:test :app:testDebugUnitTest`.
- [ ] Merged manifest permission check passes: zero `INTERNET` or network permissions.
- [ ] ProGuard / R8 shrinker keeps ONNX native libraries and Room databases intact.
- [ ] Release APKs are collected via `./gradlew assembleRelease :app:collectApks`.
- [ ] Cryptographic checksums in `SHA256SUMS.txt` match all generated APKs.
- [ ] Third-party notices and GPL-3.0 attribution verified in `NOTICE.md` and `THIRD_PARTY_NOTICES.md`.
- [ ] Release notes extracted from `CHANGELOG.md`.
