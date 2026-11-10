# Portrait artwork consistency — build 67

Continue Watching, Recommended for you and New releases validate the actual image
orientation before treating a file as a portrait poster. The name of the provider
field, cached role or filename is not proof that the image is portrait. Landscape
fallbacks in portrait slots are contained rather than cropped. Image header sizes
are cached by path, modification time and byte length to avoid repeatedly reading
them during remote navigation. The cache is bounded to 128 entries.

For episodic Continue Watching entries, title lookup uses TMDB's TV search endpoint,
matching exact title/original-title only. If 2–4 same-named series remain, a watched
season of 2 or above can rule out series with too few seasons. Every candidate must
have a verified positive season count, and exactly one must support the watched
season. Ambiguous or incomplete metadata never selects a show by popularity.
There are no hardcoded Modern Family or other show IDs.

Unresolved artwork caches from the previous matching rules are retried once with
the new matching version rather than waiting through the old failure backoff.
Complete current artwork remains cached. Successful catalog caches record the
matched ID and matching state locally; no API key or playback link is logged.
The existing source quality, YouTube choice, language-independent Continue
Watching list, resume data and provider-specific Apple episode-scene guard stay
in place. No new permission, cleanup or remote mapping is introduced.

Physical-TV validation is required in addition to the role-selection and title
matching tests. Source files: HomeActivity.java, ArtworkAssets.java, TitleArtwork.java.

References:
- https://developer.themoviedb.org/reference/search-tv
- https://developer.themoviedb.org/reference/tv-series-details
- https://developer.themoviedb.org/reference/tv-series-images
