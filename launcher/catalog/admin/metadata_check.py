"""Match private recommendations to a canonical title before copying synopsis and cast."""

from concurrent.futures import ThreadPoolExecutor
import os
from pathlib import Path
import re
import runpy
import subprocess
import unicodedata
from urllib.parse import urlsplit

PILOT = Path(__file__).resolve().parents[1] / "pilot-english.py"
IMDB = re.compile(r"tt[0-9]{1,12}")
IMAGE = re.compile(r"/[A-Za-z0-9_.-]+\.(?:jpg|jpeg|png|webp)")


def request_from_config():
    credential = os.environ.get("SWEEP_TMDB_TOKEN", "").strip()
    if not credential:
        try:
            result = subprocess.run(
                ["security", "find-generic-password", "-s", "Sweep TV Catalog TMDB",
                 "-a", os.environ.get("USER", ""), "-w"],
                capture_output=True, text=True, timeout=10)
            credential = result.stdout.strip() if result.returncode == 0 else ""
        except (OSError, subprocess.TimeoutExpired):
            pass
    if not re.fullmatch(r"[A-Za-z0-9_.-]{12,2048}", credential):
        raise ValueError("Metadata verification requires the private catalog TMDB credential")
    tmdb = runpy.run_path(str(PILOT))["tmdb"]
    return lambda path, **params: tmdb(credential, path, **params)


def title_key(name):
    if not isinstance(name, str):
        return ""
    text = unicodedata.normalize("NFKC", name).casefold()
    return "".join(char for char in text if char.isalnum())


def canonical_title(request, key):
    kind, imdb = key
    matches = request(f"/find/{imdb}", external_source="imdb_id").get(f"{kind}_results", [])
    if len(matches) != 1 or type(matches[0].get("id")) is not int:
        return {}
    detail = request(f"/{kind}/{matches[0]['id']}", append_to_response="external_ids,credits,images")
    if detail.get("external_ids", {}).get("imdb_id") != imdb or detail.get("id") != matches[0]["id"]:
        return {}
    return detail


def matches_artwork(row, detail):
    for field, group, default in (("poster", "posters", "poster_path"),
                                  ("background", "backdrops", "backdrop_path")):
        url = urlsplit(row[field])
        if url.hostname != "image.tmdb.org":
            continue
        path = "/" + url.path.rsplit("/", 1)[-1]
        images = detail.get("images", {}).get(group, [])
        if path == detail.get(default) or any(path == image.get("file_path") for image in images):
            return True
    return False


def artwork(path, size):
    return "https://image.tmdb.org/t/p/" + size + path if isinstance(path, str) and IMAGE.fullmatch(path) else ""


def verify_rows(rows, request):
    keys = sorted({(row["kind"], row["id"]) for row in rows if row["link"] and IMDB.fullmatch(row["id"])})

    def check(key):
        try:
            return canonical_title(request, key)
        except RuntimeError:
            return None

    with ThreadPoolExecutor(max_workers=6) as pool:
        canonical = dict(zip(keys, pool.map(check, keys)))
    if keys and all(match is None for match in canonical.values()):
        raise RuntimeError("TMDB was unreachable for the entire catalog")
    offers = {}
    for row in rows:
        if row["link"]:
            link = urlsplit(row["link"])
            key = (row["provider"], link.path.rstrip("/").rsplit("/", 1)[-1])
            offers.setdefault(key, set()).add((row["kind"], row["id"]))
    verified = []
    for source in rows:
        row = dict(source)
        row.pop("_metadata_issue", None)
        row.pop("_metadata_tmdb_id", None)
        key = row["kind"], row["id"]
        result = canonical.get(key, {})
        detail = result or {}
        names = {title_key(detail.get(field)) for field in ("title", "original_title", "name", "original_name")}
        link = urlsplit(row["link"])
        offer_key = (row["provider"], link.path.rstrip("/").rsplit("/", 1)[-1])
        poster = artwork(detail.get("poster_path"), "w780")
        synopsis = (detail.get("overview") or "").strip()
        if len(offers.get(offer_key, ())) > 1:
            row["_metadata_issue"] = "Provider link is assigned to multiple title identities"
        elif result is None:
            row["_metadata_issue"] = "TMDB title check unavailable"
        elif not detail:
            row["_metadata_issue"] = "No independently matched title ID"
        elif title_key(row["title"]) not in names - {""}:
            row["_metadata_issue"] = "Chart title disagrees with the matched title ID"
        elif not matches_artwork(row, detail):
            row["_metadata_issue"] = "Chart artwork does not corroborate the matched title ID"
        elif not synopsis or not poster:
            row["_metadata_issue"] = "Matched title is missing a synopsis or portrait"
        else:
            cast = [entry.get("name") for entry in detail.get("credits", {}).get("cast", [])[:5]]
            row.update(_metadata_tmdb_id=detail["id"], summary=synopsis[:1600],
                       cast=", ".join(name for name in cast if isinstance(name, str)),
                       poster=poster, background=artwork(detail.get("backdrop_path"), "w1280"))
        verified.append(row)
    return verified
