# Managed regional catalog

Sweep TV's default recommendation engine reads its own India feed. Publish a
title only with a portrait poster, a provider title link, its original language,
and a synopsis. An unavailable poster or a known title mismatch also blocks
publication. Ratings, cast, and landscape artwork are optional. The Android
app can use a saved feed when the host is unavailable, and holds each card until
its portrait can be decoded.

Catalog Admin → Listing issues shows unresolved and resolved issues with their
first and most recent observations. Scheduled refresh rechecks listings returned by
the sources. A listing can return after its required fields pass. If a title
falls off those sources, its issue stays open. Provider links are checked for
title-specific URL structure. For the private StreamRadar catalog, the Mac
also checks the IMDb ID and title against TMDB, and requires at least one
chart image to belong to that TMDB title. A provider link assigned to
multiple title identities is also withheld. Once matched, the synopsis
and cast come from the matched TMDB entry and its source appears in
Catalog Admin. The daily job uses the Mac's Sweep TV Catalog TMDB Keychain
entry or `SWEEP_TMDB_TOKEN`. The TV never needs the key. This check verifies
the metadata match and checks the provider link's structure, but does not prove
what the provider plays. Approved independent inputs retain their own review.

Prime Video recommendations require the exact Prime Video offer page to show a
Prime membership option and JustWatch's India availability (via TMDB) to list
Amazon Prime Video under the subscription category. Rentals, purchases, add-on
channels, and unconfirmed or stale offers are withheld.

Apple TV recommendations require a subscription action on the exact Apple TV
India title page. To expand the Apple TV app row with older Originals, run
`python3 launcher/catalog/admin/catalog_admin.py --feed-file research/managed-catalog/feed/catalog/IN.json refresh-apple`.
Discovery matches Apple India's title, release year and cast to TMDB and uses the
same IMDb 6.0 floor and artwork checks. Scheduled comparison refreshes carry these
entries forward and recheck their subscription pages.

For Netflix-only viewers who choose English, `refresh-netflix-english` imports
an English back catalog from Netflix India's collection. Each title needs an
exact Netflix India page, a matching title and cast, Netflix India subscription
availability via TMDB/JustWatch, and an IMDb rating of at least 6.0. The feed
reserves up to 16 distinct English Netflix titles when it reaches capacity.
The launcher can use this pool to fill recommendations for a single supported
app. Recently Released continues to use eligible debut dates in the discovery catalog.

Catalog Admin has separate Trending and Recently Released views, ordered as
published in the feed. Open a listing to move or hide it in that section.
Choose Hidden by you to restore it. Section edits persist across refreshes and
cannot override the catalog's metadata or subscription checks.

The Catalog Admin title detail dialog can pin a title at position 1–10, associate
it with a TMDB ID from the private index, and override portrait and wide art.
The private endpoint at `/catalog/IN.json` serves these edits. The dashboard
and its database stay on the Mac during comparison. The daily StreamRadar
import is private test input for this feed. Keep it separate from any material
approved for public redistribution.

Use a separate database for approved inputs. Add `"publication": "approved"`
to the root of a v1 input after reviewing its provider data and artwork rights.
Run `catalog_admin.py --db /path/to/public.sqlite3 --feed-file /path/to/catalog/IN.json import-verified INPUT`
to prepare a publishable snapshot. A scheduled server for this database can use
`serve --scheduled --verified-input INPUT` to refresh from that file without calling
the legacy catalog APIs.

For shared hosting, keep titles, offers, artwork provenance, and editorial
edits in private Supabase Postgres tables. Publish only approved snapshots to
an HTTPS object URL. Supabase Storage is one option. The TV downloads the
snapshot and has no database credentials.

Private comparison builds use `-PsweepCatalogComparison=true` and your
comparison asset directory's `comparison/IN.json` for StreamRadar.
Start the Mac listener with `python3 launcher/catalog/admin/catalog_admin.py serve-feed`.
Use `-PsweepCatalogBase=http://127.0.0.1:4190/catalog` with
`adb reverse tcp:4190 tcp:4191` for the Mac feed. The TV tunnel reaches
only the read-only feed. The normal build
uses Sweep TV with an HTTPS feed URL and ships without the comparison picker.

