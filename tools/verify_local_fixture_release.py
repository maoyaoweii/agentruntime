#!/usr/bin/env python3
"""Offline checks of merged manifests AND packaged APK manifest/DEX boundaries."""
import argparse
import hashlib
import json
import pathlib
import subprocess
import zipfile
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--root', default=str(pathlib.Path(__file__).resolve().parents[1]))
p.add_argument('--aapt2', default='/workspace/android-sdk/build-tools/34.0.0/aapt2')
p.add_argument('--evidence', required=True, help='New, nonexistent output directory')
a = p.parse_args()
root = pathlib.Path(a.root)
out = pathlib.Path(a.evidence)
out.mkdir(parents=True, exist_ok=False)
ns = '{http://schemas.android.com/apk/res/android}'
results = {}
for variant, filename in [('debug', 'agent-debug.apk'), ('release', 'agent-release-unsigned.apk')]:
    apk = root / 'agent/build/outputs/apk' / variant / filename
    manifest = root / 'agent/build/intermediates/merged_manifests' / variant / (
        'process' + variant.title() + 'Manifest') / 'AndroidManifest.xml'
    tree = ET.parse(manifest)
    components = {e.get(ns + 'name'): e for e in tree.findall('./application/*')}
    fixture_names = ['com.agentruntime.debug.LocalFixtureActivity',
                     'com.agentruntime.debug.LocalFixtureProvider']
    if variant == 'debug':
        assert all(name in components for name in fixture_names)
        assert components[fixture_names[0]].get(ns + 'exported') == 'false'
        provider = components[fixture_names[1]]
        assert provider.get(ns + 'permission') == 'android.permission.DUMP'
        assert provider.get(ns + 'grantUriPermissions') == 'false'
    else:
        assert not any(name.startswith('com.agentruntime.debug.') for name in components)
        assert 'com.agentruntime.agent.localfixture' not in manifest.read_text()
        assert components['com.agentruntime.ui.TaskExecActivity'].get(ns + 'exported') == 'false'
    dump = subprocess.run([a.aapt2, 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'],
                          check=True, capture_output=True, text=True).stdout
    (out / (variant + '-packaged-manifest.txt')).write_text(dump)
    assert ('LocalFixtureProvider' in dump) == (variant == 'debug')
    assert ('LocalFixtureActivity' in dump) == (variant == 'debug')
    assert ('com.agentruntime.agent.localfixture' in dump) == (variant == 'debug')
    with zipfile.ZipFile(apk) as z:
        dex = b''.join(z.read(n) for n in z.namelist() if n.endswith('.dex'))
    for name in [b'Lcom/agentruntime/debug/LocalFixtureProvider;',
                 b'Lcom/agentruntime/debug/LocalFixtureActivity;',
                 b'Lcom/agentruntime/debug/LocalFixturePolicy;',
                 b'Lcom/agentruntime/debug/LocalFixtureAccess;',
                 b'Lcom/agentruntime/debug/LocalFixtureAnchor;',
                 b'Lcom/agentruntime/debug/LocalFixtureControl;']:
        assert (name in dex) == (variant == 'debug'), name
    assert (b'CUE_LOCAL_SYNTHETIC_FIXTURE_V1' in dex) == (variant == 'debug')
    assert (b'CUE_SYNTHETIC_DUMMY_V1' in dex) == (variant == 'debug')
    results[variant] = {'apk': str(apk), 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
                        'fixture_present': variant == 'debug', 'passed': True}
(out / 'result.json').write_text(json.dumps(results, indent=2) + '\n')
print('PASS: debug fixture registered/permission-protected; release manifest and DEX exclude all fixture classes')
