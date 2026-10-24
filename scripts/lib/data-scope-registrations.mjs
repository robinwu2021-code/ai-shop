import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

export function parseScopeRegistrations(source) {
  const out = new Map();
  let parsedCalls = 0;
  const anchors = (body) => Object.fromEntries([...body.matchAll(/ScopeDim\.(\w+)\s*,\s*"(\w+)"/g)].map(m => [m[1],m[2]]));
  for (const m of source.matchAll(/registry\.register\(\s*"(\w+)"\s*,\s*Map\.of\(([\s\S]*?)\)\s*\)\s*;/g)) { out.set(m[1],anchors(m[2])); parsedCalls++; }
  for (const m of source.matchAll(/for\s*\(\s*String\s+(\w+)\s*:\s*new\s+String\[\]\s*\{([^}]*)\}\s*\)\s*\{\s*registry\.register\(\s*(\w+)\s*,\s*Map\.of\(([\s\S]*?)\)\s*\)\s*;/g)) {
    parsedCalls++;
    if (m[1] !== m[3]) throw new Error('Scope registration loop variable mismatch');
    for (const table of m[2].matchAll(/"(\w+)"/g)) out.set(table[1],anchors(m[4]));
  }
  if ([...source.matchAll(/registry\.register\s*\(/g)].length !== parsedCalls) throw new Error('Unparsed data scope registrar');
  return out;
}

/** Only production registrars actually declared as components or configuration beans count. */
export function readScopeRegistrations(root) {
  const files=[];
  function walk(dir) {
    for(const e of readdirSync(dir,{withFileTypes:true})) {
      if (['target','node_modules','.git','test'].includes(e.name)) continue;
      const path=join(dir,e.name);
      if(e.isDirectory()) walk(path);
      else if(e.name.endsWith('.java') && path.includes('/src/main/')) files.push({path,source:readFileSync(path,'utf8')});
    }
  }
  walk(join(root,'backend'));
  const out=new Map();
  for(const file of files) {
    if(!/implements\s+DataScopeRegistrar\b/.test(file.source)) continue;
    const name=file.source.match(/class\s+(\w+)/)?.[1];
    const component=/@Component\b/.test(file.source);
    const bean=files.some(f=>f.source.includes('@Bean') && new RegExp(`@Bean\\s+(?:\\([^)]*\\)\\s*)?${name}\\s+\\w+\\s*\\([^)]*\\)\\s*\\{\\s*return\\s+new\\s+${name}\\s*\\(`).test(f.source));
    if(!component&&!bean) continue;
    for(const [table,anchors] of parseScopeRegistrations(file.source)) out.set(table,anchors);
  }
  if(out.size===0) throw new Error('No wired data scope registrars');
  return out;
}
