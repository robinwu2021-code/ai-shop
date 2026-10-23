#!/usr/bin/env node
import {readFileSync, writeFileSync} from 'node:fs';
import {resolve, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
const {instance}=await import(process.env.FOOD_ER_VIZ_MODULE || '@viz-js/viz');
const viz=await instance();
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const s=JSON.parse(readFileSync(resolve(root,'docs/technical/design/schema/food-catalog.schema.json'),'utf8'));
for(const t of s.tables)for(const f of t.fields)f.key=f.key??[t.unique.some(k=>k.includes(f.name))?'UK':'',f.ref?'FK':''].filter(Boolean).join('/');
const canonical=JSON.parse(readFileSync(resolve(root,'docs/technical/design/schema/'+s.canonicalSource),'utf8'));
const by=new Map([...canonical.tables,...s.tables].map(t=>[t.name,t]));
const esc=x=>String(x).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');
for(const t of s.tables) for(const f of t.fields) if(f.ref){const [tn,fn]=f.ref.split('.'); if(!by.get(tn)?.fields.some(x=>x.name===fn))throw Error(`Broken relation ${t.name}.${f.name}`);}
const diagrams=[];
for(const g of s.groups){
 const ts=g.tables.map(n=>by.get(n));
 const lines=['digraph G {','graph [rankdir=LR, bgcolor="#ffffff", pad="0.4", nodesep="0.55", ranksep="1.0", splines=polyline];','node [shape=plain, fontname="Arial", fontsize=13];','edge [fontname="Arial", fontsize=10, color="#64748b", fontcolor="#334155", arrowsize=0.7];'];
 for(const t of ts){
 const color=t.existing?'#dce8f3':'#dcf1ea';
 const selected=t.fields.filter(f=>f.key || f.name==='name');
 const rows=selected.map(f=>`<TR><TD ALIGN="LEFT"><FONT POINT-SIZE="11">${esc(f.key)} ${esc(f.name)}<BR ALIGN="LEFT"/>${esc(f.label.split(" ")[0])}<BR ALIGN="LEFT"/>${esc(f.label.split(" ").slice(1).join(" "))}</FONT></TD></TR>`).join('');
 lines.push(`"${t.name}" [label=<<TABLE BORDER="1" CELLBORDER="0" CELLSPACING="0" CELLPADDING="9" COLOR="#8aabbc"><TR><TD BGCOLOR="${color}"><B>${t.name}</B><BR/>${esc(t.label.split(" ")[0])}<BR/>${esc(t.label.split(" ").slice(1).join(" "))}<BR/><FONT POINT-SIZE="10">${t.existing?'已有 Existing':'拟新增 Proposed'}</FONT></TD></TR>${rows}</TABLE>>];`);
 }
 for(const t of ts)for(const f of t.fields)if(f.ref && g.tables.includes(f.ref.split('.')[0])){
 const target=f.ref.split('.')[0]; const single=t.unique.some(k=>k.length===1&&k[0]===f.name);
 lines.push(`"${t.name}" -> "${target}" [label="${f.name}\\n${single?'0..1':'0..N'} → ${f.nullable?'0..1':'1'}"];`);
 }
 lines.push('}');
 let svg=viz.renderString(lines.join('\n'),{format:'svg',engine:'dot'}).replace(/<\?xml[^>]*>/,'').replace(/<!DOCTYPE[\s\S]*?]?>/,'');
 svg=svg.replace(/<svg\b/, '<svg role="img"').replace(/<title>[\s\S]*?<\/title>/,`<title>${esc(g.title)}</title><desc>Schema generated bilingual ER diagram. Child to parent references; cardinalities and keys are shown. 配置与基础表关联，字段详见字典。</desc>`);

 const filename=`food-catalog-${g.id}.svg`;writeFileSync(resolve(root,'docs/technical/diagrams',filename),svg);diagrams.push({...g,filename,svg});
}
const intro=`# 餐饮菜品表设计与双语 ER 图 Food Catalog Schema

> 状态：待评审 Proposed · 2026-10-19。基础商品字段取当前实体的关联字段投影，新增表均未实施。
> 由 \`node scripts/gen-food-catalog-erd.mjs\` 生成，勿手改；真源：[模型契约 Schema](./schema/food-catalog.schema.json)。
> 上游：[餐饮与商品订单基座契约](./餐饮与商品订单基座-扩展边界与契约.md)。本册补充套餐、配料组合与多类餐牌的完整目标，收费选配须完成基座接入才能上线。

生成依赖：Graphviz WASM @viz-js/viz@3.31.0。可在临时目录安装，通过环境变量 FOOD_ER_VIZ_MODULE 指向其 dist/viz.js，无需修改业务项目依赖。

## 一、设计结论 Design overview

菜品、套餐和可售配料都引用基础商品与 SKU。餐饮扩展保存套餐选择规则、口味配料组合、餐牌可见范围和工位；不复制商品主数据、门店价格或库存账。

本模型有 **${s.tables.filter(t=>!t.existing).length} 张拟新增表、${s.tables.filter(t=>t.existing).length} 张已有表的关联投影**。ER 图按主题展开；英文表名与中文业务名称同时显示。箭头从引用表指向被引用表，标注每个父对象可拥有的子行数与每个子行的父对象数。FK 为逻辑关系，不表示已建立数据库外键；跨模块仍由服务校验。

${s.notes.map(n=>`### ${n.title}\n\n${n.body}`).join('\n\n')}

