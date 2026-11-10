# TMDB recommendations restored

Requested 2026-11-02; build `1.2.4-base-f1.3` (code 40).
Adds a Recommended for you row below F1, before the app-published channels.
The original Talkies layout, time-of-day background, Continue Watching controls,
F1, and existing Home mapping are retained.

The earlier local recommendation engine is reused. It combines movie and series
picks from both regional TMDB catalog tabs, across installed streaming providers
(including Prime Video, in addition to Apple TV, Netflix and JioHotstar). Regional
subscription availability and installed-app checks are inherited from Trending.
Up to 12 unique movie/series IDs are ranked; provider quotas keep variety. Local
recent activity influences provider/genre ranking when a catalog title matches,
and matching recent titles are omitted. No streaming-account profile is accessed.
The recommendation ranking does not upload viewing history.

Cards show existing catalog artwork, title and provider. OK opens year, synopsis,
selection reason, explicitly labelled TMDB rating and an Open [app] action. That
action opens the provider app; it does not claim to deep-link to the exact title.
TMDB/JustWatch attribution appears beside the row and in details. No sharpening,
full-screen banner, or dock-specific recommendation shelf is reintroduced.

Talkies settings → Recommendations switches the row between Shown and Hidden.
Without a key or eligible cached titles, the row is omitted. Older catalog caches
still load; optional synopsis/year/rating fields are added on the next successful
refresh. The cache version change requests a refresh using the configured key;
requests and downloads stay on the existing background worker and refresh limits.

References:
- https://developer.themoviedb.org/reference/discover-movie
- https://developer.themoviedb.org/reference/discover-tv
- https://developer.themoviedb.org/reference/movie-watch-providers

Validation is recorded in `dist/tmdb-recommendations-validation.json`.


## English discovery, new releases and portrait cards (code 43)

Build `1.2.4-base-f1.6` adds separate Recommended for you and New releases rows,
both with 2:3 poster cards (140 × 210 dp) and independent Shown/Hidden settings.
They follow F1 and precede app-published channels. The card layout, scroll offsets,
All apps placement, focus and Back handling account for the taller rows.
500 px TMDB posters are decoded to the focused TV card size to bound memory use.
If a poster is missing, landscape art is letterboxed rather than stretched tall.

The catalog adds a dedicated English discovery feed, using original_language=en,
and actual weekly TMDB trending film/TV candidates. Each candidate still must
have regional subscription availability on an installed app. English discovery
is independent of the original top-tab Picks languages filter. Ranking boosts
verified weekly trends and recent debuts, while retaining local genre/provider
affinity and filtering recently watched titles. Each row reserves up to six of
its twelve slots for available English titles, with provider diversity among the
remaining selections. A quota cannot remove the English representation.

New releases uses movie primary release dates and TV first-air dates from the
last 90 days. It excludes future, missing and older dates; a new episode does not
make a long-running series a new launch. The row says Premiered [date] and does
not claim the date a service added it. English-specific and global queries are
combined. Rows prefer distinct titles; shared entries are allowed only when
needed to populate a small new-release pool or keep English represented there.

Extra catalog tabs and release/trending metadata are cached alongside the existing
TMDB cache. Artwork downloads are shared by filename across overlapping feeds.
The original Continue/Trending/New in India tabs, full time-of-day theme, F1,
YouTube-off Continue and title-art preferences are retained.

Official API references:
- https://developer.themoviedb.org/reference/trending-movies
- https://developer.themoviedb.org/reference/trending-tv
- https://developer.themoviedb.org/reference/discover-tv
- https://developer.themoviedb.org/reference/discover-movie

Validation: `dist/portrait-discovery-validation.json`.

The initial code-42 TV refresh hit a transient TLS reset. Code 43 retries transient API/network errors up to three attempts with short backoff; permanent authentication/client errors are not retried. Existing catalog data stays visible during a refresh.
