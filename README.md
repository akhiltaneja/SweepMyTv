# Sweep TV

A native Android TV launcher customized from [Talkies](https://github.com/rajupp/talkies), with local profiles, content discovery and a Formula 1 dashboard. Companion website source lives in `website/`.

## Current preview

**1.3.0-sweep.25 · build 71** is a pre-release candidate. It prefers promotional landscape covers with title text in Continue Watching, Trending and release shelves, with intact portrait-poster fallback. Its 31 focused Android checks and 10 upstream gate checks passed; lint reported no errors and 70 warnings. **Physical-TV verification is pending.** The previous build, 1.3.0-sweep.24 (70), was installed and checked on a Xiaomi/Redmi MiTV-MOOQ1 running Android TV 10. Other devices require individual verification.

- Local profiles, persistent circular avatars and original-language preferences for recommendations. Continue Watching remains independent of that language filter.
- Continue Watching with progress/resume links, a YouTube inclusion toggle, and title-artwork or playback-still choices where sources provide them.
- TMDB discovery, trending and new-release shelves. Artwork availability varies; no sharpening, resolution upscaling or guarantee of identical streaming-app artwork.
- Dark solar/weather gradients and a compact rotating air-quality estimate, headline and local-holiday badge.
- F1 schedule, countdown, published classifications and standings. Free data only; no claim of live timing access.
- Popup settings, configurable home sections and one managed Sweep TV release screen with component notes.

See [Releases](https://github.com/akhiltaneja/SweepMyTv/releases) for the signed APK, source archive, checksums and verification notes. The repository excludes signing keys, API keys, personal TV backups, raw diagnostic recordings and local SDKs. This is an early preview; physical volume-button hold behavior remains unresolved. Performance work reduces unnecessary drawing/loading, but measurements do not establish a universal FPS improvement.

## Build and check

Use JDK 17, Android SDK platform 35 / build-tools 35.0.0, and the Gradle wrapper in `launcher/`. The local helper `launcher/build-preview.sh` expects SDK/JDK tooling in `tools/` as described by its checks; `JAVA_HOME` can override its JDK. Configure your own signing key. A self-built APK with another certificate cannot update the distributed APK in place.

```sh
cd launcher
./build-preview.sh
```

The helper runs upstream checks, Android unit tests, release assembly and lint; it never connects to a TV. [Upstream maintenance](launcher/UPSTREAM_UPDATES.md) documents the pinned CleanYourTV review and packaging gate. Included shared code is reviewed through **CleanYourTV v3.4.0**, not a wholesale import of its launcher or cleanup rules.

```sh
cd website
npm run check
npm run dev
```

The website is static HTML/CSS/JS with no runtime dependencies or analytics. `npm run prepare:site` is for the maintainer's release workspace and requires its separately stored verified release artifacts and screenshot inputs. The tracked `website/dist/` contains the prepared page, metadata and curated screenshots; APK binaries are release assets.

## Setup and recovery

Install only on your own Android TV/Google TV device after verifying its Android version and APK checksum. Keep the stock launcher installed and save a device-specific recovery record before cleanup. Accessibility, secure-settings grants, remote mappings and package disabling are separate explicit choices. Never paste TMDB keys into issues or chat. Installation instructions on the companion website create a local prompt and do not remotely modify the TV.

## Credits and licenses

See [LICENSE](LICENSE), [third-party notices](launcher/THIRD_PARTY_NOTICES.md) and the individual asset notices. Talkies and selected CleanYourTV components retain their original credits. Other adapted code and bundled avatar/font/media assets retain their respective licenses. Streaming services, TMDB and Formula 1 do not endorse this project.
