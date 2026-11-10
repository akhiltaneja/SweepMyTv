# Continue Watching artwork — builds 63–65

Version 1.3.0-sweep.19, 10 November 2026.

The existing Title artwork / Playback still preference remains unchanged. Title
artwork now resolves two independent image roles. An app's explicit title poster
wins for its matching orientation; TMDB's per-title image gallery fills a missing
landscape backdrop or portrait poster. Matching still requires one unique exact
film/series title, so an ambiguous title cannot borrow another show's image.
YouTube video titles are never searched as film/series titles. Continue Watching
continues to ignore the discovery-language filter and preserves resume intents,
progress, removal identity and YouTube visibility preferences.

An episode's poster field is not sufficient proof of promotional art. On the actual
TV, Monarch's old poster cache is a scene of a character driving, while its old
landscape cache is a TMDB promotional collage. Thus Apple landscape posters
are accepted only for movie/series/season-level program records. Apple episode and
unknown records may supply a separate portrait poster. Other apps retain their
explicit distinct poster field: actual-TV testing showed that JioHotstar and
Prime Video publish legitimate landscape promotional covers there. This is a
provider-specific rule, not a universal classification of images. Identical
thumbnail and poster URIs remain rejected in Title artwork mode. Ambiguous episode landscape art
falls back to the title's catalog art, rather than being presented as promotional
art. This cannot expose every asset available inside the Apple TV app.

TMDB gallery selection checks orientation, dimensions, image language and ratings;
landscape prefers language-neutral images, and portraits prefer device-language
then English artwork. Stable path ordering breaks ties. Original TMDB image URLs
are used, rather than the old w1280 request. A portrait-only fallback is contained
within the expanded card, rather than cropped into a landscape image. Source-only
landscape art is contained on small Continue cards if no portrait exists. Continue
artwork decodes at display size in full-color ARGB, with an 8 MiB decoded cache;
there is no sharpening or upscaling.

The new recent/title-artwork-v2 cache keeps the encoded source bytes without JPEG
recompression. Downloads stream to disk, are validated by image bounds, are limited
to 12 MiB and 50 million pixels, and are decoded later at display size. Assets use
content hashes, so changed bytes yield a new file identity and refresh the UI. A
7-day refresh interval, 30-minute failure backoff, 48-asset / 64 MiB disk bound and
bounded metadata reads prevent repeated downloads and uncontrolled growth. URL
indexes store hashes and filenames, not source URLs or API credentials.

The old exact-title TMDB image is retained only as a last-resort bridge when its
replacement is unavailable or ambiguous. It is never promoted to a fresh catalog
result, so a later successful gallery lookup replaces it. This prevents a
working card from regressing to an app logo during migration. Legacy Apple boot-resume
snapshots may contain episode stills in their poster field; only a portrait from
that field is migrated. New snapshots explicitly mark their selected artwork mode
and retain both image roles. When the live Apple queue is missing at boot, catalog
fallback resolution still runs for the restored queue. New cached title selections
are also available to the local recent-session list.

Only artwork selection, artwork caches and rendering are changed. There are no new
Android permissions, cleanup rules, remote mappings, accessibility or TV-service
changes. The APK must be tested on the physical TV before claiming on-device visual
results. Data/API tests and native layout tests alone do not verify Apple's current
published artwork or the user's preferred look.

Sources:
- https://developer.android.com/reference/android/media/tv/TvContract.WatchNextPrograms
- https://developer.themoviedb.org/reference/tv-series-images
- https://developer.themoviedb.org/reference/movie-images
- https://developer.themoviedb.org/docs/image-basics
