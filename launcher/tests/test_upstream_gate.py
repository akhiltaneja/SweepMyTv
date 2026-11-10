import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('upstream_gate', Path(__file__).parents[1] / 'check-upstream.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class UpstreamGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.local = self.root / 'app/src/main/java/com/sweepmytv/launcher/ServiceRepair.java'
        self.local.parent.mkdir(parents=True)
        self.local.write_bytes(b'local adaptation')
        self.upstream = b'verified upstream source'
        self.lock = {'repository': gate.REPOSITORY, 'release': 'v3.4.0', 'commit': 'a' * 40, 'reviewed_on': '2026-11-10', 'review_scope': 'Selected shared components only', 'components': [{
            'name': 'ServiceRepair', 'local_path': str(self.local.relative_to(self.root)),
            'local_sha256': hashlib.sha256(self.local.read_bytes()).hexdigest(),
            'upstream_path': 'launcher/ServiceRepair.java',
            'upstream_sha256': hashlib.sha256(self.upstream).hexdigest()}]}
        self.bundle_path=self.root / 'app/src/main/assets/sweep-release.json'
        self.bundle_path.parent.mkdir(parents=True)
        self.bundle={'product':'Sweep TV','distribution':'managed','notes':['Bundled update'], 'version':'1.3.0-sweep.22','version_code':68,'verified_upstream':{key:self.lock[key] for key in ['repository','release','commit','reviewed_on','review_scope']}}
        self.bundle_path.write_text(json.dumps(self.bundle))
        (self.root / 'app/build.gradle').write_text("versionCode 68\nversionName '1.3.0-sweep.22'\n")
        self.release = {'tag_name': 'v3.4.0', 'html_url': 'https://github.com/Gathin23/cleanyourtv/releases/tag/v3.4.0', 'draft': False, 'prerelease': False}
        self.commit = 'a' * 40
        self.calls = []

    def fetch(self, url):
        self.calls.append(url)
        if url.endswith('/releases/latest'):
            return json.dumps(self.release).encode()
        if '/commits/' in url:
            return json.dumps({'sha': self.commit}).encode()
        return self.upstream

    def test_latest_release_commit_and_both_source_hashes_must_match(self):
        result = gate.verify(self.root, self.lock, self.fetch)
        self.assertTrue(result['latest_verified'])
        self.assertEqual(3, len(self.calls))

    def test_a_new_release_stops_shipping_until_review(self):
        self.release.update(tag_name='v3.5.0', html_url='https://github.com/Gathin23/cleanyourtv/releases/tag/v3.5.0')
        with self.assertRaisesRegex(ValueError, 'needs review'):
            gate.verify(self.root, self.lock, self.fetch)

    def test_a_retargeted_release_is_rejected(self):
        self.commit = 'b' * 40
        with self.assertRaisesRegex(ValueError, 'verified commit'):
            gate.verify(self.root, self.lock, self.fetch)

    def test_changed_upstream_source_is_rejected(self):
        self.upstream = b'new behavior requiring review'
        with self.assertRaisesRegex(ValueError, 'differs'):
            gate.verify(self.root, self.lock, self.fetch)

    def test_local_component_changes_need_reverification(self):
        self.local.write_bytes(b'new local adapter')
        with self.assertRaisesRegex(ValueError, 'changed locally'):
            gate.verify(self.root, self.lock, self.fetch)
        self.assertEqual([], self.calls)

    def test_unofficial_draft_prerelease_and_mismatched_urls_are_rejected(self):
        for change in [{'draft': True}, {'prerelease': True}, {'html_url': 'https://example.com/v3.4.0'}, {'html_url': 'https://github.com/Gathin23/cleanyourtv/releases/tag/v9.0.0'}]:
            with self.subTest(change=change):
                self.release = dict(tag_name='v3.4.0', html_url='https://github.com/Gathin23/cleanyourtv/releases/tag/v3.4.0', draft=False, prerelease=False)
                self.release.update(change)
                with self.assertRaisesRegex(ValueError, 'official stable'):
                    gate.verify(self.root, self.lock, self.fetch)

    def test_offline_diagnostics_do_not_claim_latest_verification(self):
        result = gate.verify(self.root, self.lock, self.fetch, offline=True)
        self.assertFalse(result['latest_verified'])
        self.assertEqual([], self.calls)

    def test_bundled_review_status_cannot_drift_from_the_lock(self):
        self.bundle['verified_upstream']['release']='v3.3.0'
        self.bundle_path.write_text(json.dumps(self.bundle))
        with self.assertRaisesRegex(ValueError, 'differs'):
            gate.verify(self.root,self.lock,self.fetch)

    def test_bundled_version_cannot_drift_from_the_apk(self):
        self.bundle['version_code']=67
        self.bundle_path.write_text(json.dumps(self.bundle))
        with self.assertRaisesRegex(ValueError, 'APK version'):
            gate.verify(self.root,self.lock,self.fetch)

    def test_missing_release_notes_are_rejected(self):
        self.bundle['notes']=[]
        self.bundle_path.write_text(json.dumps(self.bundle))
        with self.assertRaisesRegex(ValueError, 'release information'):
            gate.verify(self.root,self.lock,self.fetch)


if __name__ == '__main__':
    unittest.main()
