"""Overlay near-pay f7a0c87eb (StoreCollectionMethodService only) onto the captured UME166 pay-svc.jar.
Every other entry is copied byte for byte; nested library jars keep their compression; a proof JSON records hashes."""
import hashlib, io, json, os, sys, zipfile
from pathlib import Path
baseline, output, classes_root = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
LIB = 'BOOT-INF/lib/pay-domain-0.1.0-SNAPSHOT.jar'
CLASS = 'ai/neargo/shop/pay/platmch/store/policy/StoreCollectionMethodService.class'
sha = lambda d: hashlib.sha256(d).hexdigest()
os.umask(0o077)
base = baseline.read_bytes()
new_class = (classes_root / CLASS).read_bytes()
with zipfile.ZipFile(io.BytesIO(base)) as app:
    lib = app.read(LIB)
out_lib = io.BytesIO()
with zipfile.ZipFile(io.BytesIO(lib)) as old, zipfile.ZipFile(out_lib, 'w') as new:
    assert CLASS in old.namelist()
    for e in old.infolist():
        new.writestr(e, new_class if e.filename == CLASS else old.read(e.filename))
lib2 = out_lib.getvalue()
with zipfile.ZipFile(io.BytesIO(lib)) as a, zipfile.ZipFile(io.BytesIO(lib2)) as b:
    assert a.namelist() == b.namelist()
    for n in a.namelist():
        assert b.read(n) == (new_class if n == CLASS else a.read(n)), n
with zipfile.ZipFile(io.BytesIO(base)) as old, zipfile.ZipFile(output, 'w') as new:
    for e in old.infolist():
        new.writestr(e, lib2 if e.filename == LIB else old.read(e.filename))
with zipfile.ZipFile(io.BytesIO(base)) as a, zipfile.ZipFile(output) as b:
    assert a.namelist() == b.namelist()
    for n in a.namelist():
        assert b.read(n) == (lib2 if n == LIB else a.read(n)), n
        assert a.getinfo(n).compress_type == b.getinfo(n).compress_type, n
proof = {'baselineSha256': sha(base), 'replaced': {LIB: {CLASS: sha(new_class)}},
         'unrelatedEntriesPreserved': True, 'candidateSha256': sha(output.read_bytes())}
output.with_suffix('.proof.json').write_text(json.dumps(proof, indent=2))
print(json.dumps(proof, indent=1))
