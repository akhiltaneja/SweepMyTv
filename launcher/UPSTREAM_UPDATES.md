# One Sweep TV release

**System → Updates** shows the installed Sweep TV version and checks public
Sweep TV release metadata on demand. Release notes and included components
are under **System → About**. Play Store updates for other apps remain separate
from launcher maintenance.

Upstream checks, source review, integration and tests happen on the maintenance
computer. Applicable changes ship together as one signed Sweep TV APK and one
release note. Installation of managed builds continues from the maintenance
computer. The TV checks only Sweep TV's own GitHub releases.

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

Talkies v1.3.0, commit `7559a35fdc8eeb973e60fd8e5e992a5994dd858a`, was
reviewed on 13 November 2026. Sweep TV adapted the single playback-state read
from `25706c5934c7` and the package-change tracking from `17f389802f34`.
The reconnect portion of `280c5ba5916a` adds an artwork reread to Sweep TV's
existing startup retries. The upstream UI, remote behavior and device-specific
settings are outside these adaptations. Talkies source remains credited under MIT.
The Sweep TV AQI update (1.3.0-sweep.41) rechecked this same stable Talkies release
and the same CleanYourTV v3.4.0 lock. The settings update (1.3.0-sweep.42)
and the home header update (1.3.0-sweep.43) confirmed the same stable releases.
The screensaver update (1.3.0-sweep.44) rechecked those same releases.
These changes are local to Sweep TV.
The All apps shortcut (1.3.0-sweep.45) reviewed Talkies v1.3.0's Home gesture
at the pinned commit above. Sweep TV uses event timestamps with its existing
Home mapping and opens its own All apps popup. CleanYourTV remains at v3.4.0.
The icon grid and header widget updates (1.3.0-sweep.46 and 1.3.0-sweep.47)
rechecked the same pinned releases. The shelf caption update (1.3.0-sweep.48)
and the compact header controls update (1.3.0-sweep.49) use the same reviewed upstream versions.
The app drawer updates (1.3.0-sweep.50 and 1.3.0-sweep.51) keep those upstream versions.
The branding update (1.3.0-sweep.52) checked the same releases and changed only
branding comments in ServiceRepair. The local hash was refreshed. The backup
format is unchanged. To restore an older exported file, place it under the new
name `sweep-tv-backup.json` in the app's files directory.

The Home transition update (1.3.0-sweep.53) retains the same pinned upstream
versions and changes Sweep TV’s gesture dispatch.

The appearance update (1.3.0-sweep.54) rechecked both stable releases at
the same pinned commits. Its theme picker and colour palettes are Sweep TV
changes. It imports no theme assets or cleanup rules. The theme icon update
(1.3.0-sweep.55) rechecked the same pinned releases and adds original icons.
The preview update (1.3.0-sweep.56) retains those pins and uses the existing
Sweep TV backdrop renderer in the theme picker. Sweep TV 1.3.0-sweep.59
retains both pins and adds Sweep TV settings and artwork colour extraction.
Its screen-saver chooser discovers installed services and links to Aerial Views
as a separate app. No source or media from Aerial Views or Plume is bundled.
Sweep TV 1.3.0-sweep.60 rechecks the same pins and groups related settings.
Sweep TV 1.3.0-sweep.61 retains those pins. Aerial Views 1.8.5 was reviewed
for its quality enums and user-confirmed settings import. Sweep TV sends only
quality preferences using that import format. Sweep TV 1.3.0-sweep.62 retains
both upstream pins and consolidates the Sweep TV settings interface.
Sweep TV 1.3.0-sweep.63 rechecks those pins and expands the market widget.
Sweep TV 1.3.0-sweep.64 rechecks CleanYourTV v3.4.0 at
`fc148d5a9707b30e8f49f489625947421740dc55` and Talkies v1.3.0 at
`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`. The settings layout, dock
membership, Continue Watching app selection, and remote setup UI are Sweep TV
changes. No upstream changes apply to this release.

