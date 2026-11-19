"""Compare a hosted catalog refresh with a private SQLite seed."""

import argparse
from collections import Counter
import json
from pathlib import Path
import sqlite3
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "admin"))
from catalog_admin import initialize, overview, refresh


def summarize(state):
    feed = [row for row in state["titles"] if row["in_feed"]]
    checks = Counter((issue["provider"], issue["reason"]) for issue in state["issues"]
                     if issue["resolved_at"] is None and issue["provider"] in ("netflix", "apple-tv-plus", "prime"))
    return {
        "titles": state["unique_titles"],
        "feed_listings": len(feed),
        "providers": dict(sorted(Counter(row["provider"] for row in feed).items())),
        "english": {provider: counts["titles"] for provider, counts in state["tv_coverage"].items()},
        "sections": dict(sorted(Counter(row["row"] for row in feed).items())),
        "checks": [{"provider": provider, "reason": reason, "count": count}
                   for (provider, reason), count in sorted(checks.items())],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--save-state", type=Path)
    args = parser.parse_args()
    initialize(args.db)
    initial = overview(args.db)
    if not initial["snapshot"]:
        raise SystemExit("The baseline must contain a previously validated private snapshot.")
    args.output.mkdir(parents=True, exist_ok=True)
    result = {"baseline": summarize(initial)}
    report = args.output / "comparison.json"
    try:
        refresh(args.db)
        updated = overview(args.db)
        result["cloud"] = summarize(updated)
        result["result"] = "ready_for_review" if updated["tv_entries"] else "empty_feed"
        if args.save_state and updated["tv_entries"]:
            args.save_state.parent.mkdir(parents=True, exist_ok=True)
            with sqlite3.connect(args.db) as source, sqlite3.connect(args.save_state) as destination:
                source.backup(destination)
    except (OSError, RuntimeError, ValueError, sqlite3.Error, subprocess.TimeoutExpired) as error:
        result["result"] = "refresh_failed"
        result["error_type"] = type(error).__name__
        # Only application-controlled messages make it into the artifact.
        if isinstance(error, ValueError):
            result["error"] = str(error)
    report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Private cloud comparison: {result['result']}")
    return 0 if result["result"] == "ready_for_review" else 1


if __name__ == "__main__":
    raise SystemExit(main())
