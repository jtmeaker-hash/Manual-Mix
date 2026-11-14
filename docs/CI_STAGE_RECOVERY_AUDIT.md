# SetFlow Stage Branch & CI Recovery Audit
**Prepared**: 2026-11-14  
**Repository**: `https://github.com/jtmeaker-hash/Manual-Mix`  
**Reference Document**: `/storage/emulated/0/Download/Manual_Mix_Stage_Branch_CI_Recovery.txt`  
**Working Branch**: `ci/stage-recovery` (branched from `origin/main` at `acff7334`)

---

## 1. Executive Summary & Objective

In accordance with `Manual_Mix_Stage_Branch_CI_Recovery.txt`, all new functional development has been paused. A comprehensive audit of all Git branches, commit ancestry, CI workflows, and test execution baselines was executed to diagnose why CI runs did not trigger on stage branches, confirm historical integrity, and guarantee that no work is lost or unverified.

### Key Audit Findings:
1. **Ancestry is 100% Intact & Strictly Linear**:
   - `origin/main` (`acff733448f80f9c10e656ed2106688f41c1ddf8`) incorporates Stages 00, 02, 03, 04, and 05 plus pure-Kotlin decode fallbacks.
   - `origin/stage-06-analysis-grid-cache` (`5c1b96e55d579b5dad82944bc381935708f491c9`) branched directly from `origin/main`.
   - Every subsequent stage (`stage-07` through `stage-15`) is an unbroken, single-commit descendant of its immediate predecessor (`git merge-base --is-ancestor` is `True` across all stages). There are **zero missing predecessors, zero diverged branches, and zero cherry-pick duplications**.
2. **Root Cause of CI Stagnation on Stage Branches**:
   - The GitHub Actions workflow file `.github/workflows/android.yml` on `main` was configured with:
     ```yaml
     on:
       push:
         branches: [master, main]
         tags: ["v*"]
       pull_request:
     ```
   - When stage branches (`stage-02` through `stage-15`) were pushed to `origin`, GitHub Actions ignored them because they did not match `[master, main]`.
   - No PRs had been opened targeting `main` from the stage branches; the only 5 open PRs in the repository were automated Dependabot PRs targeting the old `audit/stage-00-intake` branch.
   - Hence, GitHub Actions executed solely on `main` (Run 43247265568, commit `acff7334`, SUCCESS) and never ran against `stage-*`.
3. **Module Task Alignment**:
   - `base/settings.gradle.kts` strictly includes `:app` and `:automix`.
   - Unit test tasks are `./gradlew :automix:test :app:testDebugUnitTest`.
   - Debug compilation task is `./gradlew :app:assembleDebug`.

---

## 2. Complete Stage Audit Matrix

