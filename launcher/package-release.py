#!/usr/bin/env python3
"""Package one verified Sweep TV release. Does not publish or modify a TV."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import sys
import unittest
from datetime import datetime, timezone
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent
WORKSPACE = ROOT.parent


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def package(validation=None):
    release = json.loads((ROOT / 'app/src/main/assets/sweep-release.json').read_text())
    version = release['version']
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+-sweep\.[0-9]+', version):
        raise ValueError('Invalid Sweep TV version')
    spec = importlib.util.spec_from_file_location('upstream_gate', ROOT / 'check-upstream.py')
    gate = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(gate)
    suite = unittest.defaultTestLoader.discover(str(ROOT / 'tests'), pattern='test_upstream_gate.py')
    result = unittest.TextTestRunner(stream=sys.stderr).run(suite)
    if not result.wasSuccessful():
        raise ValueError('Release gate tests failed')
    # Fresh online verification; never reuse an offline/stale success report.
    upstream = gate.verify(ROOT, json.loads((ROOT / 'upstream/cleanyourtv-lock.json').read_text()))
    source_apk = ROOT / 'app/build/outputs/apk/release/app-release.apk'
    results = list((ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
    if not results or not source_apk.is_file():
        raise ValueError('Run the relevant Android tests and release build first')
    suites = [ET.parse(p).getroot() for p in results]
    tests = sum(int(x.get('tests')) for x in suites)
    if not tests or any(int(x.get('failures')) + int(x.get('errors')) for x in suites):
        raise ValueError('Android tests have not passed')
    lint = ET.parse(ROOT / 'app/build/reports/lint-results-release.xml').getroot()
    if any(x.get('severity') in ('Error', 'Fatal') for x in lint.findall('issue')):
        raise ValueError('Release lint has errors')
    # APK must contain the exact bundled release information, not an old successful build.
    with zipfile.ZipFile(source_apk) as apk:
        if json.loads(apk.read('assets/sweep-release.json')) != release:
            raise ValueError('Release APK is stale')
    env = dict(os.environ, JAVA_HOME=str(WORKSPACE / 'tools/jdk17/Contents/Home'))
    cert = subprocess.check_output([str(WORKSPACE / 'tools/android-sdk/build-tools/35.0.0/apksigner'), 'verify', '--print-certs', str(source_apk)], env=env, text=True)
    signing = re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)', cert)
    if not signing:
        raise ValueError('APK signature verification failed')
    validation_data = None
    if validation:
        validation_data = json.loads(validation.read_text())
        if validation_data.get('version_code') != release['version_code'] or validation_data.get('apk_sha256') != sha(source_apk) or not validation_data.get('tv_installed'):
            raise ValueError('TV validation belongs to another APK or is incomplete')
    out = WORKSPACE / 'dist/releases' / version
    out.mkdir(parents=True, exist_ok=True)
    apk = out / 'sweep-tv.apk'
    shutil.copy2(source_apk, apk)
    (out / 'release.json').write_text(json.dumps(release, indent=2) + '\n')
    notes = '# Sweep TV ' + version + '\n\n' + '\n\n'.join('- ' + n for n in release['notes'])
    notes += '\n\n## Included components\n\n- CleanYourTV ' + upstream['release'] + ': selected shared source reviewed at ' + upstream['commit'] + '; existing ServiceRepair adaptation retained.\n'
    notes += '\n'.join('- ' + x['name'] + ': ' + x['detail'] for x in release['components'])
    (out / 'RELEASE_NOTES.md').write_text(notes + '\n')
    source = out / 'source.tar.gz'
    # Explicit source allowlist excludes credentials, signing keys, caches and research history.
    include = ['app/src', 'app/build.gradle', 'app/proguard-rules.pro', 'build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat', 'gradle/wrapper', 'build-preview.sh', 'check-upstream.py', 'package-release.py', 'upstream', 'tests', 'companion']
    include += [str(p.relative_to(ROOT)) for p in ROOT.glob('*.md')]
    include += ['LICENSE', 'OUTFIT-OFL.txt']
    with tarfile.open(source, 'w:gz') as archive:
        for name in include:
            archive.add(ROOT / name, arcname='launcher/' + name, filter=lambda i: None if '__pycache__' in i.name or i.name.endswith('.pyc') else i)
        archive.add(WORKSPACE / 'AGENTS.md', arcname='AGENTS.md')
    report = {'version': version, 'version_code': release['version_code'], 'apk_sha256': sha(apk), 'source_sha256': sha(source), 'signing_certificate_sha256': signing.group(1), 'upstream': upstream,
              'checked_at': datetime.now(timezone.utc).isoformat(), 'gate_tests': result.testsRun, 'gate_failures': 0, 'android_tests': tests, 'android_failures': 0, 'test_suites': [{'name': x.get('name'), 'tests': int(x.get('tests'))} for x in suites],
              'lint_errors': 0, 'lint_warnings': len(lint.findall("issue[@severity='Warning']")), 'tv_installed': validation_data is not None}
    if validation_data:
        report['tv_validation'] = validation_data
    (out / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
    names = ['sweep-tv.apk', 'source.tar.gz', 'release.json', 'RELEASE_NOTES.md', 'verification.json']
    (out / 'SHA256SUMS').write_text(''.join(sha(out / n) + '  ' + n + '\n' for n in names))
    bundle = out.parent / ('sweep-tv-' + version + '.zip')
    with zipfile.ZipFile(bundle, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name in names + ['SHA256SUMS']:
            archive.write(out / name, arcname=version + '/' + name)
    pointer = {'version': version, 'version_code': release['version_code'], 'apk': str(apk), 'apk_sha256': sha(apk), 'source_archive': str(source), 'source_sha256': sha(source), 'release_notes': str(out / 'RELEASE_NOTES.md'), 'verification': str(out / 'verification.json'), 'bundle': str(bundle), 'bundle_sha256': sha(bundle), 'tv_installed': validation_data is not None}
    (WORKSPACE / 'dist/latest-release.json').write_text(json.dumps(pointer, indent=2) + '\n')
    print(json.dumps({'release': str(out), 'bundle': str(bundle), 'bundle_sha256': sha(bundle), **report}, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tv-validation', type=Path)
    package(parser.parse_args().tv_validation)
