// B/C points account release: explicit file scope, compile only; financial tests are recorded in the plan.
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
const files=git('diff','--name-only',base,head).split('\n').filter(Boolean);
for(const file of files)if(!allowed.has(file))throw new Error(`Out of points B/C scope: ${file}`);
git('diff','--check',base,head);
const run=(cmd,args,cwd)=>execFileSync(cmd,args,{cwd,stdio:'inherit'});
run('mvn',['-o','-pl','shop-app','-am','-Dmaven.test.skip=true','compile','-q'],'backend');
for(const app of ['b-app','c-app'])run('npm',['run','type-check'],app);
console.log(`points-bc compile checks passed: ${head} (${files.length} files)`);
