import importlib.util
from datetime import datetime
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("catalog_admin", Path(__file__).parents[1] / "catalog/admin/catalog_admin.py")
admin = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(admin)


def entry(index, provider, row="trending"):
    links = {"netflix": f"https://www.netflix.com/title/{index}",
             "prime": f"https://www.primevideo.com/detail/{index}",
             "apple-tv-plus": f"https://tv.apple.com/in/show/example/umc.cmc.{index}",
             "jiohotstar": f"https://www.hotstar.com/in/shows/example/{index}",
             "zee5": f"https://www.zee5.com/movies/details/example/0-0-{index}"}
    return {"id": f"tt{index}", "kind": "tv", "title": f"Title {index}", "provider": provider,
            "row": row, "language": "en", "poster": "https://image.tmdb.org/t/p/w500/poster.jpg",
            "rating": "7.2", "summary": "Fixture synopsis", "link": links.get(provider, "")}


def catalog():
    return {"version": 1, "region": "IN", "titles": [
        entry(p * 100 + n, provider, "new" if n == 1 else "trending")
        for p, provider in enumerate(admin.PRIMARY, 1) for n in range(1, 9)
    ]}


class CatalogAdminTest(unittest.TestCase):
    def setUp(self):
        checker = patch.object(admin, "poster_responds", return_value=True)
        checker.start()
        self.addCleanup(checker.stop)
        metadata = patch.object(admin, "check_metadata", side_effect=lambda feed: dict(feed, titles=[
            dict(row, _metadata_tmdb_id=1) for row in feed["titles"]]))
        metadata.start()
        self.addCleanup(metadata.stop)
        prime = patch.object(admin, "check_prime_membership", side_effect=lambda feed: dict(feed, titles=[
            dict(row, _prime_access=row.get("_prime_access", "included"), _prime_checked=admin.timestamp())
            if row["provider"] == "prime" else row for row in feed["titles"]]))
        prime.start()
        self.addCleanup(prime.stop)
        apple = patch.object(admin, "check_apple_membership", side_effect=lambda rows: [
            dict(row, _apple_access="included") if row["provider"] == "apple-tv-plus" else row for row in rows])
        apple.start()
        self.addCleanup(apple.stop)

    def test_rent_only_prime_title_is_logged_even_when_metadata_is_complete(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            db, source = root / "catalog.db", root / "IN.json"
            feed = catalog()
            feed["titles"].append(dict(entry(999, "prime"), _prime_access="paid"))
            source.write_text(json.dumps(feed))
            admin.initialize(db)
            admin.import_feed(db, source)
            state = admin.overview(db)
            self.assertFalse(next(row for row in state["titles"] if row["id"] == "tt999")["in_feed"])
            self.assertEqual(["Prime Video link requires rental or purchase"],
                             [issue["reason"] for issue in state["issues"]])

    def test_section_edits_change_only_the_selected_placement(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            db, source = root / "catalog.db", root / "IN.json"
            feed = catalog()
            entry_in_both = entry(999, "netflix")
            feed["titles"] += [entry_in_both, dict(entry_in_both, row="new")]
            source.write_text(json.dumps(feed))
            admin.initialize(db)
            admin.import_feed(db, source)

            def edit(hidden, position=None):
                admin.save_section_edit(db, dict(entry_in_both, hidden=hidden, position=position))
                return {row["row"]: row for row in admin.overview(db)["titles"] if row["id"] == "tt999"}

            self.assertEqual(1, edit(False, 1)["trending"]["section_position"])
            hidden = edit(True)
            self.assertFalse(hidden["trending"]["in_feed"])
            self.assertTrue(hidden["new"]["in_feed"])
            self.assertTrue(edit(False)["trending"]["in_feed"])

    def test_issue_log_withholds_new_and_previously_published_rows_until_fixed(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            db, source, export = root / "catalog.db", root / "input.json", root / "IN.json"
            admin.initialize(db)
            feed = catalog()
            title = dict(entry(998, "netflix"), poster="", link="")
            feed["titles"].append(title)

            def publish():
                source.write_text(json.dumps(feed))
                admin.import_feed(db, source)
                admin.export_feed(db, export)
                return {row["id"] for row in json.loads(export.read_bytes())["titles"]}

            self.assertNotIn("tt998", publish())
            state = admin.overview(db)
            self.assertEqual({"Missing portrait poster", "Missing provider title link"},
                             {issue["reason"] for issue in state["issues"]})
            self.assertEqual(2, state["open_issues"])
            self.assertNotIn("tt998", publish())
            self.assertEqual(2, len(admin.overview(db)["issues"]))
            title.update(entry(998, "netflix"))
            self.assertIn("tt998", publish())
            self.assertEqual(0, admin.overview(db)["open_issues"])

            title["link"] = "https://www.netflix.com/browse"
            self.assertNotIn("tt998", publish())
            self.assertEqual(1, admin.overview(db)["open_issues"])
            self.assertEqual(3, len(admin.overview(db)["issues"]))

    def test_plausible_listing_with_unverified_identity_is_logged_and_held_back(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            db, source = root / "catalog.db", root / "IN.json"
            feed = catalog()
            feed["titles"].append(entry(999, "netflix"))
            source.write_text(json.dumps(feed))
            admin.initialize(db)
            reason = "Chart artwork does not corroborate the matched title ID"

            def verify(candidate):
                return dict(candidate, titles=[
                    dict(row, _metadata_issue=reason) if row["id"] == "tt999"
                    else dict(row, _metadata_tmdb_id=1) for row in candidate["titles"]])

            with patch.object(admin, "check_metadata", side_effect=verify):
                admin.import_feed(db, source)
            state = admin.overview(db)
            self.assertFalse(next(row for row in state["titles"] if row["id"] == "tt999")["in_feed"])
            self.assertEqual([(reason, None)], [(row["reason"], row["resolved_at"]) for row in state["issues"]])

    def test_repairs_only_the_mismatched_jiohotstar_obsession_offer(self):
        wrong = dict(entry(39060004, "jiohotstar"), kind="movie", title="Obsession", poster="",
                     link="https://www.hotstar.com/in/movies/obsession/1271707974")
        unrelated = dict(wrong, provider="prime")
        repaired = admin.repair_streamradar_matches({"titles": [wrong, unrelated]})["titles"]
        self.assertEqual("tt37287335", repaired[0]["id"])
        self.assertTrue(repaired[0]["poster"].endswith("bRwnj8WEKBCvmfeUNOukJPwB43K.jpg"))
        self.assertIn("Inde Navarrette", repaired[0]["cast"])
        self.assertNotIn("rating", repaired[0])
        self.assertEqual(unrelated, repaired[1])
        self.assertEqual([repaired[0]], admin.repair_streamradar_matches(
            {"titles": [wrong, repaired[0]]})["titles"])
        with patch.object(admin, "streamradar_rating", return_value="7.8") as lookup:
            corrected = admin.repair_streamradar_matches({"titles": [dict(wrong, rating="4.0")]},
                                                         rating_lookup=lookup)["titles"][0]
        lookup.assert_called_once_with("movie", "tt37287335")
        self.assertEqual("7.8", corrected["rating"])

    def test_public_input_requires_explicit_approval(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            path, source = root / "catalog.sqlite3", root / "input.json"
            admin.initialize(path)
            feed = catalog()
            source.write_text(json.dumps(feed))
            with self.assertRaisesRegex(ValueError, "approved"):
                admin.import_verified(path, source)
            source.write_text(json.dumps(dict(feed, publication="approved")))
            with patch.object(admin, "poster_responds", return_value=True):
                self.assertEqual(32, admin.import_verified(path, source))
            self.assertEqual("Sweep TV verified input", admin.overview(path)["runs"][0]["source"])

    def test_sweep_feed_keeps_editorial_order_and_a_pick_that_leaves_the_charts(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            path, source, export = root / "catalog.sqlite3", root / "source.json", root / "IN.json"
            feed = catalog()
            row = dict(entry(999, "netflix"), link="https://www.netflix.com/in/title/1234")
            feed["titles"].append(row)
            admin.initialize(path)
            source.write_text(json.dumps(feed))
            admin.import_feed(path, source)
            request = {"kind": "tv", "id": "tt999", "rank": 2, "poster": "https://image.tmdb.org/t/p/w780/poster.jpg"}
            admin.save_editorial(path, request, checker=lambda url: True)
            with self.assertRaisesRegex(ValueError, "reachable"):
                admin.save_editorial(path, dict(request, poster="https://example.org/untrusted.jpg"))
            feed["titles"].pop()
            source.write_text(json.dumps(feed))
            admin.import_feed(path, source)
            admin.export_feed(path, export)
            picks = [r for r in json.loads(export.read_bytes())["titles"] if r.get("editorial_rank")]
            self.assertEqual([("tt999", 2, request["poster"])],
                             [(r["id"], r["editorial_rank"], r["poster"]) for r in picks])

    def test_expanded_catalog_keeps_the_tv_feed_bounded_and_preserves_english_coverage(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            path, export = root / "catalog.sqlite3", root / "export/IN.json"
            admin.initialize(path)
            feed = catalog()
            extra = [dict(entry(1000 + i, "zee5", "new" if i % 2 else "trending"), language="hi")
                     for i in range(230)]
            feed["titles"] = extra + feed["titles"]
            run = admin.begin(path, "StreamRadar")
            admin.commit(path, run, feed, "StreamRadar", expanded=True)
            state = admin.overview(path)
            admin.export_feed(path, export)
            tv = json.loads(export.read_bytes())

            self.assertEqual(len(feed["titles"]), state["entries"])
            self.assertEqual(admin.MAX_ROWS, state["tv_entries"])
            self.assertEqual(state["tv_entries"], sum(r["in_feed"] for r in state["titles"]))
            self.assertEqual(state["tv_entries"], len(tv["titles"]))
            self.assertTrue(all(state["tv_coverage"][provider]["titles"] == 8 for provider in admin.PRIMARY))
            self.assertTrue(any(r["row"] == "new" and r["language"] == "en" for r in tv["titles"]))

    def test_private_refresh_keeps_lower_and_pending_ratings_out_of_recommendations(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            db, source = root / "catalog.db", root / "IN.json"
            feed = catalog()
            for index, rating in ((998, 6.0), (999, 5.9), (1000, "")):
                feed["titles"].append(dict(entry(index, "netflix", "new"), rating=rating,
                                           _just_in_date="2026-11-17"))
            source.write_text(json.dumps(feed))
            admin.initialize(db)
            admin.refresh(db, source)
            state = admin.overview(db)
            candidates = {row["id"]: row for row in state["titles"]}
            self.assertTrue(candidates["tt998"]["in_feed"])
            self.assertEqual("2026-11-17", candidates["tt998"]["_just_in_date"])
            self.assertTrue(candidates["tt998"]["eligible"])
            self.assertFalse(candidates["tt999"]["eligible"])
            self.assertFalse(candidates["tt1000"]["eligible"])
            self.assertEqual({"IMDb rating below 6.0", "IMDb rating pending"},
                             {issue["reason"] for issue in state["issues"]})

    def test_apple_refresh_mixes_originals_into_the_app_row_and_keeps_them_on_daily_refresh(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            db, raw, originals, export = [root / name for name in ("db.sqlite", "charts.json", "apple.json", "IN.json")]
            raw.write_text(json.dumps(catalog()))
            original = entry(998, "apple-tv-plus", "provider")
            originals.write_text(json.dumps({"version": 1, "region": "IN", "titles": [original]}))
            admin.initialize(db)
            admin.refresh(db, raw)
            admin.refresh_provider(db, "apple-tv-plus", originals)
            admin.refresh(db, raw)
            admin.export_feed(db, export)
            rows = json.loads(export.read_bytes())["titles"]
            apple_app = [row for row in rows if row["provider"] == "apple-tv-plus" and row["row"] != "new"]
            self.assertEqual(("tt998", "provider"), (apple_app[0]["id"], apple_app[0]["row"]))
            self.assertEqual("trending", apple_app[1]["row"])
            self.assertFalse(any(row["row"] == "new" for row in rows if row["id"] == "tt998"))

    def test_english_netflix_reserve_survives_a_full_feed_and_keeps_offer_checks(self):
        rows = [dict(entry(1000 + i, "apple-tv-plus", "provider"), _apple_access="included")
                for i in range(admin.MAX_ROWS)]
        pool = [entry(2000 + i, "netflix", "provider") for i in range(admin.NETFLIX_ENGLISH_TARGET)]
        selected = admin.select_tv_rows(rows + pool)
        self.assertEqual(admin.NETFLIX_ENGLISH_TARGET, sum(row["provider"] == "netflix" for row in selected))
        self.assertEqual(admin.MAX_ROWS, len(selected))
        held = dict(pool[0], _netflix_english=True, _netflix_access="unconfirmed")
        self.assertFalse(admin.eligible_listing(held, "StreamRadar", set()))

    def test_rejected_import_keeps_the_previous_snapshot_and_titles(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            path, source, export = root / "catalog.sqlite3", root / "source.json", root / "export/IN.json"
            feed = catalog()
            admin.initialize(path)
            source.write_text(json.dumps(feed))
            admin.import_feed(path, source)
            before = admin.overview(path)
            admin.export_feed(path, export)
            saved = export.read_bytes()
            feed["titles"].pop(0)
            feed["titles"][-1]["link"] = ""
            source.write_text(json.dumps(feed))
            with self.assertRaisesRegex(ValueError, "netflix 7/8"):
                admin.import_feed(path, source)
            after = admin.overview(path)
            admin.export_feed(path, export)
            self.assertEqual(before["snapshot"], after["snapshot"])
            self.assertEqual(before["titles"], after["titles"])
            self.assertEqual(saved, export.read_bytes())
            self.assertEqual("failed", after["runs"][0]["status"])

    def test_verified_supplement_is_provider_only_and_expires(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            path, source = root / "catalog.sqlite3", root / "source.json"
            admin.initialize(path)
            feed = catalog()
            feed["titles"].pop(0)
            item = dict(entry(999, "netflix", "provider"), link="https://www.netflix.com/in/title/1234")
            source.write_text(json.dumps(dict(feed, titles=[item])))
            admin.save_supplement(path, source, "tt999", "netflix", "Netflix India")
            candidate, used = admin.fill_with_supplements(path, feed)
            run = admin.begin(path, "StreamRadar")
            admin.commit(path, run, candidate, "StreamRadar", supplements=used)
            detail = next(r for r in admin.overview(path)["titles"] if r["id"] == "tt999")
            self.assertEqual("provider", detail["row"])
            self.assertEqual("Netflix India", detail["source"])
            self.assertIsNotNone(detail["supplement_expires"])
            with admin.closing(admin.connect(path)) as db, db:
                db.execute("UPDATE supplements SET expires_at = '2000-01-01T00:00:00+00:00'")
            _, used = admin.fill_with_supplements(path, feed)
            self.assertEqual({}, used)

    def test_feed_rejects_mismatched_links(self):
        feed = catalog()
        feed["titles"][0]["link"] = "https://wrong.example/title/123"
        rows, _ = admin.validate(feed, require_coverage=True)
        self.assertEqual("", rows[0]["link"])
    def test_refresh_schedule_uses_six_hour_india_windows(self):
        cases = [(5, 6, 17), (6, 12, 17), (13, 18, 17), (23, 0, 18)]
        for hour, next_hour, day in cases:
            with self.subTest(hour=hour):
                now = datetime(2026, 11, 17, hour, tzinfo=admin.IST)
                self.assertEqual(datetime(2026, 11, day, next_hour, tzinfo=admin.IST), admin.next_refresh(now))

    def test_broken_poster_does_not_count_toward_coverage(self):
        feed = catalog()
        feed["titles"][0]["poster"] = "https://image.tmdb.org/t/p/w500/missing.jpg"
        feed["titles"][0]["background"] = "https://image.tmdb.org/t/p/w1280/backdrop.jpg"
        with patch.object(admin, "poster_responds", side_effect=lambda url: not url.endswith("missing.jpg")):
            corrected = admin.check_posters(feed)
        self.assertTrue(corrected["titles"][0]["_poster_unavailable"])
        self.assertTrue(corrected["titles"][0]["background"])
        with self.assertRaisesRegex(ValueError, "netflix 7/8"):
            admin.validate(corrected, require_coverage=True)


if __name__ == "__main__":
    unittest.main()