## 二、分组 ER 图 ER diagrams

`;
let md=intro;
for(const g of diagrams)md+=`### ${g.title}\n\n![${g.title}](../diagrams/${g.filename})\n\n${s.groupNotes[g.id]}\n\n`;
md+='## 三、字段与约束 Tables and constraints\n\n新增表统一包含 `entity_no` 主体范围、`created_at/updated_at` 审计时间及 `version` 乐观锁版本（表内已列版本字段除外）。下列 PK 是建议逻辑主键；若实现采用仓库统一 `id`，这些业务键仍须保持唯一。已有表仅展示本设计使用的字段，未改造其主键。所有必填/可空以表中说明为准；关联对象必须同主体，门店归属由当前商户权限校验。配置停用保留编号，不级联删除历史快照。\n\n';
for(const t of s.tables){md+=`### ${t.label} · ${t.name}\n\n${t.existing?'已有表 Existing — 仅关联字段投影。':'拟新增 Proposed — 餐饮扩展。'}\n\n|字段 Field|类型 Type|含义 Meaning|键与关联 Key / Reference|可空 Nullable|\n|---|---|---|---|---|\n`;
for(const f of t.fields)md+=`|\`${f.name}\`|${f.type}|${f.label}|${f.key||'—'}${f.ref?' → `'+f.ref+'`':''}|${f.nullable?'是 Yes':'否 No'}|\n`;
if(t.unique.length)md+='\n唯一约束 Unique：'+t.unique.map(u=>'`('+u.join(', ')+')`').join('；')+'。\n';
md+='\n';}
md+='## 四、发布与交易不变量 Validation rules\n\n'+s.rules.map((r,i)=>`${i+1}. ${r}`).join('\n')+'\n';
writeFileSync(resolve(root,'docs/technical/design/餐饮菜品-表设计与双语ER图.md'),md);
const html=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(s.title)}</title><style>body{font:16px/1.7 system-ui,sans-serif;color:#182b43;background:#f5f7fb;margin:0}main{max-width:1100px;margin:auto;padding:36px}h1{font-size:30px}h2{margin-top:48px}section{background:white;padding:26px;border-radius:16px;margin:24px 0}svg{width:100%;height:auto;max-height:none}nav a{display:inline-block;margin:8px 18px 8px 0;color:#175bb0}table{width:100%;border-collapse:collapse;font-size:14px}td,th{border:1px solid #dce3ed;padding:9px;text-align:left}code{font-size:13px}summary{cursor:pointer;font-weight:600}p{max-width:950px}.badge{color:#175bb0}</style><main><h1>${esc(s.title)}</h1><p class="badge">待评审 Proposed · 2026-10-19 · ${s.tables.filter(t=>!t.existing).length} 张新增表 / proposed tables</p><p>基础商品与 SKU 复用；餐饮配置独立扩展。图中箭头从子表指向父表；0..N → 1 表示每父可有零到多条子行，每子行必须对应一个父对象。下方字段表列出全部逻辑关联与约束。</p><nav>${diagrams.map(g=>`<a href="#${g.id}">${esc(g.title)}</a>`).join('')}</nav>${s.notes.map(n=>`<section><h2>${esc(n.title)}</h2><p>${esc(n.body)}</p></section>`).join('')}${diagrams.map(g=>`<section id="${g.id}"><h2>${esc(g.title)}</h2><p>${esc(s.groupNotes[g.id])}</p><p><a href="../diagrams/${g.filename}" target="_blank">打开原尺寸 SVG / Full-size diagram</a></p>${g.svg}</section>`).join('')}<h2>字段字典 Data dictionary</h2>${s.tables.map(t=>`<section><details><summary>${esc(t.label)} · ${t.name} · ${t.existing?'已有 Existing':'新增 Proposed'}</summary><table><tr><th>字段 Field</th><th>类型 Type</th><th>含义 Meaning</th><th>关系 Reference</th></tr>${t.fields.map(f=>`<tr><td><code>${f.name}</code></td><td>${f.type}${f.nullable?' NULL':''}</td><td>${esc(f.label)}</td><td>${esc(f.key+' '+(f.ref||''))}</td></tr>`).join('')}</table><p>Unique: ${esc(t.unique.map(x=>x.join(', ')).join(' / ')||'—')}</p></details></section>`).join('')}<h2>校验与边界 Validation</h2><ol>${s.rules.map(r=>`<li>${esc(r)}</li>`).join('')}</ol></main></html>`;
writeFileSync(resolve(root,'docs/technical/design/餐饮菜品-双语ER图.html'),html);
console.log(`Generated ${diagrams.length} SVGs, Markdown dictionary and standalone HTML; ${s.tables.length} entities validated.`);
