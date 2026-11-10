"""Prepare an allowlist of website assets; never copy credentials or TV backups."""
from pathlib import Path
import hashlib
import json
import shutil

site = Path(__file__).resolve().parent
workspace = site.parent
out = site / 'dist'
for name in ['assets', 'downloads', 'notices']:
    (out / name).mkdir(parents=True, exist_ok=True)
assets = {
    'home.png': 'research/performance-2026-11-10/tv-continue-performance-70.png',
    'recommendations.png': 'research/portrait-continue-2026-11-10/tv-recommended-67.png',
    'formula-one.png': 'research/performance-2026-11-10/tv-f1-performance-70.png',
    'outfit.ttf': 'launcher/app/src/main/res/font/outfit.ttf',
}
for target, source in assets.items():
    shutil.copy2(workspace / source, out / 'assets' / target)
for target, source in {'LICENSE.txt': 'launcher/LICENSE', 'THIRD-PARTY-NOTICES.txt': 'launcher/THIRD_PARTY_NOTICES.md',
                       'OUTFIT-OFL.txt': 'launcher/OUTFIT-OFL.txt'}.items():
    shutil.copy2(workspace / source, out / 'notices' / target)
build = json.loads((workspace / 'dist/latest-release.json').read_text())
verification = json.loads(Path(build['verification']).read_text())
metadata = json.loads((workspace / 'launcher/app/src/main/assets/sweep-release.json').read_text())
apk = Path(build['apk'])
checksum = hashlib.sha256(apk.read_bytes()).hexdigest()
assert checksum == build['apk_sha256'], 'APK checksum mismatch'
name = 'sweep-tv-' + build['version'] + '.apk'
# Do not leave obsolete previews available as ambiguous downloads.
for old in (out / 'downloads').glob('*.apk'):
    old.unlink()
shutil.copy2(apk, out / 'downloads' / name)
previous = json.loads((workspace / 'dist/latest-build.json').read_text())
release = {'version': build['version'], 'code': build['version_code'], 'bytes': apk.stat().st_size, 'sha256': checksum,
           'file': 'downloads/' + name, 'tests': verification['android_tests'], 'gateTests': verification['gate_tests'],
           'deviceVerified': False, 'installedOnTestTV': bool(build['tv_installed']),
           'previousTVVersion': previous['version'], 'minimumAndroid': '8.0', 'testedAndroid': '10',
           'stage': 'Pre-release · TV verification pending', 'notes': metadata['notes'],
           'upstream': metadata['verified_upstream'], 'components': metadata['components'],
           'repository': 'https://github.com/akhiltaneja/SweepMyTv'}
(out / 'release.json').write_text(json.dumps(release, indent=2) + '\n')
shutil.copy2(Path(build['release_notes']), out / 'release-notes.md')
print('Prepared current pre-release APK and curated screenshots. Physical TV verification is pending.')
