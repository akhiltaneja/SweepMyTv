from datetime import date
import importlib.util
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest


SPEC = importlib.util.spec_from_file_location("tmdb_index", Path(__file__).parents[1] / "catalog/admin/tmdb_index.py")
index = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(index)
DAY = date(2026, 11, 17)


def exports(kind, _day):
    if kind == "movie":
        return [(kind, 4, "Star Wars", 20), (kind, 5, "Amélie", 8)]
    return [(kind, 4, "Star Trek", 10)]


class TmdbIndexTest(unittest.TestCase):
    def test_search_by_original_title_or_type_and_id(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "index.sqlite3"
            index.build(path, DAY, exports)
            result = index.search(path, "star w", "")
            self.assertEqual({"movie": 2, "tv": 1}, result["counts"])
            self.assertEqual(DAY.isoformat(), result["export_date"])
            self.assertEqual(["Star Wars"], [r["name"] for r in result["titles"]])
            self.assertEqual("Star Trek", index.search(path, "4", "tv")["titles"][0]["name"])
            self.assertEqual("Amélie", index.search(path, "amelie", "")["titles"][0]["name"])
            self.assertEqual([], index.search(path, '""*', "")["titles"])

    def test_partial_import_preserves_previous_snapshot(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "index.sqlite3"
            index.build(path, DAY, exports)
            with self.assertRaisesRegex(ValueError, "tv export is empty"):
                index.build(path, date(2026, 11, 18), lambda kind, day: exports(kind, day) if kind == "movie" else [])
            self.assertEqual(DAY.isoformat(), index.search(path, "", "")["export_date"])


if __name__ == "__main__":
    unittest.main()
