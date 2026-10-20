import { execFileSync } from 'node:child_process';
import { readFileSync, mkdirSync, writeFileSync, mkdtempSync, rmSync, realpathSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const manifest = JSON.parse(readFileSync(join(root, 'scripts/release/points-ops.json')));
export function validateFiles(files, allowed = manifest.allowedFiles) {
  if (!files.length) throw new Error('没有待发布的变更');
  for (const file of files) {
    if (/^(b-app|c-app)\//.test(file) || !allowed.includes(file)) {
      throw new Error(`points-ops 发布范围不允许：${file}，请使用通用发布流程`);
    }
  }
}
export function validateUpdate(lines, head) {
  const rows = lines.trim().split('\n').filter(Boolean).map(line => line.trim().split(/\s+/));
  if (rows.length !== 1) throw new Error('points-ops 每次只能推送一个分支');
  const [, localSha, remoteRef, base] = rows[0];
  if (localSha !== head || remoteRef !== manifest.target || !/^[0-9a-f]{40}$/.test(base) || /^0+$/.test(base)) {
    throw new Error('points-ops 只允许将当前 HEAD 快进到既有统一分支');
  }
  return base;
}
const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();
function run(cmd, args, cwd = root) {
  console.log(`› ${cmd} ${args.join(' ')}`);
  execFileSync(cmd, args, { cwd, stdio: 'inherit', env: process.env });
}
function generatedCheck(script, output) {
  const path = join(root, output);
  const before = readFileSync(path);
  try {
    run('node', [script]);
    if (!readFileSync(path).equals(before)) throw new Error(`生成物未同步：${output}`);
  } finally { writeFileSync(path, before); }
}
function schemaCheck() {
  const temp = mkdtempSync(join(tmpdir(), 'points-schema-'));
  try {
    const generated = join(temp, 'schema.sql');
    run('python3', ['backend/scripts/gen-test-schema.py', generated]);
    const table = text => text.match(/CREATE TABLE IF NOT EXISTS pts_point_type\s*\([\s\S]*?\n\);/)?.[0];
    const expected = table(readFileSync(generated, 'utf8'));
    if (!expected || expected !== table(readFileSync(join(root, 'backend/shop-app/src/test/resources/schema-test.sql'), 'utf8'))) {
      throw new Error('积分类型测试表与迁移生成结果不一致');
    }
  } finally { rmSync(temp, { recursive: true, force: true }); }
}
function main() {
  const head = git('rev-parse', 'HEAD');
  const base = validateUpdate(readFileSync(0, 'utf8'), head);
  git('merge-base', '--is-ancestor', base, head);
  const receiptDir = resolve(root, git('rev-parse', '--git-common-dir'), 'points-ops-receipts');
  const receiptPath = join(receiptDir, `${head}.json`);
  try {
    const prior = JSON.parse(readFileSync(receiptPath, 'utf8'));
    if (prior.head === head && prior.base === base && prior.profile === 'points-ops') {
      console.log(`✓ 复用同一提交的专项验证记录：${head}`);
      return;
    }
  } catch { /* No successful receipt for this exact commit and base. */ }
  git('merge-base', '--is-ancestor', manifest.releaseBase, base);
  const files = git('diff', '--name-only', '--no-renames', manifest.releaseBase, head).split('\n').filter(Boolean);
  validateFiles(files);
  run('git', ['diff', '--check', base, head]);
  run('node', ['--test', 'scripts/release/points-ops.test.mjs']);
  run('python3', ['scripts/gen-ui-catalog.py', '--check']);
  run('node', ['scripts/check-ops-contract.mjs', '--check']);
  generatedCheck('ops-web/scripts/gen-openapi.mjs', 'docs/api/openapi-ops.yaml');
  schemaCheck();
  const ops = join(root, 'ops-web');
  run('npm', ['test', '--', 'lib/points-language.test.ts', 'lib/points-types-page.test.ts', 'lib/points-ops-routes.test.ts', 'lib/api/points-operations-api.test.ts', 'lib/api/points-configuration.test.ts', 'lib/points-command.test.ts', 'lib/points-configuration-ui.test.ts', 'lib/points-store-access-page.test.ts'], ops);
  run('npm', ['run', 'typecheck'], ops);
  const backend = files.some(f => f.startsWith('backend/'));
  if (backend) {
    run('mvn', ['-pl', 'shop-app', '-am', '-Dtest=PointTypeServiceTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test', '-q'], join(root, 'backend'));
    run('mvn', ['-pl', 'shop-app,pay/pay-svc', '-am', '-DskipTests', 'package', '-q'], join(root, 'backend'));
  }
  process.env.NEXT_PUBLIC_API_BASE = '';
  process.env.NEXT_PUBLIC_USE_MOCK = '0';
  process.env.NEXT_PUBLIC_BASE_PATH = '/ops-web';
  run('node', [join(root, 'node_modules/next/dist/bin/next'), 'build', '--webpack'], ops);
  run('node', ['scripts/assert-prod-build.mjs'], ops);
  mkdirSync(receiptDir, { recursive: true });
  writeFileSync(join(receiptDir, `${head}.json`), JSON.stringify({ profile: 'points-ops', head, base, releaseBase: manifest.releaseBase, files, backend, checkedAt: new Date().toISOString() }, null, 2));
  console.log(`✓ points-ops 发布检查通过：${head}（B/C 未变更）`);
}
if (process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))) main();
