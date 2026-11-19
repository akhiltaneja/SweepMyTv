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
PRIVATE_CONTENT = re.compile(
    rb'-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----'
    rb'|(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|sk-proj-[A-Za-z0-9_-]{28,})'
    rb'|(?:AKIA|ASIA)[A-Z0-9]{16}'
    rb'|[?&](?:api_key|access_token|token|password)=[A-Za-z0-9_-]{20,}'
)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def public_apk(archive):
    names = archive.namelist()
    if names.count('assets/sweep-build-mode.txt') != 1 or archive.read('assets/sweep-build-mode.txt') != b'distribution\n':
        raise ValueError('Build the distribution APK before packaging a public release')
    if any(name.startswith(('assets/comparison/', 'assets/catalog/')) or private_path(name) for name in names):
        raise ValueError('A private asset is present in the release APK')
    for name in names:
        verify_public_content(name, archive.read(name))


def verify_public_content(name, data):
    if PRIVATE_CONTENT.search(data):
        raise ValueError('Possible credential in public file: ' + name)


def private_path(name):
    parts = Path(name).parts
    return (
        any(part in {'.git', '.gradle', '.wrangler', '__pycache__', 'node_modules', 'research',
                     'comparison', 'comparison-assets', 'local.properties', 'release.properties',
                     'tmdb_key.txt', 'credentials.json', 'secrets.json', 'id_rsa', 'id_ed25519'}
            or part.startswith(('.env', '.tv-device')) for part in parts)
        or Path(name).suffix.lower() in {'.pyc', '.sqlite', '.sqlite3', '.db', '.pem', '.p12',
                                        '.jks', '.keystore', '.log', '.apk'}
        or any(parts[index:index + 3] == ('catalog', 'cloud', 'public')
               for index in range(len(parts) - 2))
        or name.endswith(('-wal', '-shm'))
    )


def release_source(member):
    if private_path(member.name) or not (member.isdir() or member.isfile()):
        return None
    member.uid = member.gid = 0
    member.uname = member.gname = ''
    return member


def checked_release_source(member):
    member = release_source(member)
    if member and member.isfile():
        verify_public_content(member.name, (WORKSPACE / member.name).read_bytes())
    return member


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
        public_apk(apk)
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
        if validation_data.get('version_code') != release['version_code'] or validation_data.get('apk_sha256') != sha(source_apk) or validation_data.get('tv_installed') is not True:
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
    include = ['app/src', 'app/build.gradle', 'app/proguard-rules.pro', 'build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat', 'gradle/wrapper', 'build-preview.sh', 'check-upstream.py', 'check-tv.py', 'package-release.py', 'catalog', 'upstream', 'tests']
    include += [str(p.relative_to(ROOT)) for p in ROOT.glob('*.md')]
    include += ['LICENSE', 'OUTFIT-OFL.txt']
    with tarfile.open(source, 'w:gz') as archive:
        for name in include:
            archive.add(ROOT / name, arcname='launcher/' + name, filter=checked_release_source)
        archive.add(WORKSPACE / 'AGENTS.md', arcname='AGENTS.md', filter=checked_release_source)
    report = {'version': version, 'version_code': release['version_code'], 'apk_sha256': sha(apk), 'source_sha256': sha(source), 'signing_certificate_sha256': signing.group(1), 'upstream': upstream,
              'checked_at': datetime.now(timezone.utc).isoformat(), 'gate_tests': result.testsRun, 'gate_failures': 0, 'android_tests': tests, 'android_failures': 0, 'test_suites': [{'name': x.get('name'), 'tests': int(x.get('tests'))} for x in suites],
              'lint_errors': 0, 'lint_warnings': len(lint.findall("issue[@severity='Warning']")), 'tv_installed': validation_data is not None}
    if validation_data:
        report['tv_validation'] = {key: validation_data[key] for key in ('version_code', 'apk_sha256', 'tv_installed')}
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
