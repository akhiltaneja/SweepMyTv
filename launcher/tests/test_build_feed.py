import importlib.util
from datetime import date
from pathlib import Path
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("build_feed", Path(__file__).parents[1] / "catalog/build-feed.py")
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


class BuildFeedTest(unittest.TestCase):
    def test_expanded_just_in_reads_the_second_page_and_deduplicates(self):
        first = [{"id": f"tt{index}"} for index in range(100)]
        last = [{"id": "tt99"}, {"id": "tt100"}]
        with patch.object(builder, "request", side_effect=[{"metas": first}, {"metas": last}]) as request:
            _, _, results = builder.catalog(("justin", "movie"), expanded=True)
        self.assertEqual(101, len(results))
        self.assertTrue(request.call_args.args[1].endswith("/skip=100.json"))

    def test_expanded_just_in_rejects_a_failed_page(self):
        first = [{"id": f"tt{index}"} for index in range(100)]
        with patch.object(builder, "request", side_effect=[{"metas": first}, {}]):
            with self.assertRaisesRegex(SystemExit, "offset 100"):
                builder.catalog(("justin", "series"), expanded=True)

    def test_recent_window_includes_today_and_previous_59_days(self):
        today = date(2026, 11, 18)
        for value in ("2026-09-20", "2026-11-18"):
            self.assertEqual(value, builder.just_in_date({"releaseInfo": value}, today))
        for value in ("2026-09-19", "2026-11-19", "2026", ""):
            self.assertEqual("", builder.just_in_date({"releaseInfo": value}, today))
