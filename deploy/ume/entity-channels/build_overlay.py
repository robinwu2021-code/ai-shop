"""Overlay changed classes onto a captured Spring Boot jar (UME166 overlay deploys).

usage: build_overlay.py BASELINE.jar OUTPUT.jar SPEC.json
SPEC: {"targets": [{"lib": "BOOT-INF/lib/pay-domain-0.1.0-SNAPSHOT.jar" | null (boot classes),
                    "classesRoot": "/path/to/target/classes", "classes": ["a/b/C", ...]}]}
For each class C every "C.class" and "C$*.class" in classesRoot replaces (or adds) the entry; any old "C$*.class"
missing from the new build is dropped. All other entries are copied byte for byte with their compression; nested
jars are rewritten the same way. A proof JSON records hashes of every replaced, added and dropped entry."""
import hashlib, io, json, os, sys, zipfile
from pathlib import Path

sha = lambda d: hashlib.sha256(d).hexdigest()
os.umask(0o077)
baseline, output, spec = Path(sys.argv[1]), Path(sys.argv[2]), json.loads(Path(sys.argv[3]).read_text())


def wanted(root, classes, prefix):
    out = {}
    for c in classes:
        d = (root / c).parent
        stem = Path(c).name
        for f in d.iterdir():
            if f.name == stem + '.class' or (f.name.startswith(stem + '$') and f.name.endswith('.class')):
                out[prefix + str(Path(c).parent / f.name)] = f.read_bytes()
        assert prefix + c + '.class' in out, c
    return out


def owned(name, classes, prefix):
    return any(name == prefix + c + '.class' or (name.startswith(prefix + c + '$') and name.endswith('.class')) for c in classes)


def rewrite(data, repl, classes, prefix, report):
    buf = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as old, zipfile.ZipFile(buf, 'w') as new:
        seen = set()
        for e in old.infolist():
            if owned(e.filename, classes, prefix):
                if e.filename in repl:
                    new.writestr(e, repl[e.filename]); seen.add(e.filename)
                    report['replaced'][e.filename] = sha(repl[e.filename])
                else:
                    report['dropped'].append(e.filename)
                continue
            new.writestr(e, old.read(e.filename))
        for n, b in sorted(repl.items()):
            if n not in seen:
                zi = zipfile.ZipInfo(n, date_time=(2026, 11, 19, 0, 0, 0)); zi.compress_type = zipfile.ZIP_DEFLATED
                new.writestr(zi, b); report['added'][n] = sha(b)
    out = buf.getvalue()
    with zipfile.ZipFile(io.BytesIO(data)) as a, zipfile.ZipFile(io.BytesIO(out)) as b:
        for n in a.namelist():
            if not owned(n, classes, prefix):
                assert b.read(n) == a.read(n), n
                assert a.getinfo(n).compress_type == b.getinfo(n).compress_type, n
    return out


base = baseline.read_bytes()
proof = {'baselineSha256': sha(base), 'targets': []}
libs, boot = {}, None
for t in spec['targets']:
    root = Path(t['classesRoot'])
    if t.get('lib'):
        libs[t['lib']] = (wanted(root, t['classes'], ''), t['classes'])
    else:
        boot = (wanted(root, t['classes'], 'BOOT-INF/classes/'), t['classes'])
with zipfile.ZipFile(io.BytesIO(base)) as app:
    new_libs = {}
    for lib, (repl, classes) in libs.items():
        report = {'lib': lib, 'replaced': {}, 'added': {}, 'dropped': []}
        new_libs[lib] = rewrite(app.read(lib), repl, classes, '', report)
        proof['targets'].append(report)
tmp = io.BytesIO()
with zipfile.ZipFile(io.BytesIO(base)) as old, zipfile.ZipFile(tmp, 'w') as new:
    for e in old.infolist():
        new.writestr(e, new_libs.get(e.filename, old.read(e.filename)))
data = tmp.getvalue()
if boot:
    report = {'lib': None, 'replaced': {}, 'added': {}, 'dropped': []}
    data = rewrite(data, boot[0], boot[1], 'BOOT-INF/classes/', report)
    proof['targets'].append(report)
output.write_bytes(data)
proof['candidateSha256'] = sha(data)
proof['unrelatedEntriesPreserved'] = True
output.with_suffix('.proof.json').write_text(json.dumps(proof, indent=1))
print(json.dumps({k: (v if k != 'targets' else [{'lib': t['lib'], 'replaced': len(t['replaced']), 'added': len(t['added']), 'dropped': len(t['dropped'])} for t in v]) for k, v in proof.items()}, indent=1))
