"""Prepare only the TV catalogue for Cloudflare static hosting."""

import argparse
import json
from pathlib import Path
import re
import sys
from tempfile import TemporaryDirectory
from urllib.parse import urlsplit

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "admin"))
from catalog_admin import export_feed, import_verified, initialize, read_input
from catalog_admin import validate

ASSETS_IGNORE = "/*\n!/catalog/\n/catalog/*\n!/catalog/IN.json\n"
TITLE_FIELDS = (
    "id", "kind", "title", "provider", "row", "language", "year", "released",
    "summary", "cast", "poster", "background", "link", "rating", "editorial_rank",
)
PRIVATE_CONTENT = re.compile(
    rb"-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----"
    rb"|(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|sk-proj-[A-Za-z0-9_-]{28,})"
    rb"|(?:AKIA|ASIA)[A-Z0-9]{16}"
    rb"|[?&](?:api_key|access_token|token|password)=[A-Za-z0-9_-]{20,}"
)


def published_snapshot(feed):
    rows, _ = validate(feed, require_coverage=False)
    titles = []
    for source, validated in zip(feed["titles"], rows):
        title = {field: validated[field] for field in TITLE_FIELDS if field in validated}
        rank = source.get("editorial_rank")
        if type(rank) is int and 1 <= rank <= 10:
            title["editorial_rank"] = rank
        if not all(title.get(field) for field in ("language", "summary", "poster", "link")):
            raise ValueError("A published title is missing required TV metadata")
        for field in ("link", "poster", "background"):
            if title.get(field):
                title[field] = urlsplit(title[field])._replace(query="", fragment="").geturl()
        titles.append(title)
    payload = json.dumps({"version": 1, "region": "IN", "titles": titles},
                         ensure_ascii=False).encode("utf-8") + b"\n"
    if PRIVATE_CONTENT.search(payload):
        raise ValueError("Possible credential in published catalogue")
    return payload


def strip_url_parameters(feed):
    if not isinstance(feed, dict) or feed.get("publication") != "approved":
        raise ValueError("Mark the independently verified input as approved for publication")
    rows = feed.get("titles")
    if not isinstance(rows, list):
        raise ValueError("Approved input must contain a title list")
    for row in rows:
        if isinstance(row, dict):
            for key in ("link", "poster", "background"):
                if isinstance(row.get(key), str):
                    row[key] = urlsplit(row[key])._replace(query="", fragment="").geturl()
    return feed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, nargs="?")
    parser.add_argument("--db", type=Path, help="Publish the validated TV snapshot from this database")
    parser.add_argument("--output", type=Path, default=HERE / "public")
    args = parser.parse_args()
    if (args.input is None) == (args.db is None):
        parser.error("Choose one approved input file or --db")
    try:
        with TemporaryDirectory(prefix="sweep-public-") as temporary:
            db = args.db
            if db is None:
                db = Path(temporary) / "approved.sqlite3"
                sanitized = Path(temporary) / "approved.json"
                sanitized.write_text(json.dumps(strip_url_parameters(read_input(args.input, library=True))),
                                     encoding="utf-8")
                initialize(db)
                import_verified(db, sanitized)
            if not db.is_file():
                raise ValueError("The catalogue database is missing")
            snapshot = Path(temporary) / "snapshot.json"
            export_feed(db, snapshot)
            payload = published_snapshot(json.loads(snapshot.read_text(encoding="utf-8")))
            target = args.output / "catalog" / "IN.json"
            ignore = args.output / ".assetsignore"
            if any(path.is_symlink() for path in (args.output, target.parent, ignore, target)):
                raise ValueError("Public catalog staging paths must not be symbolic links")
            args.output.mkdir(parents=True, exist_ok=True)
            ignore.write_text(ASSETS_IGNORE, encoding="utf-8")
            target.parent.mkdir(parents=True, exist_ok=True)
            with TemporaryDirectory(prefix="sweep-publish-", dir=target.parent) as staging:
                ready = Path(staging) / "IN.json"
                ready.write_bytes(payload)
                ready.replace(target)
    except (OSError, ValueError) as error:
        parser.exit(1, f"{error}\n")
    print(f"Validated public catalog asset at {target}")


if __name__ == "__main__":
    main()
