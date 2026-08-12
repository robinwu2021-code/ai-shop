import { readFileSync } from 'node:fs';
const nav = readFileSync('lib/nav.ts', 'utf8');
const pm = readFileSync('lib/perm-map.ts', 'utf8');

// UI 码 → 后端码
const map = {};
for (const m of pm.matchAll(/"([^"]+)":\s*(UNIMPLEMENTED|"([^"]+)")/g)) {
  map[m[1]] = m[2] === 'UNIMPLEMENTED' ? null : m[3];
}
// 角色 → 后端码
const ROLE = {
  SUPER_ADMIN:['*'], MERCHANT_BD:['merchant:audit','order:view','community:view','quote:govern'],
  PRODUCT_OPS:['goods:audit','category:manage','order:view','community:view','marketing:govern'],
  CAMPAIGN_OPS:['marketing:govern','order:view','community:view','content:govern'],
  COMMUNITY_OPS:['industry:manage','community:view','order:view'],
  AUDITOR:['goods:audit','review:govern','community:view','content:govern'],
  CS:['order:view','review:govern','order:intervene','community:view','ticket:handle'],
  FINANCE:['settle:manage','order:view'], RISK:['order:view'],
  ANALYST:['community:view'], TECH_OPS:['audit:view','platform:config'],
};
const NAMES=[['SUPER_ADMIN','超管'],['MERCHANT_BD','商家运营'],['PRODUCT_OPS','商品运营'],['CAMPAIGN_OPS','活动运营'],
 ['COMMUNITY_OPS','社区运营'],['AUDITOR','审核员'],['CS','客服'],['FINANCE','财务'],['RISK','风控'],['ANALYST','分析'],['TECH_OPS','技术运维']];

// 解析 sections
const secs=[];
for (const m of nav.matchAll(/key:\s*"(\w+)",\s*label:\s*"([^"]+)"[\s\S]*?(?=key:\s*"\w+",\s*label:|$)/g)) {
  const [ , key, label ] = m;
  const leaves=[...m[0].matchAll(/\{\s*href:\s*"([^"]+)",\s*label:\s*"([^"]+)",\s*perm:\s*"([^"]+)"([^}]*)\}/g)]
    .map(l=>({href:l[1],label:l[2],perm:l[3],ready:/ready:\s*true/.test(l[4])}));
  secs.push({key,label,leaves});
}
const cell=(perm,role)=>{
  const back=map[perm];
  if (back===undefined) return '?';        // 未登记
  if (back===null) return '✕';             // 后端没有这块能力
  const g=ROLE[role]||[];
  return g.includes('*')||g.includes(back) ? '●' : '·';
};
console.log('| 菜单分区 | 菜单项 | UI 权限码 | 后端码 | ' + NAMES.map(n=>n[1]).join(' | ') + ' |');
console.log('|---|---|---|---|' + NAMES.map(()=>':--:').join('|') + '|');
for (const s of secs) {
  if (!s.leaves.length) { console.log(`| **${s.label}** | *(无叶子)* | — | — | ` + NAMES.map(()=>'○').join(' | ') + ' |'); continue; }
  s.leaves.forEach((l,i)=>{
    const back=map[l.perm];
    console.log(`| ${i?'':'**'+s.label+'**'} | ${l.label}${l.ready?'':' 🚧'} | \`${l.perm}\` | ${back===undefined?'**未登记**':back===null?'*无*':'`'+back+'`'} | `
      + NAMES.map(n=>cell(l.perm,n[0])).join(' | ') + ' |');
  });
}
