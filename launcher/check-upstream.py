#!/usr/bin/env python3
"""Fail a TV release build if the latest stable shared source has not been verified."""
import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import Request, urlopen

REPOSITORY = "Gathin23/cleanyourtv"
API = "https://api.github.com/repos/" + REPOSITORY


def download(url):
    request = Request(url, headers={"User-Agent": "SweepTV-VerifiedUpstream", "Accept": "application/vnd.github+json"})
    with urlopen(request, timeout=15) as response:
        value = response.read(512 * 1024 + 1)
        if len(value) > 512 * 1024:
            raise ValueError("Upstream response exceeded the size limit")
        return value


def verify(root, lock, fetch=download, offline=False):
    if lock.get("repository") != REPOSITORY:
        raise ValueError("Unexpected upstream repository")
    release, commit = lock["release"], lock["commit"]
    if not re.fullmatch(r"v?\d+(?:\.\d+){1,3}", release) or not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("Invalid pinned release or commit")
    bundle = json.loads((root / "app/src/main/assets/sweep-release.json").read_text())
    expected = {key: lock[key] for key in ["repository", "release", "commit", "reviewed_on", "review_scope"]}
    if bundle.get("verified_upstream") != expected:
        raise ValueError("Bundled component review differs from the source lock")
    gradle = (root / "app/build.gradle").read_text()
    version = re.search(r"versionName\s+['\"]([^'\"]+)['\"]", gradle)
    code = re.search(r"versionCode\s+(\d+)", gradle)
    if not version or not code or bundle.get("version") != version.group(1) or bundle.get("version_code") != int(code.group(1)):
        raise ValueError("Bundled release version differs from the APK version")
    if bundle.get("product") != "Sweep TV" or bundle.get("distribution") != "managed" or not bundle.get("notes"):
        raise ValueError("Missing Sweep TV release information")
    components = lock.get("components", [])
    if not components:
        raise ValueError("No shared components were recorded")
    for component in components:
        local = (root / component["local_path"]).resolve()
        if not local.is_relative_to(root.resolve()):
            raise ValueError("Component path is outside the launcher")
        if hashlib.sha256(local.read_bytes()).hexdigest() != component["local_sha256"]:
            raise ValueError("Shared component changed locally: " + component["name"])
    result = {"repository": REPOSITORY, "release": release, "commit": commit,
              "local_components_verified": True, "latest_verified": False, "offline": offline}
    if offline:
        return result
    latest = json.loads(fetch(API + "/releases/latest"))
    tag = latest.get("tag_name", "")
    expected_url = "https://github.com/" + REPOSITORY + "/releases/tag/" + tag
    if latest.get("draft") or latest.get("prerelease") or latest.get("html_url") != expected_url:
        raise ValueError("Latest release metadata is not an official stable release")
    if tag != release:
        raise ValueError("CleanYourTV " + tag + " needs review before releasing Sweep TV (verified: " + release + ")")
    if json.loads(fetch(API + "/commits/" + release)).get("sha") != commit:
        raise ValueError("The release tag no longer matches the verified commit")
    for component in components:
        path = component["upstream_path"]
        if not re.fullmatch(r"[A-Za-z0-9_./-]+", path) or ".." in path.split("/"):
            raise ValueError("Invalid upstream component path")
        value = fetch("https://raw.githubusercontent.com/" + REPOSITORY + "/" + commit + "/" + path)
        if hashlib.sha256(value).hexdigest() != component["upstream_sha256"]:
            raise ValueError("Upstream source differs from the reviewed component: " + component["name"])
    result["latest_verified"] = True
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="Local diagnostic only; does not verify the latest release")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    result = {"latest_verified": False, "local_components_verified": False}
    try:
        lock = json.loads((root / "upstream/cleanyourtv-lock.json").read_text())
        result = verify(root, lock, offline=args.offline)
        print("CleanYourTV " + result["release"] + (": local diagnostic passed; latest not checked" if args.offline else ": latest release and shared source verified"))
        code = 0
    except Exception as exc:
        result["error"] = str(exc)
        print("Upstream verification stopped: " + str(exc), file=sys.stderr)
        code = 1
    result["checked_at"] = datetime.now(timezone.utc).isoformat()
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, indent=2) + "\n")
    return code


if __name__ == "__main__":
    sys.exit(main())
