# ADR-014: SetFlow v1.0.0 Stable Release Gate & Source Publication

## Status
Accepted

## Context
Following the completion of Stage 14 (Automated Quality Gates, Decoupled CI, Security Hardening) and Stage 15 (Beta Trial, Bug Triage, Regression Freeze), SetFlow has reached release candidate status. The repository and CI infrastructure were repaired, audited, and verified through GitHub Actions (CI Run `43569884218` on `ci/stage-recovery` and Run `43571917672` on `stage-15-beta-trial-freeze`).

Stage 16 mandates the final stable release gate (`16_STABLE_RELEASE_GATE.txt`), establishing:
1. Production versioning at `v1.0.0` (`versionCode = 16`).
2. Final sign-off on the full 40-item acceptance matrix (`ACC-01` through `ACC-40`).
3. Strict enforcement of GPL-3.0 copyleft compliance, complete corresponding source disclosure, and attribution notices.
4. Cryptographic integrity verification: SHA-256 manifest generation for release APKs.
5. Invariant privacy guarantees: Zero network permissions, local storage only, and scrubbed diagnostics.

## Decision
1. **Version Configuration**:
   - `base/app/build.gradle.kts`:
     - `versionCode = 16`
     - `versionName = "1.0.0"`
     - ABI splits: `arm64-v8a` (primary target), `armeabi-v7a` (legacy fallback), and `universal`.
2. **Acceptance Matrix Closure**:
   - Every single entry from `ACC-01` to `ACC-40` is audited and marked PASS. No blockers or unhandled P0/P1 issues exist.
3. **Copyleft & Source Availability**:
   - The application is distributed under the GNU General Public License v3.0 (GPL-3.0-only).
   - Complete corresponding source is published on GitHub (`https://github.com/jtmeaker-hash/Manual-Mix`).
   - Attribution to upstream Myspoti author (kota-zo) is explicitly retained in `README.md`, `NOTICE.md`, and `THIRD_PARTY_NOTICES.md`.
4. **Offline Security Boundaries**:
   - The merged `AndroidManifest.xml` strips `android.permission.INTERNET`, `android.permission.ACCESS_NETWORK_STATE`, `android.permission.ACCESS_WIFI_STATE`, and `android.permission.REQUEST_INSTALL_PACKAGES`.
   - The app cannot communicate over any network interface.
5. **Release Distribution Artifacts**:
   - Split and universal release APKs signed with SetFlow release signing config (or debug fallback in local non-production environments).
   - `SHA256SUMS.txt` cryptographic manifest accompanying all binary distributions.
   - Public issue report templates (`.github/ISSUE_TEMPLATE/`) to facilitate user feedback without requiring copyrighted audio files.

## Consequences
- SetFlow v1.0.0 is frozen and ready for production deployment and distribution.
- Upgrades from beta builds preserve database revisions and all user-configured transition overrides.
- No network leakage or ad/tracking SDKs can ever be linked into the build.
