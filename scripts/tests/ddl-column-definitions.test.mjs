import {mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
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

const repository = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const migration = 'backend/shop-app/src/main/resources/db/migration';

function withSql(sql, check) {
    const root = mkdtempSync(join(tmpdir(), 'ddl-structure-'));
    try {
        mkdirSync(join(root, 'migrations'));
        writeFileSync(join(root, 'migrations/V1__test.sql'), sql);
        check(() => readSchema(root, ['migrations']));
    } finally { rmSync(root, {recursive: true, force: true}); }
}

test('frozen V506 checks remain constraints at one-space indentation', () => {
    const sql = readFileSync(join(repository, migration, 'V506__entitlement_exception_action.sql'), 'utf8');
    withSql(sql, read => {
        const table = read().get('pay_ent_exception_action');
        assert.deepEqual(table.cols.map(c => c.name), [
            'id', 'tenant_no', 'action_no', 'exception_no', 'request_key', 'payload_hash', 'reason',
            'entity_no', 'actor_no', 'status', 'response_snapshot', 'version', 'deleted',
            'created_at', 'updated_at', 'created_by', 'updated_by',
        ]);
        assert.deepEqual(table.indexes, ['tenant_no,exception_no,id']);
        assert.equal(table.indexNames.size, 3);
    });
});

test('multiline V517 and V533 checks cannot become AND columns', () => {
    for (const [file, table, count] of [
        ['V517__entitlement_migration_alias.sql', 'ent_migration_alias', 23],
        ['V593__shared_resource_identity.sql', 'mch_resource', 17],
    ]) {
        withSql(readFileSync(join(repository, migration, file), 'utf8'), read => {
            const columns = read().get(table).cols;
            assert.equal(columns.length, count, file);
            assert.ok(!columns.some(c => /^(CHECK|AND|CHAR_LENGTH)$/i.test(c.name)), file);
        });
    }
});

test('quotes comments nested expressions and ALTER preserve column metadata and indexes', () => {
    withSql(`/* CREATE TABLE bogus (x INT); */
CREATE TABLE probe (
\`id\` BIGINT NOT NULL, title VARCHAR(80) COMMENT 'it''s, (fine)',
-- fake VARCHAR(9), ),
amount DECIMAL(10,2), /* ), ignored VARCHAR(8), */
label VARCHAR(80) DEFAULT "double, (quoted)",
escaped VARCHAR(80) DEFAULT 'it\\'s, fine',
computed INT GENERATED ALWAYS AS ((amount + COALESCE(id, 0))) STORED,
CHECK (CHAR_LENGTH(title)>0 AND amount IN (1,2)),
CONSTRAINT ck CHECK (title <> 'CREATE TABLE fake (x INT);'),
KEY ix_probe (id, amount), UNIQUE KEY uk_probe (title)
) ENGINE=InnoDB
COMMENT='table, comment';
ALTER TABLE probe ADD COLUMN extra VARCHAR(32) COMMENT 'a,b',
/* separator, ignored */ ADD COLUMN other DECIMAL(8,2);`, read => {
        const table = read().get('probe');
        assert.ok(table, 'CREATE body and multiline table options must be found');
        assert.deepEqual(table.cols.map(c => c.name),
            ['id', 'title', 'amount', 'label', 'escaped', 'computed', 'extra', 'other']);
        assert.equal(table.cols[0].nullable, false);
        assert.equal(table.cols[2].type, 'decimal');
        assert.equal(table.cols[1].comment, "it's, (fine)");
        assert.deepEqual(table.indexes, ['id, amount']);
        assert.deepEqual([...table.uniques], ['title']);
        assert.equal(table.comment, 'table, comment');
    });
});

test('malformed definitions fail without swallowing the next table', () => {
    const following = '\nCREATE TABLE following (\n real_column INT\n) ENGINE=InnoDB;';
    for (const bad of [
        "CREATE TABLE broken (\n title VARCHAR(32) DEFAULT 'unterminated\n);",
        'CREATE TABLE broken (\n id INT, CHECK ((id > 0)\n);',
        'CREATE TABLE broken (\n id INT /* unterminated\n);',
        'CREATE TABLE broken (\n id INT;',
        'CREATE TABLE broken (\n id INT',
        'CREATE TABLE broken (id INT)',
        'CREATE TABLE broken id INT;',
        'CREATE TABLE broken (id INT));',
    ]) {
        withSql(bad + following, read => assert.throws(read, /Malformed SQL/i));
    }
});

test('CREATE table tail keeps the existing 600 character limit and multiline options', () => {
    withSql('CREATE TABLE probe (id INT)' + ' '.repeat(600) + ';', read => {
        assert.deepEqual(read().get('probe').cols.map(c => c.name), ['id']);
    });
    withSql('CREATE TABLE probe (id INT)' + ' '.repeat(601) + ';', read => {
        assert.throws(read, /Malformed SQL/i);
    });
});


test('ALTER quoted semicolon keeps the complete comment and subsequent statement', () => {
    withSql(`CREATE TABLE probe (id INT);
ALTER TABLE probe ADD COLUMN title VARCHAR(80) COMMENT 'left;right';
ALTER TABLE probe ADD COLUMN status VARCHAR(16) NOT NULL COMMENT 'next;statement';`, read => {
        const table = read().get('probe');
        assert.deepEqual(table.cols, [
            {name: 'id', type: 'int', nullable: true, comment: ''},
            {name: 'title', type: 'varchar', nullable: true, comment: 'left;right'},
            {name: 'status', type: 'varchar', nullable: false, comment: 'next;statement'},
        ]);
    });
});

test('ALTER structural clauses ignore semicolons and commas in quotes and SQL comments', () => {
    withSql(`CREATE TABLE probe (id INT);
-- ALTER TABLE probe ADD COLUMN line_ghost INT;
ALTER TABLE probe
 ADD COLUMN title VARCHAR(80) DEFAULT 'it''s; (fine), yes' COMMENT 'a;b,c',
 /* ; , ) ALTER TABLE probe ADD COLUMN block_ghost INT; */
 ADD COLUMN amount DECIMAL(10,2) COMMENT 'amount; total',
 -- ; , ) ALTER TABLE probe ADD COLUMN another_ghost INT;
 ADD COLUMN label VARCHAR(80) DEFAULT "double; (quoted), value" COMMENT 'last;clause';
ALTER TABLE probe ADD COLUMN following BIGINT COMMENT 'after;clauses';`, read => {
        const table = read().get('probe');
        assert.deepEqual(table.cols, [
            {name: 'id', type: 'int', nullable: true, comment: ''},
            {name: 'title', type: 'varchar', nullable: true, comment: 'a;b,c'},
            {name: 'amount', type: 'decimal', nullable: true, comment: 'amount; total'},
            {name: 'label', type: 'varchar', nullable: true, comment: 'last;clause'},
            {name: 'following', type: 'bigint', nullable: true, comment: 'after;clauses'},
        ]);
    });
});

test('ALTER downstream MODIFY RENAME and DROP use complete clauses after quoted semicolons', () => {
    withSql(`CREATE TABLE probe (id INT, title VARCHAR(80), obsolete INT);
ALTER TABLE probe MODIFY COLUMN title TEXT NOT NULL COMMENT 'modified; complete',
 ADD COLUMN marker VARCHAR(32) COMMENT 'new;field';
ALTER TABLE probe RENAME COLUMN marker TO renamed, DROP COLUMN obsolete;
ALTER TABLE probe ADD COLUMN following INT COMMENT 'still;read';`, read => {
        const table = read().get('probe');
        assert.deepEqual(table.cols, [
            {name: 'id', type: 'int', nullable: true, comment: ''},
            {name: 'title', type: 'text', nullable: false, comment: 'modified; complete'},
            {name: 'renamed', type: 'varchar', nullable: true, comment: 'new;field'},
            {name: 'following', type: 'int', nullable: true, comment: 'still;read'},
        ]);
    });
});
