# Promotional artwork — build 71

Applies to Continue Watching in Title artwork mode and the expanded landscape
cards in Trending and New in India. Explicit app-published title posters keep their
existing provider/orientation priority. TMDB fallback now prefers landscape art
tagged for the TV language, then English, rather than language-neutral backdrops.
Language tags are a selection signal, not OCR or a guarantee that every uploaded
image contains the title. This does not expose every personalised artwork variant
available inside a streaming app. Actual candidates must be checked on the TV.

When a suitable wide title cover is absent, the original portrait poster is
contained in the expanded card. The raw discovery backdrop is not used as the
landscape fallback. Promotional covers are contained too, so wordmarks near their
edges are not cut off by the wider card. Card positions and sizes stay the same;
no full-screen banner or sharpening is introduced. The Playback still choice for
Continue Watching remains available.

Continue's matching policy is version 3. Older complete catalogs are checked once
for the new cover policy instead of being treated as fresh for another week.
Successful policy versions and attempted policy versions are tracked separately,
so failed migrations retry after the normal backoff rather than being promoted to
a successful weekly cache. Portrait-only catalogs do not resurrect legacy neutral
backdrops. Current selected artwork takes priority over older Apple resume
snapshot artwork; resume links, episode metadata and progress are preserved.

Discovery cover queries use the trusted TMDB movie/TV ID already attached to the
pick. They do not search by title or treat Watch Next/program IDs as TMDB IDs.
One background-priority worker resolves only the current shelf's twelve picks,
stops remaining work on a tab/lifecycle change, and updates the existing image
view after verifying the request generation. It does not rebuild or refocus the
shelf. Cached original files are reused for seven days; failures back off for
thirty minutes. Successful no-cover responses are cached and use the poster.

Discovery uses a separate `discovery-title-covers` directory, bounded to 64 MiB
and 48 source assets with at most 192 indexes. Continue's existing cache remains
separate. Both use the same validated, content-hashed original-byte downloader,
12 MiB/50-megapixel per-image bounds and display-size background decoding. There
are no new permissions, cleanup actions, remote mappings or TV service changes.

Reference: https://developer.themoviedb.org/docs/image-languages
