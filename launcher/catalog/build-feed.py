#!/usr/bin/env python3
"""Produce the keyless India catalog for upload to the managed Sweep TV feed."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timedelta
import json
from pathlib import Path
import re
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import Request, urlopen
from urllib.error import URLError
from zoneinfo import ZoneInfo

RADAR = "https://streamradar.bricksgenius.com"
TYPE = {"movie": "movies", "series": "shows"}
HOSTS = {
    "apple-tv-plus": {"tv.apple.com"},
    "netflix": {"netflix.com"},
    "prime": {"primevideo.com", "amazon.com", "amazon.in"},
    "jiohotstar": {"hotstar.com", "jiohotstar.com"},
    "sony-liv": {"sonyliv.com"},
    "zee5": {"zee5.com"},
}
LANGUAGES = dict(zip(
    "english hindi tamil telugu punjabi bengali marathi malayalam kannada gujarati urdu spanish french german italian portuguese japanese korean chinese arabic turkish thai".split(),
    "en hi ta te pa bn mr ml kn gu ur es fr de it pt ja ko zh ar tr th".split(),
))
ID = re.compile(r"tt[0-9]{1,12}|streamradar:[a-zA-Z0-9:_-]{1,70}")
IMDB_ID = re.compile(r"tt[0-9]{1,12}")
CHART_CANDIDATES = 24
JUST_IN_CANDIDATES = 42
EXPANDED_JUST_IN_CANDIDATES = 100
EXPANDED_CHART_CANDIDATES = 40
JUST_IN_PAGES = 4
RECENT_DAYS = 60
TRENDING_LIMIT = 144
RECENT_LIMIT = 48


def request(base, path):
    req = Request(base + path, headers={"Accept": "application/json",
                  "User-Agent": "SweepTV-Catalog/1.0 (https://github.com/akhiltaneja/SweepMyTv)"})
    try:
        with urlopen(req, timeout=10) as response:
            raw = response.read(2 * 1024 * 1024 + 1)
        body = json.loads(raw) if len(raw) <= 2 * 1024 * 1024 else {}
        return body if isinstance(body, dict) else {}
    except (OSError, URLError, ValueError):
        return {}


def catalog(spec, expanded=False):
    provider, kind = spec
    tail = TYPE[kind] if provider == "justin" else "top10-" + TYPE[kind]
    path = f"/catalog/{kind}/streamradar-{provider}-{tail}"
    payload = request(RADAR, path + ".json")
    limit = JUST_IN_CANDIDATES if provider == "justin" else CHART_CANDIDATES
    if expanded:
        limit = EXPANDED_JUST_IN_CANDIDATES if provider == "justin" else EXPANDED_CHART_CANDIDATES
    entries = payload.get("metas", [])[:limit]
    if expanded and provider == "justin":
        for skip in range(limit, JUST_IN_PAGES * limit, limit):
            if len(entries) < skip:
                break
            page = request(RADAR, f"{path}/skip={skip}.json").get("metas")
            if not isinstance(page, list):
                raise SystemExit(f"Just In {kind} page at offset {skip} was unavailable. Kept the previous snapshot.")
            seen = {row["id"] for row in entries if isinstance(row, dict) and isinstance(row.get("id"), str)}
            if not any(isinstance(row, dict) and isinstance(row.get("id"), str)
                       and row["id"] not in seen for row in page):
                break
            entries.extend(page[:limit])
    by_id = {}
    for row in entries:
        if isinstance(row, dict) and ID.fullmatch(str(row.get("id", ""))):
            by_id.setdefault(row["id"], row)
    return provider, kind, list(by_id.values())


def just_in_date(title, today):
    try:
        candidate = date.fromisoformat(value(title, "releaseInfo"))
    except ValueError:
        return ""
    return candidate.isoformat() if today - timedelta(days=RECENT_DAYS - 1) <= candidate <= today else ""


def value(item, field):
    text = item.get(field)
    return text.strip() if isinstance(text, str) else ""


def clean_poster(title, meta):
    poster = value(title, "poster") or value(meta, "poster")
    if urlsplit(poster).hostname != "btttr.cc":
        return poster
    imdb = value(meta, "imdb_id") or value(title, "id")
    if IMDB_ID.fullmatch(imdb):
        return f"https://images.metahub.space/poster/large/{imdb}/img"
    alternate = value(meta, "poster")
    return alternate if urlsplit(alternate).hostname != "btttr.cc" else ""


def language(name):
    name = name.lower().strip()
    return name if re.fullmatch("[a-z]{2}", name) else LANGUAGES.get(name, "")


def original_languages(ids):
    ids = sorted({ident for ident in ids if IMDB_ID.fullmatch(ident)})
    found = {}
    for offset in range(0, len(ids), 30):
        values = " ".join(f'"{ident}"' for ident in ids[offset:offset + 30])
        query = (f"SELECT ?imdb ?code WHERE {{ VALUES ?imdb {{ {values} }} "
                 "?work wdt:P345 ?imdb; wdt:P364/wdt:P218 ?code. }")
        payload = request("https://query.wikidata.org", "/sparql?" + urlencode({"format": "json", "query": query}))
        for row in payload.get("results", {}).get("bindings", []):
            code = language(row.get("code", {}).get("value", ""))
            if code:
                found.setdefault(row.get("imdb", {}).get("value"), set()).add(code)
    return {ident: next(iter(codes)) for ident, codes in found.items() if len(codes) == 1}


def enrich(spec):
    kind, ident = spec
    path = f"/{kind}/{quote(ident, safe=':')}.json"
    data = request(RADAR, "/meta" + path).get("meta", {})
    if not isinstance(data, dict):
        data = {}
    offers = {}
    for entry in request(RADAR, "/stream" + path).get("streams", []):
        url = value(entry, "externalUrl")
        uri = urlsplit(url)
        if uri.scheme != "https" or uri.username or not uri.path.strip("/"):
            continue
        host = (uri.hostname or "").removeprefix("www.")
        for provider, known_hosts in HOSTS.items():
            if host in known_hosts:
                offers.setdefault(provider, url)
    original = language(value(data, "original_language") or value(data, "originalLanguage") or value(data, "language"))
    imdb = value(data, "imdb_id") or ident
    if not original and kind == "series" and IMDB_ID.fullmatch(imdb):
        show = request("https://api.tvmaze.com", "/lookup/shows?imdb=" + imdb)
        if show.get("externals", {}).get("imdb") == imdb:
            original = language(value(show, "language"))
    return data, offers, original


def record(provider, kind, title, details, row, verified_languages):
    meta, offers, original = details
    ident = title["id"]
    cast = meta.get("cast")
    cast = ", ".join(name for name in cast[:4] if isinstance(name, str)) if isinstance(cast, list) else ""
    year = value(meta, "year") or value(meta, "releaseInfo") or value(title, "releaseInfo")
    meta_imdb = value(meta, "imdb_id")
    rating = value(title, "imdbRating")
    if not meta_imdb or meta_imdb == ident:
        rating = value(meta, "imdbRating") or rating
    return {
        "id": ident,
        "kind": "movie" if kind == "movie" else "tv",
        "title": value(title, "name"),
        "provider": provider,
        "row": row,
        "language": original or verified_languages.get(value(meta, "imdb_id") or ident, "")
                    or language(value(title, "original_language") or value(title, "language")),
        "year": year[:4],
        "released": value(meta, "released")[:10],
        "rating": rating,
        "summary": (value(meta, "description") or value(title, "description"))[:1600],
        "cast": cast[:200],
        "poster": clean_poster(title, meta),
        "background": value(title, "background") or value(meta, "background"),
        "link": offers.get(provider, ""),
    }


def is_recent(item, today):
    try:
        released = date.fromisoformat(item["released"])
    except ValueError:
        return False
    return today - timedelta(days=45) <= released <= today


def select_records(items, limit):
    """Give verified-language titles room without reordering the selected chart entries."""
    by_id = {}
    for item in items:
        by_id.setdefault((item["kind"], item["id"], item["provider"]), item)
    records = list(by_id.values())
    verified = [i for i, item in enumerate(records) if item["language"]]
    unknown = [i for i, item in enumerate(records) if not item["language"]]
    return [records[i] for i in sorted((verified + unknown)[:limit])]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="directory to receive IN.json")
    parser.add_argument("--expanded", action="store_true", help="Keep the larger catalog for the private dashboard")
    args = parser.parse_args()
    today = datetime.now(ZoneInfo("Asia/Kolkata")).date()
    with ThreadPoolExecutor(max_workers=4) as pool:
        specs = [(provider, kind) for provider in (*HOSTS, "justin") for kind in TYPE]
        charts = list(pool.map(lambda spec: catalog(spec, args.expanded), specs))
        if (sum(bool(entries) for _, _, entries in charts) < 10
                or args.expanded and not all(entries for provider, _, entries in charts if provider == "justin")):
            raise SystemExit("Too few chart feeds were available. Kept the previous snapshot.")
        if args.expanded:
            charts = [(provider, kind, [title for title in entries if just_in_date(title, today)]
                       if provider == "justin" else entries) for provider, kind, entries in charts]
        order = [(provider, kind, entries[index]) for index in range(max(len(entries) for _, _, entries in charts))
                 for provider, kind, entries in charts if index < len(entries)]
        keys = dict.fromkeys((kind, title["id"]) for _, kind, title in order)
        details = dict(zip(keys, pool.map(enrich, keys)))

    languages = original_languages(value(meta, "imdb_id") or ident
                                   for (_, ident), (meta, _, original) in details.items() if not original)
    trending, recent = [], []
    for provider, kind, title in order:
        info = details[kind, title["id"]]
        if provider == "justin":
            # These two feeds mix providers. Include only titles with a confirmed offer.
            for provider in info[1]:
                item = record(provider, kind, title, info, "new", languages)
                if args.expanded:
                    item["_just_in_date"] = just_in_date(title, today)
                recent.append(item)
        else:
            trending.append(record(provider, kind, title, info, "trending", languages))
    if args.expanded:
        recent.sort(key=lambda item: item["_just_in_date"], reverse=True)
    else:
        seen_recent = {(item["id"], item["provider"]) for item in recent}
        recent.extend(dict(item, row="new") for item in trending
                      if is_recent(item, today) and (item["id"], item["provider"]) not in seen_recent)
    items = (select_records(recent, len(recent) if args.expanded else RECENT_LIMIT)
             + select_records(trending, len(trending) if args.expanded else TRENDING_LIMIT))
    if not any(item["language"] for item in items):
        raise SystemExit("No language data was available. Kept the previous snapshot.")
    result = {"version": 1, "region": "IN", "titles": items}
    args.output.mkdir(parents=True, exist_ok=True)
    path = args.output / "IN.json"
    temp = args.output / "IN.json.tmp"
    temp.write_text(json.dumps(result, ensure_ascii=False) + "\n", encoding="utf-8")
    temp.replace(path)
    print(f"{path}: {len(result['titles'])} entries, "
          f"{sum(bool(item['language']) for item in result['titles'])} with language, "
          f"{sum(bool(item['link']) for item in result['titles'])} with provider link")


if __name__ == "__main__":
    main()
