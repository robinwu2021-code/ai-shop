import {discoverFoodSource} from './lib/food-source-inventory.mjs';
import {writeFileSync,readFileSync,mkdirSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
const root=process.cwd(), baseline='8c035162615dcebb2a4bc9ada07830d5e469f75b';
const drift=execFileSync('git',['diff','--name-only',baseline,'--','backend-plugins/shop-industry-food'],{encoding:'utf8'}).trim();
if(drift) throw Error('FOOD source differs from deployed baseline; select a new verified baseline first: '+drift);
const actual=discoverFoodSource(root), dir='docs/api/food';
mkdirSync(dir,{recursive:true});
const clean=s=>String(s??'').replaceAll('|','&#124;').replaceAll('\n',' ');
const link=e=>'../../../'+e.sourceFile;
const publicApi=actual.endpoints.filter(e=>/food-web\/src\/main\/java\/.*\/api\/(biz|mp|ops)\//.test(e.sourceFile));
const constants=new Map();
for(const name of ['ChannelCatalogSourceInternalApi','ChannelSourceValidationInternalApi','ChannelSourceWithdrawalInternalApi']){
 const text=readFileSync('backend-plugins/shop-commerce-integration/commerce-api/src/main/java/ai/neargo/shop/spi/commerce/'+name+'.java','utf8');
 for(const m of text.matchAll(/String\s+(\w+)\s*=\s*"([^"]+)"/g))constants.set(name+'.'+m[1],m[2]);
}
for(const m of readFileSync('backend/shop-base/src/main/java/ai/neargo/shop/spi/food/FoodLocalStockInternalApi.java','utf8').matchAll(/String\s+(\w+)\s*=\s*"([^"]+)"/g)) constants.set('FoodLocalStockInternalApi.'+m[1],m[2]);
const internal=actual.endpoints.filter(e=>e.sourceFile.includes('/food-server/')||
 e.sourceFile.includes('/food-commerce/')&&e.sourceFile.includes('/api/internal/')).map(e=>({
 ...e,realm:'internal',path:e.path.replace(/\$\{([^}]+)\}/g,(_,key)=>constants.get(key)??'UNRESOLVED:'+key)}));
if(internal.some(e=>!e.path.startsWith('/internal/')))throw Error(JSON.stringify(internal.filter(e=>!e.path.startsWith('/internal/')).map(e=>[e.path,e.sourceFile])));
const types=actual.classes.filter(c=>c.sourceFile.includes('/food-api/'));
const header='# 餐饮API清单\n\n基线：'+baseline+'；日期：2026-10-30。按已部署版本源码生成，声明存在不等于每个接口已实测；条件装配、门店策略和权限仍生效。共享商品、支付、通用渠道与报表依赖需另按所属域对接。\n\n[接入指南](接入指南.md) · [DTO字段字典](DTO字段字典.md) · [JSON清单](接口清单.json) · [CSV清单](接口清单.csv)\n\n';
const counts=Object.fromEntries(['biz','mp','ops'].map(r=>[r,publicApi.filter(e=>e.realm===r).length]));
let md=header+'公开映射声明：'+publicApi.length+'（商家端 '+counts.biz+'、C端 '+counts.mp+'、平台端 '+counts.ops+'）；独立FOOD内部映射 '+internal.length+'。正则路径按一个映射保留，不把枚举视图重复计算成Handler。\n\n';
const routes=new Set();
for(const e of publicApi){if(e.path.includes('$'+'{'))throw Error('Unresolved public mapping '+e.path);const k=e.method+' '+e.path;if(routes.has(k))throw Error('Duplicate mapping '+k);routes.add(k);}
for(const [title,endpoints] of [['商家端 B端',publicApi.filter(e=>e.realm==='biz')],['消费者 C端',publicApi.filter(e=>e.realm==='mp')],['平台运营端',publicApi.filter(e=>e.realm==='ops')],['独立FOOD内部接口（仅可信服务调用）',internal]]){
 md+='## '+title+'\n\n';
 const controllers=[...new Set(endpoints.map(e=>e.controllerClass))].sort();
 for(const controller of controllers){
 const es=endpoints.filter(e=>e.controllerClass===controller), c=actual.classes.find(x=>x.qualifiedName===controller);
 const source=readFileSync(es[0].sourceFile,'utf8'), before=source.slice(0,source.indexOf('public class '));
 const auth=before.match(/@PreAuthorize\(([^\n]*(?:\n[^@]*?)?)\)/)?.[0]??'按公共认证链、门店/会话合同及方法权限执行；未声明注解不代表匿名';
 md+='### '+controller.split('.').at(-1)+'\n\n[源码]('+link(es[0])+')；类级权限/认证：'+clean(auth)+'。装配条件见源码。\n\n| 方法 | 路径 | 输入声明 | 返回data类型 | 方法权限 |\n| --- | --- | --- | --- | --- |\n';
 for(const e of es)md+='| '+e.method+' | <code>'+clean(e.path)+'</code> | '+clean(e.parameters.filter(p=>!['HttpServletRequest','HttpServletResponse'].includes(p.type)).map(p=>p.declaration).join('; '))+' | '+clean(e.responseType)+' | '+clean(e.securityAnnotations.join('; ')||'继承类级与全局约束')+' |\n';
 md+='\n';
 }
}
writeFileSync(dir+'/接口清单.md',md);
writeFileSync(dir+'/接口清单.json',JSON.stringify({baseline,generatedAt:'2026-10-30',counts,scope:'food-web public and food-server internal mapping declarations',public:publicApi,internal},null,2)+'\n');
const quote=v=>'"'+String(v??'').replaceAll('"','""')+'"';
writeFileSync(dir+'/接口清单.csv','realm,method,path,controller,handler,input,responseType,source\n'+[...publicApi,...internal].map(e=>[e.realm,e.method,e.path,e.controllerClass,e.handler,e.parameters.map(p=>p.declaration).join('; '),e.responseType,e.sourceFile].map(quote).join(',')).join('\n')+'\n');
let models='# 餐饮DTO字段字典\n\n基线：'+baseline+'。Java record 字段为源码声明，非完整OpenAPI Schema；金额、单位、枚举和业务必填条件须结合服务规则。未列入的普通class、共享域及嵌套依赖通过接口源码定位，不把未知结构伪装为已定义模型。\n\n';
let n=0;
for(const c of types.sort((a,b)=>a.qualifiedName.localeCompare(b.qualifiedName))){
 if(!c.records?.length)continue;
 models+='## '+c.qualifiedName+'\n\n[源码](../../../'+c.sourceFile+')\n\n';
 for(const record of c.records){n++;models+='### '+record.name+'\n\n| 字段声明（含校验） |\n| --- |\n'+record.fields.map(f=>'| <code>'+clean(f)+'</code> |').join('\n')+'\n\n';}
}
writeFileSync(dir+'/DTO字段字典.md',models);
console.log(JSON.stringify({public:publicApi.length,counts,internal:internal.length,recordTypes:n,unresolvedInternal:internal.filter(e=>e.path.includes('$'+'{')).length}));
