"""Overlay the store-method-preference relay classes onto the captured UME166 shop-app.jar.

Modelled on deploy/ume/mitake/build_overlay.py: every entry not named here is copied byte for byte,
nested library jars stay STORED, and a proof JSON records hashes. No migration is added.
"""
import argparse
import hashlib
import io
import json
import os
import re
import zipfile
from pathlib import Path

# library module (directory under backend/) -> class entries inside that library jar
LIB_CLASSES = {
    'pay/pay-domain': [
        'ai/neargo/shop/pay/client/PayBizInternalApi.class',
        'ai/neargo/shop/pay/client/PayBizInternalApi$MethodChoice.class',
        'ai/neargo/shop/pay/client/PayBizInternalApi$MethodPreference.class',
        'ai/neargo/shop/pay/client/PayBizInternalApi$MethodPreferences.class',
        'ai/neargo/shop/pay/client/PayBizInternalApi$MethodPreferencesReq.class',
    ],
}
# shop-app's own classes live under BOOT-INF/classes/
APP_CLASSES = [
    'ai/neargo/shop/payclient/BizPaymentAppService.class',
    'ai/neargo/shop/payclient/impl/BizPaymentAppServiceImpl.class',
    'ai/neargo/shop/payclient/impl/RemoteBizPaymentAppService.class',
    'ai/neargo/shop/portal/biz/pay/BizPaymentController.class',
    'ai/neargo/shop/portal/biz/pay/BizPaymentController$MethodChoiceReq.class',
    'ai/neargo/shop/portal/biz/pay/BizPaymentController$MethodPreferencesReq.class',
]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def rewrite(data, replacements):
    out = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as old, zipfile.ZipFile(out, 'w') as new:
        assert len(set(old.namelist())) == len(old.namelist()), 'duplicate entries'
        for entry in old.infolist():
            new.writestr(entry, replacements.get(entry.filename, old.read(entry.filename)))
        for name in sorted(replacements.keys() - set(old.namelist())):
            new.writestr(name, replacements[name], compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(io.BytesIO(data)) as old, zipfile.ZipFile(out) as new:
        for name in old.namelist():
            assert new.read(name) == replacements.get(name, old.read(name)), name
        assert set(new.namelist()) - set(old.namelist()) <= set(replacements)
    return out.getvalue()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--baseline', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--root', type=Path, required=True, help='repository root with compiled target/classes')
    a = p.parse_args()
    os.umask(0o077)
    base = a.baseline.read_bytes()
    proof = {'baselineSha256': sha(base), 'libraries': {}, 'appClasses': {}}
    changed = {}
    with zipfile.ZipFile(io.BytesIO(base)) as old:
        names = old.namelist()
        for module, classes in LIB_CLASSES.items():
            artifact = module.split('/')[-1]
            entries = [n for n in names if re.fullmatch(r'BOOT-INF/lib/' + re.escape(artifact) + r'-[0-9][^/]*\.jar', n)]
            assert len(entries) == 1, module
            reps = {c: (a.root / 'backend' / module / 'target/classes' / c).read_bytes() for c in classes}
            changed[entries[0]] = rewrite(old.read(entries[0]), reps)
            proof['libraries'][entries[0]] = {c: sha(d) for c, d in reps.items()}
        for c in APP_CLASSES:
            data = (a.root / 'backend/shop-app/target/classes' / c).read_bytes()
            changed['BOOT-INF/classes/' + c] = data
            proof['appClasses'][c] = sha(data)
    added = sorted(set(changed) - set(names))
    with zipfile.ZipFile(io.BytesIO(base)) as old, zipfile.ZipFile(a.output, 'w') as new:
        for entry in old.infolist():
            new.writestr(entry, changed.get(entry.filename, old.read(entry.filename)))
        for name in added:
            new.writestr(name, changed[name], compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(io.BytesIO(base)) as old, zipfile.ZipFile(a.output) as new:
        for name in old.namelist():
            assert new.read(name) == changed.get(name, old.read(name)), name
            assert new.getinfo(name).compress_type == old.getinfo(name).compress_type, name
        assert set(new.namelist()) - set(old.namelist()) == set(added)
        assert not any(n.startswith('BOOT-INF/classes/db/') for n in added), 'no migrations'
    proof['addedEntries'] = added
    proof['replacedEntries'] = sorted(set(changed) - set(added))
    proof['unrelatedEntriesPreserved'] = True
    proof['candidateSha256'] = sha(a.output.read_bytes())
    a.output.with_suffix('.proof.json').write_text(json.dumps(proof, indent=2))
    print(json.dumps({k: proof[k] for k in ('baselineSha256', 'candidateSha256', 'addedEntries', 'replacedEntries')}, indent=1))


if __name__ == '__main__':
    main()
