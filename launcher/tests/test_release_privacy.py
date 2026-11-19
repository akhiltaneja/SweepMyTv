import importlib.util
import io
from pathlib import Path
import tarfile
import unittest
import zipfile


def load_script(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


ROOT = Path(__file__).parents[1]
release = load_script("release_packager", ROOT / "package-release.py")
public = load_script("prepare_public", ROOT / "catalog/cloud/prepare-public.py")


class ReleasePrivacyTest(unittest.TestCase):
    def test_distribution_gate_rejects_comparison_builds_and_private_payloads(self):
        candidates = (
            (None, None), (b"comparison\n", None),
            (b"distribution\n", "assets/comparison/IN.json"),
            (b"distribution\n", "assets/catalog/IN.json"),
            (b"distribution\n", "assets/catalog.sqlite3"),
        )
        for marker, extra in (*candidates, (b"distribution\n", None)):
            with self.subTest(marker=marker, extra=extra):
                with zipfile.ZipFile(io.BytesIO(), "w") as apk:
                    if marker:
                        apk.writestr("assets/sweep-build-mode.txt", marker)
                    if extra:
                        apk.writestr(extra, "placeholder")
                    if (marker, extra) in candidates:
                        with self.assertRaises(ValueError):
                            release.public_apk(apk)
                    else:
                        release.public_apk(apk)

    def test_source_archive_drops_private_files_links_and_local_owner_metadata(self):
        for name in ("launcher/catalog/cloud/public/catalog/IN.json", "launcher/catalog/.env.local",
                     "launcher/tests/fixture.sqlite3", "launcher/app/src/main/assets/tmdb_key.txt"):
            self.assertIsNone(release.release_source(tarfile.TarInfo(name)))
        member = tarfile.TarInfo("launcher/catalog/README.md")
        member.uname, member.gname = "local-user", "local-group"
        self.assertEqual(("", ""), (release.release_source(member).uname, member.gname))
        member.type = tarfile.SYMTYPE
        self.assertIsNone(release.release_source(member))

    def test_secret_content_is_rejected_even_under_an_ordinary_name(self):
        with self.assertRaisesRegex(ValueError, "README.md"):
            release.verify_public_content("README.md", b"dummy value " + b"ghp_" + b"a" * 36)

    def test_public_input_requires_approval_and_drops_url_parameters(self):
        with self.assertRaises(ValueError):
            public.strip_url_parameters({"version": 1, "region": "IN", "titles": []})
        feed = {"publication": "approved", "titles": [
            {"link": "https://www.netflix.com/in/title/123?ref=account#fragment"}
        ]}
        self.assertEqual("https://www.netflix.com/in/title/123",
                         public.strip_url_parameters(feed)["titles"][0]["link"])