| Stage | Remote Branch | Head SHA | Parent / Merge-Base SHA | Includes Prior Stage? | Unit Tests Status | APK Build | Artifact Name / Path | Blockers / Notes |
|---|---|---|---|---|---|---|---|---|
| **00 Intake** | `origin/audit/stage-00-intake` | `5dfc1aee683a89f8f37d4e66016cc3e176ba08d4` | `42e2bdf2` (upstream myspoti-2.4) | Baseline Intake | CI Run 43181531433 PASS | PASS | `myspoti-debug` | Legacy Myspoti codebase intake. |
| **02 Shell** | `origin/stage-02-local-only-shell` | `ce1a2d92a676e720ed9f8b8d06aa3d484271f1ba` | `5dfc1aee` (stage 00) | Yes (`--is-ancestor True`) | Local PASS; in main | PASS | `myspoti-debug` | Stripped Spotify/web modules to :app and :automix. Ancestor of main. |
| **03 Import** | `origin/stage-03-local-audio-import` | `124447ac46f875da09b86b9f11186185d87eb3c2` | `ce1a2d92` (stage 02) | Yes (`--is-ancestor True`) | Local PASS; in main | PASS | `myspoti-debug` | Local audio SAF import & decode verification. Ancestor of main. |
| **04 Database** | `origin/stage-04-database-and-backup` | `40ed262743f37237b83bb6901fa34d8ccd809e95` | `124447ac` (stage 03) | Yes (`--is-ancestor True`) | Local PASS; in main | PASS | `myspoti-debug` | SQLite schema, occurrences, transitions, backup/restore. Ancestor of main. |
| **05 Two-Deck** | `origin/stage-05-two-deck-playback` | `1752fca0d1a29f803841942b616ff6227962e200` | `40ed2627` (stage 04) | Yes (`--is-ancestor True`) | Local PASS; in main | PASS | `myspoti-debug` | Two-deck crossfade and sample clock state machine. Ancestor of main. |
| **Main Baseline** | `origin/main` | `acff733448f80f9c10e656ed2106688f41c1ddf8` | `43e1b45c` (contains stage 05) | Yes (direct descendant) | CI Run 43247265568 PASS (exit 0) | PASS | `myspoti-debug` (107 MB) | Verified integration baseline. |
| **06 Grid Cache** | `origin/stage-06-analysis-grid-cache` | `5c1b96e55d579b5dad82944bc381935708f491c9` | `acff7334` (main) | Yes (`--is-ancestor True`) | Verified Local: 252/252 PASS | Verified: PASS | `app-arm64-v8a-debug.apk` | No CI run because of push branch filter. Verified locally in audit. |
| **07 Transitions** | `origin/stage-07-saved-transitions` | `a73b0a8285e9a182738c77ccc14e947f7c296265` | `5c1b96e5` (stage 06) | Yes (`--is-ancestor True`) | Verified Local in St.07 | PASS | `app-arm64-v8a-debug.apk` | Pending sequential stage re-run. |
| **08 Set Builder UI**| `origin/stage-08-set-builder-ui` | `f871475e87bd590dbabf0bd66dd94336cd85c3a8` | `a73b0a82` (stage 07) | Yes (`--is-ancestor True`) | Verified Local in St.08 | PASS | `app-arm64-v8a-debug.apk` | Pending sequential stage re-run. |
| **09 DSP Preview** | `origin/stage-09-dsp-preview` | `5054102ae217b2ae508372e9f03fbbe6733176b9` | `f871475e` (stage 08) | Yes (`--is-ancestor True`) | Verified Local in St.09 | PASS | `app-arm64-v8a-debug.apk` | Pending sequential stage re-run. |
| **10 Playback** | `origin/stage-10-set-playback` | `ccf23f74a4d9b2a98b4c6090628bb265d6c90597` | `5054102a` (stage 09) | Yes (`--is-ancestor True`) | Verified Local in St.10 | PASS | `app-arm64-v8a-debug.apk` | Pending sequential stage re-run. |
| **11 Export** | `origin/stage-11-offline-export` | `d4adcd7bba57d5384ad88aed7be61b643a610c8b` | `ccf23f74` (stage 10) | Yes (`--is-ancestor True`) | Verified Local in St.11 | PASS | `app-arm64-v8a-debug.apk` | Pending sequential stage re-run. |
| **12 Stress** | `origin/stage-12-reliability-stress` | `fa46dc8a4c642286bf245efaff33b08cd25730d1` | `d4adcd7b` (stage 11) | Yes (`--is-ancestor True`) | Verified Local in St.12 | PASS | `app-arm64-v8a-debug.apk` | Pending sequential stage re-run. |
| **13 A11y Polish** | `origin/stage-13-accessibility-polish` | `c109481eb786bc287f465b110dfdd43573494876` | `fa46dc8a` (stage 12) | Yes (`--is-ancestor True`) | Verified Local in St.13 | PASS | `app-arm64-v8a-debug.apk` | Pending sequential stage re-run. |
| **14 CI / Security**| `origin/stage-14-ci-legal-security` | `6be98304b8717e065de12a4aa88e11a1ae34fcff` | `c109481e` (stage 13) | Yes (`--is-ancestor True`) | Verified: 329/329 PASS | Verified: PASS | Release candidate APKs + Checksums | Permissions scrubbed; release validation script added. |
| **15 Beta Freeze** | `origin/stage-15-beta-trial-freeze` | `11caea2d5ddff8c384faef67a4d1558a87110450` | `6be98304` (stage 14) | Yes (`--is-ancestor True`) | Verified: 339/339 PASS | Verified: PASS | `SetFlow-Beta-0.15.0-beta.1-*.apk` | Beta triage register, 40-item acceptance matrix report. |

---

## 3. Non-Destructive Backup Manifest

To guarantee absolute data safety before modifying or testing any configuration, immutable Git backup refs were generated for all branch heads:

