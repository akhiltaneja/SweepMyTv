"""Verify Apple Originals against the India storefront for the private catalog."""

from concurrent.futures import ThreadPoolExecutor
from datetime import date
from functools import lru_cache
import json
import re
import subprocess
from urllib.parse import urlsplit

from metadata_check import IMDB, artwork, title_key

ORIGIN = "https://tv.apple.com"
TITLE = re.compile(r"/in/(show|movie)/[^/]+/(umc\.cmc\.[a-z0-9]+)/?")
ROOM = re.compile(r'href="(https://tv\.apple\.com/in/room/[^"/]+/(?:edt\.item\.[a-f0-9-]+|uts\.room\.kids-tv-plus))')
LINK = re.compile(r'href="(https://tv\.apple\.com/in/(?:show|movie)/[^"/]+/umc\.cmc\.[a-z0-9]+)')
STRUCTURED = re.compile(r'<script[^>]+type="application/ld\+json"[^>]*>(.*?)</script>', re.S)
WATCH_CARD = re.compile(r'data-testid="how-to-watch-card"[^>]*>(.{0,4500}?)</button>', re.S)


def fetch(url):
    try:
        result = subprocess.run(
            ["curl", "--disable", "-fsS", "--location", "--max-redirs", "2", "--max-time", "12",
             "--proto-redir", "=https", "--max-filesize", "4194304", "--write-out", "\n%{url_effective}", url],
            capture_output=True, text=True, timeout=15)
    except (OSError, subprocess.TimeoutExpired):
        return "", ""
    body, _, final = result.stdout.rpartition("\n")
    return (body, final) if result.returncode == 0 else ("", "")


def parse_title(body, kind, today):
    if not any("Sign Up to Watch" in card for card in WATCH_CARD.findall(body)):
        return None
    for script in STRUCTURED.findall(body):
        try:
            data = json.loads(script)
            if data.get("@type") != ("Movie" if kind == "movie" else "TVSeries"):
                continue
            published = date.fromisoformat(data["datePublished"][:10])
            if published <= today and title_key(data.get("name")):
                return data, published
        except (AttributeError, KeyError, TypeError, ValueError):
            continue
    return None


def title_page(url, today):
    link = urlsplit(url)
    expected = TITLE.fullmatch(link.path)
    if link.scheme != "https" or link.netloc != "tv.apple.com" or not expected:
        return None
    body, final = fetch(ORIGIN + link.path)
    dest = urlsplit(final)
    actual = TITLE.fullmatch(dest.path)
    if dest.scheme != "https" or dest.netloc != "tv.apple.com" or not actual or actual.groups() != expected.groups():
        return None
    return parse_title(body, "movie" if actual[1] == "movie" else "tv", today)


def originals():
    body, _ = fetch(ORIGIN + "/in")
    rooms = list(dict.fromkeys(url for url in ROOM.findall(body) if "/coming-" not in url))
    with ThreadPoolExecutor(max_workers=6) as pool:
        pages = [body, *(page for page, _ in pool.map(fetch, rooms))]
    links = {}
    for page in pages:
        for url in LINK.findall(page):
            links.setdefault(url.rsplit("/", 1)[-1], url)
    if not links:
        raise ValueError("Apple's India Originals catalog was unavailable")
    return list(links.values())


def make_candidate(url, page, request, rating_lookup):
    data, published = page
    kind = "movie" if "/movie/" in url else "tv"
    name = data["name"]
    year_key = "year" if kind == "movie" else "first_air_date_year"
    date_key = "release_date" if kind == "movie" else "first_air_date"
    names = ("title", "original_title") if kind == "movie" else ("name", "original_name")
    found = request(f"/search/{kind}", query=name, include_adult="false", **{year_key: published.year})
    matches = [item for item in found.get("results", [])
               if type(item.get("id")) is int and str(item.get(date_key, ""))[:4] == str(published.year)
               and title_key(name) in {title_key(item.get(field)) for field in names}]
    if len(matches) != 1:
        return None
    match = matches[0]
    detail = request(f"/{kind}/{match['id']}", append_to_response="external_ids,credits")
    imdb = detail.get("external_ids", {}).get("imdb_id")
    cast = [member.get("name", "") for member in detail.get("credits", {}).get("cast", [])]
    apple_cast = {title_key(member.get("name")) for member in data.get("actor") or [] if isinstance(member, dict)}
    if apple_cast and not (apple_cast - {""}).intersection(title_key(member) for member in cast):
        return None
    if not apple_cast and str(match.get(date_key)) != published.isoformat():
        return None
    poster = artwork(detail.get("poster_path"), "w780")
    language = detail.get("original_language", "")
    summary = detail.get("overview", "")
    if (detail.get("id") != match["id"] or not isinstance(imdb, str) or not IMDB.fullmatch(imdb) or not poster
            or not isinstance(language, str) or not re.fullmatch(r"[a-z]{2}", language) or not summary):
        return None
    return {"id": imdb, "kind": kind, "title": name, "row": "provider", "provider": "apple-tv-plus",
            "link": url, "language": language, "year": str(published.year), "released": published.isoformat(),
            "summary": summary[:1600], "cast": ", ".join(member for member in cast[:5] if member),
            "poster": poster, "background": artwork(detail.get("backdrop_path"), "w1280"),
            "rating": rating_lookup(kind, imdb), "_metadata_tmdb_id": match["id"],
            "_apple_original": True, "_apple_access": "included"}


def discover(today, request, rating_lookup, known=()):
    seen = {urlsplit(row["link"]).path.rsplit("/", 1)[-1]
            for row in known if row["provider"] == "apple-tv-plus" and row["link"]}

    def candidate(url):
        page = title_page(url, today)
        if page is None:
            return None
        try:
            return make_candidate(url, page, request, rating_lookup)
        except RuntimeError:
            return None

    links = [url for url in originals() if url.rsplit("/", 1)[-1] not in seen]
    by_id = {}
    with ThreadPoolExecutor(max_workers=6) as pool:
        for row in pool.map(candidate, links):
            if row:
                by_id.setdefault((row["kind"], row["id"]), row)
    return list(by_id.values())


def verify(rows, today):
    lookup = lru_cache(maxsize=None)(lambda url: title_page(url, today))
    def check(row):
        page = lookup(row["link"])
        return dict(row, _apple_access="included" if page and title_key(page[0]["name"]) == title_key(row["title"])
                    else "unconfirmed")

    with ThreadPoolExecutor(max_workers=6) as pool:
        apple = iter(pool.map(check, (row for row in rows if row["provider"] == "apple-tv-plus")))
        return [next(apple) if row["provider"] == "apple-tv-plus" else row for row in rows]
