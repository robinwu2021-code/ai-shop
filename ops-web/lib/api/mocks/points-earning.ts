import type { FinanceApi } from "../contracts/finance";
import type { EarningRule, EarningChange } from "@/lib/points-earning";
import { pointsConfigurationMock } from "./points-configuration";
import { wait } from "./_wait";
import { fail } from "@/lib/biz-error";
const rows:EarningRule[]=[];const history:EarningRule[]=[];
const receipts=new Map<string,{body:string;rule:EarningRule}>();
async function context(){const types=await pointsConfigurationMock.pointsTypes();return {types,type:types.find(t=>t.typeCode==="HXPOINTS_CN"&&t.status!=="RETIRED")??null,categories:[{categoryNo:"CAT_FOOD",parentNo:null,name:"食品",path:"食品"}]};}
async function ensure(){const c=await context();for(const type of c.types.filter(t=>t.status==="ACTIVE")){if(rows.some(r=>r.typeCode===type.typeCode&&r.scope==="DEFAULT"))continue;const legacy=type.typeCode===c.type?.typeCode?await pointsConfigurationMock.pointsFallback(type.typeCode):{mode:"RATIO" as const,value:0};const rule:EarningRule={typeCode:type.typeCode,scope:"DEFAULT",revision:1,activeRevision:1,mode:legacy.mode,value:legacy.value,activeMode:legacy.mode,activeValue:legacy.value,actor:"SYSTEM",remark:"默认规则初始化",at:Date.now()};rows.push(rule);history.push(structuredClone(rule));}return c;}

async function change(c:EarningChange){
 const ctx=await ensure();if(!ctx.types.some(t=>t.typeCode===c.typeCode&&t.status==="ACTIVE"))fail("请选择已生效积分","Select an active points type.");
 const replay=receipts.get(c.requestId);if(replay){if(replay.body!==JSON.stringify(c))fail("请求号已被其他操作使用","This request ID was already used for another operation.");return wait(structuredClone(replay.rule));}
 const prior=rows.find(r=>r.typeCode===c.typeCode&&r.scope===c.scope);if((prior?.revision??0)!==c.expectedRevision)fail("规则已变更，请刷新后重试","The rule has changed. Refresh and try again.");
 if(c.action!=="SAVE"&&!prior)fail("规则不存在","This reward rule no longer exists.");
 if(c.action==="DISABLE"&&c.scope==="DEFAULT")fail("默认规则不能停用，请设为不赠送","The default rule cannot be disabled. Set its reward rate to zero.");
 if(c.action==="SAVE"&&(!Number.isInteger(c.ratioBp)||c.ratioBp!<0||c.ratioBp!>10000))fail("比例无效","Enter a reward rate between zero and 100 percent.");
 const next:EarningRule={...(prior??{typeCode:c.typeCode,scope:c.scope,activeRevision:null,activeMode:null,activeValue:null,mode:"RATIO",value:0}),revision:c.expectedRevision+1,action:c.action,actor:"OPERATOR",remark:c.remark,at:Date.now()};
 if(c.action==="SAVE"){next.mode="RATIO";next.value=c.ratioBp!;}
 if(c.action==="PUBLISH"){if(next.mode!=="RATIO")fail("请先调整为比例赠送","Change this rule to a percentage reward before publishing.");next.activeRevision=next.revision;next.activeMode=next.mode;next.activeValue=next.value;}
 if(c.action==="DISABLE"){next.activeRevision=null;next.activeMode=null;next.activeValue=null;}
 if(prior)rows.splice(rows.indexOf(prior),1,next);else rows.push(next);history.unshift(structuredClone(next));receipts.set(c.requestId,{body:JSON.stringify(c),rule:structuredClone(next)});return wait(structuredClone(next));
}
export const pointsEarningMock:Pick<FinanceApi,"pointsOrderEarning"|"pointsEarningContext"|"pointsEarningRules"|"pointsEarningHistory"|"changePointsEarningRule"|"previewPointsEarning">={
 pointsOrderEarning:async subOrderNo=>{const c=await context();return wait({orderNo:subOrderNo,subOrderNo,typeCode:c.type?.typeCode??"",currency:c.type?.currency??"CNY",snapshot:{schemaVersion:2,ruleTraces:[],lines:[]},ledger:null});},
 pointsEarningContext:context,
 pointsEarningRules:async q=>{const c=await ensure();const code=q.typeCode??c.type?.typeCode;const filtered=rows.filter(r=>q.all||r.typeCode===code);return wait({rows:structuredClone(filtered.slice((q.page-1)*q.size,q.page*q.size)),total:filtered.length,page:q.page,size:q.size});},
 pointsEarningHistory:async(scope,typeCode)=>{const c=await ensure();return wait(structuredClone(history.filter(r=>r.scope===scope&&r.typeCode===(typeCode??c.type?.typeCode))));},
 changePointsEarningRule:change,
 previewPointsEarning:async q=>{
  const c=await ensure();const type=c.types.find(t=>t.typeCode===(q.typeCode??c.type?.typeCode));
  if(!type||type.status!=="ACTIVE")fail("请选择已生效积分","Select an active points type.");
  if(q.currency&&q.currency!==type.currency)fail("币种与积分配置不一致","The currency does not match the points configuration.");
  if(q.categoryNo&&!c.categories.some(category=>category.categoryNo===q.categoryNo))fail("品类不存在","This category no longer exists.");
  if(!Number.isSafeInteger(q.cashMinor)||q.cashMinor<0)fail("金额无效","Enter a valid nonnegative amount in minor currency units.");
  const selected=rows.filter(r=>r.typeCode===type.typeCode);
  const r=selected.find(r=>r.scope===q.categoryNo&&r.activeRevision!=null)??selected.find(r=>r.scope==="DEFAULT")!;
  if(r.activeMode!=="RATIO")fail("请先调整原固定赠送规则","Change the existing fixed reward rule to a percentage reward before calculating.");
  const money=BigInt(q.cashMinor)*BigInt(r.activeValue!)/10000n;
  let p=BigInt(type.rate.pointsUnit),m=BigInt(type.rate.minorUnit);let a=p,b=m;while(b){const n=a%b;a=b;b=n;}p/=a;m/=a;
  const points=Number(money/m*p);if(!Number.isSafeInteger(points))fail("积分数量超限","The calculated points exceed the supported limit.");
  return wait({type:{typeCode:type.typeCode,name:type.name,market:type.market,currency:type.currency,scale:type.scale,ratePoints:type.rate.pointsUnit,rateMinor:type.rate.minorUnit},matched:{scope:r.scope,revision:r.activeRevision!,rule:{mode:"RATIO",value:r.activeValue!}},cashMinor:q.cashMinor,points});
 },
};