### Cloud catalog

The private GitHub workflow in `cloud/dry-run.yml` compares Linux refresh
coverage with a SQLite baseline. Its public-source probe runs without
secrets. The full comparison uses the private `catalog-baseline` release asset
and the `SWEEP_TMDB_TOKEN` Actions secret. The workflow uploads only a summary
of a manual comparison. The midnight, 06:00, noon and 18:00 IST runs restore the last
accepted private state, or the baseline when there is no retained state, and
keeps the next accepted SQLite copy as a private workflow artifact. Use a
separate, independently approved input for the TV API.

Prepare the Cloudflare assets with `python3 launcher/catalog/cloud/prepare-public.py APPROVED_INPUT`.
This imports into a fresh database and exports the validated
`public/catalog/IN.json` used by `cloud/wrangler.jsonc`. The output directory
is ignored by Git. Run Wrangler from `launcher/catalog/cloud` using your
Cloudflare account. A rejected import leaves the last staged feed available.

The existing private workflow can publish its accepted TV snapshot using
`prepare-public.py --db PATH`. The export includes only the title fields the
launcher reads and removes URL query parameters and fragments. Only
`catalog/IN.json` is included in the static upload. Set `CLOUDFLARE_API_TOKEN`
and `CLOUDFLARE_ACCOUNT_ID` as repository Actions secrets to enable the
publication step after each successful six-hour refresh. The hosted feed is
`https://sweep-tv-catalog.akhiltaneja04.workers.dev/catalog/IN.json`.

## Legacy comparison source

Sweep TV can load a keyless regional catalog on first launch. A configured TMDB
key and the TMDB comparison selection use TMDB and its separate cache. The managed feed supplies regional
discovery candidates, while the TV filters by installed apps and selected
languages and ranks the results using local activity. Continue Watching comes
from the device. History and profile information stay on the TV.

Build an India snapshot with `python3 launcher/catalog/build-feed.py /tmp/sweep-catalog`.
The builder combines StreamRadar charts and title data with TVmaze and Wikidata
original-language metadata matched by IMDb ID. Unknown languages remain unknown.
Publish the resulting `IN.json` to an HTTPS directory approved for these feeds
and artwork sources. Build with `-PsweepCatalogBase=https://your-host/catalog`.
The app requests only `IN.json` from this directory. Keep the last valid snapshot
available when a refresh fails. Refresh the published file on an operator
schedule. No source credential belongs in the APK.

To package a fixed StreamRadar comparison, build an India snapshot and put it
under `comparison/IN.json` in the private asset directory passed to
`-PsweepCatalogAssets`. Point the private comparison build at the local Sweep
TV server for the independent Sweep TV source.
The initial managed catalog targets India. Other countries can use installed apps,
Continue Watching and eligible app-published rows, or select TMDB.

