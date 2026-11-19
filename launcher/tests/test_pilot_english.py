import importlib.util
from datetime import date
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("pilot_english", Path(__file__).parents[1] / "catalog" / "pilot-english.py")
pilot = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pilot)


class EnglishPilotTest(unittest.TestCase):
    def test_fill_uses_exact_english_subscription_and_keeps_non_english(self):
        today = date(2026, 11, 17)
        regional = {"kind": "tv", "id": "tt10", "title": "Regional", "language": "hi", "provider": "netflix"}
        english = {"kind": "tv", "id": "tt11", "title": "Existing", "language": "en", "provider": "netflix",
                   "poster": "https://btttr.cc/poster.jpg", "link": "https://www.netflix.com/title/11"}

        def api(path, **params):
            matching = {"name": "Verified", "original_language": "en", "first_air_date": "2026-11-01",
                        "poster_path": "/poster.jpg"}
            if path == "/find/tt11":
                return {"tv_results": [dict(matching, overview="Exact summary")]}
            if path.startswith("/watch/providers/"):
                return {"results": [{"provider_name": "Netflix", "provider_id": 8}]}
            if path == "/discover/tv":
                self.assertEqual("IN", params["watch_region"])
                self.assertEqual("en", params["with_original_language"])
                return {"results": [dict(matching, id=20), dict(matching, id=21)]}
            if path.endswith("/external_ids"):
                return {"imdb_id": "tt" + path.split("/")[2]}
            if path == "/tv/20/watch/providers":
                return {"results": {"IN": {"rent": [{"provider_id": 8}]}}}
            if path == "/tv/21/watch/providers":
                return {"results": {"IN": {"flatrate": [{"provider_id": 8}]}}}
            return {}

        rows, added = pilot.build_pilot([regional, english], api, today)
        self.assertEqual(regional, rows[0])
        self.assertEqual("Exact summary", rows[1]["summary"])
        self.assertNotIn("summary", english)
        self.assertEqual(["tt10", "tt11", "tt21"], [row["id"] for row in rows])
        self.assertEqual("new", rows[-1]["row"])
        self.assertEqual("", rows[-1]["link"])
        self.assertEqual(1, added["netflix"])


if __name__ == "__main__":
    unittest.main()
