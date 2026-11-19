#!/usr/bin/env python3
"""Maintain the private Sweep TV catalog and serve its local admin dashboard."""
import argparse
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from datetime import date, datetime, timedelta, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import runpy
import sqlite3
import subprocess
import sys
from tempfile import TemporaryDirectory
from threading import Event, Thread
from urllib.parse import parse_qs, urlsplit
from zoneinfo import ZoneInfo

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
BUILDER = HERE.parent / "build-feed.py"
DEFAULT_DB = ROOT / "research/managed-catalog/catalog.sqlite3"
IST = ZoneInfo("Asia/Kolkata")
TARGET = 8
MIN_IMDB_RATING = 6.0
NETFLIX_ENGLISH_TARGET = 16
MAX_ROWS = 192
MAX_BYTES = 512 * 1024
LIBRARY_MAX_ROWS = 900
LIBRARY_MAX_BYTES = 3 * 1024 * 1024
PRIMARY = ("netflix", "prime", "apple-tv-plus", "jiohotstar")
PROVIDERS = {
    "netflix": {"netflix.com", "www.netflix.com"},
    "prime": {"primevideo.com", "www.primevideo.com", "www.amazon.in", "www.amazon.com"},
    "apple-tv-plus": {"tv.apple.com"},
    "jiohotstar": {"hotstar.com", "www.hotstar.com", "jiohotstar.com", "www.jiohotstar.com"},
    "sony-liv": {"sonyliv.com", "www.sonyliv.com"},
    "zee5": {"zee5.com", "www.zee5.com"},
}
ART_HOSTS = {"image.tmdb.org", "images.metahub.space", "live.metahub.space", "img10.hotstar.com",
             "origin-staticv2.sonyliv.com", "bingeddata.s3.amazonaws.com", "ch-static-image-prod.s3.ap-south-1.amazonaws.com"}
TITLE_ID = re.compile(r"(?:tt[0-9]{1,12}|streamradar:[a-zA-Z0-9:_-]{1,70})")
SCHEMA = """
CREATE TABLE IF NOT EXISTS titles (
    id INTEGER PRIMARY KEY, kind TEXT NOT NULL, external_id TEXT NOT NULL, name TEXT NOT NULL,
    original_language TEXT NOT NULL, year TEXT, released TEXT, summary TEXT, cast TEXT,
    UNIQUE(kind, external_id)
);
CREATE TABLE IF NOT EXISTS offers (
    title_id INTEGER NOT NULL REFERENCES titles(id), region TEXT NOT NULL, provider TEXT NOT NULL,
    url TEXT NOT NULL, source TEXT NOT NULL, observed_at TEXT NOT NULL,
    PRIMARY KEY(title_id, region, provider)
);
CREATE TABLE IF NOT EXISTS ratings (
    title_id INTEGER NOT NULL REFERENCES titles(id), source TEXT NOT NULL, score REAL NOT NULL,
    observed_at TEXT NOT NULL, PRIMARY KEY(title_id, source)
);
CREATE TABLE IF NOT EXISTS runs (
    id INTEGER PRIMARY KEY, started_at TEXT NOT NULL, finished_at TEXT, source TEXT NOT NULL,
    status TEXT NOT NULL, entries INTEGER, detail TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS snapshots (
    region TEXT PRIMARY KEY, run_id INTEGER NOT NULL REFERENCES runs(id), payload BLOB NOT NULL,
    checksum TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS libraries (
    region TEXT PRIMARY KEY, run_id INTEGER NOT NULL REFERENCES runs(id), payload BLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS supplements (
    kind TEXT NOT NULL, external_id TEXT NOT NULL, provider TEXT NOT NULL,
    source TEXT NOT NULL, observed_at TEXT NOT NULL, expires_at TEXT NOT NULL, payload TEXT NOT NULL,
    PRIMARY KEY(kind, external_id, provider)
);
CREATE TABLE IF NOT EXISTS editorial (
    kind TEXT NOT NULL, external_id TEXT NOT NULL, rank INTEGER UNIQUE,
    tmdb_id INTEGER, poster TEXT NOT NULL, background TEXT NOT NULL,
    updated_at TEXT NOT NULL, PRIMARY KEY(kind, external_id)
);
CREATE TABLE IF NOT EXISTS editorial_cards (
    kind TEXT NOT NULL, external_id TEXT NOT NULL, payload TEXT NOT NULL,
    PRIMARY KEY(kind, external_id)
);
CREATE TABLE IF NOT EXISTS section_edits (
    kind TEXT NOT NULL, external_id TEXT NOT NULL, provider TEXT NOT NULL, section TEXT NOT NULL,
    hidden INTEGER NOT NULL, position INTEGER,
    PRIMARY KEY(kind, external_id, provider, section)
);
CREATE TABLE IF NOT EXISTS catalog_issues (
    id INTEGER PRIMARY KEY,
    kind TEXT NOT NULL, external_id TEXT NOT NULL, provider TEXT NOT NULL, section TEXT NOT NULL,
    title TEXT NOT NULL, reason TEXT NOT NULL, first_seen TEXT NOT NULL, last_seen TEXT NOT NULL,
    resolved_at TEXT, run_id INTEGER NOT NULL REFERENCES runs(id)
);
CREATE UNIQUE INDEX IF NOT EXISTS active_catalog_issue
ON catalog_issues(kind, external_id, provider, section, reason) WHERE resolved_at IS NULL;
"""


def timestamp():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def connect(path):
    db = sqlite3.connect(path, timeout=10)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA foreign_keys = ON")
    return db


