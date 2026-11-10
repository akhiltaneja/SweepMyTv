# Talkies + F1 base

Established on 2026-11-02 at the user's request: restore Talkies 1.2.4 and keep
the F1 widget as the only added launcher feature.

Installed version: `1.2.4-base-f1.1`, versionCode **38**, label **Talkies**.
The package remains `com.sweepmytv.launcher` with the existing local signing key,
so this is an update of the installed app and preserves its data and Home mapping.

The stock Talkies Java files and resources were restored from
`upstream/talkies-1.2.4`, with the package namespace mapped to the installed app.
Only `HomeActivity.java` (F1 integration / replacement of football) and
`SelfUpdate.java` (locally signed build cannot install official releases) differ
from that stock Java. The four added classes are `F1Assets`, `FormulaOne`,
`FormulaOneCard` and `FormulaOneDashboard`. The original Outfit font, layout,
content tabs, dock, app grid and settings are restored.

The Apple-style UI, custom full-screen banner/enhancement, selected-app card
shelf, custom Recommended for you row, and compact layout/navigation changes are
absent. Native Talkies content tabs and app channels are stock behavior.

Pinned artifacts:

- `dist/talkies-1.2.4-f1-base.apk`
- `dist/talkies-f1-base-source.tar.gz`
- `dist/talkies-f1-base-build.json` — APK/source hashes and build evidence
- `dist/talkies-f1-base-validation.json` — TV checks and preservation results

Nine regression tests and release lint passed with zero errors. The original
Home screen, F1 widgets, detail screen, Back and return Home were checked on the
TV. Streaming playback, HDMI and casting were not physically retested. F1 retains
the previously chosen free data sources; paid live timing is not connected.

The earlier system cleanup, permissions, debugging choice, TMDB key and app
preferences were preserved. No signing key, API key or device data is included
in the source archive. Later recovery should rebuild the pinned source with a
versionCode higher than the installed build and sign with the same local key;
avoid uninstalling or clearing data to roll back.
