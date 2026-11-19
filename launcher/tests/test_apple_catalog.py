from datetime import date
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1] / "catalog/admin"))
import apple_catalog


TODAY = date(2026, 11, 18)
LINK = "https://tv.apple.com/in/show/example/umc.cmc.abc123"


def page(released="2023-01-27", watch="Sign Up to Watch"):
    title = {"@type": "TVSeries", "name": "Example", "datePublished": released,
             "actor": [{"name": "Actor One"}]}
    return (
        '<button data-testid="how-to-watch-card"><p><!-- HTML_TAG_START -->'
        f'{watch}<!-- HTML_TAG_END --></p></button>'
        f'<script id=schema:tv-series type="application/ld+json">{json.dumps(title)}</script>'
    )


class AppleCatalogTest(unittest.TestCase):
    def test_requires_the_exact_india_title_and_subscription_card(self):
        with patch.object(apple_catalog, "fetch", return_value=(page(), LINK)) as fetch:
            self.assertEqual("Example", apple_catalog.title_page(LINK, TODAY)[0]["name"])
            self.assertIsNone(apple_catalog.title_page(LINK.replace("/in/", "/us/"), TODAY))
        fetch.assert_called_once_with(LINK)
        with patch.object(apple_catalog, "fetch", return_value=(page(), LINK.replace("abc123", "abc456"))):
            self.assertIsNone(apple_catalog.title_page(LINK, TODAY))
        self.assertIsNone(apple_catalog.parse_title(page(watch="Buy"), "tv", TODAY))
        self.assertIsNone(apple_catalog.parse_title(page(released="2026-11-19"), "tv", TODAY))

    def test_cross_checks_title_year_and_cast_before_using_the_imdb_id(self):
        apple_title = apple_catalog.parse_title(page(), "tv", TODAY)
        search = {"results": [{"id": 12, "name": "Example", "first_air_date": "2023-01-27"}]}
        detail = {"id": 12, "external_ids": {"imdb_id": "tt99"}, "credits": {"cast": [{"name": "Actor One"}]},
                  "poster_path": "/poster.jpg", "original_language": "en", "overview": "Synopsis"}
        def request(path, **params):
            return search if path == "/search/tv" else detail

        result = apple_catalog.make_candidate(LINK, apple_title, request, lambda *_: "7.4")
        self.assertEqual(("tt99", "provider", "7.4"), (result["id"], result["row"], result["rating"]))
        detail["credits"]["cast"] = [{"name": "Another Actor"}]
        self.assertIsNone(apple_catalog.make_candidate(LINK, apple_title, request, lambda *_: "7.4"))
