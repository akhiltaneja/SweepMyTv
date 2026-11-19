"""Check the exact Prime Video offer and corroborate membership in India."""

from concurrent.futures import ThreadPoolExecutor
from html import unescape
import re
import subprocess
from urllib.parse import urlsplit

PRIME_MEMBERSHIP_ID = 119
ENTITLEMENT = re.compile(r'''<span\b[^>]*\bdata-automation-id="entitlement-message"[^>]*>([^<]+)</span>''')
PAID_ACTION = re.compile(r'''data-automation-id="text-component"[^>]*>\s*(?:Rent|Buy)\s+(?:UHD|HD|SD)\b''', re.I)


def page_access(html):
    entitlements = {unescape(text).strip().casefold() for text in ENTITLEMENT.findall(html)}
    if entitlements & {"watch with a prime membership", "watch with prime", "included with prime"}:
        return "membership"
    return "paid" if PAID_ACTION.search(html) else "unconfirmed"


def exact_offer(link):
    url = urlsplit(link)
    asin = url.path.rstrip("/").rsplit("/", 1)[-1]
    if url.hostname not in {"primevideo.com", "www.primevideo.com"} or not re.fullmatch(r"[A-Z0-9]{10,32}", asin):
        return "unconfirmed"
    target = f"https://www.primevideo.com/detail/{asin}?tr=in"
    try:
        result = subprocess.run(["curl", "--disable", "-4", "-fsS", "--max-time", "12",
                                 "--max-filesize", "4194304", target],
                                capture_output=True, timeout=15)
    except (OSError, subprocess.TimeoutExpired):
        return "unavailable"
    return page_access(result.stdout.decode("utf-8", errors="replace")) if not result.returncode else "unavailable"


def india_membership(payload):
    offers = payload.get("results", {}).get("IN", {})
    return any(offer.get("provider_id") == PRIME_MEMBERSHIP_ID for offer in offers.get("flatrate", ()))


def check_offers(rows, request_factory, page_check=exact_offer):
    prime = [row for row in rows if row["provider"] == "prime" and row["link"]]
    if not prime:
        return
    links = sorted({row["link"] for row in prime})
    with ThreadPoolExecutor(max_workers=6) as pool:
        pages = dict(zip(links, pool.map(page_check, links)))
    titles = sorted({(row["kind"], row["_metadata_tmdb_id"]) for row in prime
                     if row.get("_metadata_tmdb_id") and pages[row["link"]] == "membership"})
    confirmed = {}
    if titles:
        try:
            request = request_factory()
        except ValueError:
            request = None

        def check(key):
            if request is None:
                return "unavailable"
            kind, tmdb_id = key
            try:
                included = india_membership(request(f"/{kind}/{tmdb_id}/watch/providers"))
            except RuntimeError:
                return "unavailable"
            return "included" if included else "unconfirmed"

        with ThreadPoolExecutor(max_workers=6) as pool:
            confirmed = dict(zip(titles, pool.map(check, titles)))
    for row in prime:
        result = pages[row["link"]]
        row["_prime_access"] = (confirmed.get((row["kind"], row.get("_metadata_tmdb_id")), "unconfirmed")
                                if result == "membership" else result)
