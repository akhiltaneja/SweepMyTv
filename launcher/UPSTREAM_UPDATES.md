# One Sweep TV release

The TV has one **Sweep TV updates** screen with the installed version, What's new,
and Included components. There is no separate CleanYourTV updater, daily dependency
check, notification or review menu. Old checker preferences are ignored. Only
bundled information from the installed APK is displayed. Play Store updates for
other installed apps remain separate from launcher dependency maintenance.

Upstream checks, source review, integration and tests happen on the maintenance
computer. Applicable changes ship together as one signed Sweep TV APK and one
release note. There is currently no published Sweep TV download feed: this build
is installed from the maintenance computer. The old Talkies release feed and
installer have been removed; it must never update Sweep TV from another project.

`app/src/main/assets/sweep-release.json` is the bundled release record. Keep its
version and version code equal to `app/build.gradle`. Its notes describe the
actual changes in this release. Included components retain original source and
license credits; do not invent upstream versions for unpinned adaptations.

Before each TV release:

1. Check official upstream releases here. For each directly adapted component,
   review changes against its pinned source. Add further versioned dependencies
   to the review inventory and release notes as they are integrated.
2. Port applicable maintenance changes while preserving Sweep TV's interface,
   profiles, settings and artwork behavior. Keep licenses and rollback artifacts.
3. Update source locks and hashes only after review. Record the actual scope:
   newly merged changes, unchanged reviewed components, and deferred changes.
4. Update the single Sweep TV version and bundled release notes. Run the online
   upstream gate, relevant Android tests, release build and lint. Package the APK,
   exact source, release JSON/notes, verification report and checksums together.
5. Install the signed Sweep TV bundle and verify actual-TV behavior, package state,
   accessibility, grants and the TMDB key. Avoid interrupting playback.
6. New cleanup rules, permissions, TV services and remote mappings still require
   approval. The user's paused physical volume-hold work stays paused.

The CleanYourTV lock is reviewed through v3.4.0, commit
`fc148d5a9707b30e8f49f489625947421740dc55` (10 November 2026). ServiceRepair is
byte-identical at v3.2.0 and v3.4.0; the existing Sweep TV adaptation is retained.
This is a shared-component review, not an import of all upstream launcher themes,
addons or cleanup rules. The bundled component notes state that scope explicitly.

`build-preview.sh` runs ten gate tests and `check-upstream.py` before Gradle.
The gate verifies the official latest stable release and tag commit, upstream and
local source hashes, the bundled component record, and the APK version. It fails
closed when the lock or release record is stale. Its report is
`build/upstream-verification.json`. Offline diagnostics never count as a verified
TV release. The gate never executes an upstream APK or installer.

Use `python3 launcher/package-release.py` after tests/build/lint. It reruns the
release-gate tests and fresh online source verification, checks the APK's embedded
release record and signature, then writes `dist/releases/<version>/` and a single
`sweep-tv-<version>.zip`. The local release pointer is `dist/latest-release.json`.
After device QA, rerun with `--tv-validation <validation.json>` to include the
physical-TV evidence in the same bundle. A failed or missing TV check must never
be described as an installed/verified release. Packaging does not publish or
install anything by itself.
