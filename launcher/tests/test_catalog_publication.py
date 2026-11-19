import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).parents[1] / "catalog/cloud/prepare-public.py"
SPEC = importlib.util.spec_from_file_location("prepare_catalog_publication", SCRIPT)
public = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(public)


class CataloguePublicationTest(unittest.TestCase):
    def test_published_snapshot_keeps_only_title_metadata_and_rejects_secrets(self):
        import json
        title = {
            "id": "tt123456", "kind": "movie", "title": "Example",
            "provider": "netflix", "row": "trending", "language": "en",
            "summary": "A film synopsis", "poster": "https://image.tmdb.org/t/p/w500/example.jpg",
            "link": "https://www.netflix.com/in/title/123?session=private#account",
            "editorial_rank": 1, "_metadata_tmdb_id": 10,
            "viewing_history": ["Private title"], "credential": "private",
        }
        feed = {"version": 1, "region": "IN", "titles": [title], "device_serial": "private"}
        output = json.loads(public.published_snapshot(feed))
        self.assertEqual({"version", "region", "titles"}, output.keys())
        self.assertNotIn("_metadata_tmdb_id", output["titles"][0])
        self.assertNotIn("viewing_history", output["titles"][0])
        self.assertNotIn("credential", output["titles"][0])
        self.assertEqual("https://www.netflix.com/in/title/123", output["titles"][0]["link"])
        self.assertEqual(1, output["titles"][0]["editorial_rank"])
        title["summary"] = "Accidental secret " + "ghp_" + "a" * 36
        with self.assertRaisesRegex(ValueError, "credential"):
            public.published_snapshot(feed)
