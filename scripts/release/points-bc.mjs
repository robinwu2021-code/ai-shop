// Points UI release: exact file scope and targeted checks; backend compilation only for backend changes.
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
const git=(...args)=>execFileSync('git',args,{encoding:'utf8'}).trim();
const head=git('rev-parse','HEAD');
const rows=readFileSync(0,'utf8').trim().split('\n');
if(rows.length!==1)throw new Error('One unified branch update required');
const [,sha,ref,base]=rows[0].split(/\s+/);
if(sha!==head||ref!=='refs/heads/codex/ai-shop-codex'||!base||/^0+$/.test(base))throw new Error('Only existing unified branch supported');
git('merge-base','--is-ancestor',base,head);
const allowed=new Set(JSON.parse(readFileSync('scripts/release/points-bc.json','utf8')));
const files=git('diff','--name-only','-z','--no-renames',base,head).split('\0').filter(Boolean);
for(const file of files)if(!allowed.has(file))throw new Error(`Out of points B/C scope: ${file}`);
git('diff','--check',base,head);
const run=(cmd,args,cwd)=>execFileSync(cmd,args,{cwd,stdio:'inherit'});
if(files.some(file=>file.startsWith('backend/')))
  run('mvn',['-o','-pl','shop-app','-am','-Dmaven.test.skip=true','compile','-q'],'backend');
for(const app of ['b-app','c-app'])
  if(files.some(file=>file.startsWith(`${app}/`) || file.startsWith('packages/'))) run('npm',['run','type-check'],app);


if(files.some(file=>file.startsWith('ops-web/'))) run('npm',['run','typecheck'],'ops-web');
if(files.includes('packages/shared/src/utils/points-name.ts')) {
  run('npm',['test','--','tests/points-name.test.ts'],'packages/shared');
  run('npm',['test','--','tests/points-name-api.test.ts','tests/points-statements.test.ts','tests/points-statement-link.test.ts'],'b-app');
  run('npm',['test','--','tests/points-ledger-api.test.ts','tests/points-account.test.ts','tests/points-order-earning.test.ts','tests/points-quote-api.test.ts'],'c-app');
  run('npm',['test','--','lib/api/points-configuration.test.ts','lib/points-store-access-page.test.ts','lib/points-types-page.test.ts'],'ops-web');
}


if(files.some(file => file.includes('/points/type/') || file.includes('PointsAdministrationService') || file.includes('PointsConfigurationAppService'))) {
  run('mvn',['-o','-pl','shop-app','-am','-Dtest=PointsOpsConfigurationTest,PointsConfigurationAuditTest,PointsOpsPermissionTest,PointsOpsErrorsTest,PointTypeActivationTest,PointsOrderTermsPortTest,AcceptedEarnBudgetTest,PointsDefaultLockOrderTest','-Dsurefire.failIfNoSpecifiedTests=false','test','-q'],'backend');
}
if(files.includes('ops-web/app/points/types-tab.tsx')) run('npm',['test','--','lib/points-types-page.test.ts','lib/api/points-configuration.test.ts'],'ops-web');
if(files.includes('packages/shared/src/utils/points-status.ts')) {
  run('npm',['test','--','tests/points-status.test.ts'],'packages/shared');
  run('npm',['test','--','tests/points-account.test.ts','tests/points-status-locales.test.ts'],'b-app');
  run('npm',['test','--','tests/points-account.test.ts'],'c-app');
}
console.log(`points-bc scoped checks passed: ${head} (${files.length} files)`);