Sweep TV 1.3.0-sweep.65 rechecks the same pinned releases. The Dock settings
filter is local to Sweep TV and changes neither upstream adaptation.
Sweep TV 1.3.0-sweep.66 rechecks those pins and makes the Dock picker on demand.
Sweep TV 1.3.0-sweep.67 retains those pins and moves the picker entry to the end of Dock settings.
Sweep TV 1.3.0-sweep.68 rechecks the same pins and puts quick controls in the existing anchored menu.
Sweep TV 1.3.0-sweep.69 retains those pins. Live theme previews, the settings layout,
and Bing UHD selection are local Sweep TV changes.
Sweep TV 1.3.0-sweep.70 rechecks the same pins and updates Sweep TV content
settings, Apple TV resume caching and playback logging.
Sweep TV 1.3.0-sweep.71 rechecks CleanYourTV v3.4.0 at
`fc148d5a9707b30e8f49f489625947421740dc55` and Talkies v1.3.0 at
`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`. The Settings focus and
Dock layout changes are local to Sweep TV. The reviewed adaptations are unchanged.
Sweep TV 1.3.0-sweep.72 rechecks CleanYourTV v3.4.0 at
`fc148d5a9707b30e8f49f489625947421740dc55` and Talkies v1.3.0 at
`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`. The selected styling and aligned
Dock preview are local to Sweep TV. The reviewed adaptations are unchanged.


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

## Sweep TV 1.3.0-sweep.73

CleanYourTV v3.5.0 was reviewed at `d11bd68c9ac45b2fb38480941586d17930137e9d`.
Its ServiceRepair change retries recovery when the accessibility master switch
reads zero and the helper is still listed. Sweep TV already checks for and rebinds
listed but stopped helpers on Home. Its recovery behavior and existing remote
mappings are retained. Talkies v1.3.0 remains at
`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`. The Dock controls, split Settings
layout, and quote rotation are Sweep TV changes.

## Sweep TV 1.3.0-sweep.74

CleanYourTV v3.5.0 at `d11bd68c9ac45b2fb38480941586d17930137e9d` passed the online
shared-component check. Talkies v1.3.0 at `7559a35fdc8eeb973e60fd8e5e992a5994dd858a`
is still the latest stable release. Both are the pinned sources reviewed in .73.
This release changes Sweep TV's content settings, published channel editor, and
Home focus rendering using the performance measurements from the Redmi.

## Sweep TV 1.3.0-sweep.75

CleanYourTV v3.5.0 at `d11bd68c9ac45b2fb38480941586d17930137e9d` passed the online
shared-component check. Talkies v1.3.0 at `7559a35fdc8eeb973e60fd8e5e992a5994dd858a`
remains the latest stable release. The pinned scope reviewed in .73 remains
current. Dock placement, icon masks, and the Dock menu are integration changes
in Sweep TV.

## Sweep TV 1.3.0-sweep.76

Verified CleanYourTV v3.5.0 at `d11bd68c9ac45b2fb38480941586d17930137e9d` and
Talkies v1.3.0 at `7559a35fdc8eeb973e60fd8e5e992a5994dd858a` against the previously
reviewed stable versions. The changes are in Sweep TV's settings screens, Home
wallpaper rotation, and the existing phone dashboard.

## Sweep TV 1.3.0-sweep.77

The latest stable releases remain CleanYourTV v3.5.0 at
`d11bd68c9ac45b2fb38480941586d17930137e9d` and Talkies v1.3.0 at
`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`. The previously reviewed shared
components remain pinned. This release changes Sweep TV's Continue Watching
card artwork and catalog poster retrieval.

## Sweep TV 1.3.0-sweep.78

Checked the existing stable CleanYourTV v3.5.0 pin
(`d11bd68c9ac45b2fb38480941586d17930137e9d`) and Talkies v1.3.0 pin
(`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). This release changes the theme
picker in Sweep TV to show the active theme first.

## Sweep TV 1.3.0-sweep.79

CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`) passed the
online shared-component check. Talkies v1.3.0
(`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`) remains the latest stable
release. The previously reviewed source scope remains current. Sweep TV
now shows provider badges on app-published and Spotify recommendation cards,
unifies settings selection marks, and groups controls under Aerial Views.

