"""Build and query a private index of TMDB's standard daily title exports."""
import argparse
from contextlib import closing
from datetime import date
import gzip
import json
from pathlib import Path
import re
import sqlite3
from tempfile import TemporaryDirectory
from urllib.request import Request, urlopen

EXPORTS = {"movie": "movie", "tv": "tv_series"}
SCHEMA = """
CREATE TABLE titles (
    row_id INTEGER PRIMARY KEY,
    kind TEXT NOT NULL,
    tmdb_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    popularity REAL NOT NULL,
    UNIQUE(kind, tmdb_id)
);
CREATE TABLE export_counts (kind TEXT PRIMARY KEY, entries INTEGER NOT NULL);
CREATE TABLE snapshot (export_date TEXT NOT NULL);
"""


def index_path(catalog_path):
    return catalog_path.with_suffix(".tmdb.sqlite3")


def export_rows(kind, day):
    date_stamp = day.strftime("%m_%d_%Y")
    url = f"https://files.tmdb.org/p/exports/{EXPORTS[kind]}_ids_{date_stamp}.json.gz"
    request = Request(url, headers={"User-Agent": "SweepTV-Catalog/1.0"})
    with urlopen(request, timeout=30) as response, gzip.GzipFile(fileobj=response) as source:
        for line in source:
            if not line.strip():
                continue
            item = json.loads(line)
            name = item.get("original_title" if kind == "movie" else "original_name") or ""
            yield kind, int(item["id"]), name, float(item.get("popularity") or 0)


def build(target, day, rows=export_rows):
    target.parent.mkdir(parents=True, exist_ok=True)
    counts = {}
    with TemporaryDirectory(prefix=".tmdb-index-", dir=target.parent) as workspace:
        staged = Path(workspace) / "index.sqlite3"
        with closing(sqlite3.connect(staged)) as db, db:
            db.executescript(SCHEMA)
            for kind in EXPORTS:
                written = db.executemany(
                    "INSERT INTO titles(kind, tmdb_id, name, popularity) VALUES (?, ?, ?, ?)", rows(kind, day)
                ).rowcount
                if not written:
                    raise ValueError(f"TMDB {kind} export is empty")
                counts[kind] = written
            db.execute("INSERT INTO snapshot VALUES (?)", (day.isoformat(),))
            db.executemany("INSERT INTO export_counts VALUES (?, ?)", counts.items())
            db.executescript("""
                CREATE INDEX titles_popularity ON titles (popularity DESC, row_id);
                CREATE INDEX titles_kind_popularity ON titles (kind, popularity DESC, row_id);
                CREATE VIRTUAL TABLE title_search USING fts5(
                    name, content='titles', content_rowid='row_id', prefix='2 3'
                );
                INSERT INTO title_search(title_search) VALUES ('rebuild');
            """)
        staged.replace(target)
    return counts


def search(path, query, kind):
    if not path.is_file():
        return {"export_date": None, "counts": {}, "titles": [], "has_more": False}
    query = query.strip()[:120]
    tokens = re.findall(r"[^\W_]+", query)[:12]
    where, args = [], []
    source = "titles AS t"
    if kind in EXPORTS:
        where.append("t.kind = ?")
        args.append(kind)
    if query.isascii() and query.isdecimal() and len(query) <= 12:
        where.append("t.tmdb_id = ?")
        args.append(int(query))
    elif query:
        if tokens:
            source += " JOIN title_search ON title_search.rowid = t.row_id"
            where.append("title_search MATCH ?")
            args.append(" AND ".join(f'"{token}"*' for token in tokens))
        else:
            where.append("0")
    conditions = "WHERE " + " AND ".join(where) if where else ""
    with closing(sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)) as db:
        db.row_factory = sqlite3.Row
        export_date = db.execute("SELECT export_date FROM snapshot").fetchone()[0]
        counts = dict(db.execute("SELECT kind, entries FROM export_counts"))
        # Source and conditions contain fixed SQL fragments. Search values are bound.
        results = db.execute(f"""
            SELECT t.kind, t.tmdb_id, t.name, t.popularity FROM {source}
            {conditions} ORDER BY t.popularity DESC, t.row_id LIMIT 51
        """, args).fetchall()
    return {"export_date": export_date, "counts": counts, "titles": [dict(row) for row in results[:50]],
            "has_more": len(results) > 50}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--date", type=date.fromisoformat, required=True, help="TMDB export date in YYYY-MM-DD form")
    args = parser.parse_args()
    try:
        counts = build(args.output, args.date)
    except (OSError, KeyError, ValueError, sqlite3.Error) as error:
        raise SystemExit(f"TMDB import failed. Previous index kept. {error}") from None
    print(f"Indexed {counts['movie']:,} movies and {counts['tv']:,} series from {args.date}.")


if __name__ == "__main__":
    main()
