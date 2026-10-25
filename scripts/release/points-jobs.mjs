// Bounded clean-HEAD gate for points scheduling. No B/C or unrelated backend changes.
import { readFileSync, realpathSync, mkdirSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const allowed = new Set([
 '.githooks/pre-push', 'scripts/release/points-jobs.mjs', 'scripts/release/points-jobs.test.mjs',
 'backend/shop-app/src/main/java/ai/neargo/shop/payclient/PointsJobHandlers.java',
 'backend/shop-app/src/main/java/ai/neargo/shop/payclient/PointsScopedJobHandler.java',
 'backend/shop-app/src/test/java/ai/neargo/shop/scenario/PointsMultiTypeJobTest.java',
 'backend/shop-app/src/test/java/ai/neargo/shop/scenario/OrderPointsRecoveryJobLeaseTest.java',
 'backend/shop-core/src/main/java/ai/neargo/shop/trade/job/OrderPointsRecoveryJob.java',
 'docs/technical/积分任务状态与后续优化-2026-10-25.md',
 'docs/technical/积分运行配置与多端核验-2026-10-25.md',
 'docs/technical/积分多类型任务与订单恢复-实施计划-2026-10-25.md',
]);
export function validateFiles(files) {
 if (!files.length) throw new Error('Empty points-jobs release');
 for (const file of files) if (!allowed.has(file)) throw new Error(`Outside points-jobs release scope: ${file}`);
}
export function validateUpdate(input, head) {
 const rows = input.trim().split('\n').filter(Boolean).map(r => r.trim().split(/\s+/));
 if (rows.length !== 1 || rows[0].length !== 4) throw new Error('One branch update required');
 const [,sha,ref,base] = rows[0];
 if (sha !== head || ref !== 'refs/heads/codex/ai-shop-codex' || !/^[0-9a-f]{40}$/.test(base) || /^0+$/.test(base)) throw new Error('Exact HEAD and existing canonical branch required');
 return base;
}
const git = (...args) => execFileSync('git', args, {cwd:root,encoding:'utf8'}).trim();
const run = (cmd,args,cwd=root) => execFileSync(cmd,args,{cwd,stdio:'inherit'});
function main() {
 const head = git('rev-parse','HEAD'), base = validateUpdate(readFileSync(0,'utf8'),head);
 git('merge-base','--is-ancestor',base,head);
 const files = git('diff','--name-only','--no-renames','-z',base,head).split('\0').filter(Boolean);
 validateFiles(files);
 run('git',['diff','--check',base,head]);
 run('node',['--test','scripts/release/points-jobs.test.mjs']);
 run('mvn',['-o','-pl','shop-app','-am','-Dtest=PointsMultiTypeJobTest,PointsJobWiringTest,OrderPointsRecoveryJobLeaseTest','-Dsurefire.failIfNoSpecifiedTests=false','test','-q'],resolve(root,'backend'));
 const dir = resolve(root,git('rev-parse','--git-common-dir'),'points-jobs-receipts');
 mkdirSync(dir,{recursive:true});
 writeFileSync(resolve(dir,head+'.json'),JSON.stringify({profile:'points-jobs',head,base,files,checkedAt:new Date().toISOString()},null,2));
 console.log(`points-jobs clean-HEAD compilation and targeted tests passed: ${head}`);
}
if (process.argv[1] && realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)) main();
