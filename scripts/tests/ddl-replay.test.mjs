import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { readSchema } from '../lib/ddl.mjs';

test('DDL replay reads shorthand column ALTER and does not invent columns inside CHECK', () => {
  const root = mkdtempSync(join(tmpdir(), 'ddl-replay-'));
  try {
    writeFileSync(join(root, 'V1__fixture.sql'), `CREATE TABLE fixture (
  id BIGINT NOT NULL, -- historical note, with commas
  /* historical comment, still not a column */
  status VARCHAR(32) COMMENT 'ACTIVE / ENDED',
  CONSTRAINT ck_fixture CHECK (id > 0
    AND JSON_VALID('{}') AND status IN ('ACTIVE', 'ENDED'))
);
ALTER TABLE fixture MODIFY status VARCHAR(64) NULL;
ALTER TABLE fixture ADD customer_no VARCHAR(64) NULL, ADD UNIQUE KEY uk_customer (customer_no);
`);
    const table = readSchema(root, ['.']).get('fixture');
    assert.deepEqual(table.cols.map(c => c.name), ['id', 'status', 'customer_no']);
    assert.equal(table.cols.find(c => c.name === 'status').comment, 'ACTIVE / ENDED');
  } finally { rmSync(root, { recursive: true, force: true }); }
});
