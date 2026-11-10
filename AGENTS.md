# Sweep TV maintenance

- Before preparing an update for the TV, check the latest stable CleanYourTV
  release and compare it with `launcher/upstream/cleanyourtv-lock.json`.
- The user authorizes compatible maintenance updates after source review and
  verification. Port applicable changes into Sweep TV; preserve its existing UI,
  profiles, artwork choices and settings. Do not replace it with the stock
  CleanYourTV launcher or claim that all upstream features were imported.
- Review changed shared components at a pinned release commit, retain licenses,
  update the lock and review notes, and run the relevant tests, release build and
  lint. The release preflight in `launcher/check-upstream.py` must pass online.
  Offline verification is for local diagnostics, not a verified TV release.
- Do not advance the review baseline just because a new version exists. Dependency
  checks, source integration and verification happen here during Sweep TV
  maintenance. The TV has one Sweep TV release screen and no separate upstream
  checker, update menu or installer. Bundle applicable upstream changes into a
  signed Sweep TV release with accurate component notes; never claim a full merge
  when only selected components were reviewed. Keep source/license credits.
  Do not describe this as unattended automatic updating.
- Ask before applying cleanup rules, changing disabled packages, granting new
  permissions, or changing TV services or remote mappings. The physical volume
  hold investigation remains paused. A newer device profile alone is not approval.
- Test requested launcher updates on the actual TV. Check the current foreground
  app first and avoid interrupting playback without a ready/approval response.
  Verify package state, accessibility, existing grants and the TMDB key survive
  installation. Never log credentials or claim local tests prove device behavior.
