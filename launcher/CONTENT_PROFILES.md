# Content languages and profile pictures

Build 54 / 1.3.0-sweep.8, 3 November 2026.

Settings → Watching and discovery → Content languages (also under Profile and greeting)
selects original title languages, independently of the TV's region and UI language.
English means original-language English, not any film with an English dub. Hindi
means Hindi, not a combined India/Tamil/Telugu feed. Multiple languages can be
selected. All languages is an explicit option; turning the last selected language
off cannot silently broaden the filter.

The policy applies to TMDB trending, the original discovery tabs, Recommended for
you, New releases, and app-published recommendation rows. Cached and in-flight
results are checked again at display time. Discovery queries request each selected
language separately and interleave their results; weekly global trends are filtered
by original_language. An English-only discovery fallback cannot bypass a Hindi-only
choice. Region still limits subscription availability on installed services.

Continue Watching and the equivalent Watch Next channel remain unfiltered by
language, as explicitly requested. The existing YouTube toggle and artwork choice
are retained. App-published recommendation cards with no verifiable original-language
metadata are hidden under a specific language selection. This may hide whole
provider rows; it never guesses a film's language from the title, poster, region or
audio tracks. F1, weather, installed apps and other non-film widgets are unaffected.

Settings → Profile and greeting includes a profile-picture menu, twelve bundled
DiceBear Thumbs flat illustrations, Surprise me, and a one-use phone-photo upload.
These are not AI-generated. The default is randomly selected once and persisted.
Avatars are decoded at 128px; the presets total about 96KB on disk. Photo upload uses
the existing five-minute tokenized LAN helper, with a 512px square crop and an 8MB
input limit. The TV validates and re-encodes the image without metadata. No new
Android permission or runtime avatar service is needed.

Up to six local launcher profiles store their own name, avatar/photo and language
selection. Switching profiles restores those choices. Streaming app logins and
Continue Watching remain shared; this is not isolation of provider accounts or
watch history. A late photo upload is attached to the profile that opened it, even
if another profile becomes active. Other launcher preferences remain TV-wide.

The YouTube TV application's sign-in is not authorization for Sweep TV to access
Google/YouTube profile details. Automatic import is not presented as connected.
Users can save their channel picture on their phone and upload it explicitly.
A future direct import would require a separate authorized account integration.

Avatar provenance/license: https://www.dicebear.com/styles/thumbs/ (DiceBear, CC0 1.0).
YouTube authorization reference: https://developers.google.com/youtube/data/reference/rest/v3/channels/list

Validation is recorded in dist/content-profiles-validation.json. No cleanup rules,
remote-key configuration, display resolution, accessibility permissions, or TV
service state are changed by this feature. The physical volume-hold issue stays
paused at the user's request.

Build 55 / 1.3.0-sweep.9 renders the avatar-picker previews as circles using native
View outline clipping. The existing avatar artwork, saved selection and grid
navigation remain unchanged; no new bitmap copies or server requests are added.

Build 56 / 1.3.0-sweep.10 adds 24 distinct Twemoji illustrations, grouped into
Animals and Little things, alongside the existing twelve Simple faces. All 36
choices are available offline, with circular previews and a four-column grid
inside each category. Existing avatar IDs 0–11 and custom photos keep their saved
identity. New choices 12–35 use native Android vectors rendered once into the same
bounded 128px bitmap cache; no extra runtime library or network request is needed.
The source SVGs are pinned to the revision recorded in assets/avatars/sources.json.
Graphics attribution and adaptation details are included in About and notices.

Build 57 / 1.3.0-sweep.11 assigns one random unused avatar when a local profile
is first created. The saved choice survives reopening, profile switching and app
updates. Surprise me picks another avatar not currently used by any TV profile.
Existing choices and explicitly selected avatars/photos are preserved. Defaults
are distinct within a TV; separate TVs may randomly choose the same library avatar.
