# Home-return navigation responsiveness (build 51)

The reported problem was a fast return from YouTube to Sweep TV followed by delayed first Up/Down inputs. The earlier build-50 warm-scrolling benchmark did not cover this return path.

Three controlled YouTube-to-Home returns on build 50 reproduced main-queue delays of 332 ms, 343 ms and 1713 ms. Timing was reset after YouTube opened, so these samples exclude leaving-Home frames. No title was selected or played during the test.

Changes:

- Build F1 and recommendation row models and content signatures on a dedicated background-priority worker. Coalesce superseded requests and apply only the latest result.
- Keep existing rows and decoded artwork when their complete content signatures are unchanged. A change to one row no longer destroys every other row. Signatures include resume progress, images, F1 classification values and driver/team artwork.
- Hold nonessential row replacement during the first 1.2 seconds of a warm resume and while arrows are active. Once navigation rests for 650 ms, apply the latest result while retaining the selected row/card. Cold-start rows can still appear immediately. Returning from another app does not wait for the refresh to finish.
- Cache the weather/solar palette for the current minute and timezone, and invalidate it when new weather arrives. Arrow presses no longer repeatedly parse forecast timestamps.
- Avoid a synchronous RecentStore load on Start and reuse the background-loaded sessions when merging Continue Watching. No new access or system setting is introduced.

`dist/home-return-validation.json` records the final build, tests, TV comparison and preservation checks. Private raw evidence is in `research/home-return-2026-11-03/`. A preliminary system trace reported a kernel tracing-option error and is not used as the quantitative comparison; the recorded frame stats and timed navigation sequences are the comparison evidence.

Volume-hold work remains paused. This build preserves its current Home-button mapping and remote-key configuration.

[Android main-thread guidance](https://developer.android.com/topic/performance/threads).