## Sweep TV 1.3.0-sweep.80

CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`) passed the online
shared-component check. Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`)
remains the latest stable release. The shared-source review scope carries forward.
This release changes Sweep TV's Home card title routing.

## Sweep TV 1.3.0-sweep.81

CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`) passed the online
shared-component check. Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`)
remains the latest stable release. The shared-source review scope carries forward.
Sweep TV adds a title details dialog and limits thumbnail badges to mixed-provider rows.

## Sweep TV 1.3.0-sweep.82

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV hides empty title shelves
and keeps app-published recommendations available without a catalog key.

## Sweep TV 1.3.0-sweep.83

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV removes the inherited
startup permissions prompt and makes Watch Next access an explicit setup action.

## Sweep TV 1.3.0-sweep.84

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV updates Netflix and Prime
Video title routing and checks whether the TV app handles a title link.

## Sweep TV 1.3.0-sweep.85

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV updates AQI card sizing
and background tint in the rotating header.

## Sweep TV 1.3.0-sweep.86

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV groups and simplifies the
Continue Watching settings.

## Sweep TV 1.3.0-sweep.87

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV adds episode context to
Continue Watching and combines its app choices in one grid.

## Sweep TV 1.3.0-sweep.88

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV removes the TV Profiles
page and refreshes the remote controls warning while Android reconnects the helper.

## Sweep TV 1.3.0-sweep.89

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV simplifies its weather location settings.

## Sweep TV 1.3.0-sweep.90

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV moves TMDB setup to Content
settings and adds a switch for choosing whether to use a saved key.

## Sweep TV 1.3.0-sweep.91

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV combines market rotation
and currency controls on one card per asset.

## Sweep TV 1.3.0-sweep.92

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV redesigns the phone
customization QR shortcut and panel.

## Sweep TV 1.3.0-sweep.93

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV simplifies Content settings
and renames its main recommendation row.

## Sweep TV 1.3.0-sweep.94

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV removes the language info button.

## Sweep TV 1.3.0-sweep.95

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV groups compact market cards
alongside their category names. When the REST API hits its quota, the release
check uses GitHub's official latest release link and verifies the remote tag and
pinned source hashes online.

## Sweep TV 1.3.0-sweep.96

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV simplifies the Header widgets
switches and adds a dedicated News sources screen.

## Sweep TV 1.3.0-sweep.97

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV updates the settings sidebar
and phone QR panel.

## Sweep TV 1.3.0-sweep.98

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV simplifies device metrics
and the greeting and removes repeated headings.

## Sweep TV 1.3.0-sweep.99

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV simplifies screen saver
selection and groups Aerial Views controls with the active choice.

## Sweep TV 1.3.0-sweep.100

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV reduces the size of its
main settings popup.

## Sweep TV 1.3.0-sweep.101

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV simplifies System settings,
adds sidebar shortcuts and checks published releases inline.

## Sweep TV 1.3.0-sweep.102

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV constrains publisher title
links to their provider app, removes unreachable menu code, and reuses the
wallpaper drawing bounds.

## Sweep TV 1.3.0-sweep.103

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV excludes YouTube by default
in Continue Watching, reserves Watch Next slots for different apps, and adds a
shortcut to optional Notification access in Content settings.

## Sweep TV 1.3.0-sweep.104

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV opens Apps settings with
directions when the TV does not expose a direct Notification access shortcut.

## Sweep TV 1.3.0-sweep.105

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV adds a list of installed
Home apps to open, uses regular-weight sidebar labels, and simplifies the
Continue Watching caption.

## Sweep TV 1.3.0-sweep.106

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The existing
shared-source review scope carries forward. Sweep TV makes the Watch Next
permission visible in Content settings until granted and explains Android’s
denial when a permission prompt is unavailable.