def initialize(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with closing(connect(path)) as db:
        db.execute("PRAGMA journal_mode = WAL")
        db.executescript(SCHEMA)


def approved_url(value, hosts):
    try:
        url = urlsplit(value)
        return value if (url.scheme == "https" and url.hostname in hosts and url.username is None
                         and url.port in (None, 443) and url.path.strip("/")) else ""
    except (TypeError, ValueError):
        return ""


def coverage(rows):
    posters, links = defaultdict(set), defaultdict(set)
    for row in rows:
        if row.get("language") == "en" and row.get("poster") and not row.get("_poster_unavailable"):
            identifier = (row["kind"], row["id"])
            posters[row["provider"]].add(identifier)
            if row.get("link"):
                links[row["provider"]].add(identifier)
    return {p: {"titles": len(posters[p]), "links": len(links[p])} for p in PROVIDERS}


def validate(feed, *, require_coverage, library=False):
    if not isinstance(feed, dict) or feed.get("version") != 1 or feed.get("region") != "IN":
        raise ValueError("Expected a Sweep TV India catalog v1")
    records = feed.get("titles")
    limit = LIBRARY_MAX_ROWS if library else MAX_ROWS
    if not isinstance(records, list) or not 0 < len(records) <= limit:
        raise ValueError(f"Catalog must contain 1–{limit} entries")
    rows, identities = [], set()
    for record in records:
        if not isinstance(record, dict) or not all(isinstance(record.get(key), str)
                                                   for key in ("id", "kind", "title", "provider", "row")):
            raise ValueError("Catalog contains a malformed title")
        row = {key: record[key] for key in ("id", "kind", "title", "provider", "row")}
        if (not TITLE_ID.fullmatch(row["id"]) or row["kind"] not in {"movie", "tv"}
                or row["provider"] not in PROVIDERS or row["row"] not in {"trending", "new", "provider"}
                or not 0 < len(row["title"].strip()) <= 200):
            raise ValueError("Catalog contains an unsupported title, provider or row")
        identity = tuple(row[key] for key in ("kind", "id", "provider", "row"))
        if identity in identities:
            raise ValueError("Catalog contains a duplicate entry in the same provider and row")
        identities.add(identity)
        for key in ("language", "year", "released", "summary", "cast"):
            value = record.get(key, "")
            row[key] = value if isinstance(value, str) else ""
        row["language"] = row["language"] if re.fullmatch(r"[a-z]{2}", row["language"]) else ""
        row["poster"] = approved_url(record.get("poster", ""), ART_HOSTS)
        row["background"] = approved_url(record.get("background", ""), ART_HOSTS)
        row["link"] = approved_url(record.get("link", ""), PROVIDERS[row["provider"]])
        if record.get("_poster_invalid") is True or record.get("poster") and not row["poster"]:
            row["_poster_invalid"] = True
        if record.get("_link_invalid") is True or record.get("link") and not row["link"]:
            row["_link_invalid"] = True
        if record.get("_poster_unavailable") is True:
            row["_poster_unavailable"] = True
        if type(record.get("_metadata_tmdb_id")) is int and record["_metadata_tmdb_id"] > 0:
            row["_metadata_tmdb_id"] = record["_metadata_tmdb_id"]
        if isinstance(record.get("_metadata_issue"), str) and record["_metadata_issue"]:
            row["_metadata_issue"] = record["_metadata_issue"][:120]
        if record.get("_prime_access") in ("included", "paid", "unconfirmed", "unavailable"):
            row["_prime_access"] = record["_prime_access"]
        if isinstance(record.get("_prime_checked"), str):
            row["_prime_checked"] = record["_prime_checked"]
        if record.get("_apple_access") in ("included", "unconfirmed"):
            row["_apple_access"] = record["_apple_access"]
        if record.get("_apple_original") is True and row["provider"] == "apple-tv-plus":
            row["_apple_original"] = True
        if record.get("_netflix_english") is True and row["provider"] == "netflix" and row["language"] == "en":
            row["_netflix_english"] = True
            if record.get("_netflix_access") in ("included", "unconfirmed"):
                row["_netflix_access"] = record["_netflix_access"]
        if record.get("_recommendation_policy") is True:
            row["_recommendation_policy"] = True
        if isinstance(record.get("_just_in_date"), str):
            try:
                row["_just_in_date"] = date.fromisoformat(record["_just_in_date"]).isoformat()
            except ValueError:
                pass
        try:
            value = record.get("rating", "")
            score = 0 if isinstance(value, bool) else float(value)
        except (TypeError, ValueError):
            score = 0
        if 0 < score <= 10:
            row["rating"] = score
        rows.append(row)
    if require_coverage and not any(row["poster"] or row["background"] for row in rows):
        raise ValueError("Catalog contains no usable artwork")
    if require_coverage:
        counts = coverage(rows)
        thin = [f"{p} {counts[p]['titles']}/{TARGET}" for p in PRIMARY if counts[p]["titles"] < TARGET]
        if thin:
            raise ValueError("English coverage below target for " + ", ".join(thin))
        if not {"trending", "new"}.issubset({r["row"] for r in rows if r["language"] == "en"}):
            raise ValueError("English Trending or Recently Released is empty")
    payload = json.dumps({"version": 1, "region": "IN", "titles": rows}, ensure_ascii=False).encode() + b"\n"
    if len(payload) > (LIBRARY_MAX_BYTES if library else MAX_BYTES):
        raise ValueError("Catalog exceeds its size limit")
    return rows, payload


def identity(row):
    return row["kind"], row["id"], row["provider"], row["row"]


TITLE_PATHS = {
    "netflix": r"/(?:[a-z]{2}/)?title/[0-9]+/?",
    "prime": r"/(?:[\w-]+/)*(?:detail|dp|gp/video/detail)/[a-zA-Z0-9]+/?",
    "apple-tv-plus": r"/(?:[a-z]{2}/)?(?:movie|show)/[^/]+/umc\.cmc\.[a-zA-Z0-9]+/?",
    "jiohotstar": r"/(?:in/)?(?:movies|shows)/(?:[^/]+/)+[0-9]+/?",
    "sony-liv": r"/(?:movies|shows)/[^/]+-[0-9]+/?",
    "zee5": r"/(?:[a-z]{2}/)?(?:movies|web-series|tv-shows)/details/[^/]+/[0-9]+-[^/]+/?",
}


def listing_issues(row):
    reasons = []
    poster, link = row["poster"], row["link"]
    if not poster:
        reasons.append("Invalid portrait poster URL" if row.get("_poster_invalid") else "Missing portrait poster")
    elif urlsplit(poster).hostname in {"images.metahub.space", "live.metahub.space"} and (
            row["id"] not in urlsplit(poster).path.split("/")):
        reasons.append("Poster belongs to a different title")
    elif row.get("_poster_unavailable"):
        reasons.append("Portrait poster unreachable")
    if not link:
        reasons.append("Invalid provider title link" if row.get("_link_invalid") else "Missing provider title link")
    elif not re.fullmatch(TITLE_PATHS[row["provider"]], urlsplit(link).path):
        reasons.append("Invalid provider title link")
    if not row["language"]:
        reasons.append("Missing original language")
    if not row["summary"].strip():
        reasons.append("Missing synopsis")
    if row.get("_metadata_issue"):
        reasons.append(row["_metadata_issue"])
    if row.get("_recommendation_policy"):
        if not row.get("rating"):
            reasons.append("IMDb rating pending")
        elif row["rating"] < MIN_IMDB_RATING:
            reasons.append("IMDb rating below 6.0")
    if row["provider"] == "prime" and link:
        access = row.get("_prime_access")
        cutoff = (datetime.now(timezone.utc) - timedelta(days=2)).isoformat(timespec="seconds")
        if access == "paid":
            reasons.append("Prime Video link requires rental or purchase")
        elif access == "unavailable":
            reasons.append("Prime membership check unavailable")
        elif access != "included" or row.get("_prime_checked", "") < cutoff:
            reasons.append("Included with Prime India not confirmed")
    if row["provider"] == "apple-tv-plus" and link and row.get("_apple_access") == "unconfirmed":
        reasons.append("Included with Apple TV India not confirmed")
    if row.get("_netflix_english") and row.get("_netflix_access") != "included":
        reasons.append("Netflix India subscription not confirmed")
    return reasons


def record_listing_issues(db, rows, run, now):
    open_issues = defaultdict(dict)
    for issue in db.execute("SELECT * FROM catalog_issues WHERE resolved_at IS NULL"):
        key = tuple(issue[k] for k in ("kind", "external_id", "provider", "section"))
        open_issues[key][issue["reason"]] = issue["id"]
    for row in rows:
        key = identity(row)
        open_reasons = open_issues[key]
        reasons = listing_issues(row)
        for reason in reasons:
            if reason in open_reasons:
                db.execute("UPDATE catalog_issues SET last_seen=?, run_id=? WHERE id=?",
                           (now, run, open_reasons[reason]))
            else:
                db.execute("""INSERT INTO catalog_issues
                    (kind, external_id, provider, section, title, reason, first_seen, last_seen, run_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", (*key, row["title"], reason, now, now, run))
        for reason, issue_id in open_reasons.items():
            if reason not in reasons:
                db.execute("UPDATE catalog_issues SET resolved_at=? WHERE id=?", (now, issue_id))


def select_tv_rows(rows):
    if len(rows) <= MAX_ROWS:
        return rows
    selected = {}
    for provider in PRIMARY:
        english = [r for r in rows if r["provider"] == provider and r["language"] == "en" and r["poster"]]
        eligible = sorted(english, key=lambda r: (not r["link"], r["row"] == "new"))
        target = NETFLIX_ENGLISH_TARGET if provider == "netflix" else TARGET
        distinct = set()
        for row in eligible:
            key = row["kind"], row["id"]
            if key in distinct:
                continue
            distinct.add(key)
            selected[identity(row)] = row
            if len(distinct) == target:
                break
    recent = [r for r in rows if r["row"] == "new"]
    # Include a recent English title when one exists for each main app.
    for provider in PRIMARY:
        match = next((r for r in recent if r["provider"] == provider and r["language"] == "en" and r["poster"]), None)
        if match:
            selected[identity(match)] = match
    recent_count = sum(r["row"] == "new" for r in selected.values())
    for row in sorted(recent, key=lambda r: (not r["language"], not r["poster"])):
        key = identity(row)
        if key not in selected and recent_count < 48:
            selected[key] = row
            recent_count += 1
    for row in sorted(rows, key=lambda r: (not r["language"], not r["poster"])):
        if len(selected) >= MAX_ROWS:
            break
        if row["row"] != "new":
            selected.setdefault(identity(row), row)
    for row in recent:
        if len(selected) >= MAX_ROWS:
            break
        selected.setdefault(identity(row), row)
    return [r for r in rows if identity(r) in selected]


def begin(path, source):
    with closing(connect(path)) as db, db:
        return db.execute("INSERT INTO runs (started_at, source, status) VALUES (?, ?, 'running')",
                          (timestamp(), source)).lastrowid


def finish(path, run, status, detail):
    with closing(connect(path)) as db, db:
        db.execute("UPDATE runs SET finished_at = ?, status = ?, detail = ? WHERE id = ?",
                   (timestamp(), status, detail, run))


def repair_streamradar_matches(feed, *, rating_lookup=None):
    # The JioHotstar offer and backdrop match Curry Barker's film (TMDB 1339713).
    # The chart paired that offer with tt39060004, a different film with the same name.
    def repair(row):
        if (not isinstance(row, dict) or row.get("kind") != "movie" or row.get("id") != "tt39060004"
                or row.get("provider") != "jiohotstar"
                or row.get("link") != "https://www.hotstar.com/in/movies/obsession/1271707974"):
            return row
        corrected = dict(row, id="tt37287335", year="2026", language="en", released="2026-05-13",
                         summary="A hopeless romantic makes a wish to win his crush's heart, "
                                 "and discovers that getting what he asked for comes at a sinister price.",
                         cast="Michael Johnston, Inde Navarrette, Cooper Tomlinson, Megan Lawless, Andy Richter",
                         poster="https://image.tmdb.org/t/p/w780/bRwnj8WEKBCvmfeUNOukJPwB43K.jpg",
                         background="https://image.tmdb.org/t/p/w1280/5lTZyuBTNOfawsfPT8Q0cIg6qAF.jpg")
        corrected.pop("rating", None)
        if rating_lookup is not None:
            rating = rating_lookup(corrected["kind"], corrected["id"])
            if rating:
                corrected["rating"] = rating
        return corrected

    if not isinstance(feed, dict) or not isinstance(feed.get("titles"), list):
        return feed
    already_matched = {row.get("row") for row in feed["titles"] if isinstance(row, dict)
                       and row.get("kind") == "movie" and row.get("id") == "tt37287335"
                       and row.get("provider") == "jiohotstar" and isinstance(row.get("row"), str)}
    rows = []
    for row in feed["titles"]:
        corrected = repair(row)
        if corrected is row or not isinstance(row.get("row"), str) or row["row"] not in already_matched:
            rows.append(corrected)
    return dict(feed, titles=rows)


def streamradar_rating(kind, imdb_id):
    builder = runpy.run_path(str(BUILDER))
    endpoint = f"/meta/{'series' if kind == 'tv' else kind}/{imdb_id}.json"
    meta = builder["request"](builder["RADAR"], endpoint).get("meta")
    if isinstance(meta, dict) and meta.get("imdb_id") == imdb_id:
        return builder["value"](meta, "imdbRating")
    return ""


def commit(path, run, feed, source, *, supplements=None, expanded=False, refresh_prime_only=False):
    if source != "Sweep TV verified input" and not refresh_prime_only:
        feed = check_metadata(repair_streamradar_matches(feed))
    feed = check_prime_membership(feed)
    if not refresh_prime_only:
        feed = check_posters(feed)
    if source != "Sweep TV verified input":
        feed = dict(feed, titles=check_apple_membership(feed["titles"]))
        feed = dict(feed, titles=check_netflix_pool(feed["titles"]))
    return store_catalog(path, run, feed, source, supplements=supplements, expanded=expanded)


def store_catalog(path, run, feed, source, *, supplements=None, expanded=False, reviewed_provider=None,
                  expected_active_run=None):
    rows, library_payload = validate(feed, require_coverage=True, library=expanded)
    _, payload = validate(dict(feed, titles=select_tv_rows(rows)), require_coverage=True)
    now = timestamp()
    supplements = supplements or {}
    with closing(connect(path)) as db, db:
        db.execute("BEGIN IMMEDIATE")
        newer = db.execute("SELECT 1 FROM catalog_issues WHERE run_id > ? LIMIT 1", (run,)).fetchone()
        active = snapshot(db)
        if newer or (active and active["run_id"] > run):
            raise ValueError("A newer run already checked the catalog")
        if expected_active_run is not None and (not active or active["run_id"] != expected_active_run):
            raise ValueError("Catalog changed during the provider review. Retry against the latest snapshot.")
        record_listing_issues(db, (row for row in rows if not reviewed_provider or row["provider"] == reviewed_provider),
                              run, now)
        for row in rows:
            provenance = supplements.get((row["kind"], row["id"], row["provider"]))
            observed = provenance["observed_at"] if provenance else now
            offer_source = provenance["source"] if provenance else (
                "Apple TV India · TMDB metadata match" if row.get("_apple_original") else
                "Netflix India · TMDB/JustWatch subscription match" if row.get("_netflix_english") else source)
            title_id = db.execute("""
                INSERT INTO titles (kind, external_id, name, original_language, year, released, summary, cast)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(kind, external_id) DO UPDATE SET name=excluded.name,
                  original_language=excluded.original_language, year=excluded.year,
                  released=excluded.released, summary=excluded.summary, cast=excluded.cast
                RETURNING id
            """, tuple(row[k] for k in ("kind", "id", "title", "language", "year", "released", "summary", "cast"))).fetchone()[0]
            db.execute("""
                INSERT INTO offers VALUES (?, 'IN', ?, ?, ?, ?)
                ON CONFLICT(title_id, region, provider) DO UPDATE SET
                  url=excluded.url, source=excluded.source, observed_at=excluded.observed_at
            """, (title_id, row["provider"], row["link"], offer_source, observed))
            if "rating" in row:
                db.execute("""
                    INSERT INTO ratings VALUES (?, 'IMDb', ?, ?)
                    ON CONFLICT(title_id, source) DO UPDATE SET score=excluded.score, observed_at=excluded.observed_at
                """, (title_id, row["rating"], observed))
        digest = hashlib.sha256(payload).hexdigest()
        db.execute("""
            INSERT INTO snapshots VALUES ('IN', ?, ?, ?) ON CONFLICT(region) DO UPDATE SET
              run_id=excluded.run_id, payload=excluded.payload, checksum=excluded.checksum
        """, (run, payload, digest))
        db.execute("""
            INSERT INTO libraries VALUES ('IN', ?, ?) ON CONFLICT(region) DO UPDATE SET
              run_id=excluded.run_id, payload=excluded.payload
        """, (run, library_payload))
        accepted = json.loads(sweep_snapshot(db, snapshot(db)))["titles"]
        detail = f"{len(accepted)} listings in the TV feed"
        if reviewed_provider == "apple-tv-plus":
            detail += " · Apple TV India refreshed"
        if reviewed_provider == "netflix":
            detail += " · Netflix India English refreshed"
        db.execute("UPDATE runs SET finished_at=?, status='success', entries=?, detail=? WHERE id=?",
                   (now, len(rows), detail, run))
    return len(rows)


def read_input(path, *, library=False):
    limit = LIBRARY_MAX_BYTES if library else MAX_BYTES
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise ValueError("Catalog input exceeds its size limit")
    return json.loads(data)


def import_feed(path, source_file):
    source = "Imported TV snapshot"
    run = begin(path, source)
    try:
        return commit(path, run, read_input(source_file), source)
    except (OSError, ValueError) as error:
        finish(path, run, "failed", str(error))
        raise


def import_verified(path, source_file):
    source = "Sweep TV verified input"
    run = begin(path, source)
    try:
        with closing(connect(path)) as db:
            active = snapshot(db)
        if active is not None and active["source"] != source:
            raise ValueError("Use a separate database for public inputs")
        feed = read_input(source_file, library=True)
        if not isinstance(feed, dict) or feed.get("publication") != "approved":
            raise ValueError("Mark the independently verified input as approved for publication")
        return commit(path, run, feed, source, expanded=True)
    except (OSError, ValueError) as error:
        finish(path, run, "failed", str(error))
        raise


def save_supplement(path, source_file, external_id, provider, source):
    rows, _ = validate(read_input(source_file), require_coverage=False)
    match = next((r for r in rows if r["id"] == external_id and r["provider"] == provider
                  and r["row"] == "provider" and r["language"] == "en" and r["poster"] and r["link"]), None)
    if match is None:
        raise ValueError("Expected one English provider-only entry with clean art and an exact title link")
    now = datetime.now(timezone.utc)
    with closing(connect(path)) as db, db:
        db.execute("""
            INSERT INTO supplements VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(kind, external_id, provider) DO UPDATE SET source=excluded.source,
              observed_at=excluded.observed_at, expires_at=excluded.expires_at, payload=excluded.payload
        """, (match["kind"], match["id"], provider, source, now.isoformat(timespec="seconds"),
              (now + timedelta(days=7)).isoformat(timespec="seconds"), json.dumps(match)))


def fill_with_supplements(path, feed, *, expanded=False):
    rows, _ = validate(feed, require_coverage=False, library=expanded)
    with closing(connect(path)) as db:
        candidates = db.execute("SELECT * FROM supplements WHERE expires_at > ? ORDER BY observed_at DESC",
                                (timestamp(),)).fetchall()
    used = {}
    for candidate in candidates:
        key = (candidate["kind"], candidate["external_id"], candidate["provider"])
        counts = coverage(rows)
        if counts[key[2]]["titles"] >= TARGET or any((r["kind"], r["id"], r["provider"]) == key for r in rows):
            continue
        item = json.loads(candidate["payload"])
        if expanded:
            if not poster_responds(item["poster"]):
                continue
        elif len(rows) >= MAX_ROWS:
            # Preserve the multilingual catalog and the leading trend entries.
            donor = next((i for i in range(len(rows) - 1, -1, -1)
                          if rows[i]["row"] == "trending" and rows[i]["language"] == "en" and not rows[i]["link"]
                          and counts[rows[i]["provider"]]["titles"] > TARGET), None)
            if donor is None:
                continue
            del rows[donor]
        rows.append(item)
        used[key] = dict(candidate)
    return dict(feed, titles=rows), used


def poster_responds(url):
    def headers(value):
        probe = subprocess.run(["curl", "--disable", "--silent", "--head", "--max-time", "8",
                                "--output", os.devnull, "--write-out", "%{http_code}\t%{content_type}\t%{redirect_url}", value],
                               capture_output=True, text=True)
        parts = probe.stdout.split("\t", 2)
        return parts if probe.returncode == 0 and len(parts) == 3 else ("", "", "")

    status, kind, location = headers(url)
    origin = urlsplit(url)
    if (status == "307" and origin.hostname == "images.metahub.space"
            and location == "https://live.metahub.space" + origin.path):
        status, kind, _ = headers(location)
    return status == "200" and kind.startswith("image/")


def check_posters(feed):
    rows, _ = validate(feed, require_coverage=False, library=True)
    urls = list(dict.fromkeys(row["poster"] for row in rows if row["poster"]))
    with ThreadPoolExecutor(max_workers=8) as pool:
        available = dict(zip(urls, pool.map(poster_responds, urls)))
    for row in rows:
        row.pop("_poster_unavailable", None)
        if row["poster"] and not available[row["poster"]]:
            row["_poster_unavailable"] = True
    return dict(feed, titles=rows)


def check_metadata(feed):
    from metadata_check import request_from_config, verify_rows

    rows, _ = validate(feed, require_coverage=False, library=True)
    try:
        verified = verify_rows(rows, request_from_config())
    except RuntimeError as error:
        raise ValueError(f"Private metadata verification failed: {error}") from None
    return dict(feed, titles=verified)


def check_prime_membership(feed):
    from metadata_check import request_from_config
    from prime_access import check_offers

    rows, _ = validate(feed, require_coverage=False, library=True)
    check_offers(rows, request_from_config)
    now = timestamp()
    for row in rows:
        if row["provider"] == "prime" and row["link"]:
            row["_prime_checked"] = now
    return dict(feed, titles=rows)


def check_apple_membership(rows):
    from apple_catalog import verify

    return verify(rows, datetime.now(IST).date())


def check_netflix_pool(rows):
    if not any(row.get("_netflix_english") for row in rows):
        return rows
    from metadata_check import request_from_config
    from netflix_catalog import verify_pool

    return verify_pool(rows, request_from_config())


def refresh(path, source_file=None):
    source = "StreamRadar + TVmaze + Wikidata · TMDB metadata check"
    run = begin(path, source)
    try:
        with TemporaryDirectory(prefix="sweep-refresh-") as directory:
            stage = Path(directory)
            candidate = source_file
            if candidate is None:
                builder_env = dict(os.environ)
                builder_env.pop("SWEEP_TMDB_TOKEN", None)
                builder = subprocess.run([sys.executable, str(BUILDER), "--expanded", directory],
                                         capture_output=True, timeout=600, env=builder_env)
                if builder.returncode:
                    raise ValueError("StreamRadar catalog build failed")
                candidate = stage / "IN.json"
            feed = read_input(candidate, library=True)
            # One fetch per corrected title, even when it appears in multiple sections.
            ratings = {}
            def repaired_rating(kind, imdb_id):
                key = kind, imdb_id
                if key not in ratings:
                    ratings[key] = streamradar_rating(kind, imdb_id)
                return ratings[key]

            raw = repair_streamradar_matches(feed, rating_lookup=repaired_rating)
            feed, supplements = fill_with_supplements(path, raw, expanded=True)
            with closing(connect(path)) as db:
                library = db.execute("SELECT payload FROM libraries WHERE region='IN'").fetchone()
            if library:
                present = {identity(row) for row in feed["titles"]}
                feed["titles"].extend(row for row in json.loads(library["payload"])["titles"]
                                      if (row.get("_apple_original") or row.get("_netflix_english"))
                                      and identity(row) not in present)
            feed = dict(feed, titles=[dict(row, _recommendation_policy=True) for row in feed["titles"]])
            return commit(path, run, feed, source, supplements=supplements, expanded=True)
    except (OSError, ValueError, subprocess.TimeoutExpired) as error:
        finish(path, run, "failed", str(error))
        raise


def refresh_provider(path, provider, source_file=None):
    from metadata_check import request_from_config

    if provider == "apple-tv-plus":
        from apple_catalog import discover
        flag, verify = "_apple_original", check_apple_membership
    else:
        from netflix_catalog import discover
        flag, verify = "_netflix_english", check_netflix_pool
    with closing(connect(path)) as db:
        db.execute("BEGIN")
        active = snapshot(db)
        library = db.execute("SELECT payload FROM libraries WHERE region='IN'").fetchone()
        if not active or not library or active["source"] == "Sweep TV verified input":
            raise ValueError("Provider discovery requires an existing private catalog")
        existing = json.loads(library["payload"])["titles"]
        provenance = {(row["kind"], row["external_id"], row["provider"]): dict(row) for row in db.execute(
            "SELECT titles.kind, titles.external_id, offers.* FROM offers JOIN titles ON titles.id=offers.title_id "
            "WHERE offers.region='IN' AND provider<>?", (provider,))}
    source = active["source"]
    run = begin(path, source)
    try:
        today = datetime.now(IST).date()
        discovered = (read_input(source_file, library=True)["titles"] if source_file
                      else discover(today, request_from_config(), streamradar_rating, existing))
        present = {identity(row) for row in existing}
        additions = [dict(row, _recommendation_policy=True, **{flag: True})
                     for row in discovered if row.get("provider") == provider and row.get("row") == "provider"
                     and (provider != "netflix" or row.get("language") == "en") and identity(row) not in present]
        if additions:
            additions = check_posters(check_metadata({"version": 1, "region": "IN", "titles": additions}))["titles"]
        rows = verify(existing + additions)
        return store_catalog(path, run, {"version": 1, "region": "IN", "titles": rows}, source,
                             supplements=provenance, expanded=True, reviewed_provider=provider,
                             expected_active_run=active["run_id"])
    except (KeyError, OSError, RuntimeError, ValueError) as error:
        finish(path, run, "failed", str(error))
        raise


def snapshot(db):
    return db.execute("""
        SELECT snapshots.*, runs.finished_at, runs.source FROM snapshots
        JOIN runs ON runs.id = snapshots.run_id WHERE region = 'IN'
    """).fetchone()


def same_source_image(first, second):
    left, right = urlsplit(first), urlsplit(second)
    return (left.hostname == right.hostname
            and left.path.rsplit("/", 1)[-1] == right.path.rsplit("/", 1)[-1])


def eligible_listing(row, source, blocked):
    return (identity(row) not in blocked and not listing_issues(row)
            and (source == "Sweep TV verified input" or (bool(row.get("_metadata_tmdb_id"))
                 and (row["provider"] != "apple-tv-plus" or row.get("_apple_access") == "included"))))


def sweep_snapshot(db, active):
    if active is None:
        return None
    seed = json.loads(active["payload"])["titles"]
    library = db.execute("SELECT payload FROM libraries WHERE region='IN' AND run_id=?", (active["run_id"],)).fetchone()
    candidates = json.loads(library["payload"])["titles"] if library else seed
    blocked = {tuple(issue) for issue in db.execute(
        "SELECT kind, external_id, provider, section FROM catalog_issues WHERE resolved_at IS NULL")}
    editorial = {(r["kind"], r["external_id"]): r for r in db.execute("SELECT * FROM editorial")}
    edits = {tuple(edit[:4]): edit for edit in db.execute("SELECT * FROM section_edits")}
    def eligible(row):
        edit = edits.get(identity(row))
        return not (edit and edit["hidden"]) and eligible_listing(row, active["source"], blocked)

    seed = select_tv_rows([row for row in candidates if eligible(row)])
    selected = {}
    for row in candidates:
        edit = editorial.get((row["kind"], row["id"]))
        if edit and edit["rank"] is not None and eligible(row):
            selected.setdefault(identity(row), row)
    for card in db.execute("""
        SELECT editorial_cards.payload FROM editorial_cards
        JOIN editorial USING(kind, external_id) WHERE editorial.rank IS NOT NULL
    """):
        for row in json.loads(card["payload"]):
            if eligible(row):
                selected.setdefault(identity(row), row)
    for row in candidates:
        edit = edits.get(identity(row))
        if len(selected) < MAX_ROWS and edit and edit["position"] is not None and eligible(row):
            selected.setdefault(identity(row), row)
    for row in seed:
        if len(selected) >= MAX_ROWS:
            break
        selected.setdefault(identity(row), row)
    ordered = list(selected.values())
    for section in ("trending", "new"):
        slots = [index for index, row in enumerate(ordered) if row["row"] == section]
        rows = [ordered[index] for index in slots]
        ranks = {identity(row): edits[identity(row)]["position"] for row in rows if identity(row) in edits}
        for row in sorted(rows, key=lambda row: ranks.get(identity(row)) or MAX_ROWS + 1):
            position = ranks.get(identity(row))
            if position is not None:
                rows.remove(row)
                rows.insert(min(position - 1, len(rows)), row)
        for index, row in zip(slots, rows):
            ordered[index] = row
    # The TV takes the first eight distinct titles for each app row. Give Apple
    # Originals room alongside its chart while preserving both discovery orders.
    apple_chart = next((row for row in ordered if row["provider"] == "apple-tv-plus"
                        and row["row"] == "trending"), None)
    if apple_chart:
        chart_titles = {(row["kind"], row["id"]) for row in ordered
                        if row["provider"] == "apple-tv-plus" and row["row"] == "trending"}
        originals = [row for row in ordered if row.get("_apple_original") and row["row"] == "provider"
                     and (row["kind"], row["id"]) not in chart_titles][:4]
        for row in originals:
            ordered.remove(row)
        first_chart = ordered.index(apple_chart)
        ordered[first_chart:first_chart] = originals
    output = []
    for row in ordered:
        item = {field: value for field, value in row.items() if not field.startswith("_")}
        edit = editorial.get((item["kind"], item["id"]))
        if edit:
            if edit["rank"] is not None:
                item["editorial_rank"] = edit["rank"]
            for field in ("poster", "background"):
                if edit[field] and (active["source"] == "Sweep TV verified input"
                                    or same_source_image(edit[field], item[field])):
                    item[field] = edit[field]
        output.append(item)
    feed = json.dumps({"version": 1, "region": "IN", "titles": output}, ensure_ascii=False).encode() + b"\n"
    if len(output) > MAX_ROWS or len(feed) > MAX_BYTES:
        raise ValueError("Edited feed exceeds the TV catalog limit")
    return feed


def save_section_edit(path, data):
    key = tuple(data.get(field) for field in ("kind", "id", "provider", "row"))
    if any(not isinstance(field, str) for field in key) or key[3] not in {"new", "trending"}:
        raise ValueError("Choose a Trending or Recently Released listing")
    hidden, position = data.get("hidden"), data.get("position")
    if type(hidden) is not bool or (position is not None and (type(position) is not int or not 1 <= position <= MAX_ROWS)):
        raise ValueError("Position must be between 1 and 192")
    with closing(connect(path)) as db, db:
        db.execute("BEGIN IMMEDIATE")
        active = snapshot(db)
        library = db.execute("SELECT payload FROM libraries WHERE region='IN'").fetchone()
        rows = json.loads(library["payload"] if library else active["payload"])["titles"] if active else []
        archived = db.execute("SELECT payload FROM editorial_cards WHERE kind=? AND external_id=?", key[:2]).fetchone()
        if archived:
            rows.extend(json.loads(archived["payload"]))
        if not any(identity(row) == key for row in rows):
            raise ValueError("Listing is no longer in the active catalog")
        if not hidden and position is None:
            db.execute("DELETE FROM section_edits WHERE (kind, external_id, provider, section) = (?, ?, ?, ?)", key)
        else:
            db.execute("""INSERT INTO section_edits VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(kind, external_id, provider, section) DO UPDATE SET
                hidden=excluded.hidden, position=excluded.position""", (*key, hidden, position))
        sweep_snapshot(db, active)


def save_editorial(path, data, checker=poster_responds):
    kind, external = data.get("kind"), data.get("id")
    if kind not in ("movie", "tv") or not isinstance(external, str) or not TITLE_ID.fullmatch(external):
        raise ValueError("Invalid catalog title")
    rank, tmdb_id = data.get("rank"), data.get("tmdb_id")
    if rank is not None and (type(rank) is not int or not 1 <= rank <= 10):
        raise ValueError("Rank must be between 1 and 10")
    if tmdb_id is not None:
        if type(tmdb_id) is not int or tmdb_id <= 0:
            raise ValueError("Enter a valid TMDB ID")
        from tmdb_index import index_path, search
        if not search(index_path(path), str(tmdb_id), kind)["titles"]:
            raise ValueError("That TMDB ID and title type are missing from the private index")
    artwork = []
    for field in ("poster", "background"):
        url = data.get(field, "")
        if url and (not isinstance(url, str) or len(url) > 2048 or not approved_url(url, ART_HOSTS) or not checker(url)):
            raise ValueError(f"The {field} must be a reachable image on a supported host")
        artwork.append(url or "")
    with closing(connect(path)) as db, db:
        db.execute("BEGIN IMMEDIATE")
        active = snapshot(db)
        library = db.execute("SELECT payload FROM libraries WHERE region='IN'").fetchone()
        rows = json.loads(library["payload"] if library else active["payload"])["titles"] if active else []
        matching = [r for r in rows if r["kind"] == kind and r["id"] == external]
        if not matching:
            raise ValueError("Title is not in the active catalog")
        if active["source"] != "Sweep TV verified input" and any(override and not all(
                same_source_image(override, row[field]) for row in matching)
                for field, override in zip(("poster", "background"), artwork)):
            raise ValueError("Private artwork overrides must use the matched title's verified image")
        if rank is not None and not any(r["link"] and r["language"] for r in matching):
            raise ValueError("A handpicked title needs a known language and a provider title link")
        if rank is not None:
            db.execute("UPDATE editorial SET rank=NULL WHERE rank=? AND (kind<>? OR external_id<>?)", (rank, kind, external))
        db.execute("""
            INSERT INTO editorial VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(kind, external_id) DO UPDATE SET rank=excluded.rank, tmdb_id=excluded.tmdb_id,
              poster=excluded.poster, background=excluded.background, updated_at=excluded.updated_at
        """, (kind, external, rank, tmdb_id, *artwork, timestamp()))
        if rank is not None:
            pinned = [r for r in matching if r["link"] and r["language"]]
            db.execute("""
                INSERT INTO editorial_cards VALUES (?, ?, ?)
                ON CONFLICT(kind, external_id) DO UPDATE SET payload=excluded.payload
            """, (kind, external, json.dumps(pinned)))
        sweep_snapshot(db, active)


def overview(path, next_refresh=None):
    with closing(connect(path)) as db:
        db.execute("BEGIN")
        active = snapshot(db)
        runs = [dict(r) for r in db.execute("SELECT * FROM runs ORDER BY id DESC LIMIT 30")]
        issues = [dict(r) for r in db.execute(
            "SELECT * FROM catalog_issues WHERE resolved_at IS NULL ORDER BY last_seen DESC, id DESC")]
        blocked = {tuple(issue[key] for key in ("kind", "external_id", "provider", "section")) for issue in issues}
        open_issues = len(issues)
        issues.extend(dict(r) for r in db.execute(
            "SELECT * FROM catalog_issues WHERE resolved_at IS NOT NULL ORDER BY resolved_at DESC, id DESC LIMIT 200"))
        tv_rows = json.loads(active["payload"])["titles"] if active else []
        library = db.execute("SELECT payload FROM libraries WHERE region='IN' AND run_id=?",
                             (active["run_id"],)).fetchone() if active else None
        rows = json.loads(library["payload"])["titles"] if library else tv_rows
        stored = {(r["kind"], r["external_id"]): r["id"] for r in db.execute("SELECT kind, external_id, id FROM titles")}
        offers = {(r["title_id"], r["provider"]): dict(r) for r in db.execute("SELECT * FROM offers WHERE region='IN'")}
        supplements = {(r["kind"], r["external_id"], r["provider"]): r["expires_at"]
                       for r in db.execute("SELECT kind, external_id, provider, expires_at FROM supplements")}
        editorial = {(r["kind"], r["external_id"]): dict(r) for r in db.execute("SELECT * FROM editorial")}
        edits = {tuple(r[:4]): dict(r) for r in db.execute("SELECT * FROM section_edits")}
        archived = {identity(row): row for item in db.execute("SELECT payload FROM editorial_cards")
                    for row in json.loads(item["payload"])}
        sweep = sweep_snapshot(db, active)
        if sweep:
            tv_rows = json.loads(sweep)["titles"]
    details = []
    section_positions, section_counts = {}, defaultdict(int)
    for row in tv_rows:
        section_counts[row["row"]] += 1
        section_positions[identity(row)] = section_counts[row["row"]]
    catalog_keys = {identity(row) for row in rows}
    rows = rows + [archived.get(identity(row), row) for row in tv_rows if identity(row) not in catalog_keys]
    for row in rows:
        title_id = stored[(row["kind"], row["id"])]
        offer = offers[(title_id, row["provider"])]
        key = identity(row)
        details.append(dict(row, title_id=title_id, source=offer["source"], observed_at=offer["observed_at"],
                            in_feed=key in section_positions, section_position=section_positions.get(key),
                            eligible=eligible_listing(row, active["source"], blocked),
                            section_edit=edits.get(key),
                            editorial=editorial.get((row["kind"], row["id"])),
                            supplement_expires=supplements.get((row["kind"], row["id"], row["provider"]))))
    return {"titles": details, "entries": len(rows), "unique_titles": len({(r["id"], r["kind"]) for r in rows}),
            "coverage": coverage(rows), "tv_coverage": coverage(tv_rows), "tv_entries": len(tv_rows),
            "runs": runs, "issues": issues, "open_issues": open_issues, "target": TARGET, "next_refresh": next_refresh,
            "snapshot": ({"checksum": hashlib.sha256(sweep).hexdigest(), "finished_at": active["finished_at"],
                          "source": "Sweep TV · " + active["source"], "bytes": len(sweep)} if sweep else None)}


def export_feed(path, target):
    with closing(connect(path)) as db:
        db.execute("BEGIN")
        saved = sweep_snapshot(db, snapshot(db))
    if saved is None:
        raise ValueError("No validated snapshot is available")
    target.parent.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(prefix="sweep-export-", dir=target.parent) as staging:
        file = Path(staging) / "IN.json"
        file.write_bytes(saved)
        file.replace(target)


def next_refresh(now):
    india_now = now.astimezone(IST)
    window = india_now.replace(hour=india_now.hour // 6 * 6, minute=0, second=0, microsecond=0)
    return window + timedelta(hours=6)


def scheduled_refresh(path, stop, feed_file, verified_input):
    while True:
        now = datetime.now(IST)
        if stop.wait((next_refresh(now) - now).total_seconds()):
            return
        try:
            if verified_input:
                import_verified(path, verified_input)
            else:
                refresh(path)
        except (OSError, ValueError, subprocess.TimeoutExpired) as error:
            print(f"Catalog refresh kept the previous snapshot: {error}", file=sys.stderr)
        if feed_file:
            try:
                export_feed(path, feed_file)
            except (OSError, ValueError) as error:
                print(f"Static export kept its previous file: {error}", file=sys.stderr)


def serve(path, port, run_scheduled, feed_file, verified_input=None, *, feed_only=False):
    from tmdb_index import index_path, search

    assets = {"/": (HERE / "index.html", "text/html; charset=utf-8"),
              "/admin.css": (HERE / "admin.css", "text/css; charset=utf-8"),
              "/admin.js": (HERE / "admin.js", "text/javascript; charset=utf-8"),
              "/outfit.ttf": (ROOT / "website/dist/assets/outfit.ttf", "font/ttf")}
    allowed_hosts = {f"localhost:{port}", f"127.0.0.1:{port}"}
    if feed_only:
        allowed_hosts.update({"localhost:4190", "127.0.0.1:4190"})

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            if feed_only:
                self.send_error(404)
                return
            host = self.headers.get("Host")
            editors = {"/api/editorial": save_editorial, "/api/section-edit": save_section_edit}
            if (self.path not in editors or host not in allowed_hosts
                    or self.headers.get("Origin") != f"http://{host}"
                    or self.headers.get_content_type() != "application/json"):
                self.send_error(403)
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 8192:
                    raise ValueError("Invalid editorial request size")
                data = json.loads(self.rfile.read(length))
                if not isinstance(data, dict):
                    raise ValueError("Invalid editorial request")
                editors[self.path](path, data)
                if feed_file:
                    export_feed(path, feed_file)
            except ValueError as error:
                self.send_payload(json.dumps({"error": str(error)}).encode(), "application/json", status=400)
            except (OSError, sqlite3.Error):
                self.send_error(503)
            else:
                self.send_payload(b'{"saved":true}', "application/json")

        def do_HEAD(self):
            self.do_GET()

        def do_GET(self):
            request = urlsplit(self.path)
            route = request.path
            if self.headers.get("Host") not in allowed_hosts:
                self.send_error(421)
                return
            if feed_only and route != "/catalog/IN.json":
                self.send_error(404)
                return
            if route in assets:
                file, content_type = assets[route]
                self.send_payload(file.read_bytes(), content_type)
            elif route == "/api/catalog":
                next_run = next_refresh(datetime.now(IST)).isoformat() if run_scheduled else None
                self.send_payload(json.dumps(overview(path, next_run)).encode(), "application/json")
            elif route == "/api/tmdb-index":
                try:
                    fields = parse_qs(request.query, max_num_fields=4)
                    result = search(index_path(path), fields.get("q", [""])[0], fields.get("kind", [""])[0])
                except ValueError:
                    self.send_error(400, "Invalid search")
                except sqlite3.Error:
                    self.send_error(503, "TMDB index unavailable")
                else:
                    self.send_payload(json.dumps(result).encode(), "application/json")
            elif route == "/catalog/IN.json":
                with closing(connect(path)) as db:
                    db.execute("BEGIN")
                    saved = sweep_snapshot(db, snapshot(db))
                if saved is None:
                    self.send_error(404, "No accepted catalog yet")
                else:
                    self.send_payload(saved, "application/json", '"' + hashlib.sha256(saved).hexdigest() + '"')
            else:
                self.send_error(404)

        def send_payload(self, payload, content_type, etag=None, status=200):
            unchanged = etag and self.headers.get("If-None-Match") == etag
            self.send_response(304 if unchanged else status)
            self.send_header("Content-Type", content_type)
            self.send_header("Cache-Control", "private, no-cache" if etag else "no-store")
            self.send_header("Content-Security-Policy", "default-src 'self'; img-src 'self' https:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            if etag:
                self.send_header("ETag", etag)
            if not unchanged:
                self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            if not unchanged and self.command != "HEAD":
                self.wfile.write(payload)

    stop = Event()
    with ThreadingHTTPServer(("127.0.0.1", port), Handler) as server:
        if run_scheduled:
            Thread(target=scheduled_refresh, args=(path, stop, feed_file, verified_input), daemon=True).start()
        label = "Sweep TV feed" if feed_only else "Sweep TV Catalog Admin"
        print(f"{label} · http://127.0.0.1:{port}/", flush=True)
        try:
            server.serve_forever()
        finally:
            stop.set()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, default=DEFAULT_DB)
    parser.add_argument("--feed-file", type=Path, help="Refresh the staged JSON file after an accepted import or refresh")
    actions = parser.add_subparsers(dest="action", required=True)
    actions.add_parser("import").add_argument("input", type=Path)
    actions.add_parser("import-verified").add_argument("input", type=Path)
    supplement = actions.add_parser("supplement")
    supplement.add_argument("input", type=Path)
    supplement.add_argument("external_id")
    supplement.add_argument("provider", choices=PROVIDERS)
    supplement.add_argument("--source", required=True)
    actions.add_parser("refresh").add_argument("--from-file", type=Path, help="Validate a StreamRadar snapshot already generated by the builder")
    actions.add_parser("refresh-apple").add_argument("--from-file", type=Path, help="Verify a staged India Apple Originals discovery")
    actions.add_parser("refresh-netflix-english").add_argument("--from-file", type=Path, help="Verify a staged Netflix India English discovery")
    actions.add_parser("export").add_argument("output", type=Path)
    server = actions.add_parser("serve")
    server.add_argument("--port", type=int, default=4190)
    server.add_argument("--scheduled", "--daily", action="store_true",
                        help="Refresh at midnight, 06:00, noon and 18:00 IST while this server runs")
    server.add_argument("--verified-input", type=Path, help="Refresh from an approved local catalog file instead of comparison sources")
    actions.add_parser("serve-feed").add_argument("--port", type=int, default=4191)
    args = parser.parse_args()
    initialize(args.db)
    try:
        if args.action in {"import", "import-verified"}:
            importer = import_verified if args.action == "import-verified" else import_feed
            print(f"Imported {importer(args.db, args.input)} catalog entries")
            if args.feed_file:
                export_feed(args.db, args.feed_file)
        elif args.action == "supplement":
            save_supplement(args.db, args.input, args.external_id, args.provider, args.source)
            print("Saved verified supplement for seven days")
        elif args.action in {"refresh", "refresh-apple", "refresh-netflix-english"}:
            if args.action == "refresh":
                entries = refresh(args.db, args.from_file)
            else:
                provider = "apple-tv-plus" if args.action == "refresh-apple" else "netflix"
                entries = refresh_provider(args.db, provider, args.from_file)
            print(f"Refreshed {entries} catalog entries")
            if args.feed_file:
                export_feed(args.db, args.feed_file)
        elif args.action == "export":
            export_feed(args.db, args.output)
            print(f"Exported {args.output}")
        elif args.action == "serve-feed":
            serve(args.db, args.port, False, None, feed_only=True)
        else:
            serve(args.db, args.port, args.scheduled, args.feed_file, args.verified_input)
    except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
        raise SystemExit(str(error)) from None
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
