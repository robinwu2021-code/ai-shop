import {mkdtempSync, mkdirSync, writeFileSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readSchema} from '../lib/ddl.mjs';

test('column lexer separates inline fields without treating multiline CHECK clauses as columns', () => {
    const root = mkdtempSync(join(tmpdir(), 'ent-ddl-'));
    try {
        mkdirSync(join(root, 'migrations'));
        writeFileSync(join(root, 'migrations/V1__test.sql'), `CREATE TABLE probe (
 id BIGINT NOT NULL, delivery_status VARCHAR(32) NOT NULL COMMENT 'PENDING, ACKNOWLEDGED',
 -- comment with comma, not a definition
 source_system VARCHAR(32), /* comment, ignored */ title VARCHAR(80) COMMENT 'a,b',
 CONSTRAINT chk_probe CHECK (source_system IS NOT NULL
 AND CHARACTER_LENGTH(source_system)>0),
 PRIMARY KEY (id)
) ENGINE=InnoDB;`);
        const table = readSchema(root, ['migrations']).get('probe');
        assert.deepEqual(table.cols.map(c=>c.name), ['id','delivery_status','source_system','title']);
        assert.equal(table.cols.find(c=>c.name==='title').comment, 'a,b');
    } finally { rmSync(root, {recursive:true,force:true}); }
});