## Sweep TV 1.3.0-sweep.107

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). Adapted the
application-context portion of CleanYourTV Home memory fix
[#3804](https://github.com/Gathin23/cleanyourtv/pull/3804) (`a4311e8`).
Sweep TV has no corresponding decor-view listener. The ServiceRepair source
and lock entry remain the same. No additional Talkies source was ported.

Quick Settings uses the existing Matinee-derived speed test. Return to original
Home follows Free My TV’s recovery idea using the TV’s settings. Settings shortcut
checks follow GlassLauncher’s compatibility approach. The HDMI recognition tests
follow Luma’s device-fixture approach. These changes do not import any additional
cleanup rules, package changes or remote mappings.

## Sweep TV 1.3.0-sweep.108

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). Reviewed the
pinned wallpaper menu and public ESPN scoreboards. Sweep TV retains its local
picture and folder picker, exposes the existing Picture of the day fallback,
and implements its own compact scoreboard with existing Sweep TV card and
streaming support. Talkies’ football league editor, NBA and NFL were outside
this change. The reviewed CleanYourTV source is the same; the local Home hash
reflects the settings changes.

## Sweep TV 1.3.0-sweep.109

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
This local visual follow-up retains the same upstream adaptations.

## Sweep TV 1.3.0-sweep.110

Verified CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
Restored Sweep TV’s cycling Dock controls and removed the Home score strip.
Sports cards and these pinned upstream adaptations are retained.

## Sweep TV 1.3.0-sweep.111

Verified CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
Sweep TV reorganizes content preferences and Home widgets. The pinned upstream
adaptations and existing recommendation providers carry forward.

## Sweep TV 1.3.0-sweep.112

Rechecked the same stable CleanYourTV and Talkies source pins from .111.
The Home Page Widgets panel now marks rows that open another settings page.

## Sweep TV 1.3.0-sweep.113

Verified CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
Quick Settings links to the existing Home app picker and starts the active
screen saver on supported TVs. The pinned upstream adaptations carry forward.

## Sweep TV 1.3.0-sweep.114

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
The stable source pins and reviewed adaptations carry forward. Sweep TV fixes
the empty sports row crash that stopped Home and its accessibility helper,
removes the duplicate Watch Next row, and moves recommendation credits to About.

## Sweep TV 1.3.0-sweep.115

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
The pinned adaptations carry forward. Sweep TV grows the Dock when adding an
app and shows capacity in Edit Dock apps. The optional editorial row has no
Rotten Tomatoes snapshot bundled in this build.

## Sweep TV 1.3.0-sweep.116

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
The pinned adaptations carry forward. Sweep TV marks the active recommendation
engine with a tick and indents the key actions under TMDB.

## Sweep TV 1.3.0-sweep.117

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
The pinned adaptations carry forward. Header widgets opens from the first row
of Home Page Widgets.

## Sweep TV 1.3.0-sweep.118

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
The pinned adaptations carry forward. Sweep TV redirects the specific PatchWall
Home activities when the owner has hidden PatchWall in Sweep TV.

## Sweep TV 1.3.0-sweep.119

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
The pinned adaptations carry forward. Sweep TV simplifies shortcut setup,
adds a working route to TV settings when the Accessibility shortcut is
unavailable, and opens Home automatically after wake.

## Sweep TV 1.3.0-sweep.120

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
Shortcut setup accepts keys delivered directly to the dialog as well as keys
sent to the Home button helper.

## Sweep TV 1.3.0-sweep.121

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
The pinned adaptations carry forward. Sweep TV keeps glow on in App colours
and adds Black glow with a near-black backdrop and softer colour.

## Sweep TV 1.3.0-sweep.122

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
The pinned adaptations carry forward. Sweep TV prioritizes installed entertainment
apps in the default Dock, uses Play Store if there is room, sizes tiles to the
visible app count, and crops the packaged Sony LIV banner in rectangular Dock tiles.

## Sweep TV 1.3.0-sweep.123

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
The pinned adaptations carry forward. Sweep TV updates Continue Watching
metadata matching and recommendation captions, adds championship styling
to Formula 1, and replaces the sports score strip cards with match centers.

## Sweep TV 1.3.0-sweep.124

