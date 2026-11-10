# Sweep TV personalisation foundation

Version 1.3.0-sweep.3 / code 49. The device-tested update also enables the hold
gesture on every section/discovery card and tests the actual key-event path. The app keeps its existing package, signing key,
accessibility component, Home-button mapping and data. There is no replacement
launcher install, cleanup rerun or new Android permission.

## Personal home

- The line above the clock shows a time-aware greeting and optional weather.
  Profile and greeting settings let users enter/remove their name and independently
  hide either part. New installs use a greeting without a name. This TV is
  configured with Akhil. No Google account identity APIs are queried.
- The old RAM/storage bars are replaced by a small status pill below the controls.
  RAM, storage, TV uptime and Codex quota can each be enabled or disabled; the
  entire widget can also be hidden. RAM and storage figures are free capacity.
- Hold OK on a card in any row below the dock for **Remove section from Home**.
  This hides the whole row locally and preserves its app, content and resume links.
  Restore it under **Home sections**. The same menu works on F1, recommendations,
  New releases, Continue Watching and app-published rows such as FanCode/Netflix.
  Continue rows retain a separate action for removing just the selected title.
- Hold OK on the discovery shelf to remove that shelf. Recommendations and
  watching settings can enable it again. App section order retains the existing
  editor's hold-to-move control.
- Settings are grouped into Profile, Appearance, Home sections, Recommendations
  and watching, Apps and dock, Status widget, Connections and controls, Updates,
  and About/licences.

## Own identity and attribution

The app, helper labels, phone configuration page, ordinary menus and icon/banner
use Sweep TV. About retains the original Talkies/TechPP source credit, MIT copyright
and permission notice, and all third-party notices. Full licence text is readable
with the remote. Data-provider notices remain, including TMDB's required wording.
This is a rebranded derivative; the original code's authorship is not reassigned.

## Optional Codex companion

Official protocol: https://learn.chatgpt.com/docs/app-server

`companion/codex_usage.py` uses local `codex app-server` over stdio. It calls only
initialize, account/read, account/rateLimits/read and optionally account/usage/read.
There are no model turns, purchases, reset-credit actions, account changes or login
flows. Existing Codex sign-in stays on the Mac. The TV receives an allowlisted JSON
snapshot with quota percentage, window length and reset timestamp. It never receives
account email, tokens, credentials or chat content.

From the workspace root:

```
python3 launcher/companion/codex_usage.py --device 192.168.0.245:5555
python3 launcher/companion/codex_usage.py --device 192.168.0.245:5555 --watch
```

The watch option reads and syncs every five minutes while that process, this Mac and
ADB are available. It is not installed as a login item or persistent OS service.
The widget labels the quota window (for example `week`) and remaining percentage.
Data older than 15 minutes, or past its reset timestamp, shows **Sync needed**.
Settings includes snapshot age. A sync failure preserves the prior snapshot,
without pretending that it is current. Other users must opt into their own companion
and sign-in; authentication is not embedded in the APK.

## CleanYourTV release review

The GitHub latest stable release endpoint is checked once per day while Home is
active, or through Check now in Updates. ETags are used; failures are bounded and
retain previous metadata. Daily checks can be disabled. A new stable release adds
a persistent Control Center notice, a release-review page and a once-per-version
notification. It never downloads/runs upstream code, changes package states,
replaces this launcher, revokes permissions or applies cleanup rules.

As of 10 November 2026, the directly adapted shared source is verified through
v3.4.0 at fc148d5a9707b30e8f49f489625947421740dc55. ServiceRepair.java is byte-identical
at v3.2.0 and v3.4.0, so the existing Sweep TV adaptation needs no upstream repair
patch. This does not mean all upstream themes, tools or features are integrated.

The user's standing policy is to incorporate compatible maintenance changes after
verification. `check-upstream.py` checks the latest stable release, exact pinned
commit, original component hashes, and local adaptation hashes before a release
build. A new unreviewed release stops the release build until its changes have
been reviewed and the lock updated. The TV's daily checker remains a notification
mechanism, not an unattended code-merging or installation agent. New cleanup,
permissions, package-state, TV-service or remote-map changes still require the
user's approval. See UPSTREAM_UPDATES.md and upstream/cleanyourtv-lock.json.

Current validation: dist/personalisation-validation.json. Earlier APKs, source
snapshots and validation records remain pinned separately.

## Device/cleanup review completed

The separate read-only review in `dist/cleanyourtv-v33-review.json` compares complete
v3.2.0/v3.3.0 tree inventories, cleanup safety scripts and all changed Xiaomi/Redmi
debloat lists against this MiTV-MOOQ1 / tarzan / Android 10 TV. There is no exact
MOOQ1 profile. The nearby MOOQ0 list adds `com.xiaomi.tvqs.overseas`, which is absent
on this TV. Newly protected fused-location is installed and enabled here; SDK
sandbox is absent. No disabled package needs restoring from those guard changes.

Other model lists and HyperOS 3 power/Home notes do not establish safety for this
2021 Android 10 set. The existing setup wizard remains enabled. No additional
disables are recommended from this update, and no cleanup or permission change
was applied. New upstream guard/tool adoption remains a separately reviewed step.

## Volume hold and Apple TV startup recovery (code 49)

With the accessibility key filter active, this Xiaomi firmware suppresses native
volume repeats. The helper now supplies one normal volume step on key-down, then
repeats after 400 ms at 100 ms intervals until release. It uses Android's public
AudioManager.adjustVolume routing and normal volume UI, retaining the Home mapping.
Release, cancellation, another key, screen-off, service unbind/destruction and a
15-second failsafe stop repeating. Connections and controls → Remote keys includes
an optional Volume hold repair switch. No Android permission is added.

Device logs showed Apple TV's three real Watch Next rows disappearing at startup
and returning when Apple TV's profile screen opened, without playback. Apple TV's
last published resume list is now saved on this TV and loaded before the first
home draw. When that provider is absent, entries show a **Saved resume** label.
Their original deep links, engagement times, artwork and progress are preserved;
no new episode or playback progress is invented. Current provider rows replace the
snapshot. Explicit non-resumable/hidden provider entries clear it, cached rows
expire after seven days, and titles removed through Continue Watching stay hidden.
Title-artwork/spoiler choices also apply to recovered entries. Other providers'
Watch Next lists are unchanged. Provider rechecks run after five and twenty seconds
on Home; callbacks are cancelled when Home stops.

A saved entry is the last synced state, not a live Apple account query. If Apple
publishes no entries at all, completion or profile changes made elsewhere cannot
be inferred until it republishes; the label and Remove from Continue control
make that boundary visible. No Apple account credentials or private API is used.

Reference: https://developer.android.com/reference/android/media/AudioManager#adjustVolume(int,%20int)
Current validation: dist/remote-resume-validation.json.
