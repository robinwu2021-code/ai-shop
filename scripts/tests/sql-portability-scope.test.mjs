import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, mkdirSync, writeFileSync, copyFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';
import { scanSqlPortability } from '../check-sql-portability.mjs';

const plugin = 'backend-plugins/shop-industry-beauty';
const checker = fileURLToPath(new URL('../check-sql-portability.mjs', import.meta.url));
const mask = fileURLToPath(new URL('../lib/sql-mask.mjs', import.meta.url));
const bad = 'CREATE INDEX IF NOT EXISTS idx ON demo(id);';
const rule = 'create-index-if-not-exists';

function fixture(run) {
  const root = mkdtempSync(join(tmpdir(), 'sql-portability-scope-'));
  const put = (file, source) => {
    const path = join(root, file);
    mkdirSync(join(path, '..'), { recursive: true });
    writeFileSync(path, source);
  };
  mkdirSync(join(root, 'backend'), { recursive: true });
  mkdirSync(join(root, plugin), { recursive: true });
  try { run(root, put); } finally { rmSync(root, { recursive: true, force: true }); }
}

function copyCli(root) {
  mkdirSync(join(root, 'scripts/lib'), { recursive: true });
  copyFileSync(checker, join(root, 'scripts/check-sql-portability.mjs'));
  copyFileSync(mask, join(root, 'scripts/lib/sql-mask.mjs'));
}

function cli(root, args = ['--check']) {
  return spawnSync(process.execPath, ['scripts/check-sql-portability.mjs', ...args], {
    cwd: root, encoding: 'utf8',
  });
}

// Removing the beauty root or masking all Java literals loses these real hits.
test('beautySqlAndJavaUseTheSamePortabilityRules', () => fixture((root, put) => {
  for (const dir of ['backend', plugin]) {
    put(`${dir}/src/test/schema.sql`, `-- example\n${bad}\nDROP INDEX IF EXISTS idx ON demo;\n`);
    put(`${dir}/src/test/Mapper.java`, `interface Mapper {\n  @Select("${bad}")\n  void query();\n  String SQL = """\n    ${bad}\n    """;\n}\n`);
  }
  const result = scanSqlPortability(root);
  const actual = result.hits.map(({ file, line, id }) => [file, line, id]).sort();
  assert.deepEqual(actual, [
    ['backend/src/test/schema.sql', 2, rule],
    ['backend/src/test/schema.sql', 3, 'drop-index-if-exists'],
    ['backend/src/test/Mapper.java', 2, rule],
    ['backend/src/test/Mapper.java', 5, rule],
    [`${plugin}/src/test/schema.sql`, 2, rule],
    [`${plugin}/src/test/schema.sql`, 3, 'drop-index-if-exists'],
    [`${plugin}/src/test/Mapper.java`, 2, rule],
    [`${plugin}/src/test/Mapper.java`, 5, rule],
  ].sort());
  assert.deepEqual(result.fresh, result.hits);
  assert.deepEqual(result.stale, []);
  assert.equal(result.knownCount, 0);
  for (const hit of result.hits) {
    assert.equal(hit.side, 'MariaDB');
    assert.ok(hit.why.length > 0 && hit.fix.length > 0);
  }
}));

// The real executable must use both roots, even if argv suggests exclusions.
test('badBeautySqlFailsCliCheck', () => fixture((root, put) => {
  put('backend/good.sql', 'CREATE INDEX idx ON demo(id);');
  put(`${plugin}/bad.sql`, bad);
  copyCli(root);
  for (const args of [['--check'], ['--check', '--scan-roots=backend', '--exclude', plugin]]) {
    const result = cli(root, args);
    assert.equal(result.status, 1, result.stdout + result.stderr);
    assert.match(result.stdout, /backend-plugins\/shop-industry-beauty\/bad.sql:1/);
    assert.match(result.stdout, /CREATE INDEX IF NOT EXISTS/);
  }
  assert.equal(cli(root, []).status, 0, 'display mode remains non-blocking');
  put(`${plugin}/bad.sql`, 'CREATE INDEX idx ON demo(id);');
  assert.equal(cli(root).status, 0);
}));

test('badBeautyJavaTextblockFailsCliCheck', () => fixture((root, put) => {
  put(`${plugin}/Mapper.java`, `class Mapper {\n String SQL = """\n ${bad}\n """;\n}\n`);
  copyCli(root);
  const result = cli(root);
  assert.equal(result.status, 1, result.stdout + result.stderr);
  assert.match(result.stdout, /Mapper.java:3/);
  assert.match(result.stdout, /CREATE INDEX IF NOT EXISTS/);
}));

// Noise masking must not erase a neighboring executable payload or lose source lines.
test('sqlNoiseDoesNotCreateHits', () => fixture((root, put) => {
  const sql = `-- ${bad}\n# ${bad}\n/* ${bad} */\nCREATE TABLE t(id INT) COMMENT='${bad}';\nSELECT '${bad}', "${bad}", \`${bad}\`;\n${bad}\n`;
  const java = String.raw`class Mapper {
 // @Select("CREATE INDEX IF NOT EXISTS fake ON demo(id)")
 /* String SQL = """ CREATE INDEX IF NOT EXISTS fake ON demo(id) """; */
 char quote = '"'; char single = '\''; char slash = '\\';
 String noise = "SELECT 'CREATE INDEX IF NOT EXISTS fake ON demo(id)', \"DROP INDEX IF EXISTS fake\"";
 String comment = "/* CREATE INDEX IF NOT EXISTS fake */ SELECT 1 -- DROP INDEX IF EXISTS fake\n";
 String block = """
   -- CREATE INDEX IF NOT EXISTS fake
   SELECT 'DROP INDEX IF EXISTS fake', "CREATE INDEX IF NOT EXISTS fake";
   /* CREATE INDEX IF NOT EXISTS fake */
   CREATE INDEX IF NOT EXISTS real ON demo(id);
   """;
 @Select("SELECT \"quoted\"; -- CREATE INDEX IF NOT EXISTS fake\nCREATE\tINDEX\sIF\tNOT EXISTS real ON demo(id)")
 void query();
 String separateA = "CREATE INDEX"; String separateB = "IF NOT EXISTS fake";
}
`;
  for (const dir of ['backend', plugin]) {
    put(`${dir}/noise.sql`, sql);
    put(`${dir}/Noise.java`, java);
  }
  assert.deepEqual(scanSqlPortability(root).hits.map(({ file, line, id }) => [file, line, id]).sort(), [
    ['backend/noise.sql', 6, rule], ['backend/Noise.java', 11, rule], ['backend/Noise.java', 13, rule],
    [`${plugin}/noise.sql`, 6, rule], [`${plugin}/Noise.java`, 11, rule], [`${plugin}/Noise.java`, 13, rule],
  ].sort());
}));