The same stable CleanYourTV v3.5.0 and Talkies v1.3.0 pins carry forward.
Sweep TV uses Watch Next media types, canonical genres and release date hints
to display or match title metadata when the initial catalog search is incomplete.

## Sweep TV 1.3.0-sweep.125

CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`) and
Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`) carry forward.
Sweep TV adjusts typography and score sizing based on the Redmi match-center check.

## Sweep TV 1.3.0-sweep.126

CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`) and
Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`) carry forward.
Sweep TV rotates the Home tabs while idle and preserves the active Continue
Watching selection through playback and card actions.

## Sweep TV 1.3.0-sweep.127

CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`) and
Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`) carry forward.
Sweep TV adds an optional Dock button for All Apps and moves the existing
double-press Home setting to Remote buttons.

## Sweep TV 1.3.0-sweep.128

CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`) and
Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`) carry forward.
Sweep TV moves Home app to the bottom of Quick Settings and marks the
launcher that handles Home separately from the Android default.

## Sweep TV 1.3.0-sweep.129

CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`) and
Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`) carry forward.
Sweep TV adds hourly theme rotation and palette variations, and decodes
wallpapers off the UI thread. The existing wallpaper and palette choices
are retained for manual use.

## Sweep TV 1.3.0-sweep.130

CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`) and
Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`) carry forward.
Sweep TV adds explanatory copy above the content language grid.

## Sweep TV 1.3.0-sweep.131

CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`) and
Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`) carry forward.
Sweep TV redesigns Cricket as a fixed, three-card scoreboard for ongoing and
upcoming matches, and removes Football from Home and sports settings.

## Sweep TV 1.3.0-sweep.132

CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`) and
Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`) carry forward.
Sweep TV corrects provisional live flags and strengthens Cricket scorecard typography.

## Sweep TV 1.3.0-sweep.133

Checked stable CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`) against
the existing shared-component review. No upstream baseline or adaptation changes.
Sweep TV adds bundled keyless catalog support and original-language enrichment.

## Sweep TV 1.3.0-sweep.134

Rechecked stable CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The pinned
shared components are unchanged. Sweep TV expands its keyless catalog and
fixes short and duplicate recommendation rows.

## Sweep TV 1.3.0-sweep.135

Rechecked stable CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The pinned
shared components are unchanged. This private candidate tests a supplemental
English title in its provider row and leaves the regional Trending charts in place.

## Sweep TV 1.3.0-sweep.136

Rechecked stable CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The pinned
shared components are unchanged. The managed catalog uses original poster
variants and keeps ratings separate in the title details.

## Sweep TV 1.3.0-sweep.137

Online preflight verified CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`).
Talkies stable remains v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`), the
previously reviewed pin. The shared maintenance baseline carries forward. This
candidate changes catalog selection, offline discovery cards and private source comparison.

## Sweep TV 1.3.0-sweep.138

Retains the reviewed CleanYourTV v3.5.0 and Talkies v1.3.0 commits above.
The Redmi comparison revealed overlapping app logos in offline cards.
This candidate gives offline discovery and missing Continue Watching
artwork a readable title card.

The lock's local HomeActivity checksum now covers the catalog changes. The
adapted application-context network lookup and both upstream pins are unchanged.

## Sweep TV 1.3.0-sweep.139

Rechecked the same stable CleanYourTV v3.5.0
(`d11bd68c9ac45b2fb38480941586d17930137e9d`) and Talkies v1.3.0
(`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`) releases against the previous
review. No shared source changes. The managed catalog records and withholds
incomplete listings, and the launcher waits for a usable portrait.

## Startup performance candidate

The stable releases reviewed for this candidate are CleanYourTV v3.5.0
(`d11bd68c9ac45b2fb38480941586d17930137e9d`) and Talkies v1.3.0
(`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`). The reviewed shared source and
the adapted application-context network lookup match the existing pins.
Sweep TV now prepares cached home rows before background refreshes and
reports managed recommendation artwork as portraits become available.

## Sweep TV 1.3.0-sweep.140 private comparison

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
The pinned shared source is unchanged. This candidate updates Home startup
artwork, the local Watch Next cache, and the private StreamRadar and TMDB modes.

## Sweep TV 1.3.0-sweep.141 private comparison

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
The pinned upstream source is unchanged. The local Home integration adds
same-provider discovery fallback to the private comparison modes and raises
the provider row capacity to 12.

## Sweep TV 1.3.0-sweep.142 private comparison

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
The pinned upstream source is unchanged. This local Home change overlays
existing IMDb ratings on title cards.

## Sweep TV 1.3.0-sweep.143 private comparison

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
The pinned upstream source is unchanged. The Home shelf now prefers managed
title posters over unclassified background images.

## Sweep TV 1.3.0-sweep.144 private comparison

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
The pinned upstream source is unchanged. The local change reduces the visual
weight and removes the source name from the Home rating badge.

## Sweep TV 1.3.0-sweep.145 private comparison

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
The pinned upstream source is unchanged. The local Home recommendation row
rotates its expanded catalogue pool every six hours. When testing another
engine, the row can also draw from the verified Sweep TV catalogue.
Rectangular Sony LIV Dock tiles use the higher-resolution logo supplied by
the installed Sony LIV app when available.

## Sweep TV 1.3.0-sweep.146 private comparison

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
The pinned upstream source is unchanged. The default catalogue URL now uses
Cloudflare HTTPS hosting, populated by the existing private GitHub workflow.

## Sweep TV 1.3.0-sweep.147 private comparison

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
The pinned upstream source is unchanged. Local startup changes restore enabled
sports sections before recommendation preparation, coalesce pending row work
without discarding valid completed rows, and reduce repeated artwork metadata
work. Cricket score snapshots are stored locally and labelled Cached on restore.

## Sweep TV 1.3.0-sweep.148 private comparison

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`) online on
19 November 2026. The pinned upstream source remains unchanged. These local
Home and CardArtwork changes decode only visible title cards at normal worker
priority, release scrolled-off card images, and store display-sized thumbnails
in a 32 MiB / 192-file internal cache. Original artwork is retained. The selected
top tab rebinds arriving artwork without replacing its cards or moving focus.