| Backup Reference | Target Commit SHA | Stage Name |
|---|---|---|
| `refs/recovery-backup/main` | `acff733448f80f9c10e656ed2106688f41c1ddf8` | Verified Main Baseline |
| `refs/recovery-backup/stage-00-intake` | `5dfc1aee683a89f8f37d4e66016cc3e176ba08d4` | Stage 00 Intake |
| `refs/recovery-backup/stage-02-local-only-shell` | `ce1a2d92a676e720ed9f8b8d06aa3d484271f1ba` | Stage 02 Local Only Shell |
| `refs/recovery-backup/stage-03-local-audio-import` | `124447ac46f875da09b86b9f11186185d87eb3c2` | Stage 03 Local Audio Import |
| `refs/recovery-backup/stage-04-database-and-backup` | `40ed262743f37237b83bb6901fa34d8ccd809e95` | Stage 04 Database & Backup |
| `refs/recovery-backup/stage-05-two-deck-playback` | `1752fca0d1a29f803841942b616ff6227962e200` | Stage 05 Two Deck Playback |
| `refs/recovery-backup/stage-06-analysis-grid-cache` | `5c1b96e55d579b5dad82944bc381935708f491c9` | Stage 06 Analysis Grid Cache |
| `refs/recovery-backup/stage-07-saved-transitions` | `a73b0a8285e9a182738c77ccc14e947f7c296265` | Stage 07 Saved Transitions |
| `refs/recovery-backup/stage-08-set-builder-ui` | `f871475e87bd590dbabf0bd66dd94336cd85c3a8` | Stage 08 Set Builder UI |
| `refs/recovery-backup/stage-09-dsp-preview` | `5054102ae217b2ae508372e9f03fbbe6733176b9` | Stage 09 DSP Preview |
| `refs/recovery-backup/stage-10-set-playback` | `ccf23f74a4d9b2a98b4c6090628bb265d6c90597` | Stage 10 Set Playback |
| `refs/recovery-backup/stage-11-offline-export` | `d4adcd7bba57d5384ad88aed7be61b643a610c8b` | Stage 11 Offline Export |
| `refs/recovery-backup/stage-12-reliability-stress` | `fa46dc8a4c642286bf245efaff33b08cd25730d1` | Stage 12 Reliability Stress |
| `refs/recovery-backup/stage-13-accessibility-polish` | `c109481eb786bc287f465b110dfdd43573494876` | Stage 13 Accessibility Polish |
| `refs/recovery-backup/stage-14-ci-legal-security` | `6be98304b8717e065de12a4aa88e11a1ae34fcff` | Stage 14 CI, Legal & Security |
| `refs/recovery-backup/stage-15-beta-trial-freeze` | `11caea2d5ddff8c384faef67a4d1558a87110450` | Stage 15 Beta Freeze |

---

## 4. Verification Evidence for Stage 06

Per **FIRST ACTION NOW**, `stage-06-analysis-grid-cache` (`5c1b96e5`) was checked out and directly verified:
- **Command**: `./gradlew :automix:test :app:testDebugUnitTest :app:assembleDebug --stacktrace --console=plain`
- **Result**: `BUILD SUCCESSFUL in 16s` (Exit code: 0)
- **Unit Test Counts**:
  - `:automix`: 171 passed, 0 failures, 0 errors, 0 skipped.
  - `:app`: 81 passed, 0 failures, 0 errors, 0 skipped.
  - **Total**: 252 tests passed.
- **Debug APK Compilation**:
  - Output directory: `base/app/build/outputs/apk/debug/`
  - Produced: `app-arm64-v8a-debug.apk`, `app-armeabi-v7a-debug.apk`, `app-universal-debug.apk`.

---

## 5. CI Configuration Repair Plan (Phase B)

On branch `ci/stage-recovery`, `.github/workflows/android.yml` is updated with:
1. **Trigger Expansion**:
   ```yaml
   on:
     push:
       branches:
         - main
         - master
         - 'stage-*'
         - 'audit/*'
         - 'ci/*'
       tags: ["v*"]
     pull_request:
       branches:
         - main
         - master
         - 'stage-*'
         - 'ci/*'
     workflow_dispatch:
   ```
2. **Explicit Task Separation**:
   - `Unit tests`: `./gradlew :automix:test :app:testDebugUnitTest --stacktrace --console=plain`
   - `Debug APK`: `./gradlew :app:assembleDebug --stacktrace --console=plain`
3. **SetFlow Specific Artifact Naming**:
   - Computes sanitized `${REF_SLUG}-${SHA_SHORT}`.
   - Uploads `base/app/build/outputs/apk/debug/*.apk` with retention.
   - Uploads test reports on failure (`base/**/build/reports/tests/`).
4. **Execution Status Summary**:
   - Writes Markdown summary to `$GITHUB_STEP_SUMMARY` detailing unit tests, APK compilation, and marking device runtime smoke tests as `NOT RUN (Unit test matrix only in CI runner)`.
5. **Release Protection**:
   - Release job executes only on `startsWith(github.ref, 'refs/tags/v')`.
   - Never triggers on branch pushes or pull requests.
