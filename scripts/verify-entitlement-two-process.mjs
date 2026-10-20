// Opt-in real separate pay JVM, no core modules in its runtime, and disposable H2 databases only.
import { spawnSync } from 'node:child_process';
import { readFileSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../', import.meta.url));
function verify(modules, test, env = {}) {
    const result = spawnSync('mvn', ['-o', '-f', 'backend/pom.xml', '-pl', modules, '-am',
        `-Dtest=${test}`, '-Dsurefire.failIfNoSpecifiedTests=false', 'test'], {
        cwd: root, env: { ...process.env, ...env }, stdio: 'inherit',
    });
    if (result.error) throw result.error;
    if (result.status !== 0) throw new Error(`${test} failed with exit ${result.status}`);
}
rmSync(`${root}backend/pay/pay-svc/target/entitlement-runtime-classpath.txt`, { force: true });
verify('pay/pay-svc', 'EntitlementDeploymentClasspathTest');
const report = `${root}backend/shop-app/target/surefire-reports/ai.neargo.shop.scenario.EntitlementTwoProcessAcceptanceTest.txt`;
rmSync(report, { force: true });
rmSync(`${root}backend/shop-app/target/surefire-reports/ai.neargo.shop.scenario.PaidCardTwoProcessAcceptanceTest.txt`, { force: true });
rmSync(root+'backend/shop-app/target/surefire-reports/ai.neargo.shop.scenario.EntitlementMigrationStageTwoProcessAcceptanceTest.txt', {force:true});
rmSync(root+'backend/shop-app/target/surefire-reports/ai.neargo.shop.scenario.LegacyCouponMigrationTwoProcessAcceptanceTest.txt', {force:true});
verify('shop-app', 'EntitlementTwoProcessAcceptanceTest,PaidCardTwoProcessAcceptanceTest,EntitlementMigrationStageTwoProcessAcceptanceTest,LegacyCouponMigrationTwoProcessAcceptanceTest', { ENTITLEMENT_DUAL_PROCESS: '1' });
if (!/Tests run: 1, Failures: 0, Errors: 0, Skipped: 0/.test(readFileSync(report, 'utf8'))) {
    throw new Error('Two-process acceptance must execute without skipping');
}
const cardReport = `${root}backend/shop-app/target/surefire-reports/ai.neargo.shop.scenario.PaidCardTwoProcessAcceptanceTest.txt`;
if (!/Tests run: 3, Failures: 0, Errors: 0, Skipped: 0/.test(readFileSync(cardReport, 'utf8'))) {
    throw new Error('All three paid-card models must execute in the separate process without skipping');
}
const stageReport=root+'backend/shop-app/target/surefire-reports/ai.neargo.shop.scenario.EntitlementMigrationStageTwoProcessAcceptanceTest.txt';
if(!/Tests run: 1, Failures: 0, Errors: 0, Skipped: 0/.test(readFileSync(stageReport,'utf8'))) {
    throw new Error('Evidence staging must execute across both processes without skipping');
}


const legacyReport=root+'backend/shop-app/target/surefire-reports/ai.neargo.shop.scenario.LegacyCouponMigrationTwoProcessAcceptanceTest.txt';
if(!/Tests run: 2, Failures: 0, Errors: 0, Skipped: 0/.test(readFileSync(legacyReport,'utf8'))) throw new Error('Legacy migration, checkout and historical return must execute across two JVMs');

console.log('Separate JVMs/databases: stored value, all three paid-card models, authenticated staging, permanent migration/import, new issuance, original checkout and historical return passed (7 scenarios, 0 skipped).');
