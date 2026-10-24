import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { persistenceOwners } from '../lib/persistence-owners.mjs';

test('ownership evidence rejects removed owner, wrong table and fabricated row model', () => {
  const root = mkdtempSync(join(tmpdir(), 'persistence-owners-'));
  try {
    mkdirSync(join(root, 'backend/module/src/main'), { recursive: true });
    const path = 'backend/module/src/main/Adapter.java';
    writeFileSync(join(root, path), 'class Adapter { JdbcTemplate jdbc; Map<String,Object> row; String sql="SELECT * FROM ord_actual"; }');
    const entry = { owner:path, querySource:path, modelSource:path, rowShape:'MAPPED_ROW', modelSymbol:'Map<String,Object>' };
    assert.deepEqual([...persistenceOwners(root, { ord_actual:entry })], ['ord_actual']);
    assert.throws(() => persistenceOwners(root, { ord_wrong:entry }), /missing table reference/);
    assert.throws(() => persistenceOwners(root, { ord_actual:{...entry,modelSymbol:'Invented'} }), /missing mapped row/);
    assert.throws(() => persistenceOwners(root, []), /Malformed/);
    assert.throws(() => persistenceOwners(root, { ord_actual:{...entry,rowShape:'INVENTED'} }), /unknown row shape/);
    assert.throws(() => persistenceOwners(root, { ord_actual:{...entry,dynamic:{prefix:'ord',suffix:'_actual'}} }), /invalid source-bound/);
    writeFileSync(join(root, path), 'class Adapter { JdbcTemplate jdbc; Map<String,Object> row; /* ord_actual */ }');
    assert.throws(() => persistenceOwners(root, { ord_actual:entry }), /missing table reference/);
    rmSync(join(root,path));
    assert.throws(() => persistenceOwners(root, { ord_actual:entry }), /ENOENT/);
  } finally { rmSync(root, { recursive:true, force:true }); }
});
