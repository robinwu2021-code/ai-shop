// Legacy coupon pay atomic import/ledger transactions against disposable loopback-only MySQL; never uses shop DB settings.
import { execFileSync, spawnSync } from 'node:child_process';
import { readFileSync, rmSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const name = `codex-legacy-history-native-${randomUUID()}`;
const docker = args => execFileSync('docker', args, { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }).trim();
docker(['run', '--detach', '--name', name, '--publish', '127.0.0.1::3306', '--tmpfs', '/var/lib/mysql',
    '--env', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes', '--env', 'MYSQL_DATABASE=legacy_import_native_fixture', 'mysql:9.7.2']);
try {
    let ready = false;
    for (let attempt = 0; attempt < 60; attempt++) {
        try {
            docker(['exec', name, 'mysql', '-uroot', 'legacy_import_native_fixture', '-e', 'SELECT 1']);
            ready = true;
            break;
        } catch {
            await new Promise(resolve => setTimeout(resolve, 500));
        }
    }
    if (!ready) throw new Error('Disposable MySQL did not become ready');
    const address = docker(['port', name, '3306/tcp']);
    if (!/^127\.0\.0\.1:\d+$/.test(address)) throw new Error('Unexpected database port binding');
    const url = `jdbc:mysql://${address}/legacy_import_native_fixture?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`;
    const reports = [
        `${root}backend/shop-app/target/surefire-reports/ai.neargo.shop.pay.LegacyCouponHistoricalReturnNativeMysqlTest.txt`,
    ];
    reports.forEach(report => rmSync(report, { force: true }));
    const result = spawnSync('mvn', ['-o', '-q', '-f', 'backend/pom.xml', '-pl', 'shop-app', '-am',
        '-Dtest=LegacyCouponHistoricalReturnNativeMysqlTest',
        '-Dsurefire.failIfNoSpecifiedTests=false', 'test'], {
        cwd: root, env: { ...process.env, LEGACY_IMPORT_NATIVE_MYSQL_URL: url }, stdio: 'inherit',
    });
    if (result.error) throw result.error;
    if (result.status !== 0) throw new Error(`Native legacy coupon verification exited ${result.status}`);
    for (const report of reports) {
        const summary = readFileSync(report, 'utf8');
        if (!/Tests run: 1, Failures: 0, Errors: 0, Skipped: 0/.test(summary)) {
            throw new Error(`Native verification must execute its test without skipping: ${report}`);
        }
    }
    console.log('Native MySQL historical coupon return: real baseline linkage, concurrent restore and durable notification: 1 test passed, 0 skipped');
} finally {
    docker(['rm', '--force', name]);
}
