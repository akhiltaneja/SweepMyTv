# Full time-of-day theme fix

Build `1.2.4-base-f1.4`, versionCode 41, requested 2026-11-02.

The former Background → Time of day option only varied the background tint.
It still honored a fixed Dark appearance, chose a blue daytime background, and
left controls, menus, focus rings and dock colours unchanged. This kept the TV
looking dark and blue at noon despite the time setting.

Time of day now drives the whole launcher theme: a warm ivory/bronze day,
rose sunset, and charcoal/lavender night. Text, controls, menu selection,
focus ring and dock follow the palette. Colours interpolate by local minute;
foreground colours are checked against both gradient endpoints. The gradient
narrows at twilight when needed to maintain readable text. App focus no longer
changes the scheduled palette. Brand logos, title artwork and F1 widget colours
retain their own colours.

A saved fixed Appearance setting is retained, but Time of day takes precedence
while enabled. The Appearance row clearly displays Time of day. Returning
Background to App colours restores the saved Light/Dark/Auto behavior.
The theme updates on minute/timezone changes and on resume; gradual palette
updates avoid reloading content cards. Open menus are recoloured in place.

No TV clock, global display mode, app permissions or system packages are changed.
F1, TMDB recommendations, YouTube-off Continue Watching and title artwork remain.
No banner, sharpening or image enhancement is added.

Validation covers all 1440 local minutes for colour continuity and readable
foregrounds, an injected clock for midnight → noon → night, the previous Dark
preference no longer blocking daytime, menu/focus changes, resume and restoration
of the saved manual appearance. Future times are simulated in automated tests;
the TV clock is never changed. Actual TV checks are recorded in
`dist/time-theme-validation.json`.
