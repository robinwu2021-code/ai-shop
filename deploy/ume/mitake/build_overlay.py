"""Add the declared SMS classes to a captured live jar; preserve every other entry."""
import argparse
import hashlib
import io
import json
import os
import re
from pathlib import Path
import zipfile


CLASSES = {
    'shop-base': ['ai/neargo/shop/common/Phones.class'],
    'shop-core': [
        'ai/neargo/shop/message/entity/NotifyChannel.class',
        'ai/neargo/shop/message/notify/NotifyChannelRegistry.class',
        'ai/neargo/shop/message/notify/NotifyLogWriter.class',
        'ai/neargo/shop/message/notify/PlatformChannelCredentials.class',
        'ai/neargo/shop/message/notify/impl/NotifyChannelServiceImpl.class',
        'ai/neargo/shop/platform/impl/MarketPhonePolicyServiceImpl.class',
    ],
    'shop-notify': [
        'ai/neargo/shop/notify/port/AliSmsGateway.class',
        'ai/neargo/shop/notify/port/MitakeSmsGateway.class',
    ],
}


CONTENT_CLASSES = {
    'shop-base': ['ai/neargo/shop/spi/notify/SendResult.class',
                  'ai/neargo/shop/spi/notify/SmsPort$SmsException.class'],
    'shop-core': ['ai/neargo/shop/message/entity/SysNotifyLog.class',
                  'ai/neargo/shop/message/notify/NotifyLogWriter.class',
                  'ai/neargo/shop/message/notify/port/NotifyLoggingSmsPort.class'],
    'shop-notify': ['ai/neargo/shop/notify/port/MitakeSmsGateway.class'],
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def overlay_library(data, replacements):
    result = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as old, zipfile.ZipFile(result, 'w') as new:
        assert len(set(old.namelist())) == len(old.namelist()), 'Duplicate library entries'
        for entry in old.infolist():
            new.writestr(entry, replacements.get(entry.filename, old.read(entry.filename)))
        for name in replacements.keys() - set(old.namelist()):
            new.writestr(name, replacements[name], compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(io.BytesIO(data)) as old, zipfile.ZipFile(result) as new:
        for name in old.namelist():
            assert new.read(name) == replacements.get(name, old.read(name)), name
        assert set(new.namelist()) - set(old.namelist()) <= set(replacements)
    return result.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--feature', choices=['mitake', 'notify-content'], default='mitake')
    args = parser.parse_args()
    os.umask(0o077)
    changed = {}
    proof = {'baselineSha256': sha(args.baseline.read_bytes()), 'libraries': {}}
    with zipfile.ZipFile(args.baseline) as old:
        assert len(set(old.namelist())) == len(old.namelist()), 'Duplicate application entries'
        for module, names in (CONTENT_CLASSES if args.feature == 'notify-content' else CLASSES).items():
            entries = [n for n in old.namelist() if re.fullmatch(r'BOOT-INF/lib/' + re.escape(module) + r'-[0-9][^/]*\.jar', n)]
            assert len(entries) == 1, module
            replacements = {n: (args.root / 'backend' / module / 'target/classes' / n).read_bytes() for n in names}
            changed[entries[0]] = overlay_library(old.read(entries[0]), replacements)
            proof['libraries'][entries[0]] = {n: sha(data) for n, data in replacements.items()}
        migration = ('V678__notify_log_content.sql' if args.feature == 'notify-content'
                     else 'V677__mitake_sms_channel.sql')
        migration_entry = 'BOOT-INF/classes/db/migration/' + migration
        assert migration_entry not in old.namelist(), 'Migration version already packaged'
        changed[migration_entry] = (args.root / 'backend/shop-app/src/main/resources/db/migration' / migration).read_bytes()
        with zipfile.ZipFile(args.output, 'w') as new:
            for entry in old.infolist():
                new.writestr(entry, changed.get(entry.filename, old.read(entry.filename)))
            new.writestr(migration_entry, changed[migration_entry], compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(args.baseline) as old, zipfile.ZipFile(args.output) as new:
        for name in old.namelist():
            assert new.read(name) == changed.get(name, old.read(name)), name
        assert set(new.namelist()) - set(old.namelist()) == {migration_entry}
        assert all(new.getinfo(n).compress_type == zipfile.ZIP_STORED for n in changed if n.endswith('.jar'))
        proof['unrelatedApplicationEntriesPreserved'] = True
        proof['unrelatedLibraryEntriesPreserved'] = True
        proof['existingMigrationsPreserved'] = True
    proof['candidateSha256'] = sha(args.output.read_bytes())
    proof['addedMigration'] = migration
    args.output.with_suffix('.proof.json').write_text(json.dumps(proof, indent=2))
    print(json.dumps(proof))


if __name__ == '__main__':
    main()
