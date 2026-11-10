"use client";

import {useEffect,useRef,useState} from "react";
import {useQuery} from "@tanstack/react-query";
import {lookupOfflineRelease} from "@/lib/offline-release-recovery";
import {CashRefundAttempt} from "@/lib/cash-refund-attempt";
import {isApiError} from "@/lib/api/error";
import {useFinancialLock} from "./financial-lock";
import {api} from "@/lib/api";
import {useCopy} from "@/lib/use-copy";
import {useCan} from "@/lib/use-can";
import {parseCashMajor,cashMoneyLabel,remainingCash,refundableClear,type CashRefundRequest,type CashPayout,type CashRefund,type CashReceipt} from "@/lib/offline-cash-types";
import {Button} from "@/components/ui/button";
import {Field,DrawerSection} from "@/components/ui/drawer";
import {Input} from "@/components/ui/input";

const COPY={zh:{title:"异常实收退款与占用解除",hint:"本页不执行银行转账。先退回经核实的原付款方，再上传独立退款凭证核验；退款未全部完成前不得释放占用。",refresh:"查询最新资金",cash:"真实到账 / 已退 / 剩余",pending:"退款处理中",normal:"正常权益对应款，不能在此退款",request:"申请退还剩余异常款",reason:"原因",payee:"经核实的原付款方标识",amount:"实际退回金额（元）",time:"实际退款时间",ref:"银行退款流水 / 对账参考",proof:"上传退款凭证",payout:"记录实际银行退回",recover:"恢复原退款",unknown:"结果待核实，原请求已冻结。先查询原记录；不可换键重复操作。",lookup:"查询原记录",retry:"重试同一请求",error:"操作失败或结果未知，请查询原记录。",release:"银行对账并解除原订单占用",releaseProof:"上传独立银行对账凭证",releaseHint:"服务器将再次核验所有实收已退清且无待处理退款；不撤销其他订单权益。",empty:"暂无异常款退款",invalid:"请填写真实金额、时间、原付款方标识、流水及独立凭证。",PREPARED:"退款准备中",AWAITING_PAYOUT:"等待实际银行退款",PAYOUT_ACCEPTED:"退款核验中",REFUNDED:"已核实退回",REJECTED:"核验拒绝，请更正",ACCEPTED:"已受理",APPLIED:"已核验"},en:{title:"Exception cash refunds and release",hint:"This page does not transfer money. Return cash to the verified original payer, then upload separate refund proof. Release requires all cash returned.",refresh:"Read latest cash",cash:"Received / refunded / remaining",pending:"Refund pending",normal:"Regular entitlement payment: not refundable here",request:"Request remaining exception cash",reason:"Reason",payee:"Verified original payer reference",amount:"Actual returned amount (major units)",time:"Actual refund time",ref:"Bank refund / reconciliation reference",proof:"Upload refund proof",payout:"Record actual bank return",recover:"Recover original refund",unknown:"Result unknown. The original request is frozen. Read its record before retrying with the same key.",lookup:"Read original record",retry:"Retry identical request",error:"Failed or unknown. Read original record.",release:"Reconcile bank and release original hold",releaseProof:"Upload separate bank reconciliation proof",releaseHint:"The server verifies all cash fully returned and no refund pending. Other orders' entitlements remain intact.",empty:"No exception cash refunds",invalid:"Enter actual amount, time, original payer, bank reference and separate proof.",PREPARED:"Preparing refund",AWAITING_PAYOUT:"Awaiting actual bank return",PAYOUT_ACCEPTED:"Verifying return",REFUNDED:"Verified returned",REJECTED:"Rejected: correct details",ACCEPTED:"Accepted",APPLIED:"Verified"}};
type Target={kind:"mall"|"entity";scope:string;orderNo:string};
type Intent={kind:"request";command:CashRefundRequest}|{kind:"payout";refundNo:string;command:CashPayout}|{kind:"release";dispositions:Record<string,string>;command:{actionNo:string;reference:string;evidenceKey:string;reason:string}};
export function CashRefundsSection({kind,scope,orderNo,canRelease,allowUnfulfilledMatchedCash=false,onChanged,onLock}:{kind:Target["kind"];scope:string;orderNo:string;canRelease:boolean;allowUnfulfilledMatchedCash?:boolean;onChanged:()=>unknown;onLock?:(locked:boolean)=>void}){
 const c=useCopy(COPY),allow=useCan(),entity=kind==="entity";
 const current=useRef(true);useEffect(()=>{current.current=true;return()=>{current.current=false;};},[]);
 const q=useQuery({queryKey:["exception-cash",kind,scope,orderNo],retry:false,queryFn:async()=>({receipts:await(entity?api.entityCashReceipts(scope,orderNo):api.mallCashReceipts(scope,orderNo)),refunds:await(entity?api.entityCashRefunds(scope,orderNo):api.mallCashRefunds(scope,orderNo))})});
 const [busy,b]=useState(false),[error,e]=useState("");
 const cashAttempt=useRef<CashRefundAttempt|null>(null),acceptedRelease=useRef(false),definiteRefusal=useRef(false);
 const [intent,setIntent]=useState<Intent|null>(null),[absent,setAbsent]=useState(false);
 const [f,set]=useState({reason:"",payee:"",amount:"",time:"",reference:"",proof:"",reconciliation:""});
 useFinancialLock(`cash:${kind}:${scope}:${orderNo}`,!!intent||busy);
 const canRefund=allow("commerce:mall:offline:refund:confirm")&&(!entity||allow("merchant:merchant:ban"));
 const canClose=allow("commerce:mall:offline:void")&&(!entity||allow("merchant:merchant:ban"));
 const status=(s:string)=>c[s as keyof typeof c]??s;
 useEffect(()=>{onLock?.(!!intent||busy);return()=>onLock?.(false);},[intent,busy,onLock]);
 async function refresh(){await q.refetch();if(current.current)await onChanged();}
 async function run(work:()=>Promise<unknown>){if(busy)return;b(true);e("");try{await work();}catch(err){if(current.current){definiteRefusal.current=isApiError(err)&&[10400,10403,10404,10409].includes(err.code);e(c.error);}}finally{if(current.current)b(false);}}
 async function upload(file:File,purpose:"REFUND_PROOF"|"RECONCILIATION"){
  await run(async()=>{const r=entity?await api.reserveEntityPlanEvidence(scope,orderNo,purpose):await api.reserveOfflineEvidence(scope,orderNo,purpose);if(!current.current)return;
   if(entity)await api.uploadEntityPlanEvidence(scope,orderNo,r.assetKey,file);else await api.uploadOfflineEvidence(scope,orderNo,r.assetKey,file);
   if(current.current)set(x=>({...x,[purpose==="REFUND_PROOF"?"proof":"reconciliation"]:r.assetKey}));});
 }
 async function submit(i:Intent){setIntent(i);setAbsent(false);await run(async()=>{
  if(i.kind!=="release"){
   const a=cashAttempt.current??new CashRefundAttempt(i);cashAttempt.current=a;
   await a.submit(async frozen=>frozen.kind==="request"?(entity?api.requestEntityCashRefund(scope,orderNo,frozen.command):api.requestMallCashRefund(scope,orderNo,frozen.command)):(entity?api.payoutEntityCashRefund(scope,orderNo,frozen.refundNo,frozen.command):api.payoutMallCashRefund(scope,orderNo,frozen.refundNo,frozen.command)));
   if(current.current&&a.phase==="done"){cashAttempt.current=null;setIntent(null);}
  }
  else {const r=entity?await api.cancelEntityPlanPurchase(scope,orderNo,i.command):await api.voidOfflineOrder(scope,orderNo,{actionNo:i.command.actionNo,reconciliationReference:i.command.reference,evidenceKey:i.command.evidenceKey,reason:i.command.reason,unmatchedDispositions:i.dispositions});if(current.current){if(r.status!=="ACCEPTED"&&r.status!=="CLOSING")setIntent(null);else acceptedRelease.current=true;}}
  if(current.current)await refresh();
 });}
 async function lookup(){if(!intent)return;await run(async()=>{
  if(intent.kind==="release"){
   const a=await lookupOfflineRelease(api,kind,scope,orderNo,intent.command.actionNo,()=>{acceptedRelease.current=true;});
   if(current.current){setAbsent(!a&&!acceptedRelease.current);if((!a&&definiteRefusal.current)||(a&&a.status!=="ACCEPTED"))setIntent(null);else if(a)acceptedRelease.current=true;}
  }else {const rows=entity?await api.entityCashRefunds(scope,orderNo):await api.mallCashRefunds(scope,orderNo);if(!current.current)return;
   const a=cashAttempt.current!;a.lookup(rows);setAbsent(a.phase==="absent");
   if(a.phase==="done"||(a.phase==="absent"&&definiteRefusal.current)){cashAttempt.current=null;setIntent(null);}
   else if(a.phase==="accepted"&&intent.kind==="payout"){if(entity)await api.recoverEntityCashRefund(scope,orderNo,intent.refundNo);else await api.recoverMallCashRefund(scope,orderNo,intent.refundNo);}
  }if(current.current)await refresh();
 });}
 function payout(r:CashRefund){const minor=parseCashMajor(f.amount);if(!minor||BigInt(minor)!==BigInt(r.amount.minor)||!f.time||!f.proof||!f.payee.trim()||!f.reference.trim())return e(c.invalid);
  void submit({kind:"payout",refundNo:r.refundNo,command:{actionNo:crypto.randomUUID(),actualAmount:{currency:r.amount.currency,minor},paidOutAt:new Date(f.time).toISOString().replace(/\.\d{3}Z$/,"Z"),payeeReference:f.payee.trim(),bankReference:f.reference.trim(),evidenceKey:f.proof}});
 }
 const field=(key:keyof typeof f,label:string,type="text")=><Field label={label}><Input type={type} step={type==="datetime-local"?"1":undefined} value={f[key]} onChange={v=>set({...f,[key]:v.target.value})}/></Field>;
 const proof=(purpose:"REFUND_PROOF"|"RECONCILIATION")=><Field label={purpose==="REFUND_PROOF"?c.proof:c.releaseProof}><Input type="file" accept="image/png,image/jpeg" onChange={v=>{const file=v.target.files?.[0];if(file)void upload(file,purpose);}}/>{f[purpose==="REFUND_PROOF"?"proof":"reconciliation"]&&<span>✓</span>}</Field>;
 return <DrawerSection title={c.title} desc={c.hint}>
  <Button variant="outline" disabled={busy} onClick={()=>void run(refresh)}>{c.refresh}</Button>
  {(error||q.error)&&<p role="alert">{error||c.error}</p>}
  {q.data?.receipts.map((r:CashReceipt)=><p key={r.paymentNo}>{r.paymentNo} · {c.cash}: {cashMoneyLabel({currency:r.amount.currency,minor:String(r.amount.minor)})} / {cashMoneyLabel({currency:r.amount.currency,minor:String(r.refundedMinor)})} / {cashMoneyLabel({currency:r.amount.currency,minor:String(remainingCash(r))})} · {r.pendingRefund?c.pending:!r.exceptionCash&&!allowUnfulfilledMatchedCash?c.normal:""}</p>)}
  {intent&&<div role="status"><p>{c.unknown}</p><Button disabled={busy} onClick={()=>void lookup()}>{c.lookup}</Button>{absent&&<Button disabled={busy} onClick={()=>void submit(intent)}>{c.retry}</Button>}</div>}
  <fieldset disabled={busy||!!intent} className="space-y-3 mt-3">
   {canRefund&&<>{field("reason",c.reason)}{q.data?.receipts.filter(r=>(r.exceptionCash||allowUnfulfilledMatchedCash)&&!r.pendingRefund&&remainingCash(r)>0n).map(r=><Button key={r.paymentNo} disabled={!f.reason.trim()} onClick={()=>void submit({kind:"request",command:{refundNo:`cash-${crypto.randomUUID()}`,paymentNo:r.paymentNo,reason:f.reason.trim()}})}>{c.request} · {r.paymentNo}</Button>)}</>}
   {q.data?.refunds.length===0&&<p>{c.empty}</p>}
   {q.data?.refunds.map(r=><div className="border rounded-card p-3 space-y-2" key={r.refundNo}><p>{r.refundNo} · {cashMoneyLabel({currency:r.amount.currency,minor:String(r.amount.minor)})} · {status(r.status)}</p><p>{r.reason} {r.rejection}</p>{r.actions.map(a=><p key={a.actionNo}>{a.actionNo} · {status(a.status)} · {a.rejection}</p>)}
    {canRefund&&["PREPARED","PAYOUT_ACCEPTED"].includes(r.status)&&<Button onClick={()=>void run(async()=>{if(entity)await api.recoverEntityCashRefund(scope,orderNo,r.refundNo);else await api.recoverMallCashRefund(scope,orderNo,r.refundNo);await refresh();})}>{c.recover}</Button>}
    {canRefund&&r.status==="AWAITING_PAYOUT"&&<>{field("amount",c.amount)}{field("time",c.time,"datetime-local")}{field("payee",c.payee)}{field("reference",c.ref)}{proof("REFUND_PROOF")}<Button onClick={()=>payout(r)}>{c.payout}</Button></>}
   </div>)}
   {canRelease&&canClose&&<div className="border rounded-card p-3 space-y-2"><p>{c.releaseHint}</p>{proof("RECONCILIATION")}{field("reference",c.ref)}{field("reason",c.reason)}<Button disabled={!q.data||!refundableClear(q.data.receipts)||!f.reconciliation||!f.reference.trim()||!f.reason.trim()} onClick={()=>void submit({kind:"release",dispositions:Object.fromEntries((q.data?.receipts??[]).filter(r=>r.exceptionCash).map(r=>[r.paymentNo,f.reason.trim()])),command:{actionNo:crypto.randomUUID(),reference:f.reference.trim(),evidenceKey:f.reconciliation,reason:f.reason.trim()}})}>{c.release}</Button></div>}
  </fieldset>
 </DrawerSection>;
}