Sources: [StreamRadar](https://streamradar.bricksgenius.com/),
[TVmaze (CC BY-SA)](https://www.tvmaze.com/api), and
[Wikidata](https://www.wikidata.org/wiki/Property:P364). The keyless client makes no direct TMDB API
call for this catalog. Some source artwork URLs use TMDB's CDN.

The builder uses clean MetaHub portraits for chart posters containing baked-in
scores and ranking labels. Rating metadata remains separate from the artwork.

## Private English catalog comparison

Run `python3 launcher/catalog/pilot-english.py BASELINE_IN_JSON PRIVATE_OUTPUT`
to measure the existing English catalog by service. When `SWEEP_TMDB_TOKEN` is
available in the local environment, the same command writes an enriched `IN.json`
and a comparison. Use `--token-stdin` to pass an approved credential directly
from a secret store. Keep the output private until its source terms are verified.
The comparison separates titles with posters from titles with exact link candidates.
New TMDB entries require an Indian subscription offer and a matching IMDb ID.
Catalog records with `row: "provider"` supplement only the streaming app's own row.

## Catalog Admin

The private service stores titles, India offers, rating observations, refresh
runs and the last accepted feed in `research/managed-catalog/catalog.sqlite3`.
It serves the dashboard and downloadable feed on loopback only. The browser can
search and filter the active catalog, inspect source and artwork links, and view
refresh results. The private refresh reads up to four Just In pages each for films
and series, checks source dates against a rolling 60-day India window, and
orders those candidates newest first. A Just In date is a discovery signal;
an exact platform launch date needs provider evidence. The six supported
services' India charts supply Trending, including popular titles outside the
recent window. A missing IMDb score or a score below 6.0 keeps a candidate out
of the TV feed. Identity, artwork, link, and Prime subscription checks still
apply. Curated picks can be chosen from eligible titles in Catalog Admin.
The TV feed keeps its 192-listing and 512 KiB limits. The dashboard shows which
listings made the feed. Playback checks happen on the TV.

```sh
python3 launcher/catalog/admin/catalog_admin.py import /path/to/catalog/IN.json
python3 launcher/catalog/admin/catalog_admin.py --feed-file research/managed-catalog/feed/catalog/IN.json refresh
python3 launcher/catalog/admin/catalog_admin.py --feed-file research/managed-catalog/feed/catalog/IN.json serve --scheduled
```

Open `http://127.0.0.1:4190/`. With `--scheduled`, the running process refreshes at
midnight, 06:00, noon and 18:00 IST and atomically replaces the staged feed after a
successful run. Existing `--daily` invocations use the same schedule.
The job needs the Mac and server to be running. Rejected or incomplete runs
leave the last accepted feed in place. The default target is at least eight
distinct English titles with reachable posters per major app and both discovery
sections. `refresh --from-file INPUT` can revalidate a recent builder output.

For a separately verified title, `supplement INPUT IMDb_ID PROVIDER --source
SOURCE` allows an already verified English provider-only entry with clean art
and a title link to fill a coverage gap for seven days. The dashboard preserves
its observation timestamp. A configured `SWEEP_TMDB_TOKEN` provides optional
enrichment for the refresh job. The service does not read the TV's key or
history. The staged JSON is local until an approved HTTPS publisher serves it.

### Private TMDB title index

```sh
python3 launcher/catalog/admin/tmdb_index.py research/managed-catalog/catalog.tmdb.sqlite3 --date 2026-11-17
```

The **TMDB index** tab searches the separate local SQLite snapshot by original
title or TMDB ID. It uses the standard movie and series exports, with an
original title and a popularity value where supplied. These exports omit
posters, plots, ratings and service availability. The index does not supply
recommendations to the launcher. To refresh it, run the command with the next
available export date. A failed import leaves the previous snapshot in place.
Database files stay under the ignored `research/` directory.

[TMDB daily ID exports](https://developer.themoviedb.org/docs/daily-id-exports).
This product uses the TMDB API but is not endorsed or certified by TMDB.

## Rotten Tomatoes® Popular Picks

Once Rotten Tomatoes approves the intended data use, bundle an approved,
editorially ordered snapshot as `launcher/app/src/main/assets/rt-popular-picks.json`.
Use `{"version":1,"movies":[{"tmdbId":123,"imdbId":"tt0000123","tomatometer":95,"reviewUrl":"https://www.rottentomatoes.com/m/example"}]}`
as the schema. The values are examples. Supply either catalog ID, or both so
the entry can match either catalog. Each review URL points to the official
movie page. Follow the approved attribution and asset requirements before
shipping a populated list. [Rotten Tomatoes licensing](https://www.rottentomatoes.com/help_desk/licensing).

The TV keeps the snapshot order and shows unique films with a Tomatometer
score of 90% or higher. A film needs a match in the existing regional
catalog and an installed provider. The existing content
language filter also applies. The row stays hidden when the file is absent or
there are no eligible matches. Refresh the curated snapshot in a future build
to change the lineup.
