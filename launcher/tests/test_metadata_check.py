import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location(
    "metadata_check", Path(__file__).parents[1] / "catalog/admin/metadata_check.py")
metadata = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(metadata)


class MetadataCheckTest(unittest.TestCase):
    def setUp(self):
        self.chart = {
            "title": "Obsession", "kind": "movie", "id": "tt39060004", "provider": "jiohotstar",
            "link": "https://www.hotstar.com/in/movies/obsession/1271707974",
            "poster": "", "background": "https://image.tmdb.org/t/p/w1280/horror.jpg",
            "summary": "Detroit police story", "cast": "A different cast",
        }
        self.responses = {
            "/find/tt39060004": {"movie_results": [{"id": 1}]},
            "/movie/1": {"id": 1, "external_ids": {"imdb_id": "tt39060004"}, "title": "Obsession",
                         "backdrop_path": "/detective.jpg", "overview": "Detroit police story"},
            "/find/tt37287335": {"movie_results": [{"id": 2}]},
            "/movie/2": {"id": 2, "external_ids": {"imdb_id": "tt37287335"}, "title": "Obsession",
                         "backdrop_path": "/horror.jpg", "poster_path": "/poster.jpg", "overview": "A dangerous wish",
                         "credits": {"cast": [{"name": "Michael Johnston"}, {"name": "Inde Navarrette"}]}},
        }

    def request(self, path, **_):
        return self.responses[path]

    def test_matching_title_text_and_id_do_not_excuse_mismatched_artwork(self):
        (row,) = metadata.verify_rows([self.chart], self.request)
        self.assertIn("artwork does not corroborate", row["_metadata_issue"])
        self.assertNotIn("_metadata_tmdb_id", row)

    def test_verified_match_replaces_wrong_synopsis_and_cast_together(self):
        (row,) = metadata.verify_rows([dict(self.chart, id="tt37287335")], self.request)
        self.assertEqual((2, "A dangerous wish", "Michael Johnston, Inde Navarrette"),
                         (row["_metadata_tmdb_id"], row["summary"], row["cast"]))

    def test_shared_provider_link_quarantines_both_identified_candidates(self):
        rows = metadata.verify_rows([self.chart, dict(self.chart, id="tt37287335")], self.request)
        self.assertTrue(all("multiple title identities" in row["_metadata_issue"] for row in rows))


if __name__ == "__main__":
    unittest.main()
