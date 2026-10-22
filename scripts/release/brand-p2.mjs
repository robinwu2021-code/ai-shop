// P2 organization release. Focused regression is recorded in the stage delivery report;
// this clean-HEAD gate compiles test sources and checks contracts without rerunning unrelated suites.
import {readFileSync,writeFileSync,realpathSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {resolve,dirname} from 'node:path';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
const allowed=JSON.parse(readFileSync(resolve(root,'scripts/release/brand-p2.json'),'utf8'));
export function validateFiles(files){if(!files.length)throw new Error('Empty P2 release');for(const file of files)if(!allowed.includes(file))throw new Error(`Outside P2 release scope: ${file}`);}
export function validateUpdate(input,head){
 const rows=input.trim().split('\n').filter(Boolean).map(r=>r.trim().split(/\s+/));
 if(rows.length!==1||rows[0].length!==4)throw new Error('One branch update required');
 const [,sha,ref,base]=rows[0];
 if(sha!==head||ref!=='refs/heads/codex/ai-shop-codex'||!(/^[0-9a-f]{40}$/.test(base))||/^0+$/.test(base))throw new Error('Existing canonical branch and exact HEAD required');return base;
}
const run=(cmd,args,cwd=root)=>execFileSync(cmd,args,{cwd,stdio:'inherit'});
const git=(...args)=>execFileSync('git',args,{cwd:root,encoding:'utf8'}).trim();
function main(){
 const head=git('rev-parse','HEAD'),base=validateUpdate(readFileSync(0,'utf8'),head);
 git('merge-base','--is-ancestor',base,head);const files=git('diff','--name-only','--no-renames','-z',base,head).split('\0').filter(Boolean);validateFiles(files);
 run('git',['diff','--check',base,head]);run('node',['--test','scripts/release/brand-p2.test.mjs']);
 if(files.some(f=>f.startsWith('backend/')))run('mvn',['-o','-pl','shop-app,pay/pay-svc','-am','-DskipTests','test-compile','-q'],resolve(root,'backend'));
 if(files.some(f=>f.startsWith('b-app/')||f.startsWith('packages/')))run('npm',['run','type-check','-w','ai-shop-b-app']);
 if(files.some(f=>f.startsWith('site/')))run('npm',['run','typecheck','-w','ai-shop-site']);if(files.some(f=>f.startsWith('c-app/')||f.startsWith('packages/')))run('npm',['run','type-check','-w','ai-shop-c-app']);
 run('python3',['scripts/gen-ui-catalog.py','--check']);run('node',['scripts/check-handrolled-ui.mjs']);
 const api=resolve(root,'docs/api/openapi-b.yaml'),before=readFileSync(api);
 try{run('node',['b-app/scripts/gen-openapi.mjs']);if(!readFileSync(api).equals(before))throw new Error('B OpenAPI is stale');}finally{writeFileSync(api,before);}
 console.log(`P2 scoped clean-HEAD checks passed: ${head}; focused regression evidence: docs/technical/delivery/P2-品牌会员共享-交付记录.md`);
}
if(process.argv[1]&&realpathSync(process.argv[1])===fileURLToPath(import.meta.url))main();
