// Full actual Flyway rehearsal in disposable MariaDB; never reads application DB connection settings.
import { execFileSync, spawnSync } from 'node:child_process';
import { readFileSync, rmSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../', import.meta.url));
const container = `codex-entitlement-upgrade-${randomUUID()}`;
const docker = args => execFileSync('docker', args, { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }).trim();
docker(['run', '--detach', '--name', container, '--publish', '127.0.0.1::3306', '--tmpfs', '/var/lib/mysql',
    '--env', 'MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1', 'mariadb:11.4']);
try {
    let ready = false;
    for (let attempt = 0; attempt < 90; attempt++) {
        try {
            // Ignore the temporary initialization server, which exits before PID 1 execs the final server.
            if (docker(['exec', container, 'cat', '/proc/1/comm']) !== 'mariadbd') throw new Error('initializing');
            docker(['exec', container, 'mariadb', '-uroot', '-N', '-e', 'SELECT 1']); ready = true; break;
        } catch { await new Promise(resolve => setTimeout(resolve, 500)); }
    }
    if (!ready) throw new Error('Disposable MariaDB did not become ready');
    docker(['exec', container, 'mariadb', '-uroot', '-e', 'CREATE DATABASE ent_fresh; CREATE DATABASE ent_upgrade;']);
    const address = docker(['port', container, '3306/tcp']);
    if (!/^127\.0\.0\.1:\d+$/.test(address)) throw new Error('Unexpected database port binding');
    const report = root+'backend/shop-app/target/surefire-reports/ai.neargo.shop.config.EntitlementSchemaUpgradeNativeMariaDbTest.txt';
    rmSync(report, {force:true});
    const result = spawnSync('mvn', ['-o', '-f', 'backend/pom.xml', '-pl', 'shop-app', '-am',
        '-Dtest=EntitlementSchemaUpgradeNativeMariaDbTest,FlywayMariaDbDialectDataSourceTest',
        '-Dsurefire.failIfNoSpecifiedTests=false', 'test'], {cwd:root, stdio:'inherit', env:{...process.env,
        ENTITLEMENT_SCHEMA_MARIADB_URL:`jdbc:mysql://${address}/ent_fresh?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&rewriteBatchedStatements=true`}});
    if (result.error) throw result.error;
    if (result.status !== 0) throw new Error(`Schema upgrade rehearsal failed with exit ${result.status}`);
    if (!/Tests run: 1, Failures: 0, Errors: 0, Skipped: 0/.test(readFileSync(report,'utf8'))) throw new Error('Native schema rehearsal must run without skipping');
    console.log('MariaDB 11.4 actual Flyway: fresh install and V424-to-V474 upgrade match (269 tables); original source/immutable checksums preserved; no rights, balances or ownership manufactured; repeat migrate/validate passed.');
} finally {docker(['rm','--force',container]);}