// Keys must remain path+rule, independent of moved source lines or duplicate hits.
test('baselineMatchesPathAndRuleWithoutLineNumbers', () => fixture((root, put) => {
  const file = `${plugin}/bad.sql`;
  put(file, `\n\n${bad}\n${bad}\nDROP INDEX IF EXISTS idx ON demo;`);
  put('backend/known-sql-dialect.txt', `# fixture\n${file}\t${rule}\n`);
  const result = scanSqlPortability(root);
  assert.equal(result.hits.length, 3);
  assert.deepEqual(result.fresh.map(h => h.id), ['drop-index-if-exists']);
  assert.equal(result.knownCount, 1);
  assert.deepEqual(result.stale, []);
  put(file, `\n\n\n${bad}`);
  assert.equal(scanSqlPortability(root).fresh.length, 0);
  assert.equal(scanSqlPortability(root).hits[0].line, 4);
  copyCli(root);
  assert.equal(cli(root).status, 0);
}));

test('staleBaselineStillFailsCliCheck', () => fixture((root, put) => {
  const key = `${plugin}/gone.sql\t${rule}`;
  put('backend/known-sql-dialect.txt', `${key}\n`);
  const result = scanSqlPortability(root);
  assert.deepEqual(result.stale, [key]);
  assert.deepEqual(result.fresh, []);
  copyCli(root);
  assert.equal(cli(root).status, 1);
}));

// An import in a repository without scan directories must neither scan nor exit.
test('importHasNoScanningOutputOrExitSideEffects', () => fixture((root) => {
  copyCli(root);
  rmSync(join(root, 'backend'), { recursive: true });
  rmSync(join(root, plugin), { recursive: true });
  const url = pathToFileURL(join(root, 'scripts/check-sql-portability.mjs')).href;
  const result = spawnSync(process.execPath, ['--input-type=module', '-e',
    `await import(${JSON.stringify(url)}); console.log('import completed');`], { cwd: root, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, 'import completed\n');
  assert.equal(result.stderr, '');
}));

test('missingRequiredRootFailsExplicitly', () => fixture((root) => {
  rmSync(join(root, plugin), { recursive: true });
  assert.throws(() => scanSqlPortability(root), /shop-industry-beauty/);
  copyCli(root);
  assert.equal(cli(root).status, 1);
}));

test('onlyExistingBuildMetadataDirectoriesAreSkipped', () => fixture((root, put) => {
  for (const dir of ['backend', plugin]) {
    for (const excluded of ['target', 'node_modules', '.git']) put(`${dir}/${excluded}/bad.sql`, bad);
    put(`${dir}/src/test/kept.sql`, bad);
  }
  assert.deepEqual(scanSqlPortability(root).hits.map(h => h.file).sort(), [
    'backend/src/test/kept.sql', `${plugin}/src/test/kept.sql`,
  ].sort());
}));

// Java source terminators must end // noise and preserve physical source positions.
for (const [label, newline] of [['LF', '\n'], ['CRLF', '\r\n'], ['CR', '\r']]) {
  test(`javaLineCommentEndsAt${label}`, () => fixture((root, put) => {
    put(`${plugin}/Newline.java`, `class Newline {${newline}// ignored${newline}String sql = "${bad}";${newline}}`);
    assert.deepEqual(scanSqlPortability(root).hits.map(({line, id}) => [line, id]), [[3, rule]]);
  }));
  test(`textblockContinuationSkips${label}AndMapsOriginalOffset`, () => fixture((root, put) => {
    put(`${plugin}/Continuation.java`, `class Continuation {${newline}String sql = """${newline}CREATE\\${newline} INDEX IF NOT EXISTS idx ON demo(id);${newline}\\${newline}DROP INDEX IF EXISTS idx ON demo;${newline}""";${newline}}`);
    assert.deepEqual(scanSqlPortability(root).hits.map(({line, id}) => [line, id]), [
      [3, rule], [6, 'drop-index-if-exists'],
    ]);
  }));
}

test('mixedSourceTerminatorsCountCrLfOnceAndJavaEscapesAddNoSourceLines', () => fixture((root, put) => {
  put(`${plugin}/Mixed.java`, 'class Mixed {\r// ignored\r\nString safe = "SELECT 1\\n";\n'
    + `String sql = "SELECT 1\\n${bad}";\r}`);
  put(`${plugin}/mixed.sql`, `SELECT 1;\rSELECT 2;\r\nSELECT 3;\n${bad}`);
  assert.deepEqual(scanSqlPortability(root).hits.map(({file, line, id}) => [file, line, id]).sort(), [
    [`${plugin}/Mixed.java`, 4, rule], [`${plugin}/mixed.sql`, 4, rule],
  ].sort());
}));
