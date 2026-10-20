import type { FinanceApi } from "../contracts/finance";
import type { EarningRule, EarningChange } from "@/lib/points-earning";
import { pointsConfigurationMock } from "./points-configuration";
import { wait } from "./_wait";
const rows:EarningRule[]=[];const history:EarningRule[]=[];
const receipts=new Map<string,{body:string;rule:EarningRule}>();
async function context(){const types=await pointsConfigurationMock.pointsTypes();return {type:types.find(t=>t.typeCode==="HXPOINTS_CN"&&t.status!=="RETIRED")??null,categories:[{categoryNo:"CAT_FOOD",parentNo:null,name:"食品",path:"食品"}]};}
async function ensure(){const c=await context();if(c.type&&!rows.length){const legacy=await pointsConfigurationMock.pointsFallback(c.type.typeCode);const rule:EarningRule={typeCode:c.type.typeCode,scope:"DEFAULT",revision:1,activeRevision:1,mode:legacy.mode,value:legacy.value,activeMode:legacy.mode,activeValue:legacy.value,actor:"SYSTEM",remark:"迁入原规则",at:Date.now()};rows.push(rule);history.push(structuredClone(rule));}return c;}
function change(c:EarningChange){
 const replay=receipts.get(c.requestId);if(replay){if(replay.body!==JSON.stringify(c))throw new Error("请求号已被其他操作使用");return wait(structuredClone(replay.rule));}
 const prior=rows.find(r=>r.scope===c.scope);if((prior?.revision??0)!==c.expectedRevision)throw new Error("规则已变更，请刷新后重试");
 if(c.action!=="SAVE"&&!prior)throw new Error("规则不存在");
 if(c.action==="DISABLE"&&c.scope==="DEFAULT")throw new Error("默认规则不能停用，请设为不赠送");
 if(c.action==="SAVE"&&(!Number.isInteger(c.ratioBp)||c.ratioBp!<0||c.ratioBp!>10000))throw new Error("比例无效");
 const next:EarningRule={...(prior??{typeCode:c.typeCode,scope:c.scope,activeRevision:null,activeMode:null,activeValue:null,mode:"RATIO",value:0}),revision:c.expectedRevision+1,action:c.action,actor:"OPERATOR",remark:c.remark,at:Date.now()};
 if(c.action==="SAVE"){next.mode="RATIO";next.value=c.ratioBp!;}
 if(c.action==="PUBLISH"){if(next.mode!=="RATIO")throw new Error("请先调整为比例赠送");next.activeRevision=next.revision;next.activeMode=next.mode;next.activeValue=next.value;}
 if(c.action==="DISABLE"){next.activeRevision=null;next.activeMode=null;next.activeValue=null;}
 if(prior)rows.splice(rows.indexOf(prior),1,next);else rows.push(next);history.unshift(structuredClone(next));receipts.set(c.requestId,{body:JSON.stringify(c),rule:structuredClone(next)});return wait(structuredClone(next));
}
export const pointsEarningMock:Pick<FinanceApi,"pointsOrderEarning"|"pointsEarningContext"|"pointsEarningRules"|"pointsEarningHistory"|"changePointsEarningRule"|"previewPointsEarning">={
 pointsOrderEarning:async subOrderNo=>{const c=await context();return wait({orderNo:subOrderNo,subOrderNo,typeCode:c.type?.typeCode??"",currency:c.type?.currency??"CNY",snapshot:{schemaVersion:2,ruleTraces:[],lines:[]},ledger:null});},
 pointsEarningContext:context,
 pointsEarningRules:async q=>{await ensure();return wait({rows:structuredClone(rows.slice((q.page-1)*q.size,q.page*q.size)),total:rows.length,...q});},
 pointsEarningHistory:scope=>wait(structuredClone(history.filter(r=>r.scope===scope))),
 changePointsEarningRule:change,
 previewPointsEarning:async q=>{const c=await ensure();if(!c.type)throw new Error("尚未配置订单默认积分");const r=rows.find(r=>r.scope===q.categoryNo&&r.activeRevision!=null)??rows.find(r=>r.scope==="DEFAULT")!;if(r.activeMode!=="RATIO")throw new Error("请先调整原固定赠送规则");const money=BigInt(q.cashMinor)*BigInt(r.activeValue!)/10000n;return wait({matched:{scope:r.scope,revision:r.activeRevision!,rule:{mode:"RATIO",value:r.activeValue!}},cashMinor:q.cashMinor,points:Number(money/BigInt(c.type.rate.minorUnit)*BigInt(c.type.rate.pointsUnit))});},
};
