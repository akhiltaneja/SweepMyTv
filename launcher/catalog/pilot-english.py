#!/usr/bin/env python3
"""Compare the India snapshot with a private TMDB-enriched English catalog."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
from urllib.parse import urlencode, urlsplit

PROVIDERS = {
    "netflix": "Netflix", "prime": "Amazon Prime Video",
    "apple-tv-plus": "Apple TV", "jiohotstar": "JioHotstar",
}
TARGET = 8
MAX_TITLES = 192
IMDB = re.compile(r"tt[0-9]{1,12}")
IMAGE_PATH = re.compile(r"/[A-Za-z0-9_.-]+\.(?:jpg|jpeg|png|webp)")
TMDB_HOST = "api.themoviedb.org"
ART_HOSTS = {"image.tmdb.org", "btttr.cc", "images.metahub.space", "img10.hotstar.com",
             "origin-staticv2.sonyliv.com", "bingeddata.s3.amazonaws.com",
             "ch-static-image-prod.s3.ap-south-1.amazonaws.com"}


def tmdb_transports():
    yield ("-4",)
    # A CDN address can reset while the other addresses for the same HTTPS host work.
    try:
        records = socket.getaddrinfo(TMDB_HOST, 443, socket.AF_INET, socket.SOCK_STREAM)
        for address in dict.fromkeys(record[4][0] for record in records):
            yield ("--resolve", f"{TMDB_HOST}:443:{address}")
    except socket.gaierror:
        pass
    yield ("--http1.1",)
    yield ("-4", "--http1.1")
    yield ()


def tmdb(credential, path, **params):
    config = []
    if re.fullmatch(r"[a-fA-F0-9]{32}", credential):
        params["api_key"] = credential
    else:
        config.append(f'header = "Authorization: Bearer {credential}"')
    config.append(f'url = "https://{TMDB_HOST}/3' + path + "?" + urlencode(params, safe=",") + '"')
    # Config via stdin keeps both API key formats out of the visible process arguments.
    for transport in tmdb_transports():
        response = subprocess.run(
            ["curl", "--disable", *transport, "--silent", "--max-time", "10",
             "--retry", "1", "--retry-delay", "1", "--max-filesize", "2097152",
             "--header", "Accept: application/json", "--config", "-", "--write-out", "\n%{http_code}"],
            input="\n".join(config) + "\n", text=True, capture_output=True,
        )
        if response.returncode not in (6, 7, 28, 35, 52, 55, 56, 92):
            break
    if response.returncode:
        raise RuntimeError(f"TMDB connection failed (curl {response.returncode})")
    body, _, status = response.stdout.rpartition("\n")
    if status == "404" and path.startswith("/find/"):
        return {}
    if status != "200":
        raise RuntimeError(f"TMDB returned HTTP {status}")
    try:
        return json.loads(body)
    except ValueError:
        raise RuntimeError("TMDB returned invalid JSON") from None


def identity(row):
    return row["kind"], row["id"], row["provider"]


def has_poster(row):
    url = urlsplit(row.get("poster", ""))
    return url.scheme == "https" and url.hostname in ART_HOSTS and not url.username and bool(url.path)


def coverage(rows):
    english = [row for row in rows if row.get("language") == "en" and has_poster(row)]
    return {
        provider: {
            "eligible_titles": len({identity(row) for row in english if row["provider"] == provider}),
            "with_title_link": len({identity(row) for row in english if row["provider"] == provider and row.get("link")}),
        }
        for provider in PROVIDERS
    }


def image_url(path, size):
    return f"https://image.tmdb.org/t/p/{size}{path}" if isinstance(path, str) and IMAGE_PATH.fullmatch(path) else ""


def supplement(item, kind, imdb_id, provider, today):
    title = item.get("title" if kind == "movie" else "name")
    date_value = item.get("release_date" if kind == "movie" else "first_air_date", "")
    try:
        released = date.fromisoformat(date_value)
    except (TypeError, ValueError):
        return None
    poster = image_url(item.get("poster_path"), "w500")
    if (item.get("original_language") != "en" or not IMDB.fullmatch(imdb_id or "")
            or not title or released > today or item.get("adult") or not poster):
        return None
    return {"id": imdb_id, "kind": kind, "title": title, "provider": provider,
            "row": "new" if released >= today - timedelta(days=45) else "provider",
            "language": "en", "year": str(released.year), "released": date_value,
            "summary": (item.get("overview") or "")[:1600], "poster": poster,
            "background": image_url(item.get("backdrop_path"), "w1280"), "link": ""}


def add_supplement(rows, candidate, *, expanded=False):
    if not expanded and len(rows) >= MAX_TITLES:
        counts = coverage(rows)
        frequency = Counter(identity(row) for row in rows if row.get("language") == "en" and has_poster(row))
        donor = max((i for i, row in enumerate(rows) if row.get("language") == "en" and not row.get("link")
                     and counts.get(row["provider"], {}).get("eligible_titles", 0) > TARGET),
                    key=lambda i: (frequency[identity(rows[i])] > 1, rows[i]["row"] != "new",
                                   counts[rows[i]["provider"]]["eligible_titles"], i), default=None)
        if donor is None:
            return False
        del rows[donor]
    rows.append(candidate)
    return True


def build_pilot(baseline, request, today, *, expanded=False):
    rows = [row.copy() for row in baseline]
    english_ids = {(r["kind"], r["id"]) for r in rows
                   if r.get("language") == "en" and IMDB.fullmatch(r["id"])
                   and any(not r.get(field) for field in ("poster", "background", "summary"))}

    def enrich(key):
        kind, imdb = key
        matches = request(f"/find/{imdb}", external_source="imdb_id").get(f"{kind}_results", [])
        return matches[0] if len(matches) == 1 and matches[0].get("original_language") == "en" else {}

    with ThreadPoolExecutor(max_workers=4) as pool:
        keys = sorted(english_ids)
        details = dict(zip(keys, pool.map(enrich, keys)))
    for row in rows:
        if row.get("language") != "en":
            continue
        match = details.get((row["kind"], row["id"]), {})
        for field, value in {
            "poster": image_url(match.get("poster_path"), "w500"),
            "background": image_url(match.get("backdrop_path"), "w1280"),
            "summary": (match.get("overview") or "")[:1600],
        }.items():
            if value and not row.get(field):
                row[field] = value

    discovered = {provider: 0 for provider in PROVIDERS}
    gaps = {provider for provider, count in coverage(rows).items() if count["eligible_titles"] < TARGET}
    if not gaps:
        return rows, discovered
    for kind in ("tv", "movie"):
        ids = {p.get("provider_name"): p.get("provider_id") for p in request(f"/watch/providers/{kind}", watch_region="IN").get("results", [])}
        for provider, name in PROVIDERS.items():
            if provider not in gaps or not ids.get(name):
                continue
            candidates = request(f"/discover/{kind}", watch_region="IN", with_watch_providers=ids[name],
                                 with_watch_monetization_types="flatrate", with_original_language="en",
                                 include_adult="false", sort_by="popularity.desc").get("results", [])
            for item in candidates[:12]:
                if item.get("original_language") != "en" or not isinstance(item.get("id"), int):
                    continue
                imdb = request(f"/{kind}/{item['id']}/external_ids").get("imdb_id")
                candidate = supplement(item, kind, imdb, provider, today)
                if candidate is None or any(identity(r) == identity(candidate) for r in rows):
                    continue
                offers = request(f"/{kind}/{item['id']}/watch/providers").get("results", {}).get("IN", {}).get("flatrate", [])
                if not any(offer.get("provider_id") == ids[name] for offer in offers):
                    continue
                if not add_supplement(rows, candidate, expanded=expanded):
                    break
                discovered[provider] += 1
                if coverage(rows)[provider]["eligible_titles"] >= TARGET:
                    gaps.remove(provider)
                    break
        if not gaps:
            break
    return rows, discovered


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path, help="Verified StreamRadar IN.json")
    parser.add_argument("output", type=Path, help="Private pilot output directory")
    parser.add_argument("--token-stdin", action="store_true", help="Read an approved TMDB credential from stdin")
    parser.add_argument("--expanded", action="store_true", help="Enrich a private catalog beyond the TV feed limit")
    args = parser.parse_args()
    original = json.loads(args.baseline.read_text(encoding="utf-8"))
    if original.get("version") != 1 or original.get("region") != "IN" or not original.get("titles"):
        raise SystemExit("Expected a Sweep TV India catalog v1")
    baseline = original["titles"]
    token = (sys.stdin.read(4096) if args.token_stdin else os.environ.get("SWEEP_TMDB_TOKEN", "")).strip()
    if token and not re.fullmatch(r"[A-Za-z0-9_.-]{12,2048}", token):
        raise SystemExit("Unexpected TMDB credential format")
    if args.token_stdin and not token:
        raise SystemExit("No TMDB credential received")
    report = {"baseline": coverage(baseline), "live_tmdb": bool(token)}
    if token:
        try:
            rows, added = build_pilot(baseline, lambda path, **params: tmdb(token, path, **params), date.today(),
                                      expanded=args.expanded)
        except RuntimeError as exc:
            raise SystemExit(str(exc)) from None
        catalog = json.dumps({"version": 1, "region": "IN", "titles": rows}, ensure_ascii=False) + "\n"
        if len(catalog.encode("utf-8")) > (3 * 1024 * 1024 if args.expanded else 512 * 1024):
            raise SystemExit("Pilot catalog exceeds its size limit")
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "IN.json").write_text(catalog, encoding="utf-8")
        report.update(pilot=coverage(rows), tmdb_titles_added=added)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "comparison.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
