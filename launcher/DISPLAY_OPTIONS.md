# Display and Continue Watching options

Follow-up to the pinned Talkies + F1 base, requested 2026-11-02.
Introduced in versionCode 39; full time-of-day theme fixed in `1.2.4-base-f1.4` / versionCode 41. The original layout, F1 and TMDB recommendation row are retained.

Control Center → Talkies settings:

- **Background** switches between original app colours and the full local-clock **Time of day** theme. Code 46 retains dark brightness with warm sunlight hues: rose dawn, golden/ochre noon, amber/orange afternoon, warm sunset and deep blue night. Dock and menus stay dark throughout the day. See [SKY_GRADIENT.md](SKY_GRADIENT.md) for location, caching and fallback details. Background gradient, text, dock, menus, control accents and focus highlight follow the time together. The palette refreshes each minute and on returning Home. While enabled, Appearance displays Time of day; the saved fixed Light/Dark/Auto choice is retained and restored when Background returns to App colours. An explicitly chosen photo wallpaper retains priority over the background gradient.
- **Continue Watching → Include YouTube** controls both Android Watch Next and saved sessions. Filtering does not delete history or change progress/resume links.
- **Continue Watching → Artwork** switches **Playback still** / **Title artwork**. Title mode uses exact, unambiguous TMDB title artwork, with a distinct provider poster as fallback, then an app icon. It never substitutes the stored playback frame. Title matching runs in the background and is cached; unavailable matches retry after 30 minutes. Search uses the existing configured TMDB key. YouTube videos are not matched to films/series.

Defaults retain stock behaviour for other installs: app colours, YouTube included,
playback stills. This TV is configured for Time of day, YouTube off, Title artwork.

The APK and validation records are in `dist/talkies-1.2.4-f1-time-theme.apk`,
`dist/latest-build.json` and `dist/time-theme-validation.json`. The original code-39 options APK and validation are retained separately.
The separate version 38 baseline APK/source archives remain unchanged.

Build checks include 22 behavioural regression tests and release lint. See the validation record for the current result.
Tests cover minute-by-minute colour continuity, app-glow isolation, reversible
YouTube filtering from both sources, exact-title matching, artwork switching,
resume-link/progress preservation, native navigation and F1 controls.

Metadata reference: https://developer.themoviedb.org/reference/search-multi
Artwork reference: https://developer.themoviedb.org/docs/image-basics
