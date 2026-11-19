"""Build a private English backup pool from Netflix India's own catalog."""

from concurrent.futures import ThreadPoolExecutor
from datetime import date
import json
import re

from apple_catalog import STRUCTURED, fetch
from metadata_check import IMDB, artwork, title_key

ORIGIN = "https://www.netflix.com"
COLLECTION = ORIGIN + "/in/browse/genre/107548"
LINK = re.compile(r"https://www\.netflix\.com/in/title/[0-9]+")
ENGLISH_POOL = 16
DISCOVERY_BATCH = 6
MAX_LINKS = 48


def title_page(url):
    if not LINK.fullmatch(url):
        return {}
    body, final = fetch(url)
    if final != url:
        return {}
    for script in STRUCTURED.findall(body):
        try:
            data = json.loads(script)
        except ValueError:
            continue
        if (isinstance(data, dict) and data.get("url") == url
                and data.get("@type") in ("Movie", "TVSeries") and title_key(data.get("name"))
                and data.get("actors") and data.get("description")):
            return data
    return {}


def make_candidate(url, request, rating_lookup, today):
    page = title_page(url)
    if not page:
        return None
    kind = "movie" if page["@type"] == "Movie" else "tv"
    date_key = "release_date" if kind == "movie" else "first_air_date"
    names = ("title", "original_title") if kind == "movie" else ("name", "original_name")
    name = title_key(page["name"])
    actors = {title_key(actor.get("name")) for actor in page["actors"] if isinstance(actor, dict)} - {""}
    if not actors:
        return None
    matches = request(f"/search/{kind}", query=page["name"], include_adult="false").get("results", [])
    candidates = []
    for item in matches:
        if (item.get("original_language") != "en" or type(item.get("id")) is not int
                or name not in {title_key(item.get(key)) for key in names}):
            continue
        try:
            released = date.fromisoformat(item.get(date_key, ""))
        except (TypeError, ValueError):
            continue
        if released > today:
            continue
        detail = request(f"/{kind}/{item['id']}", append_to_response="external_ids,credits,watch/providers")
        imdb = detail.get("external_ids", {}).get("imdb_id")
        cast = [person.get("name", "") for person in detail.get("credits", {}).get("cast", [])]
        offers = detail.get("watch/providers", {}).get("results", {}).get("IN", {}).get("flatrate", [])
        poster = artwork(detail.get("poster_path"), "w780")
        summary = detail.get("overview") or ""
        if (detail.get("id") == item["id"] and any(offer.get("provider_name") == "Netflix" for offer in offers)
                and detail.get("original_language") == "en" and actors.intersection(title_key(actor) for actor in cast)
                and isinstance(imdb, str) and IMDB.fullmatch(imdb) and poster and summary):
            candidates.append({"id": imdb, "kind": kind, "title": item[names[0]], "row": "provider",
                               "provider": "netflix", "language": "en", "link": url, "year": str(released.year),
                               "released": released.isoformat(), "summary": summary[:1600],
                               "cast": ", ".join(person for person in cast[:5] if person),
                               "poster": poster, "background": artwork(detail.get("backdrop_path"), "w1280"),
                               "_netflix_english": True})
    if len(candidates) != 1:
        return None
    candidate = candidates[0]
    try:
        rating = float(rating_lookup(kind, candidate["id"]))
    except (TypeError, ValueError):
        return None
    return dict(candidate, rating=rating) if 6 <= rating <= 10 else None


def discover(today, request, rating_lookup, known=()):
    body, final = fetch(COLLECTION)
    if final != COLLECTION:
        raise ValueError("Netflix India's English collection was unavailable")
    known_links = {row["link"].split("?")[0] for row in known
                   if row.get("provider") == "netflix" and row.get("language") == "en"
                   and (row.get("_netflix_english") or (row.get("_metadata_tmdb_id") and row.get("rating", 0) >= 6))}
    links = [url for url in dict.fromkeys(LINK.findall(body)) if url not in known_links][:MAX_LINKS]
    if not links:
        return []

    def match(url):
        try:
            return make_candidate(url, request, rating_lookup, today)
        except RuntimeError:
            return None

    rows = {}
    with ThreadPoolExecutor(max_workers=DISCOVERY_BATCH) as pool:
        for offset in range(0, len(links), DISCOVERY_BATCH):
            for row in pool.map(match, links[offset:offset + DISCOVERY_BATCH]):
                if row:
                    rows.setdefault((row["kind"], row["id"]), row)
            if len(rows) >= ENGLISH_POOL:
                break
    return list(rows.values())[:ENGLISH_POOL]


def verify_pool(rows, request):
    def check(row):
        page = title_page(row["link"])
        tmdb_id = row.get("_metadata_tmdb_id")
        included = False
        if page and title_key(page["name"]) == title_key(row["title"]) and tmdb_id:
            try:
                data = request(f"/{row['kind']}/{tmdb_id}/watch/providers")
                offers = data.get("results", {}).get("IN", {}).get("flatrate", [])
                included = any(offer.get("provider_name") == "Netflix" for offer in offers)
            except RuntimeError:
                pass
        return dict(row, _netflix_access="included" if included else "unconfirmed")

    with ThreadPoolExecutor(max_workers=DISCOVERY_BATCH) as pool:
        checked = iter(pool.map(check, (row for row in rows if row.get("_netflix_english"))))
        return [next(checked) if row.get("_netflix_english") else row for row in rows]
