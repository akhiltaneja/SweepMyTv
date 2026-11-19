"""Check public India source reachability from a potential catalog runner."""

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import date
import json
from pathlib import Path
import runpy
import subprocess
import sys

CATALOG = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CATALOG / "admin"))
from apple_catalog import title_page as apple_title
from netflix_catalog import title_page as netflix_title


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    builder = runpy.run_path(str(CATALOG / "build-feed.py"))
    radar = builder["RADAR"]
    request = builder["request"]
    tasks = {
        "streamradar_films": lambda: len(request(radar, "/catalog/movie/streamradar-justin-movies.json").get("metas", [])),
        "streamradar_series": lambda: len(request(radar, "/catalog/series/streamradar-justin-shows.json").get("metas", [])),
        "apple_india_subscription": lambda: bool(apple_title(
            "https://tv.apple.com/in/show/severance/umc.cmc.1srk2goyh2q2zdxcx605w8vtx", date.today())),
        "netflix_india_title": lambda: bool(netflix_title("https://www.netflix.com/in/title/80057281")),
        "tmdb_api_http": lambda: subprocess.run(
            ["curl", "--disable", "-sS", "--max-time", "10", "-o", "/dev/null", "-w", "%{http_code}",
             "https://api.themoviedb.org/3/configuration"], capture_output=True, text=True
        ).stdout.strip(),
    }
    with ThreadPoolExecutor(max_workers=5) as pool:
        report = dict(zip(tasks, pool.map(lambda task: task(), tasks.values())))
    args.output.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(report, indent=2) + "\n"
    (args.output / "comparison.json").write_text(payload, encoding="utf-8")
    print(payload, end="")


if __name__ == "__main__":
    main()
