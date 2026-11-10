# Launcher performance, build 50

The TV benchmark uses the physical Redmi/MiTV-MOOQ1 (Android 10, 1080p UI) with the same arrows-only sequence before and after. It never opens a title or changes Android display settings, disabled apps, permissions, launcher defaults, or home-section choices.

## Changes

- `CardArtwork` decodes photos to the actual card's pixel dimensions, including focus enlargement. It reads bounds first, subsamples before allocating, and never enlarges/sharpens the source. Downloaded source files are unchanged. Portrait fallback artwork keeps its existing fit behavior.
- An 8 MiB decoded-image cache reuses artwork during navigation. Decode work has a dedicated background worker, so provider queries, image downloads and update checks cannot queue ahead of visible card decoding. Replaced rows invalidate old image requests, and Activity destruction closes the worker.
- `PicksRow` measures only children whose widths change and lays them out locally during its spring, instead of requesting a whole-page layout every animation frame. Progress bars use a width-independent fill transform. Rebuilding or detaching a row cancels its old animation callback.
- The unresolved physical Volume hold issue has limited diagnostics for Volume Up/Down events only: action, scan code, repeat count and delivery delay. No other keys, screen content, credentials or text are logged. No new speculative volume mapping is applied in this build.

## Verification

`dist/performance-validation.json` holds the measured before/after results and device-preservation checks. `research/performance-2026-11-03/` contains private raw frame and memory captures. The warm-navigation baseline on build 49 was 97 janky frames out of 1779 (5.45%), with a 17 ms 95th percentile. This is distinct from the older mixed startup frame history (21.39%); that older sample is not a controlled cold-start comparison.

Frame timing and memory vary with other TV apps, provider publication and GPU caches. The benchmark measures the launcher's UI, not streaming playback or the whole TV firmware. A physical volume hold still needs a matching remote event sample; injected keys do not prove that path.

Reference: [Android bitmap loading guidance](https://developer.android.com/topic/performance/graphics/load-bitmap).

## F1 drawing and warm-resume work — builds 69–70

- F1 photos, logos and circuit assets load on one background-priority worker.
  Cards hold prepared images, so drawing and the countdown tick perform no asset
  I/O or bitmap decoding. Images are subsampled before allocation and sized for
  their displayed role: small avatars, circuit outline, car image or leader photo.
  Avatar allocations cover the visible face crop of the tall body-portrait assets,
  and leader photos retain their original display coverage. Android's
  `prepareToDraw()` hint runs after decoding on the worker. Original bundled
  source assets and the existing layout remain unchanged.
- The shared F1 bitmap cache remains bounded at 4 MiB. Detached cards cancel queued
  work, drop their image references and reject callbacks from older attachments.
  Missing bundled keys are remembered for the process rather than reopened during
  redraws. No network or new library is involved in this rendering change.
- Build 70 preserves full theme setup on the first resume. Later Home returns
  within the same cached sky-palette minute retain theme objects
  and surfaces. A new minute, changed weather sample/timezone, or appearance choice
  still refreshes the theme. Existing Home-return row deferral stays in place.
- Regression checks cover real asset preparation and dimensions, repeated draws
  without cache misses, detach/reattach callback isolation, unchanged-theme reuse,
  changed solar colours, row retention and navigation.

The physical-TV comparison protocol and raw captures are in
`research/performance-2026-11-10/`. The final comparison belongs to
`dist/performance-pass-validation.json` once the candidate has passed device QA.
No Android settings, grants, packages, cleanup state or remote mappings are changed.
The physical volume-hold investigation remains paused.

References: [Bitmap threading](https://developer.android.com/develop/ui/views/graphics)
[display-sized bitmap decoding](https://developer.android.com/topic/performance/graphics/load-bitmap),
and [early texture preparation](https://developer.android.com/topic/performance/issues/render).

### Final physical-TV result (build 70)

The completed candidate uses about 13.34 MiB less total launcher PSS than the
freshly reinstalled build-68 control after the same route (223,671 vs 237,336 KiB).
This is a post-route sample without forcing GC; it is not whole-device free RAM.
Final scrolling trials reported 10.55% and 0.71% janky frames (18 and 13 ms p95),
versus 11.29% and 2.61% (19 and 14 ms p95) for the fresh previous-build control.
The longer-warmed initial baseline was 6.11% and 2.74%, illustrating variability.
Home-return frame results were mixed and do not establish a consistent gain.
Do not market this as a universal FPS improvement or claim all stutter is fixed.

The final size/crop/render checks ran six focused Android tests plus ten release
checks, with release build/lint passing. The preceding build-70 startup/theme
candidate passed twelve relevant tests before the face-crop sizing correction;
build 69 passed twenty-one relevant tests earlier. Actual-TV checks confirmed
sharp driver faces, logos, navigation, saved resume/progress, and original title
artwork. App/grant/accessibility/key preservation checks passed. No Android
settings, ART compilation mode or paused volume behavior were changed.