## Sweep TV 1.3.0-sweep.149 private comparison

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`) online on
19 November 2026. Their reviewed source pins remain unchanged. The local Home
navigation change wraps title carousels in both directions, including the top
content shelf, discovery rows and app-published horizontal rows. Boundary
crossings reuse the existing cards and snap the horizontal offset to avoid a
long reverse pan. Sports mosaics, the Dock and app grid keep their current
navigation. Existing install-count code is disabled in this private comparison
build; this update adds no reporting or permissions.

## Sweep TV 1.3.0-sweep.150 distribution candidate

Rechecked CleanYourTV v3.5.0 (`d11bd68c9ac45b2fb38480941586d17930137e9d`)
and Talkies v1.3.0 (`7559a35fdc8eeb973e60fd8e5e992a5994dd858a`).
Finish setup adds separate Formula 1 and Cricket switches, both off by default.
This is a Sweep TV settings change. The local HomeActivity hash was updated
after reviewing the diff. The pinned upstream adaptation is unchanged.

## Sweep TV 1.3.0-sweep.151 — continuous title reels

Rechecked the official stable CleanYourTV v3.5.0 commit
`d11bd68c9ac45b2fb38480941586d17930137e9d` and Talkies v1.3.0 commit
`7559a35fdc8eeb973e60fd8e5e992a5994dd858a` online on 19 November 2026.
No newer stable source changes required integration. The shared HomeActivity
network-manager adaptation is unchanged; its local hash records the reviewed
Sweep TV navigation diff. Title rows now use a bounded circular layout instead
of a finite strip with end-to-start snapping. Short rows reuse a fixed number
of repeated card views to fill the viewport; catalogue contents and artwork
cache bounds do not grow during navigation. Sports mosaics, Dock, app grid,
permissions, services and remote mappings retain their existing behavior.
This is Sweep TV carousel work, not a full upstream merge.
