# Sweep TV companion website

Static HTML/CSS/JS with no runtime dependencies or analytics. The model and TV IP inputs remain in the browser session; the site does not connect to or modify a television.

Run `npm run check` and `npm run dev` in this directory. The server uses loopback port 4177, configurable with `SWEEP_SITE_PORT`.

`prepare.py` is a maintainer-only packaging step: it reads the separately stored `dist/latest-release.json`, validates the APK checksum, and copies an explicit screenshot/font/notice allowlist. Raw research and credentials are excluded from Git. The prepared static page and release metadata are tracked. APK binaries are distributed through release assets and the hosted site.

The current download is **1.3.0-sweep.25 (71)**, a candidate with **31 focused Android checks and 10 release-gate checks**, lint with zero errors / 70 warnings, and **physical-TV verification pending**. Screenshots are explicitly labelled earlier TV-tested builds (home: 65; F1: 70; recommendations: 67). Build 70 remains the last installed reference-TV version. No universal performance improvement is claimed.

The private hosted Site identity is persisted in `.openai/hosting.json`. Publication uses an isolated source checkout so the root GitHub remote is preserved. Keep MIT, third-party and media attributions intact.
